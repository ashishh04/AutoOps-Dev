"""The FinOps agent, built to the supplied spec against the tools that exist.

**What this is, and how it differs from the investigator next door.**
``aws.cost_anomaly_investigator`` answers one question somebody asked: why did
the bill move. This one produces a BACKLOG — a ranked, deduplicated list of
findings somebody works through — which is a different job with different
failure modes. The investigator may be wrong once and waste an afternoon. This
one runs daily and, without a stable key, reopens the same four hundred tickets
every morning until nobody reads any of them.

**Which of the spec's eight sub-capabilities are real here, and which are not.**
Stated up front because an agent that silently covers a third of its brief is
worse than one that covers a third and says so:

===========================  ======================================
2. spend anomaly + attribution  BUILT — RD-141 for the per-service delta,
                                RD-211 for the change that plausibly caused it
4. waste sweep                  BUILT — RD-136: unattached volumes,
                                unassociated elastic IPs, stopped instances
---------------------------  --------------------------------------
1. allocation / showback        NOT BUILT — needs a tag allocation map; the
                                platform has no tag inventory tool
3. rightsizing                  NOT BUILT — needs k8s requests vs p95 AND SLO
                                headroom. Neither exists, and the spec is
                                explicit that rightsizing without headroom is
                                the recommendation most likely to cause an
                                outage. Absent the headroom check, the correct
                                number of rightsizing findings is zero.
5. commitment coverage          NOT BUILT — needs RI/SP inventory
6. retention and tiering        NOT BUILT — needs telemetry-store metrics
7. egress                       NOT BUILT — needs flow logs or cross-AZ metrics
8. forecast / budget burn       NOT BUILT — needs a budget and a denominator
===========================  ======================================

**Unit cost is the spec's primary series and this agent cannot compute it.**
There is no denominator available — no request count, tenant count, orders, GB
ingested. So every figure here is absolute spend, which the spec correctly warns
makes every growth quarter look like a regression. The persona is required to
say so on any anomaly finding rather than let absolute movement read as waste.

**The retroactive-billing rule is the one that matters most.** Cost Explorer
lags and is revised: a day loaded at 40% looks exactly like a 60% cost drop.
The spec's fallback is not "lower the confidence" — it is emit findings with
``is_partial`` and **suppress anomaly findings entirely**. A waste finding from
a partial day is still true (an unattached volume is unattached regardless of
billing completeness); an anomaly finding from one is fiction.
"""

from __future__ import annotations

from agent_runtime.agents.spec import AgentSpec, Manifest, ToolRef
from agent_runtime.app.extraction import SubjectSource
from agent_runtime.app.state import Phase
from agent_runtime.graph import kit

REF = "aws.finops_analyst"
VERSION = "1.0.0"

#: Read-only throughout. The spec calls this "the agent most likely to cause an
#: outage", and the guardrail that actually delivers that is holding no tool
#: that can act. Reclaiming what it finds belongs to
#: ``aws.idle_resource_reclaimer``, which has a human approval gate in front of
#: every deletion.
PHASES = (Phase.TRIAGE, Phase.GATHER, Phase.HYPOTHESIZE, Phase.REPORT)

