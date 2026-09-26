#!/usr/bin/env python3
"""Publish authored workflows and agents into the PLATFORM catalog.

    python backend/agent-service/agents/_schema/publish.py --dry-run
    AUTOOPS_PROVIDER_USER=... AUTOOPS_PROVIDER_PASSWORD=... \
        python backend/agent-service/agents/_schema/publish.py

Two kinds of agent get published from here.

**JSON agents** are the files under `agents/<DOMAIN>/`. Their persona travels in
the catalog `definition` and is copied into every customer's database on
rollout, protected only by no API exposing it.

**Python agents** live in agent-runtime's registry, and only their MANIFEST is
published: name, description, model, tool refs, guardrails. The persona, the
prompts and the phase graph stay in the provider's image and are never
delivered anywhere, so what lands in a customer's row is a reference —
`{"kind":"PYTHON","ref":"linux.server_health_check","version":"1.0.0"}` — and
agent-runtime resolves it at run time. That is the stronger of the two, and new
agents should be authored that way.

Manifests are fetched from a running agent-runtime (`--runtime`), because the
registry is the source of truth and a second copy in this repo would drift:

    python .../publish.py --runtime http://localhost:8089 --dry-run

Workflows are published BEFORE agents, and an agent whose referenced workflow
is not in the catalog is refused before anything is written — a delivered agent
whose allow-list resolves to nothing is worse than no agent at all.

Publishing goes through POST /api/provider/library rather than SQL so the
catalog rows get validation, a real `created_by` and an audit trail. The
credential is read from the environment and never written anywhere.

--print-sql emits the equivalent statements instead, for an operator who would
rather not put a provider password in their shell environment. That path skips
validation and audit, so it is a bootstrap convenience, not the normal route.

Re-running is safe: an item whose title already exists in the catalog is
updated in place rather than duplicated.
"""
import argparse
import json
import os
import pathlib
import sys
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent          # .../agents
WORKFLOW_ROOT = ROOT.parent / "workflows"                      # .../workflows
DEFAULT_BASE = os.environ.get("AUTOOPS_API", "http://localhost:8080/api")
DEFAULT_RUNTIME = os.environ.get("AGENT_RUNTIME_URL", "http://localhost:8089")
RUNTIME_TOKEN = os.environ.get("AGENT_RUNTIME_INTERNAL_TOKEN", "dev-internal-token")

# Keys RolloutService.rollOutAgent reads out of an AGENT catalog definition.
AGENT_DEFINITION_KEYS = ("description", "model", "instructions", "tools")


