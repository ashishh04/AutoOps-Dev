#!/usr/bin/env bash
#
# Read the coverage and attribution counters after an agent run.
#
# Written so the check is one command rather than six queries somebody has to
# remember, and so the EXPECTED readings sit beside the actual ones — several
# of these are "correct at zero" or "correct at 100%" right now, and a reading
# that looks alarming is usually the estate rather than a defect.
#
#   scripts/check-coverage-counters.sh
#
set -uo pipefail

MYSQL="docker exec autoops-mysql-1 mysql -uroot -p\${MYSQL_ROOT_PASSWORD} -t"

run_sql() {
  docker exec autoops-mysql-1 sh -c \
    "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -t -e \"$1\"" 2>&1 | grep -v "Using a password"
}

echo "=== runs, most recent first ==="
run_sql "SELECT r.id, a.name AS agent, r.status, r.scope_status,
                r.subjects_evaluated, r.verdicts_emitted, r.finished_at
           FROM autoops_agent.agent_runs r
           LEFT JOIN autoops_agent.agents a ON a.id = r.agent_id
          ORDER BY r.id DESC LIMIT 5;"

echo "=== verdict attribution (today) ==="
run_sql "SELECT agent_name, attributed, unattributed, unscoped, out_of_scope,
                foreign_run, late
           FROM autoops_agent.verdict_attribution_daily
          WHERE bucket_day = UTC_DATE();"

echo "=== findings ==="
run_sql "SELECT COUNT(*) AS total,
                SUM(state = 'OPEN') AS open_findings,
                COUNT(DISTINCT subject_kind) AS kinds,
                SUM(subject_id_hash IS NULL) AS unhashed
           FROM autoops_agent.findings;"

echo "=== materialised subjects ==="
run_sql "SELECT subject_kind, COUNT(*) AS subjects
           FROM autoops_agent.agent_run_subject
          GROUP BY subject_kind;"

echo "=== the declared scope, verbatim ==="
run_sql "SELECT id, CAST(subject_scope AS CHAR) AS scope
           FROM autoops_agent.agent_runs
          WHERE subject_scope IS NOT NULL
          ORDER BY id DESC LIMIT 3;"

cat <<'NOTES'

--- how to read this -------------------------------------------------------

An agent with NO SubjectSource declarations (aws.incident_rca_analyst today):
  findings       > 0      verdicts reached ingest
  unscoped       > 0      <- THE PASS CONDITION: attribution correctly refuses
                             a verdict from a run that declared no scope
  attributed     = 0      correct; nothing declared a scope
  unattributed   = 0      non-zero means the run id is not reaching ingest
  late           = 0      non-zero means ingest is running after completeScope,
                             which closes the run — NOT after finish()
  foreign_run    = 0      non-zero means a tenant or agent-name mismatch
  scope_status   NULL     correct; declareScopeOnce returns early

An agent WITH declarations:
  attributed     > 0      and unscoped back to 0
  scope_status   COMPLETE (or PARTIAL if a source failed)
  subjects       counted per kind, and worth comparing to reality BY HAND —
                 the one check no invariant can do, and the only defence
                 against a tool that truncates without reporting a total
  source_verified absent from every element, because NO catalog automation
                 reports a source total yet (sweep, 2026-09-20). 100%
                 unverified is a PASS, not a finding.

Some verdicts refused with reason_code `schema_violation` and a message about
an unstable key are EXPECTED: a finding that names no single subject has
nothing stable to hash, and the runtime says so rather than inventing a key.
NOTES
