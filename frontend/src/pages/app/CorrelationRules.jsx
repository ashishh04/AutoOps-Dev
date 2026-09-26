import React, { useState, useEffect, useCallback } from "react";
import { createPortal } from "react-dom";
import { useNavigate } from "react-router-dom";
import {
  PageHeader,
  Card,
  SmallButton,
  Skeleton,
  ConfirmModal,
} from "../../components/app/appui";
import Icon from "../../components/Icon";
import ProjectScopePicker from "../../components/app/ProjectScopePicker";
import { useStore } from "../../store/store";
import { api } from "../../lib/api";
import { useAlertScope } from "../../lib/alertScope";
import {
  listCorrelationRules,
  getCorrelationVocabulary,
  createCorrelationRule,
  deleteCorrelationRule,
} from "../../lib/incidents";

/**
 * What decides that several alerts are one incident.
 *
 * <h2>Why this screen exists at all</h2>
 * Nothing correlates without a rule. An alert plane with no rules produces no
 * incidents whatsoever, however many alerts arrive — the feed fills up and the
 * Incidents page stays empty forever. Before this screen that was an operator
 * task, which meant every customer who wanted their alerts grouped asked their
 * provider, waited, and asked again when their estate changed. Most would not
 * ask; they would read the raw feed and conclude the incident half of the
 * product was broken.
 *
 * <h2>Why there is no expression box</h2>
 * The engine matches on an expression evaluated against every alert that
 * arrives, and it has no tenant concept of its own. A customer typing one would
 * be authoring the boundary that decides which alerts they see. So the form
 * offers VALUES from a server-published vocabulary, and the server writes the
 * matcher with their own workspace ANDed in.
 *
 * The result is still shown, read-only, on every rule. "Why are these grouped?"
 * is the first question anyone asks of a correlated view, and a rule nobody can
 * inspect is a grouping nobody trusts.
 */
