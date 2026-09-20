"""S3 public-exposure audit. Read-only: every call is a List/Get.

Reports, per bucket, the four things that together decide whether an object in
it can be read by the internet: the public access block, the ACL, the bucket
policy's own public verdict, and — because it is the question always asked
next — encryption and versioning.

A bucket is not "public" because one of these is set. It is public because of a
CHAIN, and reporting the parts separately is what lets the agent say which
chain is complete.
"""
import json
import re
import sys

import boto3
from botocore.config import Config
from botocore.exceptions import ClientError, NoCredentialsError

REGION = "{{Region}}"
PREFIX = "{{BucketNamePrefix}}"

if not re.fullmatch(r"[a-z0-9-]{1,32}", REGION):
    sys.exit("Region %r is not an AWS region name." % REGION)
if PREFIX and not re.fullmatch(r"[a-z0-9.\-]{0,63}", PREFIX):
    sys.exit("BucketNamePrefix %r is not a valid bucket-name prefix." % PREFIX)

CFG = Config(retries={"max_attempts": 5, "mode": "standard"})
PUBLIC_URIS = (
    "http://acs.amazonaws.com/groups/global/AllUsers",
    "http://acs.amazonaws.com/groups/global/AuthenticatedUsers",
)


def soft(call, *absent_codes):
    """Runs a Get that is ALLOWED to be absent, and says which absence it was.

    An unconfigured feature and a denied permission are different findings and
    must not collapse into one. The first is the customer's posture; the second
    is our own access. An audit that reports "not encrypted" when it merely
    could not look is worse than one that says it could not look.
    """
    try:
        return call(), None
    except ClientError as exc:
        code = exc.response.get("Error", {}).get("Code", "Unknown")
        if code in absent_codes:
            return None, None
        return None, code


def main():
    s3 = boto3.client("s3", region_name=REGION, config=CFG)
    try:
        buckets = s3.list_buckets().get("Buckets", [])
    except NoCredentialsError:
        sys.exit("No AWS credentials reached this step. Connect an AWS account to this project.")
    except ClientError as exc:
        sys.exit("Could not list buckets: %s" % exc)

    names = sorted(b["Name"] for b in buckets)
    if PREFIX:
        names = [n for n in names if n.startswith(PREFIX)]

    print("S3 PUBLIC ACCESS AUDIT")
    print("queried_from_region=%s buckets_examined=%d" % (REGION, len(names)))
    if PREFIX:
        print("filtered_to_prefix=%s" % PREFIX)
    print("")

    rows = []
    for name in names:
        row = {"bucket": name}

        located, err = soft(lambda: s3.get_bucket_location(Bucket=name))
        row["region"] = "unreadable(%s)" % err if err else (
            (located or {}).get("LocationConstraint") or "us-east-1"
        )

        blocked, err = soft(
            lambda: s3.get_public_access_block(Bucket=name),
            "NoSuchPublicAccessBlockConfiguration",
        )
        if err:
            row["public_access_block"] = "unreadable(%s)" % err
        elif blocked is None:
            # ABSENT is not the same as "off by default". With no block config
            # at all, an ACL or a policy granting the world IS honoured.
            row["public_access_block"] = "ABSENT"
        else:
            cfg = blocked["PublicAccessBlockConfiguration"]
            on = [k for k, v in sorted(cfg.items()) if v]
            row["public_access_block"] = (
                "all4" if len(on) == 4 else "partial:" + ",".join(on or ["none"])
            )

        acl, err = soft(lambda: s3.get_bucket_acl(Bucket=name))
        if err:
            row["acl_public"] = "unreadable(%s)" % err
        else:
            grants = [
                g for g in acl.get("Grants", [])
                if g.get("Grantee", {}).get("URI") in PUBLIC_URIS
            ]
            row["acl_public"] = "no" if not grants else "YES:" + ",".join(sorted(
                "%s=%s" % (g["Grantee"]["URI"].rsplit("/", 1)[-1], g.get("Permission", "?"))
                for g in grants
            ))

        status, err = soft(lambda: s3.get_bucket_policy_status(Bucket=name), "NoSuchBucketPolicy")
        if err:
            row["policy_public"] = "unreadable(%s)" % err
        elif status is None:
            row["policy_public"] = "no-policy"
        else:
            row["policy_public"] = "YES" if status["PolicyStatus"]["IsPublic"] else "no"

        enc, err = soft(
            lambda: s3.get_bucket_encryption(Bucket=name),
            "ServerSideEncryptionConfigurationNotFoundError",
        )
        if err:
            row["encryption"] = "unreadable(%s)" % err
        elif enc is None:
            row["encryption"] = "NONE"
        else:
            algos = sorted({
                r["ApplyServerSideEncryptionByDefault"]["SSEAlgorithm"]
                for r in enc["ServerSideEncryptionConfiguration"]["Rules"]
                if "ApplyServerSideEncryptionByDefault" in r
            })
            row["encryption"] = ",".join(algos) or "NONE"

        ver, err = soft(lambda: s3.get_bucket_versioning(Bucket=name))
        row["versioning"] = "unreadable(%s)" % err if err else (ver or {}).get("Status", "Disabled")

        rows.append(row)
        print(" ".join("%s=%s" % (k, v) for k, v in row.items()))

    exposed = [
        r for r in rows
        if str(r.get("acl_public", "")).startswith("YES") or r.get("policy_public") == "YES"
    ]
    unblocked = [
        r for r in rows
        if r.get("public_access_block") == "ABSENT"
        or str(r.get("public_access_block", "")).startswith("partial")
    ]
    unencrypted = [r for r in rows if r.get("encryption") == "NONE"]
    unreadable = sorted({
        str(v) for r in rows for v in r.values() if str(v).startswith("unreadable(")
    })

    print("")
    print("SUMMARY")
    print("buckets_total=%d" % len(rows))
    print("buckets_public_via_acl_or_policy=%d %s" % (len(exposed), [r["bucket"] for r in exposed]))
    print("buckets_without_full_public_access_block=%d %s"
          % (len(unblocked), [r["bucket"] for r in unblocked]))
    print("buckets_unencrypted=%d %s" % (len(unencrypted), [r["bucket"] for r in unencrypted]))
    if unreadable:
        # Named so the agent reports "I could not check X" rather than treating
        # our own missing permission as a clean result.
        print("checks_denied_or_failed=%s" % unreadable)
    print("")
    print("JSON " + json.dumps({"region": REGION, "buckets": rows}, sort_keys=True))


main()
