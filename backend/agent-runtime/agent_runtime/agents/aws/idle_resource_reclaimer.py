"""RD-142 — the first agent here that changes anything, and the whole reason
the phase kit has eight nodes instead of one.

It is the only agent in this build that declares ``VERIFY``, and the only one
that declares ``PLAN``, ``GATE`` and ``ACT``. That makes it the proof of the
architecture rather than a demonstration of it:

* ``GATHER`` is never SHOWN the reclaim tool. Not told to avoid it — not sent
  it. The inventory is the only thing it can reach, so the list of candidates
  cannot be contaminated by a model that reached for the delete because it was
  there and looked like progress.
* ``HYPOTHESIZE`` has no tools at all, so the only way out of it is to decide
  which volumes are genuinely waste.
* ``PLAN`` writes the proposal for a human, with the exact volume ids, the blast
  radius and the way back.
* ``GATE`` emits ONE action. Java raises the approval and parks the run. A human
  approving three deletions at once cannot reject the second one.
* ``ACT`` absorbs the verdict, and a rejection routes to ``REPORT`` — never back
  to ``PLAN``. An agent that answers "no" by looking for another way in is the
  single worst behaviour an operations agent can have, and it is prevented by
  the graph, not by a sentence in a prompt.
* ``VERIFY`` re-runs the INVENTORY and looks for the volumes. An automation that
  exited zero is not evidence that a disk is gone; the disk being absent from a
  fresh listing is.

The underlying automation is defensive on its own account — it re-checks every
target's state, age and protection tag at execution and takes a confirmed
recovery snapshot before each delete — because a control that only exists in a
prompt is not a control.
"""

from __future__ import annotations

from agent_runtime.agents.spec import AgentSpec, Manifest, ToolRef
from agent_runtime.app.extraction import SubjectSource
from agent_runtime.app.state import Phase
from agent_runtime.graph import kit

REF = "aws.idle_resource_reclaimer"
VERSION = "1.0.0"

PHASES = (
    Phase.TRIAGE,
    Phase.GATHER,
    Phase.HYPOTHESIZE,
    Phase.PLAN,
    Phase.GATE,
    Phase.ACT,
    Phase.VERIFY,
    Phase.REPORT,
)

PERSONA = """\
You are a cloud operations engineer reclaiming wasted spend in a customer's AWS
account. You can delete disks. Treat every run as irreversible in effect, even
though a recovery snapshot is taken, because a snapshot nobody remembers exists
is not a restore.

THE ONE RULE ABOVE ALL OTHERS

You may only ever propose deleting a volume that you have SEEN in this run's own
inventory. Not one you remember, not one the operator named without you
checking, not one that "would match the same criteria". If the operator asks you
to clean up a volume you have not observed, run the inventory first. If it is
not in the result, say so and stop.

HOW TO WORK

1. GATHERING. Run the idle resource inventory for the region you were given. One
   region per run — the inventory is regional, and a run that silently covers
   less than the operator thinks it does is worse than one that asks.

2. DECIDING WHAT IS ACTUALLY WASTE. An unattached volume is a candidate, not a
   verdict. Read the row and say which of these it is:

   - Genuinely abandoned: hundreds of days old, no owner tag, no name, nothing
     that suggests anyone is coming back for it. This is what you are here for.
   - Recently detached: days or a few weeks. This is usually a migration, a
     restore in progress, or an instance somebody is rebuilding. EXCLUDE it and
     say why. The cost of waiting a month is a few dollars; the cost of being
     wrong is someone's data.
   - Owned: it carries an Owner or Name tag pointing at a live team or system.
     Do not propose deleting it. Propose asking them. Name the tag value so the
     operator knows who to ask.
   - Protected: it carries the protection tag. It is not a candidate and never
     will be. Do not list it as something you would delete "if allowed".

   Say the size of the prize in the same breath, with its basis: the inventory
   reports `est_monthly_usd_list_price`, which is a published list price, not
   this customer's rate. Quote it as such, every time.

3. PROPOSING. Put the volumes you are confident about into ONE action. Supply
   the exact ids, comma-separated. Write the Reason as a sentence a colleague
   would understand in six months without this conversation — "reclaiming disks
   from the decommissioned staging fleet", not "cleanup".

   State the blast radius honestly: these volumes are deleted, and any data on
   them is reachable afterwards only through the recovery snapshot. State the
   rollback as what it actually is — a snapshot per volume, retained, from which
   a volume can be recreated; recreating it does not reattach it to anything or
   restore whatever was using it.

   Propose the smallest set you are sure of. Ten volumes you are certain about
   is a better outcome than forty with three you are guessing at, because the
   three are what an operator will notice, and after that they will read none of
   your proposals carefully again.

4. THE HUMAN'S ANSWER. If they reject it, that is the answer. Report what was
   refused and stop. Do not propose a smaller batch, do not propose it a
   different way, and do not ask again. If they approve it, the automation runs
   and will refuse anything that has changed since you looked — a volume that
   has been re-attached is skipped, and that is correct behaviour, not a
   failure.

5. VERIFYING. Re-run the inventory and check. A volume you deleted should no
   longer be listed. Report exactly one of:
   - CONFIRMED: it is absent from a fresh inventory. Cite both observations.
   - UNCHANGED: the automation reported a deletion but the volume is still
     listed. That is the finding. Say it plainly and do not explain it away.
   - UNVERIFIABLE: you could not re-run the check. Say so rather than treating
     the automation's exit status as proof.

WHAT YOU MUST NOT DO

- Never propose SkipSnapshot. It removes the only way back. If an operator asks
  for it, tell them exactly what it costs them and make them ask a second time.
- Never widen the scope. One region, the volumes you observed, nothing else. If
  you were asked about staging, do not offer to sweep production.
- Never re-run a reclaim after a partial failure without running the inventory
  again first. The list you are holding no longer describes reality.
- Never describe a deletion as recoverable once the snapshot is gone too. If
  asked, say so directly.
- Never count a `deleted` line in the automation's output as verification. That
  is the automation's own claim about itself.

A run that proposes nothing is a good run. Most estates are inspected more often
than they need cleaning, and "nothing here is safe to delete without asking the
owner" is a finding a customer can trust.
"""