export default function CorrelationRules() {
  const scope = useAlertScope();
  const pid = scope.projectId;
  const navigate = useNavigate();
  const { pushToast } = useStore();

  const [rules, setRules] = useState(null);
  const [vocab, setVocab] = useState(null);
  const [error, setError] = useState(null);
  const [adding, setAdding] = useState(false);
  const [removing, setRemoving] = useState(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => {
    setError(null);
    listCorrelationRules(pid)
      .then(setRules)
      .catch((e) => {
        // Honest failure. An empty list on error would tell a customer they
        // have no rules, which is the same thing this page tells them when
        // correlation genuinely is not configured — and they would go and
        // create a duplicate.
        setRules(null);
        setError(e.message || "Could not load correlation rules");
      });
  }, [pid]);

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    getCorrelationVocabulary()
      .then(setVocab)
      .catch(() => setVocab(null));
  }, []);

  const remove = async () => {
    if (!removing) return;
    setBusy(true);
    try {
      await deleteCorrelationRule(removing.id, removing.projectId);
      pushToast("Correlation rule deleted", "emerald");
      load();
    } catch (e) {
      pushToast(e.message || "Could not delete that rule", "red");
    } finally {
      setBusy(false);
      setRemoving(null);
    }
  };

  return (
    <div className="animate-fade-up">
      <PageHeader
        title="Correlation Rules"
        subtitle="What makes several alerts one incident. Without a rule, alerts stay in the feed and no incidents are created."
        actions={
          <div className="flex items-center gap-2">
            <SmallButton
              icon="pulse"
              onClick={() => navigate(scope.link("/incidents"))}
            >
              Incidents
            </SmallButton>
            <SmallButton
              icon="plus"
              variant="primary"
              onClick={() => setAdding(true)}
            >
              New rule
            </SmallButton>
          </div>
        }
      />

      <Card className="mb-4 flex flex-wrap items-center gap-3 p-4">
        <span className="text-xs font-medium uppercase tracking-wider text-slate-500">
          Showing
        </span>
        <span className="text-sm text-slate-700">
          {pid ? "one project" : "the whole workspace"}
        </span>
        <ProjectScopePicker value={pid} onChange={scope.setProject} />
      </Card>

      {error && (
        <Card className="mb-5 flex items-center justify-between gap-4 p-5">
          <span
            className="flex items-center gap-3 text-sm text-slate-600"
            role="alert"
          >
            <Icon name="warning" className="h-5 w-5 text-amber-500" />
            {error}
          </span>
          <SmallButton onClick={load}>Try again</SmallButton>
        </Card>
      )}

      {rules === null && !error && (
        <div className="space-y-3">
          {Array.from({ length: 3 }).map((_, i) => (
            <Card key={i} className="p-5">
              <Skeleton className="h-4 w-1/3" />
              <Skeleton className="mt-3 h-3 w-2/3" />
            </Card>
          ))}
        </div>
      )}

      {rules !== null && rules.length === 0 && (
        /*
         * The most important empty state in the alert plane, and the one it
         * would be easiest to get wrong. "No rules yet" reads as a tidy,
         * finished screen. It is not: with no rule, the Incidents page can
         * never show anything, and a customer watching an empty Incidents page
         * concludes the product does not work rather than that it is not
         * configured.
         */
        <Card className="p-8 text-center">
          <Icon name="pulse" className="mx-auto h-8 w-8 text-slate-300" />
          <p className="mt-3 text-sm font-medium text-slate-900">
            No correlation rules yet
          </p>
          <p className="mx-auto mt-1 max-w-lg text-sm leading-relaxed text-slate-500">
            Until you add one, alerts arrive in the feed and nothing is grouped
            — the Incidents page will stay empty. A rule says which alerts to
            watch and what makes two of them the same problem.
          </p>
          <div className="mt-4">
            <SmallButton
              icon="plus"
              variant="primary"
              onClick={() => setAdding(true)}
            >
              Create the first rule
            </SmallButton>
          </div>
        </Card>
      )}

      {rules !== null && rules.length > 0 && (
        <div className="space-y-3">
          {rules.map((r) => (
            <Card key={r.id} className="p-5">
              <div className="flex items-start justify-between gap-4">
                <div className="min-w-0">
                  <div className="text-sm font-semibold text-slate-900">
                    {r.label}
                  </div>
                  <div className="mt-1 text-xs text-slate-500">
                    Groups by{" "}
                    <span className="font-medium text-slate-700">
                      {(r.groupBy || []).join(" + ") || "—"}
                    </span>{" "}
                    within {Math.round((r.windowSeconds || 0) / 60)} min
                  </div>
                </div>
                <button
                  onClick={() => setRemoving(r)}
                  aria-label={`Delete ${r.label}`}
                  className="rounded-lg p-1.5 text-slate-400 transition hover:bg-red-50 hover:text-red-600"
                >
                  <Icon name="trash" className="h-4 w-4" />
                </button>
              </div>
              {r.expression && (
                <pre className="mt-3 overflow-x-auto rounded-lg border border-slate-200 bg-slate-50 p-2.5 font-mono text-[11px] leading-relaxed text-slate-600">
                  {r.expression}
                </pre>
              )}
            </Card>
          ))}
        </div>
      )}

      {adding && (
        <RuleModal
          vocab={vocab}
          projectId={pid}
          onClose={() => setAdding(false)}
          onSaved={() => {
            setAdding(false);
            pushToast("Correlation rule created", "emerald");
            load();
          }}
        />
      )}

      {removing && (
        <ConfirmModal
          open
          title={`Delete ${removing.label}?`}
          message="Incidents it already created are kept. Alerts matching it from now on will not be grouped."
          confirmLabel={busy ? "Deleting…" : "Delete"}
          onConfirm={remove}
          onClose={() => setRemoving(null)}
        />
      )}
    </div>
  );
}

const SELECT =
  "w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-700 outline-none focus:border-indigo-400";