def load(path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        sys.exit(f"{path}: not valid JSON — {exc}")


def discover():
    workflows = sorted(WORKFLOW_ROOT.glob("*/*.json")) if WORKFLOW_ROOT.exists() else []
    agents = sorted(p for p in ROOT.glob("*/*.json") if p.parent.name != "_schema")
    return workflows, agents


def fetch_manifests(runtime, token):
    """The PUBLIC manifest of every agent in a running agent-runtime.

    Fetched rather than read off disk on purpose. The registry in that image is
    what will actually run, and a manifest copied into this repo would be a
    second source of truth that drifts the first time someone bumps a version
    and forgets. If the runtime is unreachable, that is a refusal: publishing a
    catalog entry pointing at a build nobody has confirmed exists is how a
    tenant ends up holding a ref that resolves to nothing.
    """
    req = urllib.request.Request(f"{runtime.rstrip('/')}/v1/agents")
    req.add_header("X-Internal-Token", token)
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            body = json.loads(resp.read().decode())
    except urllib.error.HTTPError as exc:
        sys.exit(f"agent-runtime refused the manifest request: HTTP {exc.code}. "
                 f"Check AGENT_RUNTIME_INTERNAL_TOKEN.")
    except Exception as exc:
        sys.exit(f"Could not reach agent-runtime at {runtime}: {exc}")

    # The compatibility agent is a runtime fallback for JSON personas, not a
    # product. Publishing it would put a catalog entry in front of customers
    # that does nothing on its own.
    return [m for m in body.get("agents", []) if m.get("ref") != "generic.single_phase"]


def check_consistency(workflows, agents, manifests):
    """Refuse to publish a set that cannot work once delivered."""
    problems = []

    # A workflow's ref IS its file stem, and that is what gets stored in the
    # published definition. Matching on the whole ref rather than just the
    # RD- prefix is what makes a typo in the tail of a reference an error here
    # instead of a broken tool after delivery.
    refs = {path.stem for path in workflows}

    for path in agents:
        doc = load(path)
        for tool in doc["tools"]:
            if tool["ref"] not in refs:
                problems.append(
                    f"{doc['taskId']}: tool ref '{tool['ref']}' has no published "
                    f"workflow — the delivered agent would have no working tool"
                )

    for manifest in manifests:
        for tool in manifest.get("tools", []):
            if tool.get("ref") not in refs:
                problems.append(
                    f"{manifest['ref']}: tool ref '{tool.get('ref')}' has no published "
                    f"workflow — the delivered agent would have no working tool"
                )
        # The whole point of the Python path is that the product does not ship.
        # A manifest carrying a persona means someone widened Manifest.to_json,
        # and it must not reach a customer's database.
        for leaked in ("persona", "instructions", "prompts"):
            if leaked in manifest:
                problems.append(
                    f"{manifest['ref']}: manifest contains '{leaked}'. A published "
                    f"manifest must carry nothing the customer should not read."
                )

    # Agents only, and that is the point: an agent and a workflow MAY share a
    # title — they are different rows of different types — and it is only two
    # items of the SAME type that this script could not tell apart.
    titles = [m["name"] for m in manifests] + [load(p)["name"] for p in agents]
    for title in {t for t in titles if titles.count(t) > 1}:
        # The catalog has no unique key on title, but this script matches on it
        # to decide update-vs-create. Two items sharing one would leapfrog each
        # other on every run.
        problems.append(f"Two agents are both titled {title!r}; titles must be unique.")

    return problems


def superseded(agents, manifests):
    """JSON agents that a Python module has taken over, keyed by task id.

    An agent rewritten as a Python module keeps its RD- id, because that is what
    ties it to the use case that justified it. Both files then describe the same
    agent, and publishing both would put two catalog entries in front of the
    customer competing for one title.

    The Python one wins, and the JSON file STAYS in the tree — it is still the
    authoring record, it still carries the metadata the workflow half is checked
    against, and deleting it would lose the history of an agent that has been
    migrated. It simply stops being published.
    """
    claimed = {m.get("taskId") for m in manifests if m.get("taskId")}
    return {path for path in agents if load(path).get("taskId") in claimed}


def workflow_payload(doc, ref):
    # nodes[] is what ExecutionEngine walks; inputs[] is what
    # InternalAgentDispatchController.workflowInputs returns as the form.
    #
    # `ref` is how rollout finds this workflow again. An agent's allow-list
    # names a workflow by this stable key rather than by id, because an id
    # means nothing across tenants. Without it stored here there is no way to
    # match a tool reference back to a catalog row — the title is not a key.
    # requires[] travels with the definition so the delivery-time readiness
    # check can tell a customer what to provide, rather than letting them
    # discover it as a run failure.
    # `description` travels INSIDE the definition, like `ref` and `requires`,
    # because the workflows table has no column for it — and without it the
    # description dies at delivery. That matters more than it sounds: it is
    # the text an agent's model reads to decide whether a tool can answer the
    # question at all. With only the title to go on, an agent asked to
    # inventory S3 buckets refused, because "S3 Public Access Audit" sounds
    # like a security scan rather than the bucket listing it actually returns.
    # edges[] is NOT optional, and leaving it out does not produce a broken
    # workflow that announces itself. The runtime starts at the `start` node and
    # follows edges; with none, it executes that one node and stops. Three
    # shipped workflows arrived this way — three nodes, no connections — and a
    # run took 70ms, produced no output, and reported Success under a
    # validation badge reading Valid.
    #
    # The comment that used to sit here said "nodes[] is what ExecutionEngine
    # walks". That was the mistake: it walks edges.
    definition = {"ref": ref, "description": doc["description"],
                  "requires": doc.get("requires", []),
                  "nodes": doc["nodes"], "edges": doc.get("edges", []),
                  "inputs": doc["inputs"]}
    return {
        "title": doc["title"],
        "description": doc["description"],
        "type": "workflow",
        "category": doc.get("category", doc["domain"]),
        "premium": False,
        "definition": json.dumps(definition),
    }


def agent_payload(doc):
    definition = {k: doc[k] for k in AGENT_DEFINITION_KEYS if k in doc}
    return {
        "title": doc["name"],
        "description": doc["description"],
        "type": "agent",
        "category": doc["domain"],
        "premium": False,
        "definition": json.dumps(definition),
    }


def manifest_payload(manifest):
    """A Python agent's catalog row: a reference, and nothing the customer
    should not read.

    ``definition`` is the manifest verbatim. RolloutService keys off
    ``kind == "PYTHON"`` to send ``graphRef``/``graphVersion`` instead of
    ``instructions``, and agent-runtime resolves the ref against its own
    registry at run time — refusing outright if this build does not contain it.
    """
    return {
        "title": manifest["name"],
        "description": manifest["description"],
        "type": "agent",
        "category": manifest["domain"],
        "premium": False,
        "definition": json.dumps(manifest),
    }


class Api:
    def __init__(self, base, token):
        self.base, self.token = base.rstrip("/"), token

    def _call(self, method, path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(f"{self.base}{path}", data=data, method=method)
        req.add_header("Content-Type", "application/json")
        req.add_header("Authorization", f"Bearer {self.token}")
        try:
            with urllib.request.urlopen(req) as resp:
                raw = resp.read().decode()
                return json.loads(raw) if raw else {}
        except urllib.error.HTTPError as exc:
            sys.exit(f"{method} {path} failed: HTTP {exc.code} — {exc.read().decode()[:300]}")

    def catalog(self):
        """Every catalog item already published, by title.

        Read from ``GET /library`` and not ``GET /provider/library``, which does
        not exist — ``ProviderController`` has only POST and PUT there, so this
        script's API mode has been failing with a 500 ("Request method 'GET' is
        not supported") at the very first call. The rows in the catalog today
        were put there by ``--print-sql``, which never touches this path.

        ``/library`` is the right source anyway: it is the same read the
        provider's own Library screen uses, and for a PROVIDER it carries the
        definitions and the rollout counts. Matching on title is what makes
        re-running safe, so this list has to be complete or every item would be
        created a second time.
        """
        rows = self._call("GET", "/library")
        # Keyed by (TYPE, title), not title alone.
        #
        # The catalog has 213 imported PowerShell scripts and some share a title
        # with a workflow built from the same runbook — "AWS Unused EBS Volume
        # Cleanup" is both script #36 and workflow RD-142. Matching on title
        # alone found the script and sent it the workflow's node graph, which
        # left a catalog SCRIPT holding a definition with no steps[] in it: a
        # row that looks fine in the library and cannot run.
        #
        # Platform items only, for the same reason — a provider's own workspace
        # copy of a template would shadow the catalog row and take the update.
        return {(i["type"], i["title"]): i for i in rows if i.get("managed")}

    def create(self, payload):
        return self._call("POST", "/provider/library", payload)

    def update(self, item_id, payload):
        return self._call("PUT", f"/provider/library/{item_id}", payload)


def login(base, user, password):
    req = urllib.request.Request(
        f"{base.rstrip('/')}/auth/login",
        data=json.dumps({"email": user, "password": password}).encode(),
        method="POST")
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req) as resp:
            body = json.loads(resp.read().decode())
    except urllib.error.HTTPError as exc:
        sys.exit(f"Login failed: HTTP {exc.code} — {exc.read().decode()[:200]}")
    token = body.get("accessToken") or body.get("access_token") or body.get("token")
    if not token:
        sys.exit(f"Login succeeded but no token in response: {list(body)}")
    return token


def sql_for(payload):
    """Bootstrap equivalent. tenant_id NULL is what makes a row PLATFORM catalog.

    `library_items` carries no unique key beyond its primary key, so
    ON DUPLICATE KEY UPDATE would never fire and a second run would duplicate
    every row. This emits update-then-insert-if-absent instead, which is
    idempotent AND preserves the row id — delivered tenant copies point back at
    it through `source_id`, so re-inserting would orphan every rollout.
    """
    def esc(value):
        """MySQL string-literal escaping: backslash first, then the quote.

        Order matters — escaping the quote first would then have its own
        backslash doubled by the second pass.
        """
        return (value or "").replace("\\", "\\\\").replace("'", "''")

    title = esc(payload["title"])
    kind = payload["type"].upper()
    description = esc(payload["description"])
    category = esc(payload["category"])
    definition = esc(payload["definition"])
    where = f"tenant_id IS NULL AND title = '{title}' AND type = '{kind}'"
    return (
        f"UPDATE autoops_core.library_items "
        f"SET description = '{description}', category = '{category}', "
        f"definition = '{definition}' "
        f"WHERE {where};\n"
        f"INSERT INTO autoops_core.library_items "
        f"(tenant_id, title, description, type, category, premium, definition, "
        f"installs, created_by) "
        f"SELECT NULL, '{title}', '{description}', '{kind}', '{category}', 0, "
        f"'{definition}', 0, 'publish.py' "
        f"FROM DUAL WHERE NOT EXISTS "
        f"(SELECT 1 FROM autoops_core.library_items WHERE {where});"
    )


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true", help="print the plan, write nothing")
    ap.add_argument("--print-sql", action="store_true", help="emit SQL instead of calling the API")
    ap.add_argument("--api", default=DEFAULT_BASE)
    ap.add_argument("--runtime", default=DEFAULT_RUNTIME,
                    help="agent-runtime base URL; its registry is the source of truth "
                         "for Python-authored agents")
    ap.add_argument("--no-runtime", action="store_true",
                    help="publish only the JSON agents in this tree")
    args = ap.parse_args()

    workflows, agents = discover()
    manifests = [] if args.no_runtime else fetch_manifests(args.runtime, RUNTIME_TOKEN)

    if not workflows and not agents and not manifests:
        sys.exit("nothing to publish")

    # A JSON agent that has been rewritten as a Python module is not published
    # twice — see superseded().
    replaced = superseded(agents, manifests)
    remaining = [p for p in agents if p not in replaced]

    problems = check_consistency(workflows, remaining, manifests)
    if problems:
        print("Refusing to publish:")
        for problem in problems:
            print(f"  - {problem}")
        return 1

    for path in sorted(replaced):
        print(f"skipping {path.relative_to(ROOT.parent)} — superseded by a Python agent")

    # Order matters: an agent references a workflow, so the workflow goes first.
    planned = [("workflow", p, workflow_payload(load(p), p.stem)) for p in workflows] \
        + [("agent", p, agent_payload(load(p))) for p in remaining] \
        + [("agent-py", pathlib.Path(m["ref"]), manifest_payload(m)) for m in manifests]

    if args.print_sql:
        print("-- Platform catalog bootstrap. Review before running.")
        for _, _, payload in planned:
            print(sql_for(payload))
        return 0

    if args.dry_run:
        for kind, path, payload in planned:
            # A Python agent has no file here — its "path" is its registry ref,
            # which is the honest thing to show for something that lives in the
            # runtime's image rather than in this tree.
            where = path if kind == "agent-py" else path.relative_to(ROOT.parent)
            print(f"would publish {kind:9} {payload['title']!r}  ({where})")
        print(f"\n{len(planned)} item(s); nothing written")
        return 0

    user = os.environ.get("AUTOOPS_PROVIDER_USER")
    password = os.environ.get("AUTOOPS_PROVIDER_PASSWORD")
    if not (user and password):
        sys.exit("Set AUTOOPS_PROVIDER_USER and AUTOOPS_PROVIDER_PASSWORD, "
                 "or use --print-sql / --dry-run")

    api = Api(args.api, login(args.api, user, password))
    existing = api.catalog()
    created = updated = 0
    for kind, _, payload in planned:
        found = existing.get((payload["type"], payload["title"]))
        if found:
            api.update(found["id"], payload)
            updated += 1
            print(f"updated  {kind:9} {payload['title']!r}")
        else:
            api.create(payload)
            created += 1
            print(f"created  {kind:9} {payload['title']!r}")
    print(f"\n{created} created, {updated} updated")
    return 0


if __name__ == "__main__":
    sys.exit(main())
