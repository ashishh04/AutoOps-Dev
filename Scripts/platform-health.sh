#!/usr/bin/env bash
#
# Is AutoOps actually up? Answers for a human, a scheduler, or a dead-man.
#
# WHY THIS EXISTS. The whole platform sat dead for twelve hours after a machine
# restart and nothing noticed — not the operator, not a customer, nothing. Two
# separate faults produced that, and this addresses the half that monitoring
# can address:
#
#   1. fifteen of eighteen services had NO restart policy, so a reboot left
#      them stopped. Fixed in docker-compose.yml, not here.
#   2. nothing was watching. That is this.
#
# THE THING THAT MAKES SELF-MONITORING HARD. A watchdog inside the stack dies
# with the stack, and a monitor that goes quiet when the patient dies is not a
# monitor. So this supports the DEAD-MAN pattern: on a fully healthy check it
# pings HEARTBEAT_URL, and the service at the other end alarms when the pings
# STOP. Absence of a signal is the signal. Without that URL this is still a
# useful check, but it can only tell you things it is alive to tell you.
#
#   scripts/platform-health.sh              # human-readable, exit 1 if unhealthy
#   scripts/platform-health.sh --quiet      # for a scheduler
#
# Wire the dead-man by setting HEARTBEAT_URL in .env (healthchecks.io,
# cronitor, BetterStack, or any URL you own that alarms on silence), then run
# this every few minutes from the HOST — Task Scheduler on Windows, cron or a
# systemd timer on Linux. It must be scheduled outside Docker: a job inside the
# stack cannot report the stack being down.
#
set -uo pipefail

QUIET=0
[ "${1:-}" = "--quiet" ] && QUIET=1

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 2

HEARTBEAT_URL="${HEARTBEAT_URL:-}"
if [ -z "$HEARTBEAT_URL" ] && [ -f .env ]; then
  HEARTBEAT_URL="$(grep -m1 '^HEARTBEAT_URL=' .env 2>/dev/null | cut -d= -f2- || true)"
fi

say() { [ "$QUIET" -eq 1 ] || printf '%s\n' "$*"; }

PROBLEMS=0
WARNINGS=0
note() { PROBLEMS=$((PROBLEMS + 1)); printf '  %s\n' "$*" >&2; }
warn() { WARNINGS=$((WARNINGS + 1)); printf '  %s\n' "$*" >&2; }

# Services whose absence degrades a convenience, not the platform.
#
# ngrok publishes a public URL for demos and inbound webhooks; nothing in the
# product depends on it (PUBLIC_BASE_URL points at the local frontend). Its
# most common failure is an account-level URL clash with another tunnel, which
# is not an AutoOps fault and must not turn the whole check red.
#
# This list is deliberately tiny and justified per entry. Every name added here
# is something that can break without anyone being told, so the bar is "a
# customer cannot tell the difference".
OPTIONAL="ngrok"

is_optional() {
  case " $OPTIONAL " in *" $1 "*) return 0 ;; *) return 1 ;; esac
}

# ---- is Docker itself there? ---------------------------------------------
if ! docker info > /dev/null 2>&1; then
  # The most total failure, and the one the twelve-hour outage actually was.
  # Reported first because every check below would fail confusingly otherwise.
  note "Docker is not running — the entire platform is down."
  say "UNHEALTHY: Docker is not running."
  exit 1
fi

# ---- every service that should be up ------------------------------------
EXPECTED="$(docker compose config --services 2>/dev/null | sort)"
if [ -z "$EXPECTED" ]; then
  note "Could not read the compose file — is COMPOSE_FILE set correctly?"
  exit 2
fi

say "AutoOps platform health — $(date -u '+%Y-%m-%d %H:%M:%SZ')"
say ""

