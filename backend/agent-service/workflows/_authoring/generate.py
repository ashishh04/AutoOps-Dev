#!/usr/bin/env python3
"""Assemble workflow JSON from a body script and its declared contract.

    python backend/agent-service/workflows/_authoring/generate.py
    python backend/agent-service/workflows/_authoring/generate.py --check

**Why the automation body is a real file rather than a JSON string.** A
150-line boto3 script embedded in `"value"` as one line with `\\n` escapes
cannot be read in a diff, cannot be parsed by a linter, and cannot be
syntax-checked before it runs against a customer's account. As a `.py` file it
is all three. The JSON under `workflows/<DOMAIN>/` remains the published
artefact — `publish.py` reads it and nothing else — and `--check` is what stops
the two drifting: it re-generates in memory and fails if the committed JSON
differs, so an edit to either side without the other is caught in `pytest`
rather than in a run.

**The `pattern` on every string input is a security control, not documentation.**
`NativeInputValidator` enforces it on the path every run takes, and a value that
passes is then substituted verbatim into the script body. That substitution is
textual: the pattern is what stands between a tool argument and arbitrary code
execution inside the automation. An input without one has no business being in
a body that runs `boto3`.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
BODIES = HERE / "bodies"
WORKFLOWS = HERE.parent


def body(name: str) -> str:
    return (BODIES / f"{name}.py").read_text(encoding="utf-8")


def text(variable, label, help_text, *, pattern, required=True, default=None,
         placeholder=None, consumed_by="node"):
    field = {
        "variable": variable,
        "label": label,
        "type": "string",
        "required": required,
        "pattern": pattern,
        "help": help_text,
        "consumedBy": consumed_by,
    }
    if default is not None:
        field["default"] = default
    if placeholder:
        field["placeholder"] = placeholder
    return field


def number(variable, label, help_text, *, minimum, maximum, default, required=True,
           consumed_by="node"):
    return {
        "variable": variable,
        "label": label,
        "type": "number",
        "required": required,
        "min": minimum,
        "max": maximum,
        "default": default,
        "help": help_text,
        "consumedBy": consumed_by,
    }


def boolean(variable, label, help_text, *, default=False, required=False, consumed_by="node"):
    return {
        "variable": variable,
        "label": label,
        "type": "boolean",
        "required": required,
        "default": default,
        "help": help_text,
        "consumedBy": consumed_by,
    }


def select(variable, label, help_text, *, options, default, required=True, consumed_by="node"):
    return {
        "variable": variable,
        "label": label,
        "type": "select",
        "required": required,
        "options": options,
        "default": default,
        "help": help_text,
        "consumedBy": consumed_by,
    }


#: Matches an AWS region name and nothing else. Every body re-checks it, but
#: this is the check that runs before the value is ever placed into a script.
REGION_PATTERN = r"^[a-z]{2}(-gov)?-[a-z]+-\d$"

AWS_READ = {
    "kind": "cloud_connection",
    "platform": "AWS",
    "customerText": (
        "An AWS account we can read from. The access we need is read-only — "
        "nothing in this check can change anything."
    ),
}


def aws_read(*permissions):
    return [dict(AWS_READ, permissions=list(permissions))]


def entra_app(*permissions, extra=""):
    """A Microsoft 365 workflow's prerequisite.

    Declared as an AZURE cloud_connection because that is the credential shape
    the platform already carries and the one job-service already puts in a
    step's environment — tenant id, client id, client secret, which is exactly
    Microsoft Graph's client-credentials triple.

    The customerText says APP REGISTRATION rather than "Azure account" on
    purpose. A tenant that connects an Azure subscription gets a credential
    that works perfectly for ARM and returns 403 on every Graph call, which
    reads like a broken automation instead of a setup step not yet done.
    """
    return [{
        "kind": "cloud_connection",
        "platform": "AZURE",
        "permissions": list(permissions),
        "customerText": (
            "A Microsoft 365 app registration we can read your directory with. It is "
            "read-only and separate from any Azure subscription — it needs consent for "
            "the listed permissions" + (". " + extra if extra else ".")
        ),
    }]


M365_WORKFLOWS: list[dict] = [
    {
        "file": "Microsoft 365/RD-201-m365-licence-assignment-audit.json",
        "body": "m365_licence_assignment_audit",
        "taskId": "RD-201",
        "domain": "Microsoft 365",
        "category": "Cost",
        "title": "Microsoft 365 Licence Assignment Audit",
        "description": (
            "Lists every subscribed SKU with prepaid against assigned seats, and every user "
            "with the licences they hold, whether their account is still enabled and when "
            "they last signed in. Read-only. Finds seats paid for and not used: assigned to "
            "disabled accounts, to people who have never signed in, or bought and never "
            "assigned. Reports seats and SKU names, never a currency amount."
        ),
        "runtime": "python",
        "inputs": [
            select("Scope", "Who to include",
                   "licensed-only is the usual choice. disabled-only answers 'what are we "
                   "still paying for after people left'.",
                   options=["licensed-only", "disabled-only", "all"],
                   default="licensed-only"),
            number("InactiveAfterDays", "Dormant after (days)",
                   "A licensed user who has not signed in within this many days is reported "
                   "as dormant. 90 is a common review period.",
                   minimum=1, maximum=3650, default=90, consumed_by="agent"),
        ],
        "requires": entra_app(
            "User.Read.All", "Organization.Read.All", "AuditLog.Read.All",
            extra="Sign-in dates additionally need an Entra ID P1 or P2 plan; without one "
                  "the audit still runs and says the dates were unavailable",
        ),
    },
    {
        "file": "Microsoft 365/RD-202-m365-privileged-access-audit.json",
        "body": "m365_privileged_access_audit",
        "taskId": "RD-202",
        "domain": "Microsoft 365",
        "category": "Security",
        "title": "Microsoft 365 Privileged Access Audit",
        "description": (
            "Lists every holder of a Microsoft 365 directory role with the roles they hold, "
            "whether strong authentication is registered, whether they are a guest from "
            "another tenant, and when they last signed in. Read-only. Separates tier-zero "
            "roles — the ones that can grant themselves anything else — from service-desk "
            "roles, and reports applications holding roles separately from people."
        ),
        "runtime": "python",
        "inputs": [
            number("InactiveAfterDays", "Dormant after (days)",
                   "A privileged account unused for this long is reported as dormant. An "
                   "admin account nobody uses is one nobody would notice being used.",
                   minimum=1, maximum=3650, default=90, consumed_by="agent"),
        ],
        "requires": entra_app(
            "RoleManagement.Read.Directory", "User.Read.All", "AuditLog.Read.All",
        ),
    },
    {
        "file": "Microsoft 365/RD-203-m365-mailbox-rule-audit.json",
        "body": "m365_mailbox_rule_audit",
        "taskId": "RD-203",
        "domain": "Microsoft 365",
        "category": "Security",
        "title": "Microsoft 365 Mailbox Rule Audit",
        "description": (
            "Finds inbox rules that forward or redirect mail to addresses outside the "
            "organisation, and flags the ones that also mark as read, move or delete the "
            "original — the signature of business email compromise rather than a user "
            "convenience. Read-only. External is computed from the tenant's own verified "
            "domains, not from a typed-in list."
        ),
        "runtime": "python",
        "inputs": [
            select("Scope", "Which mailboxes",
                   "disabled-accounts is the offboarding check and is cheap. all-users is a "
                   "full sweep and takes one request per mailbox.",
                   options=["disabled-accounts", "named-users", "all-users"],
                   default="disabled-accounts"),
            text("UserPrincipalNames", "Mailboxes to check",
                 "Required when the scope is named-users. Comma-separated addresses.",
                 pattern=r"^[A-Za-z0-9._%+\-@]+(,[A-Za-z0-9._%+\-@]+)*$|^$",
                 required=False, default="", placeholder="leaver@customer.com"),
            text("AdditionalInternalDomains", "Extra internal domains",
                 "Domains to treat as internal beyond the tenant's own verified ones — a "
                 "parent company or a partner you legitimately forward to.",
                 pattern=r"^[a-z0-9.\-]+(,[a-z0-9.\-]+)*$|^$", required=False, default=""),
            number("MaxUsers", "Mailbox limit",
                   "A ceiling on how many mailboxes one run inspects. Reaching it is "
                   "reported, so a truncated sweep is never read as a clean one.",
                   minimum=1, maximum=2000, default=200),
        ],
        "requires": entra_app(
            "User.Read.All", "Organization.Read.All", "MailboxSettings.Read",
            extra="MailboxSettings.Read lets it read inbox RULES only — it does not grant "
                  "access to the contents of anyone's mail",
        ),
    },
]

WORKFLOWS_TO_BUILD: list[dict] = [
    # ------------------------------------------------------------ security ---
    {
        "file": "AWS/RD-149-s3-public-access-audit.json",
        "body": "s3_public_access_audit",
        "taskId": "RD-149",
        "domain": "AWS",
        "category": "Security",
        "title": "S3 Public Access Audit",
        "description": (
            "Lists every S3 bucket in the account with its public access block, ACL grants, "
            "bucket-policy public status, default encryption and versioning. Read-only. "
            "Use it to find buckets readable by the internet, and to inventory buckets and "
            "their regions."
        ),
        "runtime": "python",
        "inputs": [
            text("Region", "Region", "The region the API calls are made from. Bucket listing "
                 "covers the whole account regardless of what you choose here.",
                 pattern=REGION_PATTERN, default="us-east-1", placeholder="us-east-1"),
            text("BucketNamePrefix", "Bucket name prefix",
                 "Leave blank to audit every bucket. Supply a prefix to narrow a large estate.",
                 pattern=r"^[a-z0-9.\-]{0,63}$", required=False, default=""),
        ],
        "requires": aws_read("s3:ListAllMyBuckets", "s3:GetBucketPublicAccessBlock",
                             "s3:GetBucketAcl", "s3:GetBucketPolicyStatus",
                             "s3:GetEncryptionConfiguration", "s3:GetBucketVersioning",
                             "s3:GetBucketLocation"),
    },
    {
        "file": "AWS/RD-137-security-group-ingress-audit.json",
        "body": "security_group_ingress_audit",
        "taskId": "RD-137",
        "domain": "AWS",
        "category": "Security",
        "title": "Security Group Ingress Audit",
        "description": (
            "Finds every security-group rule open to 0.0.0.0/0 or ::/0 in one region, and "
            "reports what is actually attached behind each one — instances, load balancers, "
            "databases. Read-only. Distinguishes an open rule with something behind it from "
            "an open rule on a group attached to nothing."
        ),
        "runtime": "python",
        "inputs": [
            text("Region", "Region", "Security groups are regional. One region per run.",
                 pattern=REGION_PATTERN, default="us-east-1", placeholder="eu-west-1"),
            text("PortFilter", "Only these ports",
                 "Leave blank for every open rule. Supply a comma-separated list, e.g. "
                 "22,3389, to narrow it to specific exposures.",
                 pattern=r"^\d{1,5}(,\d{1,5})*$|^$", required=False, default=""),
        ],
        "requires": aws_read("ec2:DescribeSecurityGroups", "ec2:DescribeNetworkInterfaces"),
    },
    {
        "file": "AWS/RD-145-iam-credential-hygiene-audit.json",
        "body": "iam_credential_hygiene_audit",
        "taskId": "RD-145",
        "domain": "AWS",
        "category": "Security",
        "title": "IAM Credential Hygiene Audit",
        "description": (
            "Reads the account's IAM credential report and reports, per principal, access-key "
            "age, when each key was last used and for what, MFA status and console access. "
            "Read-only. Finds keys that are old, keys that are old AND unused, and console "
            "users without MFA."
        ),
        "runtime": "python",
        "inputs": [
            number("MaxKeyAgeDays", "Key age threshold (days)",
                   "Access keys older than this are reported. 90 is the common policy.",
                   minimum=1, maximum=3650, default=90, consumed_by="agent"),
            number("UnusedAfterDays", "Unused threshold (days)",
                   "A key not used within this many days counts as abandoned. An old key that "
                   "is also unused is the safest kind to rotate.",
                   minimum=1, maximum=3650, default=30, consumed_by="agent"),
        ],
        "requires": aws_read("iam:GenerateCredentialReport", "iam:GetCredentialReport"),
    },
    # ---------------------------------------------------------------- cost ---
    {
        "file": "AWS/RD-141-cost-explorer-service-delta.json",
        "body": "cost_explorer_service_delta",
        "taskId": "RD-141",
        "domain": "AWS",
        "category": "Cost",
        "title": "Cost Explorer Service Delta",
        "description": (
            "Compares account spend per AWS service across two consecutive windows and ranks "
            "services by how much they MOVED, not by how much they cost. Read-only. Answers "
            "'what made the bill go up', with the arithmetic done in the automation rather "
            "than estimated."
        ),
        "runtime": "python",
        "inputs": [
            number("WindowDays", "Window length (days)",
                   "Each window is this many days. 7 compares last week with the week before.",
                   minimum=1, maximum=90, default=7),
            select("CostMetric", "Cost metric",
                   "Unblended is what most accounts are billed on. Amortized spreads reserved "
                   "and savings-plan charges across the period they cover.",
                   options=["UnblendedCost", "AmortizedCost", "NetUnblendedCost"],
                   default="UnblendedCost"),
        ],
        "requires": [dict(
            AWS_READ,
            permissions=["ce:GetCostAndUsage"],
            customerText=(
                "An AWS account we can read cost data from, with Cost Explorer switched on. "
                "Cost Explorer is enabled per payer account and takes about a day to "
                "populate the first time."
            ),
        )],
    },
    {
        "file": "AWS/RD-136-idle-resource-inventory.json",
        "body": "idle_resource_inventory",
        "taskId": "RD-136",
        "domain": "AWS",
        "category": "Cost",
        "title": "Idle Resource Inventory",
        "description": (
            "Lists unattached EBS volumes, unassociated elastic IPs and stopped instances in "
            "one region, with age, tags and an indicative monthly cost per item. Read-only. "
            "This is the inventory a cleanup is proposed FROM — it names the exact resource "
            "ids a reclaim would act on."
        ),
        "runtime": "python",
        "inputs": [
            text("Region", "Region", "EBS volumes and elastic IPs are regional. One region "
                 "per run.", pattern=REGION_PATTERN, default="us-east-1"),
            number("MinimumAgeDays", "Minimum age (days)",
                   "Ignore volumes created more recently than this. A disk detached this "
                   "morning is usually a migration in progress, not waste.",
                   minimum=0, maximum=3650, default=7),
        ],
        "requires": aws_read("ec2:DescribeVolumes", "ec2:DescribeAddresses",
                             "ec2:DescribeInstances"),
    },
    # -------------------------------------------------- incident response ---
    {
        "file": "AWS/RD-210-cloudwatch-alarm-state-audit.json",
        "body": "cloudwatch_alarm_state_audit",
        "taskId": "RD-210",
        "domain": "AWS",
        "category": "Incident Response",
        "title": "CloudWatch Alarm State Audit",
        "description": (
            "Lists every alarm not in OK with the resource it is about (from its dimensions, "
            "not its name), how long it has been in that state, and how many times it has "
            "flipped in the window. Read-only. Groups alarms whose first state change falls "
            "in the same ten minutes, which is the raw material for deciding whether nine "
            "alarms are nine problems or one."
        ),
        "runtime": "python",
        "inputs": [
            text("Region", "Region", "Alarms are regional. One region per run.",
                 pattern=REGION_PATTERN, default="us-east-1"),
            number("LookbackHours", "Lookback (hours)",
                   "How far back to read state changes. Use the window around the incident, "
                   "not the whole week — a wider window buries the cluster you are looking for.",
                   minimum=1, maximum=168, default=6),
            text("AlarmNamePrefix", "Alarm name prefix",
                 "Leave blank for every alarm. Narrow it when one naming convention covers "
                 "the affected service.",
                 pattern=r"^[A-Za-z0-9 ._:\-/]{0,255}$", required=False, default=""),
        ],
        "requires": aws_read("cloudwatch:DescribeAlarms", "cloudwatch:DescribeAlarmHistory"),
    },
    {
        "file": "AWS/RD-211-cloudtrail-change-timeline.json",
        "body": "cloudtrail_change_timeline",
        "taskId": "RD-211",
        "domain": "AWS",
        "category": "Incident Response",
        "title": "CloudTrail Change Timeline",
        "description": (
            "Every write API call in the account within a time window, in order, with who "
            "made it, from where, against what, and whether it succeeded. Read-only calls "
            "are excluded entirely. Read-only. This is what answers 'what changed just "
            "before it broke' — including the case where the honest answer is that nothing "
            "did."
        ),
        "runtime": "python",
        "inputs": [
            text("Region", "Region", "CloudTrail lookup is regional; global-service events "
                 "appear in the region they were issued from.",
                 pattern=REGION_PATTERN, default="us-east-1"),
            number("LookbackHours", "Lookback (hours)",
                   "The window to read. Start narrow — an hour either side of the incident — "
                   "and widen only if it comes back empty.",
                   minimum=1, maximum=168, default=6),
            text("ResourceNameContains", "Only changes touching",
                 "Leave blank for every change. Supply part of a resource name to narrow the "
                 "timeline to one service.",
                 pattern=r"^[A-Za-z0-9 ._:\-/]{0,255}$", required=False, default=""),
            number("MaxEvents", "Event limit",
                   "A ceiling on how many events one run returns. Hitting it is reported, so "
                   "a truncated timeline is never read as a complete one.",
                   minimum=1, maximum=2000, default=500),
        ],
        "requires": aws_read("cloudtrail:LookupEvents"),
    },
    # ----------------------------------------------------------- reclaiming ---
    {
        "file": "AWS/RD-142-unused-resource-cleanup.json",
        "body": "unused_volume_reclaim",
        "taskId": "RD-142",
        "domain": "AWS",
        "category": "Cost",
        "title": "AWS Unused EBS Volume Cleanup",
        "description": (
            "Deletes named unattached EBS volumes after taking and CONFIRMING a recovery "
            "snapshot of each. Report-only unless Execute is true. Acts only on the exact "
            "volume ids supplied — never on a filter — and re-verifies every one is still "
            "unattached, still old enough and still unprotected immediately before deleting."
        ),
        "runtime": "python",
        "sourceScript": "Scripts/01-AWS/Remove-AwsUnusedEbsVolume.ps1",
        "inputs": [
            text("Region", "Region", "Must be the region the volumes are in.",
                 pattern=REGION_PATTERN, default="us-east-1"),
            text("VolumeIds", "Volume ids",
                 "The exact volumes to reclaim, comma-separated. Take these from an idle "
                 "resource inventory you have read — this automation never selects targets "
                 "for you.",
                 pattern=r"^vol-[0-9a-f]{8,17}(,vol-[0-9a-f]{8,17})*$",
                 placeholder="vol-0a1b2c3d4e5f67890"),
            text("Reason", "Reason",
                 "Recorded on every recovery snapshot and in the run log. Write what a "
                 "colleague would need to understand this deletion in six months.",
                 pattern=r"^[\w .,:;()\-/]{5,200}$",
                 placeholder="Reclaiming disks from the decommissioned staging fleet"),
            number("MinimumAgeDays", "Minimum age (days)",
                   "Re-checked at execution. A volume younger than this is skipped even if "
                   "it was named and approved.",
                   minimum=0, maximum=3650, default=30),
            text("ExcludeTagKey", "Protection tag",
                 "A volume carrying this tag key is never deleted. No argument overrides it.",
                 pattern=r"^[A-Za-z0-9:_.\- ]{1,64}$", default="autoops:protect"),
            number("SnapshotWaitSeconds", "Snapshot wait (seconds)",
                   "How long to wait for each recovery snapshot to COMPLETE before deleting. "
                   "A snapshot that is still running when this expires means the volume is "
                   "left alone, not deleted without a recovery point.",
                   minimum=30, maximum=3600, default=300),
            boolean("Execute", "Execute",
                    "Off by default. Off means the run reports exactly what it would delete "
                    "and changes nothing."),
            boolean("SkipSnapshot", "Skip the recovery snapshot",
                    "Removes the only way back. Leave this off unless someone has accepted "
                    "in writing that the data is disposable."),
        ],
        "requires": [{
            "kind": "cloud_connection",
            "platform": "AWS",
            "permissions": ["ec2:DescribeVolumes", "ec2:DescribeSnapshots",
                            "ec2:CreateSnapshot", "ec2:CreateTags", "ec2:DeleteVolume"],
            "customerText": (
                "An AWS account we can clean up unused disks in. This one needs permission "
                "to delete, so it is the only automation here that can change anything — "
                "and it takes a recovery snapshot of every disk first."
            ),
        }],
    },
] + M365_WORKFLOWS


def build(entry: dict) -> dict:
    """One workflow's published JSON, in the key order the schema lists."""
    document = {
        "taskId": entry["taskId"],
        "domain": entry["domain"],
        "title": entry["title"],
        "description": entry["description"],
        "category": entry["category"],
        "runtime": entry["runtime"],
    }
    if "sourceScript" in entry:
        document["sourceScript"] = entry["sourceScript"]
    document["inputs"] = entry["inputs"]
    document["nodes"] = [{
        "type": "pyscript",
        "label": entry["title"],
        "value": body(entry["body"]),
        # No retries. Every one of these is either read-only — where a retry
        # just doubles the API calls on a throttled account, and boto3 already
        # retries internally — or destructive, where a retry is the last thing
        # anyone wants.
        "retries": 0,
        "continueOnError": False,
    }]
    document["requires"] = entry["requires"]
    return document


def render(entry: dict) -> str:
    return json.dumps(build(entry), indent=2, ensure_ascii=False) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true",
                        help="fail if any committed JSON differs from what this would write")
    args = parser.parse_args(argv)

    stale: list[str] = []
    for entry in WORKFLOWS_TO_BUILD:
        target = WORKFLOWS / entry["file"]
        rendered = render(entry)

        if args.check:
            current = target.read_text(encoding="utf-8") if target.exists() else ""
            if current != rendered:
                stale.append(entry["file"])
            continue

        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(rendered, encoding="utf-8")
        print(f"wrote {entry['file']}")

    if args.check:
        if stale:
            print("These workflow JSON files no longer match their body scripts:")
            for name in stale:
                print(f"  - {name}")
            print("\nRun: python backend/agent-service/workflows/_authoring/generate.py")
            return 1
        print(f"{len(WORKFLOWS_TO_BUILD)} workflow(s) match their sources.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
