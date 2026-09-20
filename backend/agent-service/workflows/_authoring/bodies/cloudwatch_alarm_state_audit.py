"""What is broken, and since when. Read-only CloudWatch.

The raw material for correlation. An incident is rarely one alarm — it is nine
alarms that fired within four minutes of each other because one thing underneath
them moved. To see that, three things have to be reported together and usually
are not:

* **the resource**, from the alarm's dimensions, not its name. Alarm names are
  written by whoever created them and say whatever that person felt like;
  dimensions say `InstanceId=i-0abc` and `LoadBalancer=app/prod-lb`, which is
  what lets two alarms be recognised as being about the same thing.
* **the transition time**, to the second. Correlation is a question about
  clustering in time, and "in ALARM" without "since when" cannot answer it.
* **the flapping count**. An alarm that has changed state eleven times in an
  hour is a different problem from one that went to ALARM once and stayed
  there, and treating them alike is how the noisy one gets muted and the real
  one gets missed.

INSUFFICIENT_DATA is reported alongside ALARM and never merged with OK. A check
that stopped reporting is not a check that passed — it frequently means the
thing being measured has gone away entirely, which during an incident is the
most informative state of the three.
"""
import json
import re
import sys
from datetime import datetime, timedelta, timezone

import boto3
from botocore.config import Config
from botocore.exceptions import ClientError, NoCredentialsError

REGION = "{{Region}}"
LOOKBACK_HOURS = int("{{LookbackHours}}")
NAME_PREFIX = "{{AlarmNamePrefix}}"

if not re.fullmatch(r"[a-z0-9-]{1,32}", REGION):
    sys.exit("Region %r is not an AWS region name." % REGION)
if not 1 <= LOOKBACK_HOURS <= 168:
    sys.exit("LookbackHours %d is outside 1-168." % LOOKBACK_HOURS)
if NAME_PREFIX and not re.fullmatch(r"[A-Za-z0-9 ._:\-/]{0,255}", NAME_PREFIX):
    sys.exit("AlarmNamePrefix %r contains characters an alarm name cannot have." % NAME_PREFIX)

CFG = Config(retries={"max_attempts": 5, "mode": "standard"})
NOW = datetime.now(timezone.utc)
SINCE = NOW - timedelta(hours=LOOKBACK_HOURS)

#: Alarms whose first state change is within this of each other are treated as
#: one cluster. Ten minutes is wide enough to cover a failure propagating from a
#: target group to a load balancer to a synthetic canary, and narrow enough that
#: two genuinely separate incidents in the same hour stay separate.
CLUSTER_GAP_MINUTES = 10


def resource_of(alarm):
    """The thing an alarm is about, from its dimensions.

    Falls back to the metric and namespace when an alarm has no dimensions —
    a metric-math or composite alarm — rather than inventing a resource id.
    """
    dimensions = alarm.get("Dimensions") or []
    if dimensions:
        return ",".join("%s=%s" % (d.get("Name"), d.get("Value")) for d in dimensions)
    return "%s/%s" % (alarm.get("Namespace", "?"), alarm.get("MetricName", "?"))


