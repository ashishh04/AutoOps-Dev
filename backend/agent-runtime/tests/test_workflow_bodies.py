"""The automation bodies, exercised against a stubbed AWS.

These scripts are the only code here that reaches a customer's production
cloud, and one of them deletes disks. What is asserted below is the judgement
in them, not their syntax:

* an audit must not report "not encrypted" when it was merely denied the read;
* an open port with a load balancer behind it is a different finding from the
  same port on a group attached to nothing;
* a volume that has been RE-ATTACHED between the audit and the approval is no
  longer a deletion candidate, whatever the approval said;
* a recovery snapshot that has not finished means the volume lives.

The structural checks — does it parse, are its placeholders declared, does
every string input carry the pattern that makes substitution safe — are in
``workflows/_authoring/validate_bodies.py`` and run from here too, so a body
cannot be published in a state that would fail on the execution host.
"""

from __future__ import annotations

import subprocess
import sys
from datetime import datetime, timedelta, timezone

import pytest

from tests.awsfakes import ClientError, FakeClient, freeze_clock, run_body

S3 = "AWS/RD-149-s3-public-access-audit.json"
SG = "AWS/RD-137-security-group-ingress-audit.json"
IAM = "AWS/RD-145-iam-credential-hygiene-audit.json"
COST = "AWS/RD-141-cost-explorer-service-delta.json"
IDLE = "AWS/RD-136-idle-resource-inventory.json"
RECLAIM = "AWS/RD-142-unused-resource-cleanup.json"

ALL_USERS = "http://acs.amazonaws.com/groups/global/AllUsers"
NOW = datetime.now(timezone.utc)


def days_ago(count: int) -> datetime:
    return NOW - timedelta(days=count)


# ------------------------------------------------------------- structural ---


def test_every_published_body_passes_the_authoring_checks():
    """The gate that stops an unrunnable script reaching a customer's account."""
    from tests.awsfakes import WORKFLOWS

    script = WORKFLOWS / "_authoring" / "validate_bodies.py"
    result = subprocess.run(
        [sys.executable, str(script)], capture_output=True, text=True, check=False
    )
    assert result.returncode == 0, result.stdout + result.stderr


def test_the_published_json_still_matches_its_body_scripts():
    """Generated artefact, so drift is a test failure rather than a surprise."""
    from tests.awsfakes import WORKFLOWS

    script = WORKFLOWS / "_authoring" / "generate.py"
    result = subprocess.run(
        [sys.executable, str(script), "--check"], capture_output=True, text=True, check=False
    )
    assert result.returncode == 0, result.stdout + result.stderr


# --------------------------------------------------------------------- s3 ---


def s3_client(**overrides) -> FakeClient:
    responses = {
        "list_buckets": {"Buckets": [{"Name": "logs-prod"}, {"Name": "website-assets"}]},
        "get_bucket_location": {"LocationConstraint": "eu-west-1"},
        "get_public_access_block": {
            "PublicAccessBlockConfiguration": {
                "BlockPublicAcls": True, "IgnorePublicAcls": True,
                "BlockPublicPolicy": True, "RestrictPublicBuckets": True,
            }
        },
        "get_bucket_acl": {"Grants": []},
        "get_bucket_policy_status": {"PolicyStatus": {"IsPublic": False}},
        "get_bucket_encryption": {
            "ServerSideEncryptionConfiguration": {
                "Rules": [{"ApplyServerSideEncryptionByDefault": {"SSEAlgorithm": "AES256"}}]
            }
        },
        "get_bucket_versioning": {"Status": "Enabled"},
    }
    responses.update(overrides)
    return FakeClient(service="s3", responses=responses)


def test_a_clean_estate_reports_nothing_public():
    result = run_body(S3, {"Region": "eu-west-1", "BucketNamePrefix": ""},
                      {"s3": s3_client()})
    assert result.exit_message is None
    assert result.line("buckets_public_via_acl_or_policy=").startswith(
        "buckets_public_via_acl_or_policy=0"
    )
    assert result.line("buckets_unencrypted=").startswith("buckets_unencrypted=0")


