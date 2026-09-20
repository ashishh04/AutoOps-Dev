"""The two automations behind the RCA analyst, against a stubbed AWS.

What is asserted here is the JOIN MATERIAL — the facts the agent needs in order
to collapse a wall of alarms into incidents and line them up against the change
record. Specifically:

* alarms that went red within the same ten minutes are grouped **by the
  automation**, because bucketing forty timestamps is exactly the arithmetic a
  model gets subtly wrong, and a wrong bucket is a wrong incident boundary;
* a flapping alarm is counted so it can be set aside rather than narrated;
* `INSUFFICIENT_DATA` is never merged into OK — a check that stopped reporting
  usually means the thing it measured has gone away;
* an alarm is identified by its resource dimensions, not its name, because names
  say whatever their author felt like;
* an EMPTY change record comes back as an explicit finding with the window
  attached. "Nothing changed" is claimed in every incident call and is wrong
  often enough that the true case has to be stated, not implied by silence.
"""

from __future__ import annotations

import json
from datetime import datetime, timedelta, timezone

from tests.awsfakes import FakeClient, run_body

ALARMS = "AWS/RD-210-cloudwatch-alarm-state-audit.json"
TRAIL = "AWS/RD-211-cloudtrail-change-timeline.json"

NOW = datetime.now(timezone.utc)

ALARM_ARGS = {"Region": "us-east-1", "LookbackHours": 6, "AlarmNamePrefix": ""}
TRAIL_ARGS = {
    "Region": "us-east-1",
    "LookbackHours": 6,
    "ResourceNameContains": "",
    "MaxEvents": 500,
}


# ------------------------------------------------------------- alarms ---


def alarm(name="prod-alb-5xx", state="ALARM", minutes_ago=30, dimensions=None):
    return {
        "AlarmName": name,
        "StateValue": state,
        "StateUpdatedTimestamp": NOW - timedelta(minutes=minutes_ago),
        "Namespace": "AWS/ApplicationELB",
        "MetricName": "HTTPCode_ELB_5XX_Count",
        "Dimensions": dimensions if dimensions is not None else [
            {"Name": "LoadBalancer", "Value": "app/prod-lb/abc"}
        ],
        "StateReason": "Threshold Crossed",
    }


def history(*minutes_ago):
    return {"AlarmHistoryItems": [{"Timestamp": NOW - timedelta(minutes=m)} for m in minutes_ago]}


def alarm_client(alarms, histories=None) -> FakeClient:
    histories = histories or {}
    return FakeClient(service="cloudwatch", responses={
        "paginate:describe_alarms": [{"MetricAlarms": alarms, "CompositeAlarms": []}],
        "describe_alarm_history": lambda **kwargs: histories.get(
            kwargs["AlarmName"], history(30)
        ),
    })


def test_healthy_alarms_are_left_out_so_the_red_ones_are_visible():
    client = alarm_client([alarm(), alarm(name="quiet", state="OK")])
    result = run_body(ALARMS, ALARM_ARGS, {"cloudwatch": client})

    assert result.line("alarms_not_ok=").startswith("alarms_not_ok=1 in_alarm=1")
    assert "alarm=quiet" not in result.stdout


def test_alarms_that_went_red_together_are_clustered_by_the_automation():
    client = alarm_client(
        [alarm(name="alb-5xx"), alarm(name="ecs-cpu"), alarm(name="unrelated", minutes_ago=300)],
        histories={
            "alb-5xx": history(31),
            "ecs-cpu": history(33),
            "unrelated": history(301),
        },
    )
    result = run_body(ALARMS, ALARM_ARGS | {"LookbackHours": 8}, {"cloudwatch": client})

    cluster = result.line("cluster_at=")
    assert "count=2" in cluster
    assert "alb-5xx" in cluster and "ecs-cpu" in cluster
    assert "unrelated" not in cluster


def test_a_lone_alarm_produces_no_cluster_rather_than_a_cluster_of_one():
    client = alarm_client([alarm(name="only-one")], histories={"only-one": history(20)})
    result = run_body(ALARMS, ALARM_ARGS, {"cloudwatch": client})

    assert result.line("time_clusters=").startswith("time_clusters=0")


def test_a_flapping_alarm_is_counted_so_it_can_be_set_aside():
    client = alarm_client([alarm(name="marginal")],
                          histories={"marginal": history(5, 12, 19, 26, 33, 40)})
    result = run_body(ALARMS, ALARM_ARGS, {"cloudwatch": client})

    assert "state_changes_in_window=6" in result.stdout
    assert "marginal" in result.line("flapping_4_or_more_changes=")


