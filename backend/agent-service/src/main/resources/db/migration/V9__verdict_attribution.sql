-- Can a verdict be traced to the run that produced it?
--
-- WHY THIS IS A COUNTER TABLE AND NOT A COLUMN. Making run_id mandatory on
-- verdict ingest is a breaking change across three codebases, so by
-- backend/MIGRATIONS.md it is expand/contract — three phases, not one:
--
--   A. Ingest accepts verdicts with or without a run_id, rejects nothing, and
--      counts both. (This migration.)
--   B. Agents open runs and pass run_id. The run-less count falls to zero per
--      agent as each one ships, and the coverage gauge starts reading something
--      other than 100% silent. The two move together.
--   C. Ingest rejects run-less verdicts — only once A's count has been zero
--      across a full cycle of the SLOWEST agent, which for a monthly-cadence
--      FinOps sweep is a month, not a night.
--
-- Phase C cannot be scheduled without the number phase A produces, and the
-- number has to be durable: an in-memory metric resets on every deploy, and
-- "has this been zero for a month" is exactly the question a counter that
-- forgets cannot answer.
--
-- AGGREGATED PER DAY, not a row per verdict. A nightly sweep across a large
-- estate emits thousands; the question being asked is "is this agent still
-- emitting anything untraceable", which needs a count and a date and nothing
-- else. A row per verdict would make the audit larger than the findings.
--
-- agent_name rather than agent_id ON PURPOSE. It is what the verdict itself
-- carries, and the point of this table is to record what ARRIVED — including a
-- verdict naming an agent that no longer exists, which is a case worth seeing
-- rather than one worth failing a foreign key on.

CREATE TABLE verdict_attribution_daily (
    tenant_id     VARCHAR(64)  NOT NULL,
    agent_name    VARCHAR(128) NOT NULL,
    bucket_day    DATE         NOT NULL,

    -- Verdicts carrying a run_id that passed ownership validation.
    attributed    BIGINT       NOT NULL DEFAULT 0,
    -- Verdicts with no run_id at all. This is the number that must reach zero
    -- and stay there before phase C.
    unattributed  BIGINT       NOT NULL DEFAULT 0,
    -- Verdicts naming a run that exists but is not this agent's, or not this
    -- tenant's. Never expected. Non-zero here is not a migration lagging, it is
    -- an agent emitting under somebody else's coverage claim.
    foreign_run   BIGINT       NOT NULL DEFAULT 0,
    -- Verdicts arriving after their run already reported completion. Counted
    -- separately from the run-less case so the gauge can tell "agent has not
    -- been updated" apart from "agent raced its own completion".
    late          BIGINT       NOT NULL DEFAULT 0,

    PRIMARY KEY (tenant_id, agent_name, bucket_day)
) ENGINE=InnoDB;
