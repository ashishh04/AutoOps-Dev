"""Reclaim unattached EBS volumes. THE ONLY DESTRUCTIVE AUTOMATION HERE.

Five properties, each of which exists because the alternative is how estates
lose data:

1. **It acts on an explicit list of volume ids, never on a filter.** A filter
   re-evaluated at execution time matches whatever is unattached NOW, which
   includes the volume someone detached five minutes ago during a migration.
   The caller must name exactly what it saw.
2. **Every target is re-verified immediately before deletion** — still
   `available`, still old enough, still not protected. The gap between the
   audit that produced the list and the approval that authorised it can be
   hours, and a volume that has been re-attached in between is no longer a
   candidate.
3. **A recovery snapshot is taken and CONFIRMED COMPLETE first.** Not started
   — completed. A volume whose snapshot has not finished within the wait is
   skipped rather than deleted, so a slow snapshot costs a retry instead of
   the data.
4. **Report-only unless `Execute` is true.** The default run changes nothing
   and returns the same shape, so the caller sees exactly what would happen.
5. **A protection tag is absolute.** No argument overrides it.
"""
import json
import re
import sys
import time

import boto3
from botocore.config import Config
from botocore.exceptions import ClientError, NoCredentialsError

REGION = "{{Region}}"
VOLUME_IDS = "{{VolumeIds}}"
MIN_AGE_DAYS = int("{{MinimumAgeDays}}")
EXCLUDE_TAG_KEY = "{{ExcludeTagKey}}"
EXECUTE = "{{Execute}}".strip().lower() == "true"
SKIP_SNAPSHOT = "{{SkipSnapshot}}".strip().lower() == "true"
SNAPSHOT_WAIT_SECONDS = int("{{SnapshotWaitSeconds}}")
REASON = "{{Reason}}"

if not re.fullmatch(r"[a-z0-9-]{1,32}", REGION):
    sys.exit("Region %r is not an AWS region name." % REGION)
if not re.fullmatch(r"vol-[0-9a-f]{8,17}(,vol-[0-9a-f]{8,17})*", VOLUME_IDS):
    sys.exit("VolumeIds must be a comma-separated list of volume ids, got %r." % VOLUME_IDS)
if not re.fullmatch(r"[A-Za-z0-9:_.\- ]{1,64}", EXCLUDE_TAG_KEY):
    sys.exit("ExcludeTagKey %r is not a valid tag key." % EXCLUDE_TAG_KEY)
if not 0 <= MIN_AGE_DAYS <= 3650:
    sys.exit("MinimumAgeDays %d is outside 0-3650." % MIN_AGE_DAYS)
if not 30 <= SNAPSHOT_WAIT_SECONDS <= 3600:
    sys.exit("SnapshotWaitSeconds %d is outside 30-3600." % SNAPSHOT_WAIT_SECONDS)

TARGETS = VOLUME_IDS.split(",")
CFG = Config(retries={"max_attempts": 5, "mode": "standard"})


