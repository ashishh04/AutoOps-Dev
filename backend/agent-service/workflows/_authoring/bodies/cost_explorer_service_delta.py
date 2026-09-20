"""What moved the bill. Read-only: Cost Explorer GetCostAndUsage.

Compares a recent window against the window immediately before it, by service,
and ranks services by how much they MOVED rather than by how much they cost.
The largest line on an AWS bill is almost never the interesting one; the line
that doubled is.

Two honesty constraints are built in rather than left to the prompt:

* Cost Explorer's last day or two are estimates that settle later. The window
  therefore ends YESTERDAY, and the fact is printed, so nobody reports a
  "drop" that is really an incomplete day.
* The delta is computed here, in arithmetic, and printed. A model asked to
  subtract forty pairs of numbers will get one wrong, and a cited-but-wrong
  figure is the worst possible output.
"""
import json
import sys
from datetime import date, timedelta

import boto3
from botocore.config import Config
from botocore.exceptions import ClientError, NoCredentialsError

WINDOW_DAYS = int("{{WindowDays}}")
METRIC = "{{CostMetric}}"

if not 1 <= WINDOW_DAYS <= 90:
    sys.exit("WindowDays %d is outside 1-90." % WINDOW_DAYS)
if METRIC not in ("UnblendedCost", "AmortizedCost", "NetUnblendedCost"):
    sys.exit("CostMetric %r is not a Cost Explorer metric this audit supports." % METRIC)

# Cost Explorer is a global service with a us-east-1 endpoint. The region here
# is the ENDPOINT, not the region being reported on: the figures cover the
# whole account.
CFG = Config(retries={"max_attempts": 5, "mode": "standard"})


def totals(client, start, end):
    """Cost by service for a half-open [start, end) window."""
    by_service = {}
    token = None
    while True:
        kwargs = {
            "TimePeriod": {"Start": start.isoformat(), "End": end.isoformat()},
            "Granularity": "MONTHLY" if (end - start).days > 31 else "DAILY",
            "Metrics": [METRIC],
            "GroupBy": [{"Type": "DIMENSION", "Key": "SERVICE"}],
        }
        if token:
            kwargs["NextPageToken"] = token
        page = client.get_cost_and_usage(**kwargs)
        for block in page.get("ResultsByTime", []):
            for group in block.get("Groups", []):
                name = group["Keys"][0]
                amount = float(group["Metrics"][METRIC]["Amount"])
                by_service[name] = by_service.get(name, 0.0) + amount
        token = page.get("NextPageToken")
        if not token:
            break
    return by_service


def main():
    client = boto3.client("ce", region_name="us-east-1", config=CFG)

    # Ends yesterday: today is partial and the last settled day is the only
    # one worth comparing against.
    recent_end = date.today()
    recent_start = recent_end - timedelta(days=WINDOW_DAYS)
    prior_end = recent_start
    prior_start = prior_end - timedelta(days=WINDOW_DAYS)

    try:
        recent = totals(client, recent_start, recent_end)
        prior = totals(client, prior_start, prior_end)
    except NoCredentialsError:
        sys.exit("No AWS credentials reached this step. Connect an AWS account to this project.")
    except ClientError as exc:
        code = exc.response.get("Error", {}).get("Code", "")
        if code == "AccessDeniedException":
            sys.exit("This account's credentials cannot read Cost Explorer (ce:GetCostAndUsage). "
                     "Cost Explorer must also be enabled on the payer account.")
        sys.exit("Could not read Cost Explorer: %s" % exc)

    currency = "USD"
    print("COST EXPLORER SERVICE DELTA")
    print("metric=%s currency=%s" % (METRIC, currency))
    print("recent_window=%s..%s prior_window=%s..%s (each %d days, end date exclusive)"
          % (recent_start, recent_end, prior_start, prior_end, WINDOW_DAYS))
    print("note=the most recent day or two of Cost Explorer data are estimates and settle later")
    print("")

    services = sorted(set(recent) | set(prior))
    rows = []
    for name in services:
        now = round(recent.get(name, 0.0), 2)
        before = round(prior.get(name, 0.0), 2)
        delta = round(now - before, 2)
        if abs(now) < 0.01 and abs(before) < 0.01:
            continue
        percent = "new" if before < 0.01 else "%+.1f%%" % ((delta / before) * 100.0)
        rows.append({
            "service": name,
            "recent": now,
            "prior": before,
            "delta": delta,
            "change": percent,
        })

    rows.sort(key=lambda r: abs(r["delta"]), reverse=True)
    for row in rows:
        print("service=%s recent=%.2f prior=%.2f delta=%+.2f change=%s"
              % (row["service"], row["recent"], row["prior"], row["delta"], row["change"]))

    recent_total = round(sum(r["recent"] for r in rows), 2)
    prior_total = round(sum(r["prior"] for r in rows), 2)
    movement = round(recent_total - prior_total, 2)
    risers = [r for r in rows if r["delta"] > 0]
    fallers = [r for r in rows if r["delta"] < 0]

    print("")
    print("SUMMARY")
    print("recent_total=%.2f prior_total=%.2f delta=%+.2f change=%s"
          % (recent_total, prior_total, movement,
             "new" if prior_total < 0.01 else "%+.1f%%" % ((movement / prior_total) * 100.0)))
    print("services_up=%d services_down=%d" % (len(risers), len(fallers)))
    top = risers[:3]
    if top:
        covered = round(sum(r["delta"] for r in top), 2)
        print("largest_increases=%s" % [
            "%s %+.2f" % (r["service"], r["delta"]) for r in top
        ])
        print("those_three_explain=%.2f of the %+.2f movement" % (covered, movement))
    print("")
    print("JSON " + json.dumps({
        "metric": METRIC,
        "recent_window": [recent_start.isoformat(), recent_end.isoformat()],
        "prior_window": [prior_start.isoformat(), prior_end.isoformat()],
        "services": rows,
        "recent_total": recent_total,
        "prior_total": prior_total,
        "delta": movement,
    }, sort_keys=True))


main()
