"""What is running that nobody is using. Read-only: EC2 Describe* only.

Three kinds of waste, in one pass, because they are usually the same story:
an instance was terminated, its volume was kept, and its elastic IP was never
released.

**On the cost figures.** AWS does not expose "what is this volume costing me"
as an API. The estimates below are computed from published list prices for the
region named in PRICES, and every line that carries one says so in the field
name. They are the right order of magnitude for deciding what to clean up and
the wrong number to put in a financial report, which is exactly how they are
labelled. Where a volume type or region is not in the table, the field reads
`unpriced` rather than guessing — a made-up figure is worse than no figure.
"""
import json
import re
import sys
from datetime import datetime, timezone

import boto3
from botocore.config import Config
from botocore.exceptions import ClientError, NoCredentialsError

REGION = "{{Region}}"
MIN_AGE_DAYS = int("{{MinimumAgeDays}}")

if not re.fullmatch(r"[a-z0-9-]{1,32}", REGION):
    sys.exit("Region %r is not an AWS region name." % REGION)
if not 0 <= MIN_AGE_DAYS <= 3650:
    sys.exit("MinimumAgeDays %d is outside 0-3650." % MIN_AGE_DAYS)

CFG = Config(retries={"max_attempts": 5, "mode": "standard"})
NOW = datetime.now(timezone.utc)

#: Published USD per GB-month, us-east-1, at time of authoring. Used ONLY to
#: rank candidates by rough size of prize. Every output field built from this
#: carries the basis in its own name so a figure cannot be quoted without it.
PRICES_REGION = "us-east-1"
PRICES = {
    "gp3": 0.08, "gp2": 0.10, "io1": 0.125, "io2": 0.125,
    "st1": 0.045, "sc1": 0.015, "standard": 0.05,
}
#: An idle (unassociated) elastic IP is charged by the hour.
EIP_MONTHLY = 0.005 * 730


def estimate(volume_type, size_gb):
    rate = PRICES.get(volume_type)
    if rate is None:
        return "unpriced"
    return round(rate * size_gb, 2)


def age_of(timestamp):
    return int((NOW - timestamp).total_seconds() // 86400)


def tags_of(item):
    return {t["Key"]: t["Value"] for t in item.get("Tags", [])}


def main():
    ec2 = boto3.client("ec2", region_name=REGION, config=CFG)

    print("IDLE RESOURCE INVENTORY")
    print("region=%s minimum_age_days=%d" % (REGION, MIN_AGE_DAYS))
    print("cost_estimate_basis=published %s list prices; indicative only, not billing data"
          % PRICES_REGION)
    if REGION != PRICES_REGION:
        print("cost_estimate_caveat=this run is against %s and the prices are %s; "
              "treat the figures as relative sizes, not amounts" % (REGION, PRICES_REGION))
    print("")

    volumes, eips, stopped = [], [], []
    failures = []

    try:
        pages = ec2.get_paginator("describe_volumes").paginate(
            Filters=[{"Name": "status", "Values": ["available"]}]
        )
        for page in pages:
            for vol in page["Volumes"]:
                age = age_of(vol["CreateTime"])
                if age < MIN_AGE_DAYS:
                    continue
                tags = tags_of(vol)
                row = {
                    "volume_id": vol["VolumeId"],
                    "size_gb": vol["Size"],
                    "type": vol.get("VolumeType", "?"),
                    "az": vol.get("AvailabilityZone", "?"),
                    "age_days": age,
                    "encrypted": "yes" if vol.get("Encrypted") else "no",
                    "snapshot_of_origin": vol.get("SnapshotId") or "none",
                    "est_monthly_usd_list_price": estimate(vol.get("VolumeType", ""), vol["Size"]),
                    "name_tag": tags.get("Name", ""),
                    "owner_tag": tags.get("Owner") or tags.get("owner", ""),
                }
                volumes.append(row)
                print("UNATTACHED_VOLUME " + " ".join("%s=%s" % kv for kv in row.items()))
    except NoCredentialsError:
        sys.exit("No AWS credentials reached this step. Connect an AWS account to this project.")
    except ClientError as exc:
        failures.append("describe_volumes:%s" % exc.response.get("Error", {}).get("Code", "?"))

    try:
        for addr in ec2.describe_addresses().get("Addresses", []):
            if addr.get("AssociationId"):
                continue
            tags = tags_of(addr)
            row = {
                "allocation_id": addr.get("AllocationId", "classic"),
                "public_ip": addr.get("PublicIp", "?"),
                "domain": addr.get("Domain", "?"),
                "est_monthly_usd_list_price": round(EIP_MONTHLY, 2),
                "name_tag": tags.get("Name", ""),
            }
            eips.append(row)
            print("UNASSOCIATED_EIP " + " ".join("%s=%s" % kv for kv in row.items()))
    except ClientError as exc:
        failures.append("describe_addresses:%s" % exc.response.get("Error", {}).get("Code", "?"))

    try:
        pages = ec2.get_paginator("describe_instances").paginate(
            Filters=[{"Name": "instance-state-name", "Values": ["stopped"]}]
        )
        for page in pages:
            for reservation in page["Reservations"]:
                for inst in reservation["Instances"]:
                    tags = tags_of(inst)
                    attached = [
                        m["Ebs"]["VolumeId"] for m in inst.get("BlockDeviceMappings", [])
                        if m.get("Ebs")
                    ]
                    transition = inst.get("StateTransitionReason", "")
                    row = {
                        "instance_id": inst["InstanceId"],
                        "type": inst.get("InstanceType", "?"),
                        "stopped_since": transition or "unknown",
                        "attached_volumes": len(attached),
                        "name_tag": tags.get("Name", ""),
                    }
                    # A stopped instance is free; its disks are not. That is the
                    # part people are surprised by, so it is the part reported.
                    stopped.append(row)
                    print("STOPPED_INSTANCE " + " ".join("%s=%s" % kv for kv in row.items()))
    except ClientError as exc:
        failures.append("describe_instances:%s" % exc.response.get("Error", {}).get("Code", "?"))

    priced = [v["est_monthly_usd_list_price"] for v in volumes
              if isinstance(v["est_monthly_usd_list_price"], float)]
    volume_cost = round(sum(priced), 2)
    eip_cost = round(sum(e["est_monthly_usd_list_price"] for e in eips), 2)
    unpriced = [v["volume_id"] for v in volumes
                if v["est_monthly_usd_list_price"] == "unpriced"]

    print("")
    print("SUMMARY")
    print("unattached_volumes=%d total_gb=%d est_monthly_usd_list_price=%.2f"
          % (len(volumes), sum(v["size_gb"] for v in volumes), volume_cost))
    if unpriced:
        print("volumes_with_no_published_price=%s" % unpriced)
    print("unassociated_eips=%d est_monthly_usd_list_price=%.2f" % (len(eips), eip_cost))
    print("stopped_instances=%d (the instances are free; their attached disks are not)"
          % len(stopped))
    print("combined_est_monthly_usd_list_price=%.2f" % round(volume_cost + eip_cost, 2))
    if failures:
        # An audit that could not look must not read as an audit that found
        # nothing.
        print("collections_that_failed=%s" % failures)
    print("")
    print("JSON " + json.dumps({
        "region": REGION,
        "price_basis_region": PRICES_REGION,
        "unattached_volumes": volumes,
        "unassociated_eips": eips,
        "stopped_instances": stopped,
        "failures": failures,
    }, sort_keys=True, default=str))


main()