def age_days(created):
    from datetime import datetime, timezone
    return int((datetime.now(timezone.utc) - created).total_seconds() // 86400)


def wait_for_snapshot(ec2, snapshot_id, budget):
    """Blocks until the snapshot is `completed`, or gives up and says so.

    Returning a reason rather than raising: a snapshot that is merely slow must
    leave the volume ALIVE and the run successful-with-a-skip, not failed. The
    operator re-runs it and the second attempt finds a finished snapshot.
    """
    deadline = time.time() + budget
    while time.time() < deadline:
        described = ec2.describe_snapshots(SnapshotIds=[snapshot_id])["Snapshots"][0]
        state = described["State"]
        if state == "completed":
            return True, described.get("Progress", "100%")
        if state == "error":
            return False, "the snapshot failed"
        time.sleep(5)
    return False, "the snapshot did not complete within %ds" % budget


def main():
    ec2 = boto3.client("ec2", region_name=REGION, config=CFG)

    print("UNUSED EBS VOLUME RECLAIM")
    print("region=%s mode=%s targets=%d" % (REGION, "EXECUTE" if EXECUTE else "REPORT-ONLY",
                                            len(TARGETS)))
    print("minimum_age_days=%d protection_tag=%s snapshot=%s"
          % (MIN_AGE_DAYS, EXCLUDE_TAG_KEY, "SKIPPED" if SKIP_SNAPSHOT else "required"))
    print("reason=%s" % REASON)
    if EXECUTE and SKIP_SNAPSHOT:
        print("WARNING=deleting without a recovery snapshot. This is irreversible.")
    print("")

    try:
        described = ec2.describe_volumes(VolumeIds=TARGETS)["Volumes"]
    except NoCredentialsError:
        sys.exit("No AWS credentials reached this step. Connect an AWS account to this project.")
    except ClientError as exc:
        code = exc.response.get("Error", {}).get("Code", "")
        if code == "InvalidVolume.NotFound":
            # Already gone is not an error worth failing on, but it IS worth
            # saying: a caller working from a stale list should learn that.
            sys.exit("One or more volumes no longer exist: %s. Re-run the inventory and "
                     "work from a current list." % exc)
        sys.exit("Could not describe the target volumes: %s" % exc)

    by_id = {v["VolumeId"]: v for v in described}
    outcomes = []

    for volume_id in TARGETS:
        volume = by_id.get(volume_id)
        result = {"volume_id": volume_id}

        if volume is None:
            result.update(action="skipped", reason="no longer exists")
        elif volume["State"] != "available":
            # The important one. Between the audit and now, something attached
            # it — a migration, a restore, a person. It is not a candidate any
            # more and no approval covers deleting an in-use disk.
            result.update(
                action="skipped",
                reason="state is %s, not available — it has been attached since the audit"
                       % volume["State"],
            )
        elif age_days(volume["CreateTime"]) < MIN_AGE_DAYS:
            result.update(
                action="skipped",
                reason="age %dd is below the %dd minimum"
                       % (age_days(volume["CreateTime"]), MIN_AGE_DAYS),
            )
        elif any(t["Key"] == EXCLUDE_TAG_KEY for t in volume.get("Tags", [])):
            result.update(action="skipped", reason="carries the %s protection tag" % EXCLUDE_TAG_KEY)
        else:
            result.update(
                action="would delete" if not EXECUTE else "pending",
                size_gb=volume["Size"],
                type=volume.get("VolumeType", "?"),
                az=volume.get("AvailabilityZone", "?"),
                age_days=age_days(volume["CreateTime"]),
            )

        outcomes.append(result)

    eligible = [r for r in outcomes if r["action"] in ("would delete", "pending")]

    if not EXECUTE:
        for row in outcomes:
            print(" ".join("%s=%s" % kv for kv in row.items()))
        print("")
        print("SUMMARY")
        print("mode=REPORT-ONLY nothing_was_changed=true")
        print("would_delete=%d skipped=%d" % (len(eligible), len(outcomes) - len(eligible)))
        print("to_execute=re-run with Execute set true and the same VolumeIds")
        print("")
        print("JSON " + json.dumps({"executed": False, "outcomes": outcomes}, sort_keys=True))
        return

    for row in eligible:
        volume_id = row["volume_id"]
        snapshot_id = None

        if not SKIP_SNAPSHOT:
            try:
                snapshot = ec2.create_snapshot(
                    VolumeId=volume_id,
                    Description="AutoOps recovery snapshot before reclaim. %s" % REASON,
                    TagSpecifications=[{
                        "ResourceType": "snapshot",
                        "Tags": [
                            {"Key": "autoops:origin", "Value": volume_id},
                            {"Key": "autoops:reason", "Value": REASON[:255]},
                        ],
                    }],
                )
                snapshot_id = snapshot["SnapshotId"]
                row["snapshot_id"] = snapshot_id
            except ClientError as exc:
                row.update(action="failed", reason="could not create a recovery snapshot: %s" % exc)
                continue

            ok, detail = wait_for_snapshot(ec2, snapshot_id, SNAPSHOT_WAIT_SECONDS)
            if not ok:
                # Alive, with a snapshot that may yet finish. The next run will
                # find it complete.
                row.update(
                    action="skipped",
                    reason="%s — the volume was NOT deleted and the snapshot was kept" % detail,
                )
                continue

        try:
            ec2.delete_volume(VolumeId=volume_id)
            row.update(action="deleted", recovery_snapshot=snapshot_id or "NONE")
        except ClientError as exc:
            row.update(action="failed", reason=str(exc), recovery_snapshot=snapshot_id or "NONE")

    for row in outcomes:
        print(" ".join("%s=%s" % kv for kv in row.items()))

    deleted = [r for r in outcomes if r["action"] == "deleted"]
    failed = [r for r in outcomes if r["action"] == "failed"]
    skipped = [r for r in outcomes if r["action"] == "skipped"]
    undocumented = [r for r in deleted if r.get("recovery_snapshot") in (None, "NONE")]

    print("")
    print("SUMMARY")
    print("mode=EXECUTE deleted=%d skipped=%d failed=%d"
          % (len(deleted), len(skipped), len(failed)))
    print("reclaimed_gb=%d" % sum(r.get("size_gb", 0) for r in deleted))
    if undocumented:
        # Stated as a finding, not a success line. A deletion with no recovery
        # point is the thing a human needs to know about first.
        print("DELETED_WITHOUT_RECOVERY_SNAPSHOT=%s" % [r["volume_id"] for r in undocumented])
    print("")
    print("JSON " + json.dumps({"executed": True, "outcomes": outcomes}, sort_keys=True))

    if failed:
        # Partial failure is a failed step: the caller must not read "some were
        # deleted" as "the request was satisfied".
        sys.exit("%d volume(s) could not be deleted; see the outcomes above." % len(failed))


main()