def test_a_world_readable_acl_is_reported_as_public():
    client = s3_client(get_bucket_acl={
        "Grants": [{"Grantee": {"URI": ALL_USERS}, "Permission": "READ"}]
    })
    result = run_body(S3, {"Region": "eu-west-1", "BucketNamePrefix": ""}, {"s3": client})

    assert "acl_public=YES:AllUsers=READ" in result.stdout
    assert result.line("buckets_public_via_acl_or_policy=").startswith(
        "buckets_public_via_acl_or_policy=2"
    )


def test_a_missing_public_access_block_is_absent_not_merely_off():
    """ABSENT is a distinct state: with no block config, an open ACL is honoured."""
    client = s3_client(get_public_access_block=ClientError(
        "NoSuchPublicAccessBlockConfiguration"
    ))
    result = run_body(S3, {"Region": "eu-west-1", "BucketNamePrefix": ""}, {"s3": client})

    assert "public_access_block=ABSENT" in result.stdout
    assert result.line("buckets_without_full_public_access_block=").startswith(
        "buckets_without_full_public_access_block=2"
    )


def test_a_denied_read_is_never_reported_as_a_clean_result():
    """The distinction the whole `soft` helper exists for.

    An audit that says "not encrypted" when it was refused the read sends
    someone to fix a bucket that was fine, and — far worse — implies the audit
    covered ground it never saw.
    """
    client = s3_client(get_bucket_encryption=ClientError("AccessDenied"))
    result = run_body(S3, {"Region": "eu-west-1", "BucketNamePrefix": ""}, {"s3": client})

    assert "encryption=unreadable(AccessDenied)" in result.stdout
    assert "encryption=NONE" not in result.stdout
    assert result.line("buckets_unencrypted=").startswith("buckets_unencrypted=0")
    assert result.has("checks_denied_or_failed=")


def test_no_credentials_fails_the_step_with_an_instruction():
    from tests.awsfakes import NoCredentialsError

    client = s3_client(list_buckets=NoCredentialsError())
    result = run_body(S3, {"Region": "eu-west-1", "BucketNamePrefix": ""}, {"s3": client})

    assert result.exit_message is not None
    assert "Connect an AWS account" in result.exit_message


def test_a_prefix_narrows_the_estate():
    result = run_body(S3, {"Region": "eu-west-1", "BucketNamePrefix": "logs"},
                      {"s3": s3_client()})
    assert result.line("queried_from_region=").endswith("buckets_examined=1")


# --------------------------------------------------------- security groups ---


def sg_pages(rules, groups_extra=None):
    return [{"SecurityGroups": [{
        "GroupId": "sg-0123456789abcdef0",
        "GroupName": "web",
        "VpcId": "vpc-1",
        "IpPermissions": rules,
        **(groups_extra or {}),
    }]}]


OPEN_SSH = {
    "IpProtocol": "tcp", "FromPort": 22, "ToPort": 22,
    "IpRanges": [{"CidrIp": "0.0.0.0/0"}], "Ipv6Ranges": [],
}


def test_an_open_port_with_something_behind_it_outranks_one_without():
    behind = [{"NetworkInterfaces": [{
        "InterfaceType": "interface",
        "Description": "ELB app/prod-lb",
        "Association": {"PublicIp": "203.0.113.10"},
        "Groups": [{"GroupId": "sg-0123456789abcdef0"}],
    }]}]
    client = FakeClient(service="ec2", responses={
        "paginate:describe_security_groups": sg_pages([OPEN_SSH]),
        "paginate:describe_network_interfaces": behind,
    })
    result = run_body(SG, {"Region": "eu-west-1", "PortFilter": ""}, {"ec2": client})

    assert "notorious_ports_opened=22(SSH)" in result.stdout
    assert "public_ip=203.0.113.10" in result.stdout
    assert result.line("world_open_rules_with_something_behind_them=").endswith("1")
    assert result.line("world_open_rules_on_groups_attached_to_nothing=").startswith(
        "world_open_rules_on_groups_attached_to_nothing=0"
    )