def main():
    cloudwatch = boto3.client("cloudwatch", region_name=REGION, config=CFG)

    alarms = []
    try:
        paginator = cloudwatch.get_paginator("describe_alarms")
        kwargs = {"AlarmTypes": ["MetricAlarm", "CompositeAlarm"]}
        if NAME_PREFIX:
            kwargs["AlarmNamePrefix"] = NAME_PREFIX
        for page in paginator.paginate(**kwargs):
            alarms.extend(page.get("MetricAlarms", []))
            alarms.extend(page.get("CompositeAlarms", []))
    except NoCredentialsError:
        sys.exit("No AWS credentials reached this step. Connect an AWS account to this project.")
    except ClientError as exc:
        sys.exit("Could not describe alarms: %s" % exc)

    print("CLOUDWATCH ALARM STATE AUDIT")
    print("region=%s lookback_hours=%d window_start=%s alarms_examined=%d"
          % (REGION, LOOKBACK_HOURS, SINCE.isoformat(timespec="seconds"), len(alarms)))
    if NAME_PREFIX:
        print("filtered_to_prefix=%s" % NAME_PREFIX)
    print("")

    rows = []
    starts = []
    history_failures = []
    for alarm in alarms:
        state = alarm.get("StateValue", "?")
        if state == "OK":
            # A healthy alarm is not evidence in an incident, and a report that
            # lists four hundred of them buries the nine that matter.
            continue

        name = alarm.get("AlarmName", "?")
        changed = alarm.get("StateUpdatedTimestamp")
        row = {
            "alarm": name,
            "state": state,
            "resource": resource_of(alarm),
            "metric": "%s/%s" % (alarm.get("Namespace", "?"), alarm.get("MetricName", "-")),
            "in_state_since": changed.isoformat(timespec="seconds") if changed else "unknown",
            "minutes_in_state": (
                int((NOW - changed).total_seconds() // 60) if changed else "unknown"
            ),
            "reason": (alarm.get("StateReason") or "")[:120].replace("\n", " "),
        }

        # How often it has flipped inside the window. This is what separates a
        # genuine outage from a threshold set too tightly.
        first_change = None
        try:
            history = cloudwatch.describe_alarm_history(
                AlarmName=name,
                HistoryItemType="StateUpdate",
                StartDate=SINCE,
                EndDate=NOW,
                MaxRecords=100,
            )
            transitions = history.get("AlarmHistoryItems", [])
            row["state_changes_in_window"] = len(transitions)
            if transitions:
                first_change = min(item["Timestamp"] for item in transitions)
                row["first_change_in_window"] = first_change.isoformat(timespec="seconds")
        except ClientError as exc:
            history_failures.append("%s:%s" % (name, exc.response.get("Error", {}).get("Code", "?")))
            row["state_changes_in_window"] = "unreadable"

        rows.append(row)
        starts.append((first_change, row["alarm"]))
        print(" ".join("%s=%s" % kv for kv in row.items()))

    firing = [r for r in rows if r["state"] == "ALARM"]
    blind = [r for r in rows if r["state"] == "INSUFFICIENT_DATA"]
    flapping = [
        r for r in rows
        if isinstance(r["state_changes_in_window"], int) and r["state_changes_in_window"] >= 4
    ]

    # The correlation hint: alarms that started within CLUSTER_GAP_MINUTES of
    # each other are very likely one incident. Computed here rather than left to
    # the model, because bucketing forty timestamps by hand is exactly the
    # arithmetic a model gets subtly wrong — and a wrong bucket is a wrong
    # incident boundary.
    #
    # Gap-based, NOT a fixed grid. Flooring each timestamp to a ten-minute slot
    # is the obvious implementation and it is wrong in the most common case:
    # two alarms ninety seconds apart at 12:39 and 12:41 land in different
    # slots and get reported as two incidents. Clustering on the GAP between
    # consecutive starts has no boundary to fall the wrong side of.
    ordered = sorted((when, name) for when, name in starts if when is not None)
    clusters = []
    current = []
    previous = None
    for when, name in ordered:
        if previous is not None and (when - previous).total_seconds() > CLUSTER_GAP_MINUTES * 60:
            if len(current) > 1:
                clusters.append((current[0][0], [n for _, n in current]))
            current = []
        current.append((when, name))
        previous = when
    if len(current) > 1:
        clusters.append((current[0][0], [n for _, n in current]))
    clusters = [(when.isoformat(timespec="seconds"), names) for when, names in clusters]

    print("")
    print("SUMMARY")
    print("alarms_not_ok=%d in_alarm=%d insufficient_data=%d"
          % (len(rows), len(firing), len(blind)))
    print("flapping_4_or_more_changes=%d %s"
          % (len(flapping), sorted(r["alarm"] for r in flapping)))
    if clusters:
        print("time_clusters=%d (alarms that started within %d minutes of each other)"
              % (len(clusters), CLUSTER_GAP_MINUTES))
        for when, names in clusters:
            print("cluster_at=%s count=%d alarms=%s" % (when, len(names), sorted(names)))
    else:
        print("time_clusters=0 (no two alarms started within %d minutes of each other)"
              % CLUSTER_GAP_MINUTES)
    if blind:
        print("note=INSUFFICIENT_DATA is not OK. A check that stopped reporting often means "
              "the thing it measured has gone away.")
    if history_failures:
        print("alarm_history_unreadable=%s" % history_failures[:10])
    print("")
    print("JSON " + json.dumps({
        "region": REGION,
        "window_start": SINCE.isoformat(),
        "alarms": rows,
        "clusters": [{"at": when, "alarms": names} for when, names in clusters],
    }, sort_keys=True, default=str))


main()