def test_insufficient_data_is_reported_and_never_merged_with_ok():
    client = alarm_client([alarm(name="gone", state="INSUFFICIENT_DATA")])
    result = run_body(ALARMS, ALARM_ARGS, {"cloudwatch": client})

    assert result.line("alarms_not_ok=").endswith("insufficient_data=1")
    assert result.has("INSUFFICIENT_DATA is not OK")


def test_an_alarm_is_identified_by_its_resource_not_its_name():
    client = alarm_client([alarm(name="CRITICAL-CHECK-7")])
    result = run_body(ALARMS, ALARM_ARGS, {"cloudwatch": client})

    assert "resource=LoadBalancer=app/prod-lb/abc" in result.stdout


def test_an_alarm_with_no_dimensions_falls_back_rather_than_inventing_a_resource():
    client = alarm_client([alarm(name="composite", dimensions=[])])
    result = run_body(ALARMS, ALARM_ARGS, {"cloudwatch": client})

    assert "resource=AWS/ApplicationELB/HTTPCode_ELB_5XX_Count" in result.stdout


# -------------------------------------------------------- change trail ---


def trail_event(name="ModifySecurityGroupRules", minutes_ago=34, actor="deploy-ci",
                resources="sg-0f1e2d3c", error=None):
    detail = {
        "eventSource": "ec2.amazonaws.com",
        "sourceIPAddress": "203.0.113.7",
        "userIdentity": {"userName": actor},
        "resources": [{"resourceName": resources}],
    }
    if error:
        detail["errorCode"] = error
    return {
        "EventTime": NOW - timedelta(minutes=minutes_ago),
        "EventName": name,
        "CloudTrailEvent": json.dumps(detail),
        "Resources": [{"ResourceName": resources}],
    }


def trail_client(events) -> FakeClient:
    return FakeClient(service="cloudtrail",
                      responses={"paginate:lookup_events": [{"Events": events}]})


def test_the_change_timeline_is_ordered_oldest_first_and_carries_who_and_when():
    client = trail_client([
        trail_event(minutes_ago=10, name="UpdateService"),
        trail_event(minutes_ago=34, name="ModifySecurityGroupRules"),
    ])
    result = run_body(TRAIL, TRAIL_ARGS, {"cloudtrail": client})

    lines = [line for line in result.stdout.splitlines() if line.startswith("at=")]
    assert "ModifySecurityGroupRules" in lines[0], "the oldest change must come first"
    assert "actor=deploy-ci" in lines[0]
    assert "minutes_ago=34" in lines[0]
    assert result.has("earliest_change=")


def test_an_empty_change_record_is_an_explicit_finding():
    result = run_body(TRAIL, TRAIL_ARGS, {"cloudtrail": trail_client([])})

    assert result.has("no write API call was recorded in this window")
    assert result.line("changes_in_window=").startswith("changes_in_window=0")


def test_sign_ins_are_kept_but_counted_separately_from_changes():
    client = trail_client([
        trail_event(name="ConsoleLogin", resources="-"),
        trail_event(name="ModifySecurityGroupRules"),
    ])
    result = run_body(TRAIL, TRAIL_ARGS, {"cloudtrail": client})

    line = result.line("changes_in_window=")
    assert line.startswith("changes_in_window=1")
    assert "1 sign-in/assume-role events" in line


def test_a_failed_call_is_kept_and_marked():
    client = trail_client([trail_event(name="AssumeRole", error="AccessDenied")])
    result = run_body(TRAIL, TRAIL_ARGS, {"cloudtrail": client})

    assert "outcome=AccessDenied" in result.stdout
    assert "AssumeRole" in result.line("failed_calls=")


def test_the_resource_filter_narrows_the_timeline_to_one_service():
    client = trail_client([
        trail_event(name="ModifySecurityGroupRules", resources="sg-0f1e2d3c"),
        trail_event(name="PutBucketPolicy", resources="unrelated-bucket"),
    ])
    result = run_body(TRAIL, TRAIL_ARGS | {"ResourceNameContains": "sg-0f1e"},
                      {"cloudtrail": client})

    assert "ModifySecurityGroupRules" in result.stdout
    assert "PutBucketPolicy" not in result.stdout


def test_a_truncated_timeline_never_reads_as_a_complete_one():
    client = trail_client([trail_event(minutes_ago=n) for n in range(1, 4)])
    result = run_body(TRAIL, TRAIL_ARGS | {"MaxEvents": 3}, {"cloudtrail": client})

    assert result.has("truncated=yes")