def test_an_open_group_attached_to_nothing_is_separated_from_a_live_one():
    client = FakeClient(service="ec2", responses={
        "paginate:describe_security_groups": sg_pages([OPEN_SSH]),
        "paginate:describe_network_interfaces": [{"NetworkInterfaces": []}],
    })
    result = run_body(SG, {"Region": "eu-west-1", "PortFilter": ""}, {"ec2": client})

    assert "attached_to=nothing" in result.stdout
    assert result.line("world_open_rules_with_something_behind_them=").endswith("0")


def test_all_protocols_open_is_read_as_every_port():
    everything = {"IpProtocol": "-1", "IpRanges": [{"CidrIp": "0.0.0.0/0"}], "Ipv6Ranges": []}
    client = FakeClient(service="ec2", responses={
        "paginate:describe_security_groups": sg_pages([everything]),
        "paginate:describe_network_interfaces": [{"NetworkInterfaces": []}],
    })
    result = run_body(SG, {"Region": "eu-west-1", "PortFilter": ""}, {"ec2": client})

    assert "ports=all" in result.stdout
    # Every notorious port is inside 0-65535, so all of them are named.
    assert "3389(RDP)" in result.stdout and "22(SSH)" in result.stdout


def test_a_failed_attachment_lookup_is_stated_rather_than_read_as_nothing_behind():
    client = FakeClient(service="ec2", responses={
        "paginate:describe_security_groups": sg_pages([OPEN_SSH]),
        "paginate:describe_network_interfaces": ClientError("UnauthorizedOperation"),
    })
    result = run_body(SG, {"Region": "eu-west-1", "PortFilter": ""}, {"ec2": client})

    assert "attachment_lookup_failed=UnauthorizedOperation" in result.stdout
    assert "attached_to=UNKNOWN" in result.stdout


# -------------------------------------------------------------------- iam ---


def credential_report(rows: str) -> dict:
    header = (
        "user,arn,user_creation_time,password_enabled,password_last_used,"
        "password_last_changed,mfa_active,access_key_1_active,"
        "access_key_1_last_rotated,access_key_1_last_used_date,"
        "access_key_1_last_used_service,access_key_2_active,"
        "access_key_2_last_rotated,access_key_2_last_used_date,"
        "access_key_2_last_used_service\n"
    )
    return {"Content": (header + rows).encode("utf-8")}


def test_an_old_key_that_is_also_unused_is_the_safest_finding():
    row = (
        "deploy-bot,arn,2020-01-01T00:00:00+00:00,false,N/A,N/A,false,"
        "true,%s,N/A,N/A,false,N/A,N/A,N/A\n" % days_ago(400).isoformat()
    )
    client = FakeClient(service="iam", responses={
        "get_credential_report": credential_report(row),
    })
    result = run_body(IAM, {"MaxKeyAgeDays": 90, "UnusedAfterDays": 30}, {"iam": client})

    assert "key1_last_used_days=NEVER" in result.stdout
    assert result.line("users_with_a_key_older_than_threshold=").startswith(
        "users_with_a_key_older_than_threshold=1"
    )
    assert "deploy-bot" in result.line("users_with_an_old_key_that_is_also_unused=")


def test_an_old_key_in_daily_use_is_reported_but_not_as_abandoned():
    row = (
        "ci,arn,2020-01-01T00:00:00+00:00,false,N/A,N/A,false,"
        "true,%s,%s,s3,false,N/A,N/A,N/A\n"
        % (days_ago(400).isoformat(), days_ago(1).isoformat())
    )
    client = FakeClient(service="iam", responses={
        "get_credential_report": credential_report(row),
    })
    result = run_body(IAM, {"MaxKeyAgeDays": 90, "UnusedAfterDays": 30}, {"iam": client})

    assert result.line("users_with_a_key_older_than_threshold=").startswith(
        "users_with_a_key_older_than_threshold=1"
    )
    assert result.line("users_with_an_old_key_that_is_also_unused=").startswith(
        "users_with_an_old_key_that_is_also_unused=0"
    )


def test_console_access_without_mfa_is_called_out():
    row = (
        "alice,arn,2020-01-01T00:00:00+00:00,true,%s,N/A,false,"
        "false,N/A,N/A,N/A,false,N/A,N/A,N/A\n" % days_ago(3).isoformat()
    )
    client = FakeClient(service="iam", responses={
        "get_credential_report": credential_report(row),
    })
    result = run_body(IAM, {"MaxKeyAgeDays": 90, "UnusedAfterDays": 30}, {"iam": client})

    assert "alice" in result.line("users_with_console_access_and_no_mfa=")