/** Builds a rule from values. Never from an expression — see the page docs. */
function RuleModal({ vocab, projectId, onClose, onSaved }) {
  const fields = vocab?.fields || [
    { value: "severity", label: "Severity" },
    { value: "service", label: "Service" },
    { value: "source", label: "Source" },
  ];
  const severities = vocab?.severities || [
    "critical",
    "high",
    "warning",
    "info",
    "low",
  ];

  const [label, setLabel] = useState("");
  const [conditions, setConditions] = useState([
    { field: "severity", values: "critical" },
  ]);
  const [groupBy, setGroupBy] = useState(["service"]);
  const [minutes, setMinutes] = useState(vocab?.defaultWindowMinutes || 10);
  const [project, setProject] = useState(projectId || "");
  const [projects, setProjects] = useState([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  useEffect(() => {
    api
      .listProjects()
      .then((rows) => setProjects(Array.isArray(rows) ? rows : []))
      .catch(() => setProjects([]));
  }, []);

  // A rule is named into one project, the same way a monitoring source is —
  // the computed name is the only ownership record. One project means no
  // decision to make.
  const target =
    projectId ||
    project ||
    (projects.length === 1 ? String(projects[0].id) : "");

  const toggleGroup = (value) =>
    setGroupBy((prev) =>
      prev.includes(value) ? prev.filter((v) => v !== value) : [...prev, value],
    );

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await createCorrelationRule(target, {
        label,
        // Split here rather than server-side: the server takes a list, and a
        // comma-separated string arriving there would make it guess at a
        // delimiter for values that may legitimately contain one.
        conditions: conditions
          .map((c) => ({
            field: c.field,
            values: String(c.values || "")
              .split(",")
              .map((v) => v.trim())
              .filter(Boolean),
          }))
          .filter((c) => c.values.length > 0),
        groupBy,
        windowMinutes: Number(minutes) || 10,
      });
      onSaved();
    } catch (err) {
      setError(err.message || "Could not create this rule");
    } finally {
      setBusy(false);
    }
  };

  // Portalled to document.body like every other overlay here: a bare
  // `fixed inset-0` does not cover the viewport in this app, because an
  // ancestor carries a transform and becomes the containing block.
  return createPortal(
    <div className="fixed inset-0 z-[95] flex items-center justify-center p-4">
      <div
        className="absolute inset-0 bg-slate-900/25 backdrop-blur-md"
        onClick={onClose}
      />
      <form
        onSubmit={submit}
        className="rw-pop relative flex max-h-[90vh] w-full max-w-lg flex-col overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-2xl"
      >
        <div className="border-b border-slate-100 px-5 py-4">
          <h2 className="text-base font-semibold text-slate-900">
            New correlation rule
          </h2>
          <p className="mt-0.5 text-xs text-slate-500">
            Which alerts to watch, and what makes two of them the same incident.
          </p>
        </div>

        <div className="space-y-4 overflow-y-auto px-5 py-4">
          <div>
            <label
              htmlFor="rule-name"
              className="mb-1.5 block text-xs font-semibold text-slate-700"
            >
              Name
            </label>
            <input
              id="rule-name"
              value={label}
              onChange={(e) => setLabel(e.target.value)}
              placeholder="Payments outage"
              className={SELECT}
            />
          </div>

          {projects.length > 1 && !projectId && (
            <div>
              <label
                htmlFor="rule-project"
                className="mb-1.5 block text-xs font-semibold text-slate-700"
              >
                Project
              </label>
              <select
                id="rule-project"
                value={project}
                onChange={(e) => setProject(e.target.value)}
                className={SELECT}
              >
                <option value="">Choose a project…</option>
                {projects.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name}
                  </option>
                ))}
              </select>
            </div>
          )}

          <div>
            <span className="mb-1.5 block text-xs font-semibold text-slate-700">
              Watch alerts where
            </span>
            <p className="mb-2 text-[11px] leading-relaxed text-slate-500">
              Leave this empty to watch every alert in the workspace. Your
              workspace is always applied — a rule can never match another
              customer&rsquo;s alerts.
            </p>
            <div className="space-y-2">
              {conditions.map((c, i) => (
                <div key={i} className="flex items-center gap-2">
                  <select
                    value={c.field}
                    aria-label={`Condition ${i + 1} field`}
                    onChange={(e) =>
                      setConditions((prev) =>
                        prev.map((p, j) =>
                          j === i
                            ? { ...p, field: e.target.value, values: "" }
                            : p,
                        ),
                      )
                    }
                    className={`${SELECT} max-w-[9rem]`}
                  >
                    {fields.map((f) => (
                      <option key={f.value} value={f.value}>
                        {f.label}
                      </option>
                    ))}
                  </select>
                  {c.field === "severity" ? (
                    <select
                      value={c.values}
                      aria-label={`Condition ${i + 1} value`}
                      onChange={(e) =>
                        setConditions((prev) =>
                          prev.map((p, j) =>
                            j === i ? { ...p, values: e.target.value } : p,
                          ),
                        )
                      }
                      className={SELECT}
                    >
                      {severities.map((s) => (
                        <option key={s} value={s}>
                          {s}
                        </option>
                      ))}
                    </select>
                  ) : (
                    <input
                      value={c.values}
                      aria-label={`Condition ${i + 1} value`}
                      onChange={(e) =>
                        setConditions((prev) =>
                          prev.map((p, j) =>
                            j === i ? { ...p, values: e.target.value } : p,
                          ),
                        )
                      }
                      placeholder="checkout-api, payments-api"
                      className={SELECT}
                    />
                  )}
                  <button
                    type="button"
                    aria-label={`Remove condition ${i + 1}`}
                    onClick={() =>
                      setConditions((prev) => prev.filter((_, j) => j !== i))
                    }
                    className="rounded-lg p-1.5 text-slate-400 transition hover:bg-red-50 hover:text-red-600"
                  >
                    <Icon name="trash" className="h-4 w-4" />
                  </button>
                </div>
              ))}
            </div>
            <button
              type="button"
              onClick={() =>
                setConditions((prev) => [
                  ...prev,
                  { field: "service", values: "" },
                ])
              }
              className="mt-2 text-xs font-medium text-violet-600 hover:underline"
            >
              + Add a condition
            </button>
          </div>

          <div>
            <span className="mb-1.5 block text-xs font-semibold text-slate-700">
              Same incident when they share
            </span>
            <div className="flex flex-wrap gap-2">
              {fields.map((f) => (
                <label
                  key={f.value}
                  className="flex cursor-pointer items-center gap-1.5 rounded-lg border border-slate-200 px-2.5 py-1.5 text-xs text-slate-700"
                >
                  <input
                    type="checkbox"
                    checked={groupBy.includes(f.value)}
                    onChange={() => toggleGroup(f.value)}
                    className="h-3.5 w-3.5 rounded border-slate-300"
                  />
                  {f.label}
                </label>
              ))}
            </div>
          </div>

          <div>
            <label
              htmlFor="rule-window"
              className="mb-1.5 block text-xs font-semibold text-slate-700"
            >
              Within (minutes)
            </label>
            <input
              id="rule-window"
              type="number"
              min="1"
              value={minutes}
              onChange={(e) => setMinutes(e.target.value)}
              className={`${SELECT} max-w-[8rem]`}
            />
          </div>

          {error && (
            <p
              className="rounded-lg bg-red-50 px-3 py-2 text-xs font-medium text-red-700"
              role="alert"
            >
              {error}
            </p>
          )}
        </div>

        <div className="flex items-center justify-end gap-2 border-t border-slate-100 px-5 py-3">
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg px-3 py-2 text-sm font-semibold text-slate-500 transition hover:text-slate-800"
          >
            Cancel
          </button>
          <SmallButton
            icon="check"
            variant="primary"
            type="submit"
            disabled={busy || !label.trim() || groupBy.length === 0 || !target}
          >
            {busy ? "Creating…" : "Create rule"}
          </SmallButton>
        </div>
      </form>
    </div>,
    document.body,
  );
}
