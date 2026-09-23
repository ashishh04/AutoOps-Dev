-- An agent authored in the provider console can declare its phases.
--
-- WHAT THIS CHANGES. Until now an agent had two ways to exist and they were not
-- equal. An agent with a `graph_ref` is a Python module in agent-runtime's
-- image: phase narrowing, evidence enforcement, subject extraction, a coverage
-- claim. An agent without one — which is every agent anybody has ever built in
-- the console — resolved to the un-phased compatibility loop, with an empty
-- manifest, so it could declare no subject kinds, could never claim coverage,
-- and produced findings that were structurally unreapable.
--
-- The difference was never the Python. Ten of the eleven shipped agents build
-- their graph with literally `kit.build(list(PHASES))` and contain no custom
-- code at all; what distinguishes them is a persona, a model, an allow-list, a
-- phase list and a set of subject declarations. Four of those five already had
-- a home in this table. This adds the fifth.
--
-- WHY A COLUMN RATHER THAN A FLAG. "Which phases, in what order" is not a
-- boolean. The graph is built from the list, so [GATHER, TRIAGE] is a different
-- agent from [TRIAGE, GATHER], and a flag meaning "phased" would have to pick
-- an order on the agent's behalf.
--
-- WHY NULL IS MEANINGFUL AND STAYS THAT WAY. NULL means "this agent made no
-- phase declaration", and every agent running today is NULL. Those resolve to
-- the compatibility loop exactly as before. That is not a migration convenience
-- — it is the contract. A legacy persona was written for a loop with no
-- citation rule, and switching evidence enforcement on underneath one would
-- fill its report with [e:..] markers its author never accounted for. The
-- phased runtime is something an author opts into, never something a deploy
-- does to them. There is deliberately no backfill here and there should never
-- be one.
--
-- The subject declarations need no column: they ride inside the existing
-- `tools` MEDIUMTEXT, per entry, beside the `ref` and `mutating` flags that
-- already live there.

ALTER TABLE agents
    ADD COLUMN phases VARCHAR(255) NULL
        COMMENT 'Ordered, comma-separated phase names for an agent with no graph_ref. NULL means no declaration, which runs the un-phased compatibility loop. Never backfill this.';