def test_the_report_is_generated_when_the_account_has_none_yet(monkeypatch):
    freeze_clock(monkeypatch)
    row = "root,arn,2020-01-01T00:00:00+00:00,false,N/A,N/A,true,false,N/A,N/A,N/A,false,N/A,N/A,N/A\n"
    client = FakeClient(service="iam", responses={
        "get_credential_report": [ClientError("ReportNotPresent"), credential_report(row)],
        "generate_credential_report": {"State": "STARTED"},
    })
    result = run_body(IAM, {"MaxKeyAgeDays": 90, "UnusedAfterDays": 30}, {"iam": client})

    assert "generate_credential_report" in client.operations()
    assert result.exit_message is None


# ------------------------------------------------------------------- cost ---


def cost_pages(service_totals: dict[str, str]) -> dict:
    return {
        "ResultsByTime": [{
            "Groups": [
                {"Keys": [name], "Metrics": {"UnblendedCost": {"Amount": amount}}}
                for name, amount in service_totals.items()
            ]
        }]
    }


def test_services_are_ranked_by_movement_not_by_size():
    """The whole point. The biggest line on a bill is rarely the interesting one."""
    answers = [
        cost_pages({"Amazon EC2": "1000.00", "AWS Lambda": "50.00"}),   # recent
        cost_pages({"Amazon EC2": "990.00", "AWS Lambda": "10.00"}),    # prior
    ]
    client = FakeClient(service="ce", responses={
        "get_cost_and_usage": lambda **kwargs: answers.pop(0),
    })
    result = run_body(COST, {"WindowDays": 7, "CostMetric": "UnblendedCost"}, {"ce": client})

    lines = [ln for ln in result.stdout.splitlines() if ln.startswith("service=")]
    # Lambda moved +40 against EC2's +10, so Lambda is first despite being 5% of the bill.
    assert lines[0].startswith("service=AWS Lambda")
    assert "delta=+40.00" in lines[0]
    assert "change=+400.0%" in lines[0]


def test_the_arithmetic_is_done_in_the_automation_not_left_to_the_model():
    answers = [
        cost_pages({"Amazon S3": "120.50"}),
        cost_pages({"Amazon S3": "100.25"}),
    ]
    client = FakeClient(service="ce", responses={
        "get_cost_and_usage": lambda **kwargs: answers.pop(0),
    })
    result = run_body(COST, {"WindowDays": 7, "CostMetric": "UnblendedCost"}, {"ce": client})

    assert "recent_total=120.50 prior_total=100.25 delta=+20.25" in result.line("recent_total=")


def test_a_brand_new_service_is_labelled_new_rather_than_infinite_percent():
    answers = [cost_pages({"Amazon Bedrock": "75.00"}), cost_pages({})]
    client = FakeClient(service="ce", responses={
        "get_cost_and_usage": lambda **kwargs: answers.pop(0),
    })
    result = run_body(COST, {"WindowDays": 7, "CostMetric": "UnblendedCost"}, {"ce": client})

    assert "change=new" in result.line("service=Amazon Bedrock")


def test_cost_explorer_being_switched_off_says_so():
    client = FakeClient(service="ce", responses={
        "get_cost_and_usage": ClientError("AccessDeniedException"),
    })
    result = run_body(COST, {"WindowDays": 7, "CostMetric": "UnblendedCost"}, {"ce": client})

    assert result.exit_message is not None
    assert "Cost Explorer must also be enabled" in result.exit_message


# ------------------------------------------------------------------- idle ---


def volume(volume_id="vol-0a1b2c3d4e5f67890", size=100, kind="gp3", age=90, tags=None):
    return {
        "VolumeId": volume_id, "Size": size, "VolumeType": kind,
        "AvailabilityZone": "eu-west-1a", "CreateTime": days_ago(age),
        "Encrypted": True, "SnapshotId": "", "State": "available",
        "Tags": tags or [],
    }


