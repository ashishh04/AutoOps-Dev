#!/usr/bin/env bash
#
# Run a Maven build in the container and exit with ITS status.
#
# WHY THIS EXISTS. The exit code of a shell command list is the exit code of the
# LAST command, and this project has now been bitten by that twice through two
# different doors:
#
#   mvn ... | grep ...            # reports grep's status, not Maven's
#   mvn ... > log 2>&1; echo $?   # reports echo's status — always 0
#
# The second one is worse, and it is worse specifically when backgrounded: the
# completion notification carries the exit code, nobody is watching the log, and
# a failed build reports success. It happened on a core-service run that was
# actually red — two tests pinning a JSON shape that a contract change had
# altered — and the notification said exit 0.
#
# That is the same family as the other bugs this codebase keeps finding: the
# instrument you would naturally trust returns a well-formed, plausible, wrong
# answer. A rule you have to remember gets remembered nine times.
#
#   scripts/run-build.sh agent-service verify
#   scripts/run-build.sh core-service test -Dtest=RolloutToolResolutionTest
#
set -uo pipefail

if [ $# -lt 1 ]; then
  echo "usage: run-build.sh <service> [maven-args...]" >&2
  exit 2
fi

SERVICE="$1"; shift
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVICE_DIR="$REPO_ROOT/backend/$SERVICE"

if [ ! -d "$SERVICE_DIR" ]; then
  echo "no such service: $SERVICE_DIR" >&2
  exit 2
fi

LOG="$REPO_ROOT/${SERVICE}-build.log"
GOALS=("$@")
[ ${#GOALS[@]} -eq 0 ] && GOALS=(test)

MSYS_NO_PATHCONV=1 docker run --rm \
  -v "$SERVICE_DIR":/app -v "$REPO_ROOT/.m2":/root/.m2 -w /app \
  maven:3.9-eclipse-temurin-21 mvn -B -ntp "${GOALS[@]}" > "$LOG" 2>&1
rc=$?

# Summary to stdout, full output in the log. Printed BEFORE the exit so it is
# visible either way.
grep -E "Tests run:.*Failures: [0-9]+, Errors|BUILD (SUCCESS|FAILURE)" "$LOG" | tail -3
if [ $rc -ne 0 ]; then
  echo "--- first failures ---"
  grep -E "\[ERROR\].*(Test|\.java)" "$LOG" | head -10
fi
echo "log: $LOG"

# The whole point: this script's status IS the build's status.
exit $rc
