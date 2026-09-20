"""IAM credential hygiene. Read-only: the account's own credential report.

Built on GenerateCredentialReport rather than walking ListUsers and calling
five APIs per user. The report is one call, it is consistent at a point in
time, and — the part that matters for an audit — it includes last-used dates
that are otherwise only reachable per key.

A key's AGE is not a finding. A key that is old AND still has permissions AND
has not been used in months is a finding, and so is a key that is old and used
daily — for opposite reasons. Both facts are reported so the agent can tell
them apart.
"""
import csv
import io
import json
import sys
import time
from datetime import datetime, timezone

import boto3
from botocore.config import Config
from botocore.exceptions import ClientError, NoCredentialsError

MAX_KEY_AGE_DAYS = int("{{MaxKeyAgeDays}}")
UNUSED_AFTER_DAYS = int("{{UnusedAfterDays}}")

if not 1 <= MAX_KEY_AGE_DAYS <= 3650:
    sys.exit("MaxKeyAgeDays %d is outside 1-3650." % MAX_KEY_AGE_DAYS)
if not 1 <= UNUSED_AFTER_DAYS <= 3650:
    sys.exit("UnusedAfterDays %d is outside 1-3650." % UNUSED_AFTER_DAYS)

CFG = Config(retries={"max_attempts": 5, "mode": "standard"})
NOW = datetime.now(timezone.utc)


def age_days(value):
    """Days since an ISO timestamp from the report, or None for N/A."""
    if not value or value in ("N/A", "not_supported", "no_information"):
        return None
    try:
        return int((NOW - datetime.fromisoformat(value.replace("Z", "+00:00"))).total_seconds() // 86400)
    except ValueError:
        return None


def fetch_report(iam):
    """The credential report, generating it first if AWS has not got one.

    GenerateCredentialReport is asynchronous and returns STARTED the first time.
    Polling is the documented way through; the bound is low because the report
    is small and a run that sits here for minutes is a run nobody waits for.
    """
    for _ in range(12):
        try:
            return iam.get_credential_report()["Content"].decode("utf-8")
        except ClientError as exc:
            code = exc.response.get("Error", {}).get("Code", "")
            if code not in ("ReportNotPresent", "ReportInProgress", "ReportExpired"):
                raise
            if code != "ReportInProgress":
                iam.generate_credential_report()
            time.sleep(5)
    sys.exit("The IAM credential report did not become available within 60 seconds.")


def main():
    iam = boto3.client("iam", config=CFG)
    try:
        content = fetch_report(iam)
    except NoCredentialsError:
        sys.exit("No AWS credentials reached this step. Connect an AWS account to this project.")
    except ClientError as exc:
        sys.exit("Could not read the IAM credential report: %s" % exc)

    rows = list(csv.DictReader(io.StringIO(content)))

    print("IAM CREDENTIAL HYGIENE AUDIT")
    print("principals_examined=%d key_age_threshold_days=%d unused_threshold_days=%d"
          % (len(rows), MAX_KEY_AGE_DAYS, UNUSED_AFTER_DAYS))
    print("")

    people = []
    for row in rows:
        user = row.get("user", "")
        record = {
            "user": user,
            "mfa": "yes" if row.get("mfa_active") == "true" else "NO",
            "console_password": "enabled" if row.get("password_enabled") == "true" else "no",
        }

        if row.get("password_enabled") == "true":
            last = age_days(row.get("password_last_used"))
            record["password_last_used_days"] = "never" if last is None else last

        for slot in ("1", "2"):
            if row.get("access_key_%s_active" % slot) != "true":
                continue
            created = age_days(row.get("access_key_%s_last_rotated" % slot))
            used = age_days(row.get("access_key_%s_last_used_date" % slot))
            record["key%s_age_days" % slot] = "unknown" if created is None else created
            record["key%s_last_used_days" % slot] = "NEVER" if used is None else used
            record["key%s_last_used_service" % slot] = (
                row.get("access_key_%s_last_used_service" % slot) or "n/a"
            )

        people.append(record)
        print(" ".join("%s=%s" % (k, v) for k, v in record.items()))

    def keys_of(record):
        return [
            (slot, record.get("key%s_age_days" % slot), record.get("key%s_last_used_days" % slot))
            for slot in ("1", "2")
            if "key%s_age_days" % slot in record
        ]

    def older_than(value, limit):
        return isinstance(value, int) and value > limit

    stale = [
        r for r in people
        for _, age, _ in keys_of(r)
        if older_than(age, MAX_KEY_AGE_DAYS)
    ]
    abandoned = [
        r for r in people
        for _, age, used in keys_of(r)
        if older_than(age, MAX_KEY_AGE_DAYS) and (used == "NEVER" or older_than(used, UNUSED_AFTER_DAYS))
    ]
    no_mfa_console = [
        r for r in people if r["console_password"] == "enabled" and r["mfa"] == "NO"
    ]
    # The root account is reported separately, always. It is the one principal
    # whose posture is never acceptable-by-exception.
    root = next((r for r in people if r["user"] == "<root_account>"), None)

    print("")
    print("SUMMARY")
    print("principals_total=%d" % len(people))
    print("users_with_a_key_older_than_threshold=%d %s"
          % (len(stale), sorted({r["user"] for r in stale})))
    print("users_with_an_old_key_that_is_also_unused=%d %s"
          % (len(abandoned), sorted({r["user"] for r in abandoned})))
    print("users_with_console_access_and_no_mfa=%d %s"
          % (len(no_mfa_console), sorted({r["user"] for r in no_mfa_console})))
    if root:
        print("root_account mfa=%s has_active_keys=%s"
              % (root["mfa"], "yes" if keys_of(root) else "no"))
    print("")
    print("JSON " + json.dumps({"principals": people}, sort_keys=True, default=str))


main()