def idle_client(**overrides) -> FakeClient:
    responses = {
        "paginate:describe_volumes": [{"Volumes": [volume()]}],
        "describe_addresses": {"Addresses": []},
        "paginate:describe_instances": [{"Reservations": []}],
    }
    responses.update(overrides)
    return FakeClient(service="ec2", responses=responses)


def test_an_idle_volume_is_costed_with_its_basis_stated():
    result = run_body(IDLE, {"Region": "eu-west-1", "MinimumAgeDays": 7},
                      {"ec2": idle_client()})

    assert "est_monthly_usd_list_price=8.0" in result.stdout
    # The basis travels with the figure, every time.
    assert result.has("cost_estimate_basis=published us-east-1 list prices")
    assert result.has("cost_estimate_caveat=")  # because this run is eu-west-1


def test_an_unpriced_volume_type_says_unpriced_rather_than_guessing():
    """The no-invented-figures rule, made mechanical."""
    client = idle_client(**{
        "paginate:describe_volumes": [{"Volumes": [volume(kind="gp9")]}]
    })
    result = run_body(IDLE, {"Region": "us-east-1", "MinimumAgeDays": 7}, {"ec2": client})

    assert "est_monthly_usd_list_price=unpriced" in result.stdout
    assert result.has("volumes_with_no_published_price=")


def test_a_volume_younger_than_the_floor_is_not_a_candidate():
    """A disk detached this morning is a migration, not waste."""
    client = idle_client(**{
        "paginate:describe_volumes": [{"Volumes": [volume(age=1)]}]
    })
    result = run_body(IDLE, {"Region": "us-east-1", "MinimumAgeDays": 7}, {"ec2": client})

    assert result.line("unattached_volumes=").startswith("unattached_volumes=0")


def test_a_failed_collection_is_reported_rather_than_read_as_a_clean_estate():
    client = idle_client(describe_addresses=ClientError("UnauthorizedOperation"))
    result = run_body(IDLE, {"Region": "us-east-1", "MinimumAgeDays": 7}, {"ec2": client})

    assert result.has("collections_that_failed=")
    assert "describe_addresses:UnauthorizedOperation" in result.stdout


# ---------------------------------------------------------------- reclaim ---


RECLAIM_ARGS = {
    "Region": "eu-west-1",
    "VolumeIds": "vol-0a1b2c3d4e5f67890",
    "MinimumAgeDays": 30,
    "ExcludeTagKey": "autoops:protect",
    "Execute": "false",
    "SkipSnapshot": "false",
    "SnapshotWaitSeconds": 300,
    "Reason": "Decommissioned staging fleet",
}


def reclaim_client(volumes=None, **overrides) -> FakeClient:
    responses = {
        "describe_volumes": {"Volumes": volumes if volumes is not None else [volume()]},
        "create_snapshot": {"SnapshotId": "snap-0abc"},
        "describe_snapshots": {"Snapshots": [{"State": "completed", "Progress": "100%"}]},
        "delete_volume": {},
    }
    responses.update(overrides)
    return FakeClient(service="ec2", responses=responses)


def test_report_only_is_the_default_and_changes_nothing():
    client = reclaim_client()
    result = run_body(RECLAIM, RECLAIM_ARGS, {"ec2": client})

    assert result.has("mode=REPORT-ONLY nothing_was_changed=true")
    assert "action=would delete" in result.stdout
    assert "delete_volume" not in client.operations()
    assert "create_snapshot" not in client.operations()


def test_execute_snapshots_before_it_deletes_and_records_the_recovery_point():
    client = reclaim_client()
    result = run_body(RECLAIM, RECLAIM_ARGS | {"Execute": "true"}, {"ec2": client})

    order = [op for op in client.operations() if op in ("create_snapshot", "delete_volume")]
    assert order == ["create_snapshot", "delete_volume"], "the snapshot must come first"
    assert "action=deleted" in result.stdout
    assert "recovery_snapshot=snap-0abc" in result.stdout
    # The snapshot is confirmed COMPLETE, not merely started, before the delete.
    assert client.kwargs_for("describe_snapshots") == [{"SnapshotIds": ["snap-0abc"]}]
    assert result.exit_message is None


