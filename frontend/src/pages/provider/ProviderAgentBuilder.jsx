import React, { useEffect, useMemo, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { PageHeader, Card, SmallButton, Chip } from "../../components/app/appui";
import Icon from "../../components/Icon";
import { api } from "../../lib/api";
import { useStore } from "../../store/store";
import RolloutDialog from "../../components/provider/RolloutDialog";
import { chatModels } from "../../components/app/ModelPicker";

const inputCls =
  "w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 outline-none transition placeholder:text-slate-400 focus:border-violet-400 focus:ring-2 focus:ring-violet-400/20 hover:border-blue-500";

const labelCls = "mb-1.5 block text-xs font-semibold text-slate-700";

/** Mirrors agents/_schema/agent.schema.json, so the two cannot disagree. */
const SCOPES = ["NOC", "SOC", "Both"];
const RISK_LEVELS = ["Low", "Medium", "High"];
const AUTOMATION_TYPES = [
  "Read / Report",
  "Change / Write",
  "Destructive / High-Impact",
];
/** Approval belongs on a state-changing agent; a read-only one asking for it is mis-tagged. */
const STATE_CHANGING = AUTOMATION_TYPES.slice(1);

const MIN_INSTRUCTIONS = 50;
const MAX_DESCRIPTION = 512;

const STARTER_PERSONA = `You are a <role> operator. Describe what the agent is for in one line.

How to work:

1. Start read-only. Gather evidence with the reporting tools before proposing anything.
2. Present findings as a table the operator can check, and total the impact.
3. Call out what a filter cannot notice — the exception that looks wrong.
4. Report per item afterwards: done, skipped, or failed, with the reason.

What you must not do:

- Never widen the scope you were given.
- Never describe an irreversible action as recoverable.
- Never act on a general instruction like "just fix it".
`;

/**
 * A catalog workflow's stable `ref` lives inside its definition JSON — the
 * workflows table has no column for it. Without one the workflow cannot be an
 * agent's tool at all: rollout matches the allow-list to the customer's
 * delivered copies by ref, and a title is not a key.
 */
function workflowOption(item) {
  let spec = {};
  try {
    spec = JSON.parse(item.definition || "{}");
  } catch {
    spec = {};
  }
  return {
    id: item.id,
    title: item.title,
    ref: typeof spec.ref === "string" ? spec.ref : null,
    // The text an agent's MODEL reads to decide whether this tool can answer
    // the question, so the definition's copy wins over the catalog row's.
    description: spec.description || item.description || "",
    rollouts: item.rollouts ?? 0,
  };
}

/** The stored definition back into the form. Defensive: a hand-edited row must
 *  not open a blank builder over someone's persona without saying so. */
function parseAgent(definition) {
  try {
    const spec = JSON.parse(definition || "{}");
    if (spec.kind === "PYTHON") return { python: true };
    return {
      description: spec.description || "",
      model: spec.model || "",
      instructions: spec.instructions || "",
      tools: Array.isArray(spec.tools) ? spec.tools : [],
      guardrails: Array.isArray(spec.guardrails) ? spec.guardrails : [],
      scope: SCOPES.includes(spec.scope) ? spec.scope : "NOC",
      riskLevel: RISK_LEVELS.includes(spec.riskLevel) ? spec.riskLevel : "Low",
      automationType: AUTOMATION_TYPES.includes(spec.automationType)
        ? spec.automationType
        : AUTOMATION_TYPES[0],
      approvalRequired: Boolean(spec.approvalRequired),
    };
  } catch {
    return null;
  }
}

/**
 * Author a production agent and put it in the catalog, from the console.
 *
 * Until now an agent could only be created by editing a JSON file under
 * `backend/agent-service/agents/` and running `_schema/publish.py` with a
 * provider credential in the shell — which means shipping an agent needed a
 * checkout, and no agent could be built by anyone who is not holding the repo.
 * Everything AFTER the catalog row already worked: {@link RolloutDialog}
 * delivers it, RolloutService seals it, agent-service runs it. This is the
 * missing first step, and it writes exactly the same row publish.py does.
 *
 * <p><b>What this produces is a JSON agent</b> — the persona travels in the
 * catalog definition and is copied into each customer's database on rollout,
 * protected by no API exposing it. The stronger PYTHON path (persona stays in
 * agent-runtime's image, the customer's row holds only a ref) cannot be driven
 * from a browser: it is a module in a deployed image. That is the trade for
 * being able to roll out an agent without shipping a build, and the footer
 * says so rather than leaving it implied.
 *
 * <p>The tool allow-list offers CATALOG WORKFLOWS and nothing else, because
 * that is the only thing rollout can resolve: jobs are the customer's own and
 * a provider agent cannot know their ids (RolloutService.resolveTools refuses
 * a non-WORKFLOW tool outright). A workflow with no `ref` in its definition is
 * shown as unusable rather than hidden — "why is my workflow not listed" is a
 * worse question than a row that answers it.
 */
export default function ProviderAgentBuilder() {
  const { pushToast } = useStore();
  const navigate = useNavigate();
  const { id } = useParams();
  const editing = Boolean(id);
  const backTo = "/provider/library/agents";

  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [category, setCategory] = useState("");
  const [model, setModel] = useState("");
  const [premium, setPremium] = useState(false);
  const [instructions, setInstructions] = useState(editing ? "" : STARTER_PERSONA);
  const [guardrails, setGuardrails] = useState("");
  const [scope, setScope] = useState("NOC");
  const [riskLevel, setRiskLevel] = useState("Low");
  const [automationType, setAutomationType] = useState(AUTOMATION_TYPES[0]);
  const [approvalRequired, setApprovalRequired] = useState(false);
  const [tools, setTools] = useState([]); // [{ ref, mutating }]

  const [workflows, setWorkflows] = useState([]);
  const [categories, setCategories] = useState([]);
  const [models, setModels] = useState([]);
  const [saved, setSaved] = useState(null); // the catalog row, once it exists
  const [rolloutOpen, setRolloutOpen] = useState(false);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);

  // One read of the catalog serves three jobs: the agent being edited, the
  // workflows it may use as tools, and the categories already in use. They are
  // the same rows the library page renders, so there is no second read path.
  useEffect(() => {
    let cancelled = false;
    Promise.all([
      api.providerLibrary(),
      // Suggestions only, and a failure here must not block authoring: the
      // model that matters is the one the RECEIVING workspace can reach, which
      // this console cannot see anyway.
      api.listWorkspaceModels().catch(() => []),
    ])
      .then(([rows, providers]) => {
        if (cancelled) return;
        const list = rows || [];
        setWorkflows(
          list.filter((r) => r.type === "workflow").map(workflowOption),
        );
        setCategories(
          [
            ...new Set(list.map((r) => (r.category || "").trim()).filter(Boolean)),
          ].sort((a, b) => a.localeCompare(b)),
        );
        setModels(
          [
            ...new Set(
              (providers || [])
                .filter((p) => p.verified)
                .flatMap((p) => chatModels(p)),
            ),
          ].sort((a, b) => a.localeCompare(b)),
        );

        if (!editing) return;
        const item = list.find((r) => String(r.id) === String(id));
        if (!item) {
          setError("That agent is no longer in the catalog.");
          return;
        }
        setSaved(item);
        setTitle(item.title || "");
        setCategory(item.category || "");
        setPremium(Boolean(item.premium));
        const spec = parseAgent(item.definition);
        if (spec?.python) {
          // Its persona is not here to edit — it lives in agent-runtime's
          // image. Saving from this form would replace a reference with an
          // empty JSON agent, so refuse instead of quietly doing it.
          setError(
            "This agent is authored in code (agent-runtime), so its persona is not in the catalog and cannot be edited here. Change it in backend/agent-runtime and re-publish.",
          );
          return;
        }
        if (!spec) {
          setError(
            "This agent's stored definition could not be read, so the form is empty. Saving will replace it.",
          );
          return;
        }
        setDescription(spec.description || item.description || "");
        setModel(spec.model);
        setInstructions(spec.instructions);
        setTools(
          spec.tools.map((t) => ({
            ref: t.ref,
            // Absent means mutating, on both sides of the wire.
            mutating: typeof t.mutating === "boolean" ? t.mutating : true,
          })),
        );
        setGuardrails(spec.guardrails.join("\n"));
        setScope(spec.scope);
        setRiskLevel(spec.riskLevel);
        setAutomationType(spec.automationType);
        setApprovalRequired(spec.approvalRequired);
      })
      .catch((err) => !cancelled && setError(err.message || "Could not load the catalog"))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [editing, id]);

  const chosen = useMemo(() => new Map(tools.map((t) => [t.ref, t])), [tools]);

  const toggleTool = (ref) => {
    setTools((current) =>
      current.some((t) => t.ref === ref)
        ? current.filter((t) => t.ref !== ref)
        // Mutating by default, matching the schema and RolloutService. An
        // unmarked read-only tool goes unused and is noticed; an unmarked
        // destructive one would reach the phase that must not see it.
        : [...current, { ref, mutating: true }],
    );
  };

  const setMutating = (ref, mutating) =>
    setTools((current) =>
      current.map((t) => (t.ref === ref ? { ...t, mutating } : t)),
    );

  // Named so the person is told WHICH field, not just that something is wrong.
  const problems = useMemo(() => {
    const found = [];
    if (title.trim().length < 3) found.push("Give the agent a name.");
    if (description.trim().length < 10)
      found.push("Write a description — it is what a customer reads in the catalog.");
    if (description.trim().length > MAX_DESCRIPTION)
      found.push(`The description is over ${MAX_DESCRIPTION} characters.`);
    if (!model.trim()) found.push("Choose a model.");
    if (instructions.trim().length < MIN_INSTRUCTIONS)
      found.push(
        `The operating instructions are the product — write at least ${MIN_INSTRUCTIONS} characters.`,
      );
    if (tools.length === 0)
      found.push("Pick at least one workflow. An agent with no tool can only talk.");
    if (approvalRequired && !STATE_CHANGING.includes(automationType))
      found.push(
        "An approval gate belongs on a state-changing agent — set the automation type to Change / Write or Destructive.",
      );
    return found;
  }, [title, description, model, instructions, tools, approvalRequired, automationType]);

  const save = async ({ thenRollOut = false } = {}) => {
    if (problems.length) {
      setError(problems[0]);
      return;
    }
    setSaving(true);
    setError(null);
    // The same shape publish.py writes. RolloutService reads description,
    // model, instructions and tools out of this; the rest travels for the
    // catalog's own sake and is harmless to a reader.
    const definition = JSON.stringify({
      description: description.trim(),
      model: model.trim(),
      instructions: instructions.trim(),
      tools: tools.map((t) => ({ type: "WORKFLOW", ref: t.ref, mutating: t.mutating })),
      guardrails: guardrails
        .split("\n")
        .map((g) => g.trim())
        .filter(Boolean),
      domain: category.trim() || "General",
      scope,
      riskLevel,
      automationType,
      approvalRequired,
    });
    const common = {
      title: title.trim(),
      description: description.trim(),
      category: category.trim() || "General",
      definition,
      premium,
    };
    try {
      if (editing) {
        await api.providerUpdateLibrary(id, common);
        pushToast(`"${common.title}" saved`, "emerald");
        setSaved((s) => ({ ...s, ...common, id }));
      } else {
        const created = await api.providerCreateLibrary({ ...common, type: "agent" });
        pushToast(`"${common.title}" published to the catalog`, "emerald");
        setSaved({ ...common, id: created.id, type: "agent" });
      }
      if (thenRollOut) setRolloutOpen(true);
      else navigate(backTo);
    } catch (err) {
      setError(err.message || "Could not save the agent");
    } finally {
      setSaving(false);
    }
  };

  const usable = workflows.filter((w) => w.ref);
  const unusable = workflows.filter((w) => !w.ref);

  return (
    <div className="animate-fade-up">
      <PageHeader
        title={editing ? "Edit agent" : "New agent"}
        subtitle="Author it here, then roll it out to any customer — sealed, persona withheld"
        actions={
          <>
            <SmallButton icon="chevron" onClick={() => navigate(backTo)}>
              Cancel
            </SmallButton>
            <SmallButton
              icon="bolt"
              onClick={() => save({ thenRollOut: true })}
              disabled={saving || loading}
            >
              Save &amp; roll out
            </SmallButton>
            <SmallButton
              icon="check"
              variant="primary"
              onClick={() => save()}
              disabled={saving || loading}
            >
              {saving ? "Saving…" : editing ? "Save changes" : "Publish to catalog"}
            </SmallButton>
          </>
        }
      />

      <div className="grid gap-6 lg:grid-cols-[minmax(0,340px)_minmax(0,1fr)]">
        {/* ── Left: what it is, and how it is classified ── */}
        <div className="space-y-6">
          <Card className="h-fit p-6">
            <h3 className="mb-4 text-sm font-semibold text-slate-900">Agent details</h3>
            <div className="space-y-4">
              <div>
                <label className={labelCls} htmlFor="agent-name">
                  Name
                </label>
                <input
                  id="agent-name"
                  autoFocus
                  value={title}
                  onChange={(e) => setTitle(e.target.value)}
                  placeholder="AWS Unused EBS Volume Cleanup Agent"
                  className={inputCls}
                />
              </div>

              <div>
                <label className={labelCls} htmlFor="agent-description">
                  Description
                </label>
                <textarea
                  id="agent-description"
                  rows={3}
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                  placeholder="What it finds, what it changes, and what it will never do without approval."
                  className={`${inputCls} resize-none`}
                />
                <p className="mt-1 text-right text-[11px] text-slate-400">
                  {description.trim().length}/{MAX_DESCRIPTION}
                </p>
              </div>

              <div>
                <label className={labelCls} htmlFor="agent-category">
                  Category
                </label>
                <input
                  id="agent-category"
                  list="agent-category-options"
                  value={category}
                  onChange={(e) => setCategory(e.target.value)}
                  placeholder="AWS"
                  className={inputCls}
                />
                <datalist id="agent-category-options">
                  {categories.map((c) => (
                    <option key={c} value={c} />
                  ))}
                </datalist>
              </div>

              <div>
                <label className={labelCls} htmlFor="agent-model">
                  Model
                </label>
                {/* A datalist rather than a select: the models listed are the
                    ones THIS workspace can reach, and the agent runs in the
                    CUSTOMER's. resolveForModel refuses a model no enabled
                    connection there offers rather than silently picking
                    another vendor, so the id must stay typeable. */}
                <input
                  id="agent-model"
                  list="agent-model-options"
                  value={model}
                  onChange={(e) => setModel(e.target.value)}
                  placeholder="anthropic.claude-sonnet-5"
                  className={`${inputCls} font-mono text-xs`}
                />
                <datalist id="agent-model-options">
                  {models.map((m) => (
                    <option key={m} value={m} />
                  ))}
                </datalist>
                <p className="mt-1.5 text-[11px] leading-relaxed text-slate-500">
                  The receiving workspace must have an enabled provider offering
                  this exact id — there is no fallback, and a run refuses rather
                  than sending their data to a vendor they did not choose.
                </p>
              </div>

              <label className="flex cursor-pointer items-center gap-2.5 border-t border-slate-200 pt-4">
                <input
                  type="checkbox"
                  checked={premium}
                  onChange={(e) => setPremium(e.target.checked)}
                  className="h-4 w-4 rounded border-slate-300 accent-violet-600"
                />
                <span className="text-sm text-slate-700">
                  Premium — Business plan and above
                </span>
              </label>
            </div>
          </Card>

          <Card className="h-fit p-6">
            <h3 className="mb-1 text-sm font-semibold text-slate-900">Blast radius</h3>
            <p className="mb-4 text-[11px] leading-relaxed text-slate-500">
              What happens if this agent acts wrongly — not how hard it was to
              build. The approval gate is enforced by core-service.
            </p>
            <div className="space-y-4">
              <div>
                <label className={labelCls} htmlFor="agent-automation-type">
                  Automation type
                </label>
                <select
                  id="agent-automation-type"
                  value={automationType}
                  onChange={(e) => setAutomationType(e.target.value)}
                  className={inputCls}
                >
                  {AUTOMATION_TYPES.map((t) => (
                    <option key={t} value={t}>
                      {t}
                    </option>
                  ))}
                </select>
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className={labelCls} htmlFor="agent-risk">
                    Risk level
                  </label>
                  <select
                    id="agent-risk"
                    value={riskLevel}
                    onChange={(e) => setRiskLevel(e.target.value)}
                    className={inputCls}
                  >
                    {RISK_LEVELS.map((r) => (
                      <option key={r} value={r}>
                        {r}
                      </option>
                    ))}
                  </select>
                </div>
                <div>
                  <label className={labelCls} htmlFor="agent-scope">
                    Scope
                  </label>
                  <select
                    id="agent-scope"
                    value={scope}
                    onChange={(e) => setScope(e.target.value)}
                    className={inputCls}
                  >
                    {SCOPES.map((s) => (
                      <option key={s} value={s}>
                        {s}
                      </option>
                    ))}
                  </select>
                </div>
              </div>
              <label className="flex cursor-pointer items-start gap-2.5">
                <input
                  type="checkbox"
                  checked={approvalRequired}
                  onChange={(e) => setApprovalRequired(e.target.checked)}
                  className="mt-0.5 h-4 w-4 rounded border-slate-300 accent-violet-600"
                />
                <span className="text-sm text-slate-700">
                  Requires human approval
                  <span className="mt-0.5 block text-[11px] text-slate-500">
                    The run parks in the customer&rsquo;s approvals inbox before
                    anything changes.
                  </span>
                </span>
              </label>
            </div>
          </Card>
        </div>

        {/* ── Right: the product — the persona, the tools, the limits ── */}
        <div className="space-y-6">
          <Card className="overflow-hidden p-0">
            <div className="flex items-center justify-between border-b border-slate-800 bg-slate-950 px-4 py-2.5">
              <span className="flex items-center gap-2 font-mono text-xs text-slate-300">
                <Icon name="robot" size={13} />
                operating instructions
              </span>
              <span className="font-mono text-[10px] text-slate-500">
                {instructions.trim().length < MIN_INSTRUCTIONS
                  ? `${instructions.trim().length}/${MIN_INSTRUCTIONS} minimum`
                  : `${instructions.trim().length} characters`}
              </span>
            </div>
            <textarea
              value={loading ? "" : instructions}
              onChange={(e) => setInstructions(e.target.value)}
              disabled={loading}
              spellCheck={false}
              aria-label="Operating instructions"
              placeholder="How this agent works, and what it must never do."
              className="w-full resize-none border-0 bg-slate-950 px-4 py-4 font-mono text-xs leading-6 text-slate-100 caret-emerald-400 outline-none placeholder:text-slate-600"
              style={{ height: "clamp(320px, calc(100vh - 34rem), 560px)" }}
            />
            <div className="border-t border-slate-800 bg-slate-950 px-4 py-2 font-mono text-[10px] leading-relaxed text-slate-500">
              This is the product. It is never serialised to a customer — not in
              the run output, not in the API.
            </div>
          </Card>

          <Card className="p-6">
            <div className="mb-1 flex items-center justify-between">
              <h3 className="text-sm font-semibold text-slate-900">
                Tools it may use
              </h3>
              <Chip>{tools.length} selected</Chip>
            </div>
            <p className="mb-4 text-[11px] leading-relaxed text-slate-500">
              A closed allow-list of catalog workflows. Jobs are the
              customer&rsquo;s own and cannot be referenced here. Every workflow
              you tick must ALSO be rolled out to the target project, or that
              customer&rsquo;s delivery fails naming what is missing.
            </p>

            {loading ? (
              <p className="text-sm text-slate-500">Loading workflows…</p>
            ) : usable.length === 0 ? (
              <p className="rounded-lg border border-amber-400/30 bg-amber-400/5 px-3 py-2.5 text-sm text-amber-700">
                No selectable workflow in the catalog yet. An agent needs at
                least one — publish a workflow first.
              </p>
            ) : (
              <div className="space-y-2">
                {usable.map((w) => {
                  const picked = chosen.get(w.ref);
                  return (
                    <div
                      key={w.ref}
                      className={`rounded-xl border px-3 py-2.5 transition ${
                        picked
                          ? "border-violet-400/40 bg-violet-400/[0.04]"
                          : "border-slate-200"
                      }`}
                    >
                      <label className="flex cursor-pointer items-start gap-2.5">
                        <input
                          type="checkbox"
                          checked={Boolean(picked)}
                          onChange={() => toggleTool(w.ref)}
                          className="mt-0.5 h-4 w-4 rounded border-slate-300 accent-violet-600"
                        />
                        <span className="min-w-0 flex-1">
                          <span className="block truncate text-sm font-medium text-slate-900">
                            {w.title}
                          </span>
                          <span className="block truncate font-mono text-[10px] text-slate-400">
                            {w.ref}
                          </span>
                          {w.description && (
                            <span className="mt-1 block text-xs leading-relaxed text-slate-500">
                              {w.description}
                            </span>
                          )}
                        </span>
                        {w.rollouts === 0 && (
                          <span className="shrink-0 rounded-full bg-amber-50 px-2 py-0.5 text-[10px] font-medium text-amber-700">
                            not delivered
                          </span>
                        )}
                      </label>

                      {picked && (
                        // Nothing in a workflow's own definition records
                        // whether running it changes anything — a workflow is
                        // a list of steps, and "does step four delete
                        // something" is not a question its schema can answer.
                        // So the agent's author declares it, here.
                        <div className="mt-2.5 flex items-center gap-2 pl-7">
                          <select
                            aria-label={`Effect of ${w.title}`}
                            value={picked.mutating ? "mutating" : "read"}
                            onChange={(e) =>
                              setMutating(w.ref, e.target.value === "mutating")
                            }
                            className="rounded-lg border border-slate-200 bg-slate-50 px-2.5 py-1.5 text-xs text-slate-700 outline-none focus:border-violet-400"
                          >
                            <option value="read">
                              Read-only — safe while gathering evidence
                            </option>
                            <option value="mutating">
                              Changes state — hidden until the evidence phase ends
                            </option>
                          </select>
                        </div>
                      )}
                    </div>
                  );
                })}

              </div>
            )}

            {/* Outside the list above on purpose. Nested inside it, this was
                unreachable in the one case it exists to explain — every
                workflow refless, so the panel showed "no workflow in the
                catalog" over a catalog that plainly had one. */}
            {!loading && unusable.length > 0 && (
              <div className="mt-2 rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5">
                {/* One string, not three text nodes glued by JSX: the broken-up
                    version renders the same and is unfindable by the text a
                    person actually reads. */}
                <p className="text-xs font-medium text-slate-600">
                  {unusable.length === 1
                    ? "1 workflow is not selectable"
                    : `${unusable.length} workflows are not selectable`}
                </p>
                <p className="mt-1 text-[11px] leading-relaxed text-slate-500">
                  {unusable.map((w) => w.title).join(", ")} — published without a
                  stable <code className="font-mono">ref</code>, so a rollout
                  cannot match it to the customer&rsquo;s copy. Re-publish
                  through{" "}
                  <code className="font-mono">agents/_schema/publish.py</code> to
                  give it one.
                </p>
              </div>
            )}
          </Card>

          <Card className="p-6">
            <h3 className="mb-1 text-sm font-semibold text-slate-900">Guardrails</h3>
            <p className="mb-3 text-[11px] leading-relaxed text-slate-500">
              One per line. These are shown to the customer — they are what the
              agent promises not to do, and unlike the instructions they are not
              withheld.
            </p>
            <textarea
              rows={5}
              value={guardrails}
              onChange={(e) => setGuardrails(e.target.value)}
              placeholder={
                "Report-only by default: nothing is changed without an approval.\nScope is one region per run."
              }
              className={`${inputCls} resize-y leading-relaxed`}
            />
          </Card>
        </div>
      </div>

      {problems.length > 0 && !error && (
        <Card className="mt-6 border-amber-400/30 bg-amber-400/[0.04] p-4">
          <p className="mb-2 flex items-center gap-2 text-sm font-semibold text-amber-700">
            <Icon name="warning" size={15} />
            Not ready to publish
          </p>
          <ul className="space-y-1 pl-6 text-sm text-amber-700">
            {problems.map((p) => (
              <li key={p} className="list-disc">
                {p}
              </li>
            ))}
          </ul>
        </Card>
      )}

      {error && (
        <p className="mt-4 rounded-lg border border-red-400/30 bg-red-400/5 px-3 py-2 text-sm text-red-600">
          {error}
        </p>
      )}

      <p className="mt-6 border-t border-slate-200 pt-4 text-[11px] leading-relaxed text-slate-500">
        An agent authored here carries its persona in the catalog, which is
        copied into each customer&rsquo;s database on rollout and protected by
        no API exposing it. For an agent whose prompts must never leave your
        infrastructure, author it as a module in{" "}
        <code className="font-mono">backend/agent-runtime</code> instead — the
        customer then holds only a reference, at the cost of a deploy per agent.
      </p>

      {rolloutOpen && saved && (
        <RolloutDialog
          item={{ ...saved, type: "agent" }}
          onClose={() => {
            setRolloutOpen(false);
            navigate(backTo);
          }}
          onDone={() => {}}
        />
      )}
    </div>
  );
}