while read -r service; do
  [ -z "$service" ] && continue

  # `ps -a` so a STOPPED container is distinguished from one that was never
  # defined. "not running" and "does not exist" need different answers.
  line="$(docker compose ps -a --format '{{.Service}}\t{{.State}}\t{{.Status}}' 2>/dev/null \
          | awk -F'\t' -v s="$service" '$1==s {print; exit}')"

  if [ -z "$line" ]; then
    note "$service: no container at all"
    say "  ✗ $(printf '%-22s' "$service") missing"
    continue
  fi

  state="$(printf '%s' "$line" | cut -f2)"
  status="$(printf '%s' "$line" | cut -f3)"

  case "$status" in
    *"(healthy)"*)
      say "  ✓ $(printf '%-22s' "$service") healthy" ;;
    *"(unhealthy)"*)
      if is_optional "$service"; then
        warn "$service: unhealthy (optional) — $status"
        say "  ! $(printf '%-22s' "$service") unhealthy (optional)"
      else
        note "$service: UNHEALTHY — $status"
        say "  ✗ $(printf '%-22s' "$service") unhealthy"
      fi ;;
    *"(health: starting)"*)
      # Not a failure. A service inside its start_period is doing what it is
      # supposed to; alarming here would page somebody on every deploy.
      say "  · $(printf '%-22s' "$service") starting" ;;
    *)
      if [ "$state" = "running" ]; then
        # Running with no healthcheck declared. Honest about the limit: this
        # says the process exists, not that it works.
        say "  ✓ $(printf '%-22s' "$service") running (no healthcheck)"
      elif is_optional "$service"; then
        warn "$service: $status (optional)"
        say "  ! $(printf '%-22s' "$service") $state (optional)"
      else
        note "$service: $status"
        say "  ✗ $(printf '%-22s' "$service") $state"
      fi ;;
  esac
done <<< "$EXPECTED"

# ---- the door a customer actually knocks on ------------------------------
#
# Checked separately because every container can be healthy while the way IN
# is broken — a gateway route, a published port taken by another process on
# this machine (which happened), a proxy misconfigured.
say ""
GATEWAY_PORT="${GATEWAY_PORT:-8080}"
code="$(docker run --rm --network=host curlimages/curl:latest -s -o /dev/null \
          -w '%{http_code}' --max-time 10 \
          "http://localhost:${GATEWAY_PORT}/api/alerts" 2>/dev/null || echo 000)"
case "$code" in
  # 401 is the CORRECT answer here: the route exists and authentication ran.
  # A 200 would mean an unauthenticated request reached tenant data.
  401|403) say "  ✓ gateway reachable and authenticating (HTTP $code)" ;;
  200)     note "gateway answered 200 UNAUTHENTICATED on a tenant route"
           say "  ✗ gateway is not authenticating" ;;
  000)     note "gateway did not answer on :${GATEWAY_PORT}"
           say "  ✗ gateway unreachable" ;;
  *)       note "gateway answered HTTP $code"
           say "  ✗ gateway HTTP $code" ;;
esac

# ---- verdict, and the dead-man -------------------------------------------
say ""
if [ "$PROBLEMS" -eq 0 ]; then
  if [ "$WARNINGS" -gt 0 ]; then
    # Said out loud, but not treated as an outage. A monitor that is always red
    # gets ignored, and then it protects nothing.
    say "HEALTHY — all required services up ($WARNINGS optional warning(s) above)."
  else
    say "HEALTHY — all services up, gateway answering."
  fi
  if [ -n "$HEARTBEAT_URL" ]; then
    # Only on a CLEAN check. Pinging while degraded would tell the dead-man
    # everything is fine, which is the one lie that makes it worthless.
    docker run --rm --network=host curlimages/curl:latest \
      -fsS --max-time 10 "$HEARTBEAT_URL" > /dev/null 2>&1 \
      && say "Heartbeat sent." \
      || say "Heartbeat could NOT be sent (check HEARTBEAT_URL)."
  else
    say ""
    say "No HEARTBEAT_URL set, so nothing outside this machine knows the"
    say "platform is alive. Set one in .env and schedule this script to get"
    say "an alert when it STOPS reporting — that is the only way a total"
    say "outage is noticed, because a watchdog inside the stack dies with it."
  fi
  exit 0
fi

say "UNHEALTHY — $PROBLEMS problem(s) above."
exit 1
