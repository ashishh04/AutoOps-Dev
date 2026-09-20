-- The findings store: a mutable head over an append-only tail.
--
-- Until now an agent run produced prose. A person read it or nobody did, and
-- either way nothing survived: a nightly agent re-derived the same conclusions
-- every night, nobody could dismiss anything because there was no thing to
-- dismiss, and "is this new or is this the same problem as yesterday" had no
-- answer. This is that answer.
--
-- Three concepts, and conflating any two is where this usually goes wrong:
--
--   VERDICT      one emission from one run. Immutable, disposable after ingest.
--                Not stored as such — it becomes an observation or it collapses
--                into a counter.
--   OBSERVATION  an accepted verdict, persisted. Append-only.
--   FINDING      the durable entity keyed by idempotency_key, carrying
--                lifecycle, dismissal and counters.
--
-- TRANSLATED FROM A POSTGRES DESIGN, and the differences are not cosmetic:
--   * MySQL has no partial indexes, so the worklist and reaper indexes here
--     cover every row rather than only the live ones. They are wider than the
--     originals and do the same job.
--   * No RETURNING and no xmax, so "was this an insert" is decided in the
--     service by a read inside the same transaction, with the unique key as
--     the backstop against a race.
--   * finding_observations is NOT partitioned. MySQL RANGE partitioning needs
--     an integer expression and forbids foreign keys on the partitioned table,
--     and trading referential integrity for a retention convenience is the
--     wrong way round at this size. Retention is a documented job; revisit
--     partitioning when the row count justifies losing the FK.
--   * No row-level security. Every query carries tenant_id and the repository
--     layer is where that is enforced, exactly as every other table here does.

CREATE TABLE findings (
    id                        BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id                 VARCHAR(64)  NOT NULL,
    project_id                BIGINT       NOT NULL,

    -- IDENTITY. Two hashes, deliberately separate.
    --   idempotency_key answers "is this the same finding".
    --   content_hash    answers "has the substance changed".
    -- Keeping them apart is what lets a dismissed finding stay quiet while its
    -- numbers drift, and resurface when they do more than drift.
    idempotency_key           VARCHAR(128) NOT NULL,
    -- In the unique key rather than the payload ON PURPOSE. An agent author
    -- will eventually change what goes into the hash; with the version here,
    -- that forks cleanly into a new row and is a declared migration. Buried in
    -- the payload it would silently duplicate every finding in the estate.
    idempotency_key_version   SMALLINT     NOT NULL DEFAULT 1,
    content_hash              VARCHAR(64)  NOT NULL,

    -- PROVENANCE
    agent_name                VARCHAR(128) NOT NULL,
    agent_version             VARCHAR(32)  NOT NULL,
    category                  VARCHAR(64)  NOT NULL,

    -- SUBJECT
    subject_kind              VARCHAR(64)  NOT NULL DEFAULT 'unknown',
    subject_id                VARCHAR(512) NOT NULL DEFAULT '',
    service_ref               VARCHAR(255),
    environment               VARCHAR(32)  NOT NULL DEFAULT 'unknown',

    -- SCORING, denormalised out of the payload so a worklist query is an index
    -- scan rather than a JSON walk.
    severity                  VARCHAR(16),
    risk_tier                 VARCHAR(16),
    confidence                DECIMAL(4,3),
    confidence_band           VARCHAR(16),
    priority_score            DECIMAL(12,4),

    -- LIFECYCLE.
    -- `stale` and `resolved` are deliberately different. `resolved` means
    -- somebody or something acted. `stale` means it stopped being observed and
    -- nobody knows why. Collapsing them destroys the only signal that
    -- separates "we fixed it" from "it stopped being reported".
    state                     ENUM('OPEN','ACKNOWLEDGED','SUPPRESSED','RESOLVED',
                                   'STALE','SUPERSEDED') NOT NULL DEFAULT 'OPEN',
    state_reason              VARCHAR(255),

    first_seen_at             TIMESTAMP(6) NOT NULL,
    last_seen_at              TIMESTAMP(6) NOT NULL,
    last_material_change_at   TIMESTAMP(6) NOT NULL,
    state_changed_at          TIMESTAMP(6) NOT NULL,
    updated_at                TIMESTAMP(6) NOT NULL,

    -- COUNTERS. occurrence counts every sighting; episode counts how many
    -- times it came BACK after being closed, which is the number that says a
    -- fix did not hold.
    occurrence_count          INT          NOT NULL DEFAULT 1,
    episode_count             INT          NOT NULL DEFAULT 1,
    material_change_count     INT          NOT NULL DEFAULT 0,

    head_observation_id       BIGINT,
    payload                   JSON         NOT NULL,
    last_run_id               BIGINT,

    assignee                  VARCHAR(255),

    CONSTRAINT uq_finding_identity
        UNIQUE (tenant_id, idempotency_key, idempotency_key_version)
);