def test_a_volume_reattached_since_the_audit_is_skipped_however_it_was_approved():
    """The reason the targets are re-verified rather than trusted.

    Hours can pass between the inventory that produced the list and the human
    who approved it. A disk that has been attached in the meantime is in use,
    and no approval covers deleting an in-use disk.
    """
    client = reclaim_client(volumes=[dict(volume(), State="in-use")])
    result = run_body(RECLAIM, RECLAIM_ARGS | {"Execute": "true"}, {"ec2": client})

    assert "it has been attached since the audit" in result.stdout
    assert "delete_volume" not in client.operations()


def test_a_snapshot_that_does_not_finish_leaves_the_volume_alive(monkeypatch):
    """A slow snapshot costs a re-run. It must never cost the data."""
    freeze_clock(monkeypatch)
    client = reclaim_client(
        describe_snapshots={"Snapshots": [{"State": "pending", "Progress": "40%"}]},
    )
    result = run_body(
        RECLAIM, RECLAIM_ARGS | {"Execute": "true", "SnapshotWaitSeconds": 30}, {"ec2": client}
    )

    assert "the volume was NOT deleted and the snapshot was kept" in result.stdout
    assert "delete_volume" not in client.operations()


def test_a_failed_snapshot_never_becomes_a_deletion(monkeypatch):
    freeze_clock(monkeypatch)
    client = reclaim_client(create_snapshot=ClientError("SnapshotCreationPerVolumeRateExceeded"))
    result = run_body(RECLAIM, RECLAIM_ARGS | {"Execute": "true"}, {"ec2": client})

    assert "could not create a recovery snapshot" in result.stdout
    assert "delete_volume" not in client.operations()


def test_the_protection_tag_is_absolute():
    client = reclaim_client(volumes=[volume(tags=[{"Key": "autoops:protect", "Value": "yes"}])])
    result = run_body(RECLAIM, RECLAIM_ARGS | {"Execute": "true"}, {"ec2": client})

    assert "carries the autoops:protect protection tag" in result.stdout
    assert "delete_volume" not in client.operations()


def test_the_minimum_age_is_rechecked_at_execution_not_only_at_audit():
    client = reclaim_client(volumes=[volume(age=2)])
    result = run_body(RECLAIM, RECLAIM_ARGS | {"Execute": "true"}, {"ec2": client})

    assert "is below the 30d minimum" in result.stdout
    assert "delete_volume" not in client.operations()


def test_deleting_without_a_snapshot_is_reported_as_a_finding_not_a_success():
    client = reclaim_client()
    result = run_body(
        RECLAIM, RECLAIM_ARGS | {"Execute": "true", "SkipSnapshot": "true"}, {"ec2": client}
    )

    assert "create_snapshot" not in client.operations()
    assert "delete_volume" in client.operations()
    assert result.has("DELETED_WITHOUT_RECOVERY_SNAPSHOT=")
    assert result.has("WARNING=deleting without a recovery snapshot")


def test_a_partial_failure_fails_the_step():
    """"Some were deleted" must never be read as "the request was satisfied"."""
    client = reclaim_client(delete_volume=ClientError("VolumeInUse"))
    result = run_body(RECLAIM, RECLAIM_ARGS | {"Execute": "true"}, {"ec2": client})

    assert result.exit_message is not None
    assert "could not be deleted" in result.exit_message


@pytest.mark.parametrize(
    "bad, expected",
    [
        ({"VolumeIds": "vol-0a1b2c3d4e5f67890; rm -rf /"}, "comma-separated list of volume ids"),
        ({"Region": "not a region"}, "is not an AWS region name"),
        ({"MinimumAgeDays": 9999}, "outside 0-3650"),
        ({"SnapshotWaitSeconds": 5}, "outside 30-3600"),
    ],
)
def test_the_body_refuses_a_value_that_should_never_have_reached_it(bad, expected):
    """Defence in depth behind NativeInputValidator's declared patterns.

    The validator is the control; this is the second wall. A body that runs
    boto3 should not depend on something upstream having been configured
    correctly to avoid executing whatever it was handed.
    """
    result = run_body(RECLAIM, RECLAIM_ARGS | bad, {"ec2": reclaim_client()})

    assert result.exit_message is not None
    assert expected in result.exit_message