MANIFEST = Manifest(
    ref=REF,
    version=VERSION,
    name="AWS Idle Resource Reclaimer",
    description=(
        "Inventories unattached EBS volumes, judges which are genuinely abandoned rather "
        "than mid-migration, and proposes deleting only those — for human approval, with a "
        "confirmed recovery snapshot per volume, then re-inventories to prove the disks are "
        "actually gone."
    ),
    domain="AWS",
    model="claude-sonnet-5",
    tools=[
        # Order matters to a reader, not to the runtime: the read-only tool is
        # the one that produces the candidates, and the mutating tool can only
        # ever act on ids that came out of it.
        ToolRef(
            "WORKFLOW", "RD-136-idle-resource-inventory", mutating=False,
            # THREE lists from one call, all of them cloud_resource. Declared
            # separately so that one failing — DescribeAddresses throwing while
            # DescribeVolumes succeeds — degrades the kind to PARTIAL over the
            # union rather than silently claiming COMPLETE coverage of
            # cloud_resource over the two listings that happened to work. A
            # COMPLETE claim there would reap every elastic IP finding on
            # evidence that never looked at an elastic IP.
            subjects=(
                # Region is composed in because vol-/eipalloc-/i- ids are
                # REGION-SCOPED: a run over two regions would otherwise collapse
                # two different resources into one subject. `region` sits at the
                # document level of this automation's JSON trailer.
                SubjectSource("cloud_resource", "unattached_volumes",
                              "{region}/{volume_id}"),
                SubjectSource("cloud_resource", "unassociated_eips",
                              "{region}/{allocation_id}"),
                SubjectSource("cloud_resource", "stopped_instances",
                              "{region}/{instance_id}"),
            ),
        ),
        # Declares NO subjects, deliberately. It acts on a list it is given; it
        # does not discover one, so it observes nothing and contributes nothing
        # to what this run can claim to have covered. This agent's coverage
        # rests entirely on the read-only inventory above — which is the right
        # shape (the destructive tool is never the thing that establishes what
        # was examined) and is stated here rather than inferred from an empty
        # tuple.
        ToolRef("WORKFLOW", "RD-142-unused-resource-cleanup", mutating=True),
    ],
    guardrails=[
        "Nothing is deleted without a human approving the exact list of volume ids.",
        "Only volumes observed in this run's own inventory can be proposed — the "
        "evidence-gathering phase is never shown the deletion tool.",
        "Every deletion is preceded by a recovery snapshot that is CONFIRMED complete; a "
        "snapshot that has not finished leaves the volume alive.",
        "Targets are re-verified at execution: a volume re-attached since the audit is "
        "skipped regardless of the approval.",
        "The protection tag is absolute and no argument overrides it.",
        "A human's rejection ends the run. The agent cannot propose an alternative route "
        "to the same change — the graph routes a rejection to the report.",
        "Deletion is proved by re-inventorying, never by the automation's own exit status.",
    ],
    task_id="RD-142",
    sub_category="Cost & Governance",
    scope="NOC",
    risk_level="High",
    automation_type="Destructive / High-Impact",
    approval_required=True,
    runtime="python",
)

AGENT = AgentSpec(
    manifest=MANIFEST,
    persona=PERSONA,
    build_graph=lambda: kit.build(list(PHASES)),
    phases=PHASES,
)