-- The worklist. MySQL cannot index only the live states, so this covers them
-- all and the state predicate does the filtering.
CREATE INDEX idx_findings_worklist
    ON findings (tenant_id, project_id, state, priority_score DESC);

-- Cursor pagination is on (updated_at, id) and never on an offset: the set
-- mutates under a reader mid-page, and an offset silently skips rows.
CREATE INDEX idx_findings_cursor ON findings (tenant_id, updated_at, id);

-- Prefixed on subject_id. MySQL's index key limit is 3072 BYTES and utf8mb4
-- charges 4 per character, so a VARCHAR(512) carried whole eats 2048 of it and
-- the composite overflows (error 1071). 191 characters discriminates between
-- resource ids perfectly well; the column keeps its full width for the ARNs
-- that genuinely need it.
CREATE INDEX idx_findings_subject ON findings (tenant_id, subject_kind, subject_id(191));

-- Supports the staleness reaper AND the duplicate-key-fork alarm: two OPEN
-- findings from one agent on one (subject, category) means somebody changed
-- the key composition without declaring it.
CREATE INDEX idx_findings_agent_seen ON findings (tenant_id, agent_name, last_seen_at);
CREATE INDEX idx_findings_fork_watch
    ON findings (tenant_id, agent_name, subject_id(191), category, state);


CREATE TABLE finding_observations (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    finding_id      BIGINT       NOT NULL,
    -- The verdict's own id, so an exact replay is recognised independently of
    -- finding identity. A network retry must not increment occurrence_count.
    verdict_id      VARCHAR(64)  NOT NULL,
    run_id          BIGINT,
    agent_version   VARCHAR(32)  NOT NULL,
    observed_at     TIMESTAMP(6) NOT NULL,
    content_hash    VARCHAR(64)  NOT NULL,
    is_material     BOOLEAN      NOT NULL,
    payload         JSON         NOT NULL,

    CONSTRAINT fk_observation_finding FOREIGN KEY (finding_id)
        REFERENCES findings (id) ON DELETE CASCADE,
    -- Replay protection. Two ingests of one verdict collapse to one row.
    CONSTRAINT uq_observation_verdict UNIQUE (tenant_id, verdict_id)
);

CREATE INDEX idx_observations_finding ON finding_observations (finding_id, observed_at DESC);


