"""Replace the Dify-backed rows with the ported native definitions.

Catalog rows (library_items, tenant NULL) and the tenant's own workflow rows
are both updated in place, matched by TITLE — the ids differ between the two
tables and the title is what a person recognises.

The third workflow, "Agentic Business Research and Report Generator", has no
local DSL export, so there is nothing to port it from. Its row is deleted; its
DESIGN still exists only inside the Dify instance.
"""
import io
import json
import re

BS = chr(92)
Q = chr(39)

PORTED = {
    "Incident Postmortem Writer": ".dify-archive/Incident_Postmortem_Writer.native.json",
    "Meeting Actions and Follow-up": ".dify-archive/Meeting_Actions_and_Follow-up.native.json",
}

statements = []
for title, path in PORTED.items():
    spec = json.load(io.open(path, encoding="utf-8"))
    payload = json.dumps(spec)
    esc = payload.replace(BS, BS + BS).replace(Q, BS + Q)
    node_count = len(spec["nodes"])
    safe_title = title.replace(Q, BS + Q)
    statements.append(
        "UPDATE autoops_core.library_items SET definition='%s' "
        "WHERE tenant_id IS NULL AND title='%s';" % (esc, safe_title))
    statements.append(
        "UPDATE autoops_workflow.workflows SET definition='%s', node_count=%d "
        "WHERE name='%s';" % (esc, node_count, safe_title))

# Nothing to port this one from; archived as a row, but its design is not here.
statements.append(
    "DELETE FROM autoops_core.library_items "
    "WHERE definition LIKE '%difyWorkflow%';")
statements.append(
    "DELETE FROM autoops_workflow.workflows "
    "WHERE definition LIKE '%difyWorkflow%';")

statements.append(
    "SELECT 'remaining-dify-rows' AS check_name, "
    "(SELECT COUNT(*) FROM autoops_core.library_items WHERE definition LIKE '%difyWorkflow%') "
    "+ (SELECT COUNT(*) FROM autoops_workflow.workflows WHERE definition LIKE '%difyWorkflow%') "
    "AS n;")
statements.append(
    "SELECT id, name, node_count FROM autoops_workflow.workflows ORDER BY id;")

io.open(".dify-archive/install.sql", "w", encoding="utf-8", newline="\n").write(
    "\n".join(statements) + "\n")
print("wrote .dify-archive/install.sql with", len(statements), "statements")
