-- ------------------------------------------------------------
-- Notification rules can watch agents and alerts, not just jobs and workflows.
--
-- WHY. The rule table shipped with target_type ENUM('JOB','WORKFLOW') because
-- those two share one run engine in core-service. Two things have since become
-- notifiable and neither could be expressed:
--
--   AGENT  — an agent run queues, starts, succeeds and fails like any other
--            run, and it does one thing no job does: it PARKS on an approval
--            and waits for a human. Nobody watches a console for that, which
--            makes it the single most valuable notification in this table.
--
--   ALERT  — the thing the platform receives rather than the thing it runs.
--            An alert has no numeric id, so an ALERT rule is always
--            project-wide or workspace-wide; see min_severity below.
--
-- Widening an ENUM in place is an ALGORITHM=INPLACE metadata change in MySQL
-- 8.0 as long as values are only APPENDED — existing rows are untouched and no
-- table copy happens. Do not reorder the existing two.
-- ------------------------------------------------------------
ALTER TABLE notification_rules
    MODIFY COLUMN target_type ENUM('JOB','WORKFLOW','AGENT','ALERT') NOT NULL;

ALTER TABLE delivery_attempts
    MODIFY COLUMN target_type ENUM('JOB','WORKFLOW','AGENT','ALERT') NULL;

-- ------------------------------------------------------------
-- The floor an event has to clear.
--
-- This exists for ALERT and would be optional without it. A job fires a handful
-- of events a day; a workspace receives hundreds of alerts, most of them
-- informational. A rule that cannot say "critical only" is a rule nobody can
-- afford to enable, and a notification channel people mute is worse than one
-- they never had — they stop reading the ones that matter too.
--
-- NULL means "every event, whatever its severity", which is what every existing
-- row means and why this is nullable rather than defaulted to INFO. The two are
-- equivalent today and would stop being so the moment a severity below INFO is
-- added; a row that says nothing must keep meaning "no opinion".
-- ------------------------------------------------------------
ALTER TABLE notification_rules
    ADD COLUMN min_severity ENUM('INFO','WARNING','CRITICAL') NULL AFTER events;
