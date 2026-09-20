-- ============================================================
-- Files a run produced: one row per artifact, the bytes themselves in object
-- storage.
--
-- Until now a run could only report TEXT -- runs.log, truncated at the
-- runner's output cap. That is fine for "what happened" and useless for
-- "here is the document". A step that builds a report, a diff, an export or
-- an inventory snapshot had nowhere to put it, so the work either did not
-- happen or left the platform entirely and landed in somebody's bucket.
--
-- Two things fall out of this table that are worth naming, because the second
-- is the reason it exists at all:
--
--   1. A customer-facing document can be downloaded from the run that made it.
--   2. A run can READ THE PREVIOUS RUN'S ARTIFACT. Day-over-day comparison --
--      today's cloud inventory against yesterday's -- needs somewhere durable
--      to have put yesterday. This is that place. Without it, every
--      "what changed since the last check" automation has to invent its own
--      storage and its own idea of which run came before.
--
-- NO FOREIGN KEY to runs, and deliberately not the usual reason. Elsewhere
-- here it is "history outlives its definition"; here it is that an FK would
-- make things WORSE. ON DELETE CASCADE would drop these rows when retention
-- purges a run -- and the row is the only record of which object in the
-- bucket to delete. The bytes would be orphaned forever, with nothing left
-- pointing at them. Rows outlive their run on purpose: a sweeper reads them,
-- deletes the objects, and only then deletes the rows.
-- ============================================================

CREATE TABLE run_artifacts (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    tenant_id     VARCHAR(64)     NOT NULL,
    run_id        BIGINT UNSIGNED NOT NULL,

    -- Which step produced it. Label for humans, index for ordering; both
    -- nullable because a run-level artifact (an assembled report) belongs to
    -- the run rather than to any one step.
    step_index    INT UNSIGNED    NULL,
    step_label    VARCHAR(128)    NULL,

    -- As the step named it. Sanitised on the way in to a bare filename: a
    -- step writes outputs/report.docx and this is "report.docx", never a
    -- path, never anything containing "..".
    filename      VARCHAR(255)    NOT NULL,
    content_type  VARCHAR(128)    NOT NULL DEFAULT 'application/octet-stream',
    size_bytes    BIGINT UNSIGNED NOT NULL,

    -- Where the bytes are. Opaque to everything except ArtifactStore, so the
    -- same row works against MinIO in compose and S3 in AWS.
    storage_key   VARCHAR(512)    NOT NULL,

    -- Lets a later run tell "yesterday's snapshot is unchanged" from
    -- "yesterday's snapshot failed to upload and this is a different file
    -- of the same name".
    sha256        CHAR(64)        NOT NULL,

    created_at    TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    -- Set by the retention sweeper once the object is gone from the bucket.
    -- The row survives its run precisely so this can happen in that order.
    purged_at     TIMESTAMP(6)    NULL,

    PRIMARY KEY (id),

    -- The download path: every read is scoped by tenant, never by id alone.
    KEY idx_artifacts_tenant_run (tenant_id, run_id),

    -- The day-over-day path: "the artifact called snapshot.json on that run".
    KEY idx_artifacts_run_name (run_id, filename),

    -- The sweeper's path: unpurged rows, oldest first.
    KEY idx_artifacts_purge (purged_at, created_at),

    -- One object per row, and a retry that re-uploads cannot silently
    -- duplicate the row.
    UNIQUE KEY uq_artifacts_storage_key (storage_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
