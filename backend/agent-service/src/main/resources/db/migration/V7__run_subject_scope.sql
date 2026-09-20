-- What a run actually covered, in a shape the reaper can push into a WHERE.
--
-- THE CONSTRAINT THAT DECIDES EVERYTHING HERE: the reaper evaluates coverage
-- against every open finding for an agent — thousands at steady state, and more
-- the worse things are going. If coverage is a JSON predicate evaluated row by
-- row in application code, the reaper is a full scan plus N deserializations
-- that degrades exactly when it is most needed.
--
-- So coverage is not a predicate language. It is a set of shapes that each
-- compile to a SQL predicate over INDEXED COLUMNS on `findings`. Anything that
-- cannot be pushed into a WHERE clause is excluded by construction:
--
--   * no regex or glob on subject_id
--   * no arbitrary boolean nesting
--   * no predicates over fields not denormalised onto `findings`
--   * no lookups into another system at reap time
--
-- Three shapes, and deliberately no more:
--
--   {"kind":"all"}
--       Every subject this agent owns. Compiles to TRUE.
--
--   {"kind":"dimensional","subject_kind":"cloud_resource",
--    "dimensions":{"environment":["prod"],"service_ref":["svc-checkout"]}}
--       A conjunction of disjunctions. Each key is a column on `findings`,
--       each list an IN. An absent key is unconstrained on that dimension.
--
--   {"kind":"enumerated","subject_kind":"alert_rule","subject_id_count":847,
--    "subject_ids_digest":"sha256:..."}
--       An explicit list, materialised into agent_run_subject below.
--
-- NO NEGATION AND NO WILDCARDS, and the reason is the same for both. An
-- exclusion is only correct if the excluded set at reap time matches the set at
-- run time, and environment tags change: an agent that skipped prod on Monday
-- plus a finding retagged to prod on Tuesday yields a scope claiming coverage
-- it never had, and the finding gets silently resolved. Inclusion-only means
-- the claim is always NARROWER than reality when data drifts, which is the
-- direction that fails safe. `svc-*` expands differently as services are
-- created, for the same reason.
--
-- SUBJECT_SCOPE IS AN ARRAY. One run legitimately covers several subject
-- kinds: `aws.public_exposure_auditor` correlates S3 buckets and security
-- groups (cloud_resource) with IAM users (principal), and the chain between
-- those kinds IS the product. A single scope object could not describe what it
-- covered, and the reaper would either skip its findings forever or resolve
-- them against a scope that never included them. Cheap now, awkward once runs
-- exist.

-- The materialised form of an `enumerated` scope. The reaper joins to this
-- rather than parsing a JSON array per candidate finding.
CREATE TABLE agent_run_subject (
    run_id          BIGINT UNSIGNED NOT NULL,
    subject_kind    VARCHAR(64)  NOT NULL,
    -- The HASH, not the id. sha256 is fixed width, so it sidesteps the
    -- key-length problem that `subject_id VARCHAR(512)` creates in utf8mb4
    -- (2048 bytes of a 3072-byte budget) and it needs no prefix, so there is
    -- no prefix-collision question to revisit at volume.
    subject_id_hash BINARY(32)   NOT NULL,

    PRIMARY KEY (run_id, subject_kind, subject_id_hash),
    CONSTRAINT fk_run_subject_run FOREIGN KEY (run_id)
        REFERENCES agent_runs (id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- The join key on the other side.
--
-- Nullable in this migration and NOT NULL in a later one, because every writer
-- has to be populating it before the constraint can hold — the expand half of
-- expand/contract, per backend/MIGRATIONS.md.
ALTER TABLE findings
    ADD COLUMN subject_id_hash BINARY(32) NULL AFTER subject_id;

-- A no-op today: `findings` is empty. Written anyway, because the next
-- migration that adds a derived column will not be, and the pattern for
-- backfilling one belongs somewhere it can be copied from rather than
-- reinvented under pressure.
UPDATE findings
   SET subject_id_hash = UNHEX(SHA2(subject_id, 256))
 WHERE subject_id_hash IS NULL;

-- The reaper's join. subject_kind leads because it is the coarsest filter and
-- every scope shape constrains it.
CREATE INDEX idx_findings_subject_hash
    ON findings (tenant_id, subject_kind, subject_id_hash);

-- How the run ended, distinctly from whether it finished.
--
-- PARTIAL never reaps. A run that covered 40% of its subjects tells you nothing
-- about the other 60%, and a scope narrowed to what it actually reached is
-- still a claim about a set nobody can reconstruct mid-failure. FAILED never
-- reaps either, so abandonment defaults to "reap nothing" — the only direction
-- that cannot silently clear a backlog.
ALTER TABLE agent_runs
    ADD COLUMN scope_status ENUM('RUNNING','COMPLETE','PARTIAL','FAILED') NULL AFTER subject_scope;

-- Finding the run that may drive a reap, per agent.
CREATE INDEX idx_agent_runs_reap
    ON agent_runs (tenant_id, agent_id, scope_status, finished_at);