PERSONA = """\
You are a FinOps analyst for a managed-services provider, producing a BACKLOG
rather than answering a question. Someone will work through your findings one by
one, and they will stop reading the day your list repeats itself or sends them
to delete something that was in use.

WHAT YOU PRODUCE

A ranked list of cost findings. Each one names a resource, a category, an
estimated monthly saving, a risk tier, and the evidence behind it. Rank by
SAVINGS DIVIDED BY RISK, never by savings alone — the largest number on the page
is usually the thing most likely to break something.

THE TWO THINGS YOU CAN FIND, AND THE SIX YOU CANNOT

You can find WASTE: unattached volumes, unassociated elastic IPs, stopped
instances still paying for their disks. The inventory gives you these directly.

You can find a SPEND ANOMALY and, sometimes, attribute it: the cost delta names
a service that moved, and the change timeline may contain a change in the same
window that plausibly explains it. "Spend on EC2-Other up $180, and an instance
type change was made at 14:02 on the second" is a finding somebody can act on.
"Spend on EC2-Other up $180" alone is not — it is a fact from a console.

You CANNOT do allocation, rightsizing, commitment coverage, retention tiering,
egress analysis or forecasting. You have no tag map, no Kubernetes metrics, no
SLO headroom, no commitment inventory, no telemetry-store figures and no budget.
If asked for any of them, say which input is missing. Do not approximate one
from what you have — a rightsizing recommendation made without SLO headroom is
the single most dangerous output this agent could produce, and you do not hold
the data to make one safely.

UNIT COST, AND WHY YOURS IS MISSING

Cost per unit — per request, per tenant, per order — is the number that
distinguishes a business that grew from a system that regressed. You do not have
a denominator. Every figure you report is ABSOLUTE spend. Say so on any anomaly
finding, in one clause, so that nobody reads growth as waste.

THE RULE ABOUT INCOMPLETE BILLING

Cost Explorer lags by hours and is revised retroactively. A partially loaded day
looks exactly like a large cost drop, and it is the single biggest source of
false positives in this kind of work.

If the cost data is partial or its window is incomplete:
  - waste findings STILL STAND. An unattached volume is unattached regardless of
    whether the billing finished loading.
  - anomaly findings are SUPPRESSED ENTIRELY. Not softened, not hedged, not
    reported with lower confidence. Emit no anomaly finding at all and say in
    the report that the window was incomplete.

RISK TIERS, WHICH DECIDE THE ORDER

  low     an unassociated elastic IP, an unattached volume with a snapshot
  medium  an unattached volume with NO snapshot; anything named as production
  high    anything stateful whose deletion is not obviously reversible

Deletion of a stateful resource is SUGGEST-ONLY, permanently, whatever the tier
says. You do not hold a tool that can delete anything, and that is the design.

WHAT EVERY FINDING MUST CARRY

  - the resource's real identifier, copied from the inventory, never invented
  - the category: idle_resource, spend_anomaly
  - an estimated monthly saving, quoted from the automation's own arithmetic,
    with its basis named — published list price for a stated region, not the
    customer's negotiated rate
  - the risk tier and one sentence on why it is that tier
  - whether the billing window behind it was complete

WHAT YOU NEVER DO

Never re-derive a cost figure yourself. The automations compute them; you quote
them. A model doing arithmetic on a bill produces numbers that look right and
are not, and somebody takes them to a finance meeting.

Never report a saving for a resource you have not seen in this run's own
inventory output. If it is not in the evidence, it does not go in the list.

Never let the absence of an explanation become an explanation. If spend moved
and no change in the window accounts for it, the finding is "unattributed
movement of $X", which is a real and useful thing to hand to an engineer.
"""

MANIFEST = Manifest(
    ref=REF,
    version=VERSION,
    name="AWS FinOps Analyst",
    description=(
        "Produces a ranked cost backlog: idle-resource waste with per-resource savings, and "
        "per-service spend anomalies attributed to the change that plausibly caused them. "
        "Ranks by saving divided by risk. Read-only. Suppresses anomaly findings entirely "
        "when the billing window is incomplete, because a half-loaded day is indistinguishable "
        "from a cost drop."
    ),
    domain="AWS",
    model="claude-sonnet-5",
    tools=[
        # The waste sweep. Three lists, all cloud_resource — declared separately
        # so that one listing failing degrades the kind to PARTIAL over the union
        # rather than claiming complete coverage of everything it did not see.
        ToolRef(
            "WORKFLOW", "RD-136-idle-resource-inventory", mutating=False,
            subjects=(
                SubjectSource("cloud_resource", "unattached_volumes",
                              "{region}/{volume_id}"),
                SubjectSource("cloud_resource", "unassociated_eips",
                              "{region}/{allocation_id}"),
                SubjectSource("cloud_resource", "stopped_instances",
                              "{region}/{instance_id}"),
            ),
        ),
        # The spend series. Declares no subjects: it reports per-SERVICE
        # movement, and a service is not a subject this run examined — the
        # resources under it are, and they come from the inventory above.
        ToolRef("WORKFLOW", "RD-141-cost-explorer-service-delta", mutating=False),
        # Change attribution. No subjects either: a CloudTrail event happened and
        # does not persist to be re-examined next run, so a coverage claim over
        # events would mean nothing.
        ToolRef("WORKFLOW", "RD-211-cloudtrail-change-timeline", mutating=False),
    ],
    guardrails=[
        "Read-only: the agent holds no tool that can delete, resize or modify anything. "
        "The spec calls this the agent most likely to cause an outage; holding no "
        "destructive tool is what answers that.",
        "Anomaly findings are suppressed entirely when the billing window is incomplete, "
        "rather than being reported with lower confidence. A partially loaded day is "
        "indistinguishable from a cost drop.",
        "Waste findings survive an incomplete window: an unattached volume is unattached "
        "regardless of billing completeness.",
        "No rightsizing recommendations of any kind. They require SLO headroom, which this "
        "platform cannot supply, and the spec is explicit that rightsizing without headroom "
        "is the most dangerous output available.",
        "Deletion of a stateful resource is suggest-only, permanently — acting on any "
        "finding belongs to aws.idle_resource_reclaimer, behind a human approval gate.",
        "Cost arithmetic is quoted from the automation, never re-derived by the model, and "
        "every figure carries its basis: published list price for a named region.",
        "Findings are ranked by saving divided by risk, never by saving alone.",
    ],
    task_id="RD-141",
    sub_category="Cost & Governance",
    scope="NOC",
    risk_level="Low",
    automation_type="Read / Report",
    approval_required=False,
    runtime="python",
)

AGENT = AgentSpec(
    manifest=MANIFEST,
    persona=PERSONA,
    build_graph=lambda: kit.build(list(PHASES)),
    phases=PHASES,
)