-- Dismissal is a RECORD, not a flag on the finding. A flag cannot answer "who
-- silenced this, why, under what scope, and when does it come back" — which is
-- the first thing anybody asks when a finding nobody saw turns into an outage.
CREATE TABLE finding_suppressions (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id       VARCHAR(64) NOT NULL,
    -- Precedence on ingest runs narrowest first: FINDING, SUBJECT_CATEGORY,
    -- SERVICE_CATEGORY, CATEGORY_GLOBAL. The winning id goes on the
    -- transition row, because without it nobody can answer why a finding never
    -- appeared.
    scope           ENUM('FINDING','SUBJECT_CATEGORY','SERVICE_CATEGORY',
                         'CATEGORY_GLOBAL') NOT NULL,

    finding_id      BIGINT,
    subject_kind    VARCHAR(64),
    subject_id      VARCHAR(512),
    service_ref     VARCHAR(255),
    category        VARCHAR(64),

    reason          VARCHAR(1024) NOT NULL,
    -- A closed set. Free-text-only permanent dismissal is how a backlog
    -- quietly becomes fiction.
    reason_code     ENUM('BY_DESIGN','COMPENSATING_CONTROL','ACCEPTED_RISK',
                         'FALSE_POSITIVE','DEFERRED') NOT NULL,
    created_by      VARCHAR(255) NOT NULL,
    created_at      TIMESTAMP(6) NOT NULL,
    -- NULL means permanent, which requires an elevated role at the API.
    expires_at      TIMESTAMP(6) NULL,
    revoked_at      TIMESTAMP(6) NULL,
    revoked_by      VARCHAR(255),

    -- False by default: a dismissal covers the problem AS IT WAS. If the
    -- substance changes materially the finding comes back, because the thing
    -- somebody accepted is not the thing that is now true.
    survives_material_change BOOLEAN NOT NULL DEFAULT FALSE,

    CONSTRAINT fk_suppression_finding FOREIGN KEY (finding_id)
        REFERENCES findings (id) ON DELETE CASCADE,

    -- The scope decides which targets are meaningful. A SERVICE_CATEGORY
    -- suppression with no service_ref would silently match everything.
    CONSTRAINT ck_suppression_scope_coherent CHECK (
        (scope = 'FINDING'          AND finding_id  IS NOT NULL) OR
        (scope = 'SUBJECT_CATEGORY' AND subject_id  IS NOT NULL AND category IS NOT NULL) OR
        (scope = 'SERVICE_CATEGORY' AND service_ref IS NOT NULL AND category IS NOT NULL) OR
        (scope = 'CATEGORY_GLOBAL'  AND category    IS NOT NULL)
    )
);

CREATE INDEX idx_suppression_lookup
    ON finding_suppressions (tenant_id, category, service_ref(100), subject_id(191), revoked_at);
CREATE INDEX idx_suppression_expiry ON finding_suppressions (expires_at, revoked_at);


-- State audit, kept apart from observations because the ACTORS differ: an
-- observation is always an agent, a transition is frequently a person.
CREATE TABLE finding_transitions (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id     VARCHAR(64) NOT NULL,
    finding_id    BIGINT      NOT NULL,
    from_state    VARCHAR(16),
    to_state      VARCHAR(16) NOT NULL,
    at            TIMESTAMP(6) NOT NULL,
    actor_kind    ENUM('HUMAN','AGENT','SYSTEM') NOT NULL,
    actor_ref     VARCHAR(255),
    reason_code   VARCHAR(64) NOT NULL,
    detail        VARCHAR(1024),
    -- suppression id, observation id, or a decision id once a policy engine
    -- exists. Untyped because the referent varies and a column per kind would
    -- be four nullable columns that are always three nulls.
    ref_id        BIGINT,

    CONSTRAINT fk_transition_finding FOREIGN KEY (finding_id)
        REFERENCES findings (id) ON DELETE CASCADE
);

CREATE INDEX idx_transitions_finding ON finding_transitions (finding_id, at DESC);


-- What a run CLAIMS to have covered.
--
-- This is the column that makes staleness reaping safe, and without it reaping
-- is actively dangerous: absence of a verdict has two causes — the problem went
-- away, or the agent never looked. Reaping on last_seen_at alone means one
-- agent outage marks the whole backlog resolved, and it looks like a great
-- week. Nothing may be reaped except against a COMPLETED run whose scope
-- covers the finding's subject.
ALTER TABLE agent_runs
    ADD COLUMN subject_scope JSON NULL,
    ADD COLUMN subjects_evaluated INT NULL,
    ADD COLUMN verdicts_emitted INT NULL;
