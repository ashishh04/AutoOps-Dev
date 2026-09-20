"""What changed, in order, and who did it. Read-only CloudTrail.

This is the other half of a root-cause investigation and the half nobody has to
hand during an incident. Monitoring says what broke; only the change record says
what happened to it immediately beforehand, and "nothing changed" is said in
every incident call and is wrong about a third of the time.

**Write events only.** `ReadOnly=false` is passed to CloudTrail, so a Describe
or a List never appears. During an incident the read traffic is enormous and
none of it can have caused anything; including it buries the four events that
matter under four thousand that cannot.

**Console logins and role assumptions are kept**, even though they change
nothing themselves, because "who was in the account at the time" is the first
question asked when the change record is empty.

**Failed calls are kept and marked.** An AccessDenied burst is not a cause of an
outage but it is frequently a symptom of one — a role that lost a permission
fails loudly here before anything else notices.
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
RESOURCE_FILTER = "{{ResourceNameContains}}"
MAX_EVENTS = int("{{MaxEvents}}")

if not re.fullmatch(r"[a-z0-9-]{1,32}", REGION):
    sys.exit("Region %r is not an AWS region name." % REGION)
if not 1 <= LOOKBACK_HOURS <= 168:
    sys.exit("LookbackHours %d is outside 1-168." % LOOKBACK_HOURS)
if RESOURCE_FILTER and not re.fullmatch(r"[A-Za-z0-9 ._:\-/]{0,255}", RESOURCE_FILTER):
    sys.exit("ResourceNameContains %r contains characters a resource name cannot have."
             % RESOURCE_FILTER)
if not 1 <= MAX_EVENTS <= 2000:
    sys.exit("MaxEvents %d is outside 1-2000." % MAX_EVENTS)

CFG = Config(retries={"max_attempts": 5, "mode": "standard"})
NOW = datetime.now(timezone.utc)
SINCE = NOW - timedelta(hours=LOOKBACK_HOURS)

#: Events that change nothing but answer "who was in here".
PRESENCE = ("ConsoleLogin", "AssumeRole", "AssumeRoleWithSAML", "AssumeRoleWithWebIdentity")


def actor_of(event):
    """Who did it, in the shortest form that is still unambiguous."""
    identity = event.get("userIdentity", {}) or {}
    for key in ("userName", "principalId", "arn"):
        value = identity.get(key)
        if value:
            return value.rsplit("/", 1)[-1] if key == "arn" else value
    return event.get("Username") or "unknown"


def resources_of(event, record):
    names = [
        r.get("resourceName") or r.get("ResourceName")
        for r in (record.get("Resources") or event.get("resources") or [])
        if isinstance(r, dict)
    ]
    return ",".join(sorted({n for n in names if n}))[:160] or "-"


def main():
    trail = boto3.client("cloudtrail", region_name=REGION, config=CFG)

    events = []
    try:
        paginator = trail.get_paginator("lookup_events")
        pages = paginator.paginate(
            # The whole point: mutations only.
            LookupAttributes=[{"AttributeKey": "ReadOnly", "AttributeValue": "false"}],
            StartTime=SINCE,
            EndTime=NOW,
            PaginationConfig={"MaxItems": MAX_EVENTS},
        )
        for page in pages:
            events.extend(page.get("Events", []))
    except NoCredentialsError:
        sys.exit("No AWS credentials reached this step. Connect an AWS account to this project.")
    except ClientError as exc:
        code = exc.response.get("Error", {}).get("Code", "")
        if code in ("AccessDeniedException", "UnauthorizedOperation"):
            sys.exit("This account's credentials cannot read CloudTrail "
                     "(cloudtrail:LookupEvents).")
        sys.exit("Could not read CloudTrail: %s" % exc)

    print("CLOUDTRAIL CHANGE TIMELINE")
    print("region=%s lookback_hours=%d window_start=%s write_events_found=%d"
          % (REGION, LOOKBACK_HOURS, SINCE.isoformat(timespec="seconds"), len(events)))
    print("note=read-only API calls are excluded; nothing in this list is a Describe or a List")
    if RESOURCE_FILTER:
        print("filtered_to_resources_containing=%s" % RESOURCE_FILTER)
    if len(events) >= MAX_EVENTS:
        print("truncated=yes (hit MaxEvents; the window may contain changes not listed here)")
    print("")

    rows = []
    for record in sorted(events, key=lambda e: e.get("EventTime")):
        try:
            detail = json.loads(record.get("CloudTrailEvent") or "{}")
        except json.JSONDecodeError:
            detail = {}

        resources = resources_of(detail, record)
        name = record.get("EventName", "?")
        if RESOURCE_FILTER and RESOURCE_FILTER not in resources and RESOURCE_FILTER not in name:
            continue

        when = record.get("EventTime")
        row = {
            "at": when.isoformat(timespec="seconds") if when else "?",
            "minutes_ago": int((NOW - when).total_seconds() // 60) if when else "?",
            "event": name,
            "actor": actor_of(detail),
            "source": detail.get("eventSource", "?").replace(".amazonaws.com", ""),
            "resources": resources,
            "from_ip": detail.get("sourceIPAddress", "-"),
            "outcome": detail.get("errorCode") or "ok",
        }
        rows.append(row)
        print(" ".join("%s=%s" % kv for kv in row.items()))

    changes = [r for r in rows if r["event"] not in PRESENCE]
    failed = [r for r in rows if r["outcome"] != "ok"]
    presence = [r for r in rows if r["event"] in PRESENCE]

    by_actor = {}
    by_event = {}
    for row in changes:
        by_actor[row["actor"]] = by_actor.get(row["actor"], 0) + 1
        by_event[row["event"]] = by_event.get(row["event"], 0) + 1

    print("")
    print("SUMMARY")
    print("changes_in_window=%d (excluding %d sign-in/assume-role events)"
          % (len(changes), len(presence)))
    print("failed_calls=%d %s" % (len(failed), sorted({r["event"] for r in failed})[:10]))
    print("changes_by_actor=%s"
          % sorted(by_actor.items(), key=lambda kv: kv[1], reverse=True)[:10])
    print("most_frequent_changes=%s"
          % sorted(by_event.items(), key=lambda kv: kv[1], reverse=True)[:10])
    if changes:
        print("earliest_change=%s %s" % (changes[0]["at"], changes[0]["event"]))
        print("latest_change=%s %s" % (changes[-1]["at"], changes[-1]["event"]))
    else:
        # The answer everybody claims and nobody verifies. Saying it explicitly,
        # with the window it applies to, is the useful form.
        print("earliest_change=none — no write API call was recorded in this window")
    print("")
    print("JSON " + json.dumps({
        "region": REGION,
        "window_start": SINCE.isoformat(),
        "events": rows,
    }, sort_keys=True, default=str))


main()
