#!/usr/bin/env bash
#
# Can each delivered agent actually run, and would it produce a real answer?
#
# WHY THIS EXISTS. Every agent failure seen so far surfaced MID-RUN, after
# tokens had been spent, as an error a customer could not act on:
#
#   - the model could not be invoked at all (a Bedrock id needing an
#     inference profile);
#   - the model could not hold the structured contract a phase requires;
#   - the tools needed a cloud connection the project did not have;
#   - a tool workflow had nodes but no edges, so it "succeeded" doing nothing.
#
# Every one of those is knowable BEFORE the click. This checks them.
#
# It is a read-only audit and states what it cannot know: whether a given model
# will hold a structured-output contract is only answerable by running it, so
# that column reports history rather than a prediction.
#
#   scripts/agent-readiness.sh                       # every tenant
#   scripts/agent-readiness.sh intertec-systems-...  # one
#
set -uo pipefail

TENANT="${1:-}"
CONTAINER="${MYSQL_CONTAINER:-autoops-mysql-1}"
ROOT_PW="${MYSQL_ROOT_PASSWORD:-rootpass}"

q() { docker exec -e MYSQL_PWD="$ROOT_PW" "$CONTAINER" mysql -uroot -N -B -e "$1" 2>/dev/null; }

WHERE="WHERE a.enabled = 1"
[ -n "$TENANT" ] && WHERE="$WHERE AND a.tenant_id = '$TENANT'"

printf '%-6s %-9s %-30s %-30s %s\n' ID PROJECT AGENT MODEL VERDICT
printf '%s\n' "--------------------------------------------------------------------------------------------------------"

# NULLIF/IFNULL so no field is ever EMPTY. Tab is IFS whitespace, so bash
# `read` collapses consecutive tabs and every later column shifts left — which
# showed an agent's tools JSON in the model column and invented a failure that
# was not real. A placeholder costs nothing and removes the whole class.
q "SELECT a.id, a.tenant_id, a.project_id, a.name,
          IFNULL(NULLIF(a.model,''),'~none~'), IFNULL(NULLIF(a.tools,''),'[]')
   FROM autoops_agent.agents a $WHERE ORDER BY a.tenant_id, a.project_id, a.id;" |
