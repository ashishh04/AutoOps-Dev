#!/usr/bin/env bash
#
# Run agent-service's integration tests locally.
#
# WHY THIS SCRIPT EXISTS. Testcontainers cannot start a container from inside
# the Maven container on a Windows Docker Desktop host: docker-java's /info gets
# a 400 and a stub pointing at npipe://./pipe/docker_cli, which a Linux
# container cannot reach. The socket is fine — curl and the docker CLI use it
# through the same mount — so it is Testcontainers' daemon discovery
# specifically, and not something this repo can fix.
#
# The workaround it replaces was worse: every migration from V6 to V9 was
# validated by hand against a clone of the live schema. That works and it
# depends on somebody choosing to do it, which is the wrong property for the
# reaper — the first component whose bugs delete state rather than failing
# loudly.
#
# So this starts a throwaway MySQL 8.4, hands it to the tests through
# AUTOOPS_TEST_MYSQL_URL, and tears it down. CI on Linux needs none of this:
# without that variable the tests fall back to Testcontainers unchanged.
#
#   ./run-its.sh              # every *IT
#   ./run-its.sh SchemaInvariantsIT
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SERVICE_DIR="$REPO_ROOT/backend/agent-service"
NETWORK="autoops-it"
MYSQL_NAME="autoops-it-mysql"
IMAGE="mysql:8.4"

# Generated per run and never written to a file. These credentials reach a
# container that exists for the length of this script and holds no real data —
# the deployed database is deliberately not involved, so a test can never write
# to it by misconfiguration.
MYSQL_PASSWORD="$(head -c 18 /dev/urandom | base64 | tr -d '/+=' | head -c 24)"

cleanup() {
  docker rm -f "$MYSQL_NAME" >/dev/null 2>&1 || true
  docker network rm "$NETWORK" >/dev/null 2>&1 || true
}
trap cleanup EXIT

cleanup
docker network create "$NETWORK" >/dev/null

echo "starting $IMAGE ..."
MSYS_NO_PATHCONV=1 docker run -d --name "$MYSQL_NAME" --network "$NETWORK" \
  -e MYSQL_ROOT_PASSWORD="$MYSQL_PASSWORD" \
  -e MYSQL_DATABASE=placeholder \
  "$IMAGE" >/dev/null

# Poll the server rather than sleeping a guessed interval: MySQL's first start
# initialises the data directory and is slower than every later one.
until docker exec "$MYSQL_NAME" mysqladmin ping -uroot -p"$MYSQL_PASSWORD" --silent >/dev/null 2>&1; do
  sleep 2
done
echo "ready."

IT_FILTER="${1:-}"
MVN_ARGS=(-B -ntp verify)
if [ -n "$IT_FILTER" ]; then
  # -DfailIfNoTests=false so filtering to one IT does not fail the unit phase.
  MVN_ARGS+=("-Dit.test=$IT_FILTER" -DfailIfNoTests=false)
fi

MSYS_NO_PATHCONV=1 docker run --rm --network "$NETWORK" \
  -v "$SERVICE_DIR":/app -v "$REPO_ROOT/.m2":/root/.m2 -w /app \
  -e AUTOOPS_TEST_MYSQL_URL="jdbc:mysql://$MYSQL_NAME:3306/" \
  -e AUTOOPS_TEST_MYSQL_USER=root \
  -e AUTOOPS_TEST_MYSQL_PASSWORD="$MYSQL_PASSWORD" \
  maven:3.9-eclipse-temurin-21 mvn "${MVN_ARGS[@]}"
