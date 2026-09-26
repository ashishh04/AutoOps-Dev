import React, { useEffect, useMemo, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import {
  PageHeader,
  Card,
  SmallButton,
  Chip,
} from "../../components/app/appui";
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

/**
 * "Whatever this customer runs on", said explicitly.
 *
 * The same sentinel NativeWorkflowService uses. Leaving the model blank means
 * the same thing, but a form that can SHOW the intent is better than one where
 * the absence of a field is the intent — and an agent is delivered to many
 * workspaces, each with its own vendor.
 */
const TENANT_DEFAULT = "@tenant-default";

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
  return {
    id: item.id,
    title: item.title,
    // Straight off the row now. `ref` used to be dug out of the definition
    // JSON, which meant this screen had to download every definition in the
    // catalog — about two megabytes — to read one string per workflow.
    ref: typeof item.ref === "string" && item.ref ? item.ref : null,
    // The catalog row's description. The definition carries its own, which the
    // agent's MODEL reads at run time and which rollout delivers with the
    // workflow — but that copy is not worth two megabytes to display here.
    description: item.description || "",
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
      phases: Array.isArray(spec.phases) ? spec.phases : [],
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
  const [instructions, setInstructions] = useState(
    editing ? "" : STARTER_PERSONA,
  );
  const [guardrails, setGuardrails] = useState("");
  const [scope, setScope] = useState("NOC");
  const [riskLevel, setRiskLevel] = useState("Low");
  const [automationType, setAutomationType] = useState(AUTOMATION_TYPES[0]);
  const [approvalRequired, setApprovalRequired] = useState(false);
  const [tools, setTools] = useState([]); // [{ ref, mutating, subjects[] }]
  const [phases, setPhases] = useState([]);
  // The authoring contract, read from the runtime rather than remembered here.
  // Null while loading and null when the runtime could not be reached — and the
  // second case is SAID rather than papered over with a built-in list, because
  // an author building against a stale palette finds out at run time.
  const [schema, setSchema] = useState(null);
  const [schemaError, setSchemaError] = useState(null);

  const [workflows, setWorkflows] = useState([]);
  const [categories, setCategories] = useState([]);
  const [models, setModels] = useState([]);
  // Model ids already in use by catalog agents. The provider workspace often
  // has no verified vendor connection of its own — the agents run in the
  // CUSTOMER's workspace — so without this the dropdown would be empty on the
  // one screen whose whole job is choosing a model.
  const [catalogModels, setCatalogModels] = useState([]);
  // True when the author is typing an id the dropdown does not offer.
  const [customModel, setCustomModel] = useState(false);
  // Clamped against the page count on read rather than reset on write, so a
  // filtered-down list cannot leave the picker showing an empty page.
  const [toolPageRaw, setToolPage] = useState(1);
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
      // Separated from the others' failure handling on purpose. A missing
      // model list costs a suggestion; a missing schema costs the two fields
      // that decide whether this agent's findings can ever be checked, so it
      // has to be reported rather than absorbed.
      api.providerAuthoringSchema().catch((err) => ({ __error: err })),
    ])
      .then(async ([rows, providers, contract]) => {
        if (cancelled) return;
        if (contract && contract.__error) {
          setSchemaError(
            contract.__error.message ||
              "The agent runtime did not answer, so phases and subject declarations cannot be offered.",
          );
        } else {
          setSchema(contract?.agent || null);
        }
        if (cancelled) return;
        const list = rows || [];
        setWorkflows(
          list.filter((r) => r.type === "workflow").map(workflowOption),
        );
        setCategories(
          [
            ...new Set(
              list.map((r) => (r.category || "").trim()).filter(Boolean),
            ),
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
        // `model` arrives as a column now, for the same reason `ref` does:
        // reading one string per agent used to mean downloading every
        // definition in the catalog.
        setCatalogModels([
          ...new Set(
            list
              .filter((r) => r.type === "agent")
              .map((r) => r.model)
              .filter(Boolean),
          ),
        ]);

        if (!editing) return;
        // ONE definition, fetched on its own. The list no longer carries them —
        // a screen that edits a single agent has no business downloading every
        // body in the catalog to find it.
        const item = await api.libraryItem(id).catch(() => null);
        if (!item) {
          setError("That agent is no longer in the catalog.");
          return;
        }
        if (cancelled) return;
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
            subjects: Array.isArray(t.subjects) ? t.subjects : [],
          })),
        );
        setPhases(spec.phases);
        setGuardrails(spec.guardrails.join("\n"));
        setScope(spec.scope);
        setRiskLevel(spec.riskLevel);
        setAutomationType(spec.automationType);
        setApprovalRequired(spec.approvalRequired);
      })
      .catch(
        (err) =>
          !cancelled && setError(err.message || "Could not load the catalog"),
      )
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [editing, id]);

  const chosen = useMemo(() => new Map(tools.map((t) => [t.ref, t])), [tools]);

  /**
   * Everything offerable in the model dropdown.
   *
   * `model` itself is always included. Editing an agent whose model this
   * console cannot see would otherwise open a select with nothing chosen, and
   * saving would silently blank a working agent's model.
   */
  // The same split `save` uses, so the count shown and the list published
  // cannot disagree — a blank line is not a promise.
  const guardrailLines = useMemo(
    // Split on a regex rather than a newline literal, so a guardrail list
    // pasted from a Windows editor does not arrive with a trailing \r on every
    // line and get published that way.
    () =>
      guardrails
        .split(/\r?\n/)
        .map((g) => g.trim())
        .filter(Boolean),
    [guardrails],
  );

  const modelOptions = useMemo(() => {
    const all = new Set([...models, ...catalogModels]);
    if (model.trim()) all.add(model.trim());
    // The sentinel has its own entry above; listing it here too would show it
    // twice, once as a literal id.
    all.delete(TENANT_DEFAULT);
    return [...all].sort((a, b) => a.localeCompare(b));
  }, [models, catalogModels, model]);

  const toggleTool = (ref) => {
    setTools((current) =>
      current.some((t) => t.ref === ref)
        ? current.filter((t) => t.ref !== ref)
        : // Mutating by default, matching the schema and RolloutService. An
          // unmarked read-only tool goes unused and is noticed; an unmarked
          // destructive one would reach the phase that must not see it.
          [...current, { ref, mutating: true, subjects: [] }],
    );
  };

  const setMutating = (ref, mutating) =>
    setTools((current) =>
      current.map((t) => (t.ref === ref ? { ...t, mutating } : t)),
    );

  const editSubjects = (ref, fn) =>
    setTools((current) =>
      current.map((t) =>
        t.ref === ref ? { ...t, subjects: fn(t.subjects || []) } : t,
      ),
    );

  const addSubject = (ref) =>
    editSubjects(ref, (rows) => [
      ...rows,
      // Every field starts empty, including subject_kind. A pre-filled kind
      // would be a guess about what somebody else's workflow returns, and a
      // wrong one produces a coverage claim over the wrong sort of thing —
      // which is worse than no claim, because a claim grounds a reap.
      { subject_kind: "", items: "", id_template: "" },
    ]);

  const removeSubject = (ref, index) =>
    editSubjects(ref, (rows) => rows.filter((_, i) => i !== index));

  const setSubjectField = (ref, index, field, value) =>
    editSubjects(ref, (rows) =>
      rows.map((row, i) => (i === index ? { ...row, [field]: value } : row)),
    );

  const togglePhase = (value) =>
    setPhases((current) =>
      current.includes(value)
        ? current.filter((p) => p !== value)
        : // Appended, not inserted in palette order. The graph is built from this
          // list, so the order the author picks IS the order the agent runs — and
          // sorting it back into the canonical order would silently rewrite their
          // agent into a different one.
          [...current, value],
    );

  // Named so the person is told WHICH field, not just that something is wrong.
  const problems = useMemo(() => {
    const found = [];
    if (title.trim().length < 3) found.push("Give the agent a name.");
    if (description.trim().length < 10)
      found.push(
        "Write a description — it is what a customer reads in the catalog.",
      );
    if (description.trim().length > MAX_DESCRIPTION)
      found.push(`The description is over ${MAX_DESCRIPTION} characters.`);
    // Still required — but "the customer's default" is now one of the answers,
    // rather than the field having to name a vendor the provider is guessing at.
    if (!model.trim())
      found.push("Choose a model, or let each customer use their own default.");
    if (instructions.trim().length < MIN_INSTRUCTIONS)
      found.push(
        `The operating instructions are the product — write at least ${MIN_INSTRUCTIONS} characters.`,
      );
    if (tools.length === 0)
      found.push(
        "Pick at least one workflow. An agent with no tool can only talk.",
      );
    if (approvalRequired && !STATE_CHANGING.includes(automationType))
      found.push(
        "An approval gate belongs on a state-changing agent — set the automation type to Change / Write or Destructive.",
      );
    return found;
  }, [
    title,
    description,
    model,
    instructions,
    tools,
    approvalRequired,
    automationType,
  ]);

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
      tools: tools.map((t) => {
        const entry = { type: "WORKFLOW", ref: t.ref, mutating: t.mutating };
        const declared = (t.subjects || []).filter(
          (sub) => sub.subject_kind?.trim() && sub.id_template?.trim(),
        );
        if (declared.length) {
          // Omitted rather than written as [] when there is nothing to say.
          // Both mean "enumerate nothing", but an empty array in a stored row
          // reads as though somebody considered the question and decided no.
          entry.subjects = declared.map((sub) => {
            const source = {
              subject_kind: sub.subject_kind.trim(),
              items: (sub.items || "").trim(),
              id_template: sub.id_template.trim(),
            };
            // total_field is left out unless it was filled. The guidance is to
            // omit it rather than point it at anything derived from the list:
            // a total that equals the list by construction agrees with it in
            // every case including the broken ones.
            if (sub.total_field?.trim())
              source.total_field = sub.total_field.trim();
            if (sub.truncated_field?.trim())
              source.truncated_field = sub.truncated_field.trim();
            return source;
          });
        }
        return entry;
      }),
      // Absent means no declaration, which runs the un-phased compatibility
      // loop — what every agent authored before this existed does.
      ...(phases.length ? { phases } : {}),
      // The same value the count beside the field shows. Splitting twice is
      // how the two quietly come to disagree.
      guardrails: guardrailLines,
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
        const created = await api.providerCreateLibrary({
          ...common,
          type: "agent",
        });
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

  // The catalog holds fourteen workflows and grows; rendering all of them made
  // the tool picker several screens tall and pushed everything below it out of
  // reach. Four to a page keeps the card the same height as the ones beside it.
  // Four. With Guardrails moved out from under it the left column is three
  // cards, and four tool rows puts the two columns within a card's height of
  // each other — which is what removes the gap, rather than stretching
  // anything to cover one.
  const TOOLS_PER_PAGE = 4;
  const toolPages = Math.max(1, Math.ceil(usable.length / TOOLS_PER_PAGE));
  const toolPage = Math.min(toolPageRaw, toolPages);
  const visibleTools = usable.slice(
    (toolPage - 1) * TOOLS_PER_PAGE,
    toolPage * TOOLS_PER_PAGE,
  );

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
              {saving
                ? "Saving…"
                : editing
                  ? "Save changes"
                  : "Publish to catalog"}
            </SmallButton>
          </>
        }
      />

      <div className="grid gap-6 lg:grid-cols-[minmax(0,340px)_minmax(0,1fr)]">
        {/* ── Left: what it is, how it is classified, and how it behaves ──
            "How it works" and "Guardrails" live here rather than on the right,
            and the reason is layout, not taxonomy: the right column holds the
            two cards that genuinely NEED width — the persona editor and the
            tool list with its descriptions — and those alone ran far taller
            than this column, leaving most of a screen blank beside them. These
            two are form-shaped and read fine narrow, so they balance it. ── */}
        <div className="flex flex-col gap-6">
          <Card className="h-fit p-6">
            <h3 className="mb-4 text-sm font-semibold text-slate-900">
              Agent details
            </h3>
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
                {/* A select, with a way out. The dropdown was a datalist
                    before, which renders as a plain text box with no affordance
                    — and on a provider workspace with no verified vendor of its
                    own the list was empty, so it looked like a free-text field
                    that happened to validate.

                    The escape hatch is not decoration. The models offered here
                    are the ones THIS workspace can see, and the agent runs in
                    the CUSTOMER's; resolveForModel refuses an id no enabled
                    connection there offers rather than quietly picking another
                    vendor. So an id this console has never heard of is a
                    legitimate answer and has to stay typeable. */}
                <select
                  id="agent-model"
                  value={customModel ? "__custom__" : model}
                  onChange={(e) => {
                    if (e.target.value === "__custom__") {
                      setCustomModel(true);
                      setModel("");
                    } else {
                      setCustomModel(false);
                      setModel(e.target.value);
                    }
                  }}
                  className={`${inputCls} font-mono text-xs`}
                >
                  <option value="">Choose a model…</option>
                  {/* First, because it is the right answer for most catalog
                      agents: the agent runs in the CUSTOMER's workspace, and
                      the provider rarely knows which vendor they are on. */}
                  <option value={TENANT_DEFAULT}>
                    Use each customer&rsquo;s own default model
                  </option>
                  {modelOptions.map((m) => (
                    <option key={m} value={m}>
                      {m}
                    </option>
                  ))}
                  <option value="__custom__">Another id…</option>
                </select>
                {customModel && (
                  <input
                    aria-label="Model id"
                    value={model}
                    onChange={(e) => setModel(e.target.value)}
                    placeholder="anthropic.claude-sonnet-5"
                    className={`${inputCls} mt-2 font-mono text-xs`}
                    autoFocus
                  />
                )}
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
            <h3 className="mb-1 text-sm font-semibold text-slate-900">
              Blast radius
            </h3>
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

          <Card className="p-6">
            <div className="mb-1 flex items-center justify-between gap-2">
              <h3 className="text-sm font-semibold text-slate-900">
                How it works
              </h3>
              <div className="flex items-center gap-2">
                {phases.length > 0 && (
                  // Unpicking eight phases one at a time to get back to the
                  // legacy loop is a lot of clicking to reach the DEFAULT.
                  <button
                    type="button"
                    onClick={() => setPhases([])}
                    className="text-[11px] font-medium text-slate-500 hover:text-rose-600"
                  >
                    Clear
                  </button>
                )}
                <Chip>
                  {phases.length ? `${phases.length} phases` : "single loop"}
                </Chip>
              </div>
            </div>
            <p className="mb-4 text-[11px] leading-relaxed text-slate-500">
              Pick phases and the agent runs the phased runtime: each step sees
              only the tools it should, and the report must cite the evidence it
              used. Pick none and it runs one un-narrowed loop with every tool
              visible at once — which is what agents built before this existed
              do, and the right choice for a persona written for that.
            </p>

            {schemaError ? (
              // No fallback list. An author building against a remembered
              // palette would find out at run time, having been shown nothing.
              <p
                role="alert"
                className="rounded-lg border border-amber-400/30 bg-amber-400/5 px-3 py-2.5 text-sm text-amber-700"
              >
                {schemaError}
              </p>
            ) : !schema ? (
              <p className="text-sm text-slate-500">Loading phases…</p>
            ) : (
              <>
                <div className="flex flex-wrap gap-1.5">
                  {schema.phases.map((phase) => {
                    const at = phases.indexOf(phase.value);
                    return (
                      <button
                        key={phase.value}
                        type="button"
                        aria-pressed={at >= 0}
                        onClick={() => togglePhase(phase.value)}
                        className={`rounded-lg border px-2.5 py-1.5 text-xs font-medium transition ${
                          at >= 0
                            ? "border-violet-400/40 bg-violet-400/10 text-violet-700"
                            : "border-slate-200 text-slate-600 hover:border-slate-300"
                        }`}
                      >
                        {/* The position is shown because it is the meaning. A
                            phase picker that looked like a set of checkboxes
                            would hide that [GATHER, TRIAGE] is a different
                            agent from [TRIAGE, GATHER]. */}
                        {at >= 0 && (
                          <span className="mr-1 font-mono text-[10px] opacity-60">
                            {at + 1}
                          </span>
                        )}
                        {phase.value}
                      </button>
                    );
                  })}
                </div>
                {phases.length > 0 && (
                  <div className="mt-3 rounded-lg border border-slate-200 bg-slate-50 px-3 py-2">
                    <p className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">
                      Runs in this order
                    </p>
                    <p className="mt-1 font-mono text-[11px] leading-relaxed text-slate-700">
                      {phases.join(" → ")}
                      {!phases.includes("REPORT") && (
                        // Appended by the runtime, so it is shown here rather
                        // than left as a surprise on the saved agent.
                        <span className="text-amber-700"> → REPORT</span>
                      )}
                    </p>
                    {!phases.includes("REPORT") && (
                      <p className="mt-1 text-[10px] leading-relaxed text-amber-700">
                        REPORT is added automatically — without it a run reaches
                        the end of its graph with nothing to hand the operator.
                      </p>
                    )}
                    {/*
                      The cost of picking phases, stated where the choice is
                      made. A phased agent requires the model to return a typed
                      object at each phase; one that answers in prose instead
                      fails the run outright, minutes and tokens after the
                      click, with an error naming a class nobody recognises
                      (StructuredOutputError / HypothesisOut).

                      A single-loop agent has no such contract and runs on
                      anything that can call a tool. That is a real trade and
                      the author is the only person positioned to make it, so
                      it belongs here rather than in a runbook.
                    */}
                    <p className="mt-2 border-t border-slate-200 pt-2 text-[10px] leading-relaxed text-slate-600">
                      <span className="font-semibold text-slate-700">
                        Needs a capable model.
                      </span>{" "}
                      Each phase requires the model to return a structured
                      result, not prose. Smaller models often cannot, and the
                      run fails partway through rather than at the click. Leave
                      phases empty for a single loop, which runs on any model
                      that can call a tool.
                    </p>
                  </div>
                )}
              </>
            )}
          </Card>
        </div>

        {/* ── Right: the two things that need the room — the persona and the
            allow-list.

            Neither column stretches a card to reach the other any more. With
            Guardrails moved out to full width below, the left is three cards
            and the right is two tall ones, which land close enough that there
            is nothing left to cover. ── */}
        <div className="flex flex-col gap-6">
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

          {/* Deliberately NOT flex-1. Stretching a list card to close a gap
              only moves the emptiness inside it, where a large blank panel
              reads as something that failed to load — worse than the gap it
              was hiding. A card grows only where the growth is usable, which
              here means the Guardrails textarea and nothing else. */}
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
                {visibleTools.map((w) => {
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
                              Changes state — hidden until the evidence phase
                              ends
                            </option>
                          </select>
                        </div>
                      )}

                      {picked && schema?.subject_source && (
                        <div className="mt-2.5 pl-7">
                          <div className="flex items-center justify-between">
                            <span className="text-[11px] font-medium text-slate-600">
                              What this tool enumerates
                            </span>
                            <button
                              type="button"
                              onClick={() => addSubject(w.ref)}
                              className="text-[11px] font-medium text-violet-600 hover:underline"
                            >
                              + Declare
                            </button>
                          </div>

                          {(picked.subjects || []).length === 0 ? (
                            // Said plainly rather than left blank. "No
                            // declaration" is a real and sometimes correct
                            // answer — a log query enumerates nothing that
                            // persists — but it has a consequence the author
                            // should be choosing knowingly.
                            <p className="mt-1 text-[11px] leading-relaxed text-slate-500">
                              Nothing declared, so this tool contributes no
                              coverage and findings from it can never be closed
                              automatically. Correct for a tool that reads
                              events; wrong for one that lists resources.
                            </p>
                          ) : (
                            <div className="mt-1.5 space-y-2">
                              {picked.subjects.map((sub, index) => (
                                <div
                                  key={index}
                                  className="rounded-lg border border-slate-200 bg-slate-50 p-2.5"
                                >
                                  <div className="flex gap-1.5">
                                    <select
                                      aria-label={`Subject kind ${index + 1} for ${w.title}`}
                                      value={sub.subject_kind}
                                      onChange={(e) =>
                                        setSubjectField(
                                          w.ref,
                                          index,
                                          "subject_kind",
                                          e.target.value,
                                        )
                                      }
                                      className="min-w-0 flex-1 rounded-md border border-slate-200 bg-white px-2 py-1 text-[11px] text-slate-700 outline-none focus:border-violet-400"
                                    >
                                      <option value="">Kind…</option>
                                      {(
                                        schema.subject_source.fields.find(
                                          (f) => f.name === "subject_kind",
                                        )?.options || []
                                      ).map((kind) => (
                                        <option key={kind} value={kind}>
                                          {kind}
                                        </option>
                                      ))}
                                    </select>
                                    <button
                                      type="button"
                                      aria-label={`Remove declaration ${index + 1} for ${w.title}`}
                                      onClick={() =>
                                        removeSubject(w.ref, index)
                                      }
                                      className="shrink-0 rounded-md px-1.5 text-[11px] text-slate-400 hover:text-rose-600"
                                    >
                                      ×
                                    </button>
                                  </div>
                                  <input
                                    aria-label={`List path ${index + 1} for ${w.title}`}
                                    value={sub.items}
                                    onChange={(e) =>
                                      setSubjectField(
                                        w.ref,
                                        index,
                                        "items",
                                        e.target.value,
                                      )
                                    }
                                    placeholder="unattached_volumes — path to the list"
                                    className="mt-1.5 w-full rounded-md border border-slate-200 bg-white px-2 py-1 font-mono text-[11px] outline-none focus:border-violet-400"
                                  />
                                  <input
                                    aria-label={`Id template ${index + 1} for ${w.title}`}
                                    value={sub.id_template}
                                    onChange={(e) =>
                                      setSubjectField(
                                        w.ref,
                                        index,
                                        "id_template",
                                        e.target.value,
                                      )
                                    }
                                    placeholder="{region}/{volume_id}"
                                    className="mt-1 w-full rounded-md border border-slate-200 bg-white px-2 py-1 font-mono text-[11px] outline-none focus:border-violet-400"
                                  />
                                  {/* The one field worth explaining inline: it
                                      is the field authors most often get wrong,
                                      and getting it wrong is silent. */}
                                  <p className="mt-1 text-[10px] leading-relaxed text-slate-500">
                                    A template, not a field name — a volume id
                                    is region-scoped and an IAM user is
                                    account-scoped, so a bare id merges two
                                    different resources into one subject.
                                  </p>
                                </div>
                              ))}
                            </div>
                          )}
                        </div>
                      )}
                    </div>
                  );
                })}

                {toolPages > 1 && (
                  <div className="flex items-center justify-between border-t border-slate-200 pt-2.5">
                    {/* The RANGE, not just the page number. A picker showing
                        "page 2 of 4" over four rows leaves you counting; the
                        count is also how you notice a tick you made on another
                        page is still held — the selected chip above never
                        moves. */}
                    <span className="text-[11px] text-slate-500">
                      {(toolPage - 1) * TOOLS_PER_PAGE + 1}–
                      {Math.min(toolPage * TOOLS_PER_PAGE, usable.length)} of{" "}
                      {usable.length}
                    </span>
                    <div className="flex items-center gap-1.5">
                      <button
                        type="button"
                        onClick={() => setToolPage(toolPage - 1)}
                        disabled={toolPage === 1}
                        className="rounded-lg border border-slate-200 px-2.5 py-1 text-[11px] font-medium text-slate-600 transition hover:border-slate-300 disabled:cursor-not-allowed disabled:opacity-40"
                      >
                        Previous
                      </button>
                      <span className="px-1 text-[11px] text-slate-500">
                        {toolPage} / {toolPages}
                      </span>
                      <button
                        type="button"
                        onClick={() => setToolPage(toolPage + 1)}
                        disabled={toolPage === toolPages}
                        className="rounded-lg border border-slate-200 px-2.5 py-1 text-[11px] font-medium text-slate-600 transition hover:border-slate-300 disabled:cursor-not-allowed disabled:opacity-40"
                      >
                        Next
                      </button>
                    </div>
                  </div>
                )}
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
                  {unusable.map((w) => w.title).join(", ")} — published without
                  a stable <code className="font-mono">ref</code>, so a rollout
                  cannot match it to the customer&rsquo;s copy. Re-publish
                  through{" "}
                  <code className="font-mono">agents/_schema/publish.py</code>{" "}
                  to give it one.
                </p>
              </div>
            )}
          </Card>
        </div>
      </div>

      {/* Full width, below both columns, and that is the point.
          
          Guardrails are the only thing on this page a CUSTOMER ever reads —
          the persona is sealed and the allow-list is machinery — so the one
          field they see should not be the narrowest box on the screen. Given
          the whole width it also stops wrapping every promise onto three
          lines, which is what made two guardrails look like six. */}
      <Card className="mt-6 p-6">
        <div className="mb-1 flex items-center justify-between gap-2">
          <h3 className="text-sm font-semibold text-slate-900">Guardrails</h3>
          {/* Counted, like the tools and the phases beside it. These are
              the only field on this page a CUSTOMER reads, so "how many
              have I written" is worth showing rather than making somebody
              count lines in a textarea. */}
          <Chip>
            {guardrailLines.length === 1
              ? "1 promise"
              : `${guardrailLines.length} promises`}
          </Chip>
        </div>
        <p className="mb-3 text-[11px] leading-relaxed text-slate-500">
          One per line. These are shown to the customer — they are what the
          agent promises not to do, and unlike the instructions they are not
          withheld.
        </p>
        <textarea
          // Labelled, because the heading above it is an <h3> and a heading
          // is not a label — a screen reader reached this field and
          // announced "edit text, blank".
          aria-label="Guardrails"
          value={guardrails}
          onChange={(e) => setGuardrails(e.target.value)}
          placeholder={
            "Report-only by default: nothing is changed without an approval.\nScope is one region per run."
          }
          // Sized to invite a real list rather than the two lines a five-row
          // box suggests. Still resizable, because how many promises an agent
          // needs is not something this form can know.
          className={`${inputCls} min-h-[13rem] resize-y leading-relaxed`}
        />
      </Card>

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