while IFS=$'\t' read -r id tenant project name model tools; do
  problems=""

  # ---- 1. is there a model, and does an ENABLED provider offer it? --------
  [ "$model" = "~none~" ] && model=""
  if [ -z "$model" ]; then
    dflt=$(q "SELECT IFNULL(default_model,'') FROM autoops_core.model_providers
              WHERE tenant_id='$tenant' AND enabled=1 AND default_model IS NOT NULL LIMIT 1;")
    if [ -z "$dflt" ]; then
      problems="$problems; no model set and the workspace has no default"
    fi
    model="(default: ${dflt:-none})"
  else
    offered=$(q "SELECT COUNT(*) FROM autoops_core.model_providers
                 WHERE tenant_id='$tenant' AND enabled=1
                   AND models_json LIKE '%\"$model\"%';")
    if [ "${offered:-0}" -eq 0 ]; then
      problems="$problems; no enabled AI connection offers '$model'"
    else
      # A provider whose last credential test FAILED will fail at the first call.
      badtest=$(q "SELECT COUNT(*) FROM autoops_core.model_providers
                   WHERE tenant_id='$tenant' AND enabled=1
                     AND models_json LIKE '%\"$model\"%' AND last_test_ok = 0;")
      good=$(q "SELECT COUNT(*) FROM autoops_core.model_providers
                WHERE tenant_id='$tenant' AND enabled=1
                  AND models_json LIKE '%\"$model\"%' AND last_test_ok = 1;")
      [ "${badtest:-0}" -gt 0 ] && [ "${good:-0}" -eq 0 ] &&
        problems="$problems; the only connection offering this model last failed its test"
    fi
  fi

  # ---- 2. do its tools resolve, and can those workflows be walked? --------
  #
  # Resolved by ref OR id. Agents delivered before rollout learned to carry
  # `ref` have only an id, and a check keyed on ref alone examined NOTHING for
  # them and still reported READY — passing for the wrong reason, which is
  # worse than failing.
  declared=$(printf '%s' "$tools" | grep -o '"type"' | wc -l | tr -d ' ')
  if [ "${declared:-0}" -eq 0 ]; then
    problems="$problems; declares no tools, so it can only talk"
  fi

  keys=$(printf '%s' "$tools" | grep -oE '"ref"[[:space:]]*:[[:space:]]*"[^"]+"|"id"[[:space:]]*:[[:space:]]*[0-9]+' \
         | sed 's/^"[a-z]*"[[:space:]]*:[[:space:]]*//' | tr -d '"')
  for key in $keys; do
    case "$key" in
      ''|*[!0-9]*) match="JSON_UNQUOTE(JSON_EXTRACT(definition,'\$.ref'))='$key'" ;;
      *)           match="id=$key" ;;
    esac
    row=$(q "SELECT JSON_LENGTH(JSON_EXTRACT(definition,'\$.nodes')),
                    IFNULL(JSON_LENGTH(JSON_EXTRACT(definition,'\$.edges')),-1)
             FROM autoops_workflow.workflows
             WHERE tenant_id='$tenant' AND project_id=$project AND $match LIMIT 1;")
    if [ -z "$row" ]; then
      problems="$problems; tool '$key' is not delivered to this project"
      continue
    fi
    nodes=$(printf '%s' "$row" | cut -f1)
    edges=$(printf '%s' "$row" | cut -f2)
    # >1 node with no edges is the graph that runs Start and stops.
    if [ "${nodes:-0}" -gt 1 ] && [ "${edges:-0}" -le 0 ]; then
      problems="$problems; tool '$key' has $nodes nodes and no edges — it cannot run"
    fi
  done

  # ---- 3. does the project hold the cloud connection its tools need? ------
  needs=$(q "SELECT DISTINCT JSON_UNQUOTE(JSON_EXTRACT(r.req,'\$.platform'))
             FROM autoops_workflow.workflows w
             JOIN JSON_TABLE(w.definition, '\$.requires[*]'
                  COLUMNS (req JSON PATH '\$')) r
             WHERE w.tenant_id='$tenant' AND w.project_id=$project
               AND JSON_UNQUOTE(JSON_EXTRACT(r.req,'\$.kind'))='cloud_connection';")
  for platform in $needs; do
    { [ -z "$platform" ] || [ "$platform" = "NULL" ]; } && continue
    # project_id IS NULL means GLOBAL — reachable from every project. Matching
    # only project_id=X is a false negative that blocks runs which would have
    # worked; it is the same rule resolveForStep enforces when a step binds.
    # credentials_enc NOT NULL because a connection with none fails at use.
    have=$(q "SELECT COUNT(*) FROM autoops_core.cloud_connections
              WHERE tenant_id='$tenant'
                AND (project_id IS NULL OR project_id=$project)
                AND platform='$platform' AND status='CONNECTED'
                AND credentials_enc IS NOT NULL;")
    [ "${have:-0}" -eq 0 ] &&
      problems="$problems; no CONNECTED $platform account in this project"
  done

  # ---- 4. what has actually happened when it ran? ------------------------
  ok=$(q "SELECT COUNT(*) FROM autoops_agent.agent_runs
          WHERE agent_id=$id AND status='SUCCEEDED';")
  bad=$(q "SELECT COUNT(*) FROM autoops_agent.agent_runs
           WHERE agent_id=$id AND status='FAILED';")

  if [ -n "$problems" ]; then
    verdict="WILL FAIL${problems}"
  elif [ "${ok:-0}" -gt 0 ]; then
    verdict="READY (proven: ${ok} ok / ${bad} failed)"
  else
    # Everything checkable passes. Whether the model holds the structured
    # contract a phased agent needs is only answerable by running it.
    verdict="READY (never run — structured output unproven)"
  fi

  printf '%-6s %-9s %-30s %-30s %s\n' \
    "$id" "$project" "$(printf '%.30s' "$name")" "$(printf '%.30s' "$model")" "$verdict"
done
