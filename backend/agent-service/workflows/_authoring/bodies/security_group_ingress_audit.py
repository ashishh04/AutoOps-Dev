"""Security-group ingress audit. Read-only: DescribeSecurityGroups + DescribeNetworkInterfaces.

An open security group is not by itself an incident — thousands of them sit
unattached in every estate. What matters is whether anything is BEHIND it, and
what that thing is. So this reports the rule and the attachment together, and
the attachment is looked up from network interfaces rather than instances:
a load balancer, an RDS instance and a Lambda ENI are all reachable and none of
them is an EC2 instance.
"""
import json
import re
import sys

import boto3
from botocore.config import Config
from botocore.exceptions import ClientError, NoCredentialsError

REGION = "{{Region}}"
PORT_FILTER = "{{PortFilter}}"

if not re.fullmatch(r"[a-z0-9-]{1,32}", REGION):
    sys.exit("Region %r is not an AWS region name." % REGION)
if PORT_FILTER and not re.fullmatch(r"\d{1,5}(,\d{1,5})*", PORT_FILTER):
    sys.exit("PortFilter %r must be a comma-separated list of port numbers." % PORT_FILTER)

WANTED_PORTS = {int(p) for p in PORT_FILTER.split(",")} if PORT_FILTER else None
CFG = Config(retries={"max_attempts": 5, "mode": "standard"})

#: Ports whose exposure to the whole internet is a finding on its own, with the
#: reason stated so the report can say WHY rather than just listing a number.
NOTORIOUS = {
    22: "SSH — remote shell",
    23: "Telnet — remote shell, unencrypted",
    445: "SMB — file sharing, wormable",
    1433: "MSSQL — database",
    3306: "MySQL — database",
    3389: "RDP — remote desktop",
    5432: "PostgreSQL — database",
    6379: "Redis — frequently unauthenticated",
    9200: "Elasticsearch — frequently unauthenticated",
    27017: "MongoDB — frequently unauthenticated",
}
OPEN_V4 = "0.0.0.0/0"
OPEN_V6 = "::/0"


def port_range(rule):
    """A rule's ports as (from, to), normalising the all-protocols case."""
    if rule.get("IpProtocol") == "-1":
        return 0, 65535
    return rule.get("FromPort", 0), rule.get("ToPort", 65535)


def covers(low, high):
    """The notorious ports a rule's range actually opens."""
    return sorted(p for p in NOTORIOUS if low <= p <= high)


def main():
    ec2 = boto3.client("ec2", region_name=REGION, config=CFG)

    try:
        groups = []
        for page in ec2.get_paginator("describe_security_groups").paginate():
            groups.extend(page["SecurityGroups"])
    except NoCredentialsError:
        sys.exit("No AWS credentials reached this step. Connect an AWS account to this project.")
    except ClientError as exc:
        sys.exit("Could not describe security groups: %s" % exc)

    # What is actually behind each group. An ENI is the honest unit: it covers
    # instances, load balancers, RDS, Lambda and anything else with an address.
    attached = {}
    try:
        for page in ec2.get_paginator("describe_network_interfaces").paginate():
            for eni in page["NetworkInterfaces"]:
                description = eni.get("Description") or ""
                kind = eni.get("InterfaceType", "interface")
                public = eni.get("Association", {}).get("PublicIp")
                label = "%s%s%s" % (
                    kind,
                    "/" + description[:48] if description else "",
                    " public_ip=" + public if public else "",
                )
                for group in eni.get("Groups", []):
                    attached.setdefault(group["GroupId"], []).append(label)
        eni_error = None
    except ClientError as exc:
        # Reported, not fatal. The rules are still worth having; the agent is
        # told the attachment half is missing so it does not read silence as
        # "nothing is behind these".
        eni_error = exc.response.get("Error", {}).get("Code", "Unknown")

    print("SECURITY GROUP INGRESS AUDIT")
    print("region=%s security_groups_examined=%d" % (REGION, len(groups)))
    if WANTED_PORTS:
        print("filtered_to_ports=%s" % sorted(WANTED_PORTS))
    if eni_error:
        print("attachment_lookup_failed=%s (rules below are complete; "
              "what is behind them is NOT known)" % eni_error)
    print("")

    findings = []
    for group in sorted(groups, key=lambda g: g["GroupId"]):
        gid = group["GroupId"]
        for rule in group.get("IpPermissions", []):
            worlds = [r["CidrIp"] for r in rule.get("IpRanges", []) if r.get("CidrIp") == OPEN_V4]
            worlds += [
                r["CidrIpv6"] for r in rule.get("Ipv6Ranges", []) if r.get("CidrIpv6") == OPEN_V6
            ]
            if not worlds:
                continue

            low, high = port_range(rule)
            hits = covers(low, high)
            if WANTED_PORTS and not (WANTED_PORTS & set(range(low, min(high, 65535) + 1))):
                continue

            behind = attached.get(gid, [])
            finding = {
                "group_id": gid,
                "group_name": group.get("GroupName", ""),
                "vpc": group.get("VpcId", "EC2-Classic"),
                "protocol": rule.get("IpProtocol"),
                "ports": "all" if (low, high) == (0, 65535) else (
                    str(low) if low == high else "%d-%d" % (low, high)
                ),
                "open_to": ",".join(worlds),
                "notorious_ports_opened": ",".join(
                    "%d(%s)" % (p, NOTORIOUS[p].split(" — ")[0]) for p in hits
                ) or "none",
                "attached_to_count": len(behind),
                "attached_to": ";".join(sorted(set(behind))[:5]) or (
                    "UNKNOWN" if eni_error else "nothing"
                ),
            }
            findings.append(finding)
            print(" ".join("%s=%s" % (k, v) for k, v in finding.items()))

    live = [f for f in findings if f["attached_to_count"] > 0]
    dangerous = [f for f in live if f["notorious_ports_opened"] != "none"]
    dormant = [f for f in findings if f["attached_to_count"] == 0]

    print("")
    print("SUMMARY")
    print("world_open_rules_total=%d" % len(findings))
    print("world_open_rules_with_something_behind_them=%d" % len(live))
    print("world_open_rules_on_notorious_ports_with_something_behind_them=%d %s"
          % (len(dangerous), sorted({f["group_id"] for f in dangerous})))
    print("world_open_rules_on_groups_attached_to_nothing=%d %s"
          % (len(dormant), sorted({f["group_id"] for f in dormant})))
    print("")
    print("JSON " + json.dumps(
        {"region": REGION, "rules": findings, "attachment_lookup_failed": eni_error},
        sort_keys=True,
    ))


main()
