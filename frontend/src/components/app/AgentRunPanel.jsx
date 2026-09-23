import React, { useCallback, useEffect, useRef, useState } from "react";
import Icon from "../Icon";
import ModalPortal from "./ModalPortal";
import ReportText from "./ReportText";
import { agentRuns } from "../../lib/api";
import { fmtDate } from "../../lib/format";

/**
 * Ask an agent to do something, and watch what it does.
 *
 * A run is asynchronous and can take minutes, so this polls. It polls only
 * while the open run is unfinished and stops the moment it reaches a terminal
 * state — a panel left open on a finished run costs nothing.
 *
 * There is no Approve button here, deliberately. When an agent's tool needs a
 * human the run parks and the request appears in the normal Approvals inbox;
 * a second place to approve things would be a second place to check on the day
 * something ran that should not have. The panel says where to go and the run
 * resumes on its own once someone decides.
 */

/** How often to re-read a live run. Fast enough to feel live, slow enough not to hammer. */
const POLL_MS = 2500;

// How many polls may fail in a row before the panel admits it is not live.
const STALL_AFTER = 3;

const STATUS_STYLES = {
  PENDING: "border-slate-300 bg-slate-100 text-slate-600",
  RUNNING: "border-blue-400/30 bg-blue-400/10 text-blue-700",
  AWAITING_APPROVAL: "border-amber-400/40 bg-amber-400/10 text-amber-700",
  SUCCEEDED: "border-emerald-400/30 bg-emerald-400/10 text-emerald-700",
  FAILED: "border-red-400/30 bg-red-400/10 text-red-600",
  CANCELLED: "border-slate-300 bg-slate-100 text-slate-500",
};

const STATUS_LABELS = {
  PENDING: "Queued",
  RUNNING: "Running",
  AWAITING_APPROVAL: "Waiting for approval",
  SUCCEEDED: "Done",
  FAILED: "Failed",
  CANCELLED: "Cancelled",
};

const RunStatus = ({ status }) => (
  <span
    className={`inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-[10px] font-medium ${
      STATUS_STYLES[status] || STATUS_STYLES.PENDING
    }`}
  >
    {status === "RUNNING" && (
      <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-blue-600" />
    )}
    {STATUS_LABELS[status] || status}
  </span>
);

const STEP_ICONS = {
  MODEL_CALL: "sparkles",
  TOOL_CALL: "play",
  TOOL_RESULT: "list",
  APPROVAL_REQUESTED: "shield",
  APPROVAL_GRANTED: "check",
};

const STEP_LABELS = {
  MODEL_CALL: "Thought",
  TOOL_CALL: "Ran",
  TOOL_RESULT: "Result",
  APPROVAL_REQUESTED: "Approval requested",
  APPROVAL_GRANTED: "Approval decided",
};

/**
 * A MODEL_CALL's response is the raw provider-neutral summary JSON. Showing
 * that verbatim would be unreadable; showing nothing would hide the reasoning.
 * So the model's own words are pulled out and the rest is dropped.
 */
function readModelStep(step, nameFor = (id) => id) {
  try {
    const parsed = JSON.parse(step.response || "{}");
    const calls = (parsed.toolCalls || [])
      .map((c) => c.name)
      .filter(Boolean)
      .map(nameFor);
    return {
      text: parsed.text || "",
      calls,
      stopReason: parsed.stopReason || "",
    };
  } catch {
    return { text: step.response || "", calls: [], stopReason: "" };
  }
}

/**
 * Maps a tool's internal identifier to the automation's actual name.
 *
 * <p>The model is offered tools as `workflow_9231` / `job_14` — an id is the
 * only thing guaranteed unique and stable enough to bind a call to a target.
 * That is an implementation detail of how the agent is wired, and printing it
 * to the customer both means nothing to them and advertises the numbering of
 * their automations. Every TOOL_CALL step already carries the real name
 * alongside its type and target id, so the run itself supplies the dictionary.
 */
export function toolNamesById(steps) {
  const names = {};
  for (const step of steps || []) {
    if (step.toolType && step.toolTargetId && step.toolName) {
      names[`${step.toolType.toLowerCase()}_${step.toolTargetId}`] = step.toolName;
    }
  }
  return (id) => names[id] || id;
}

const StepRow = ({ step, nameFor }) => {
  const [open, setOpen] = useState(false);
  const model = step.kind === "MODEL_CALL" ? readModelStep(step, nameFor) : null;
  const body = model ? model.text : step.response;
  const hasBody = !!(body && body.trim());

  return (
    <li className="border-t border-slate-200 first:border-t-0">
      <div className="flex items-start gap-3 py-3">
        <span
          className={`mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-lg border text-[10px] ${
            step.isError
              ? "border-red-400/30 bg-red-400/10 text-red-600"
              : "border-slate-200 bg-slate-50 text-slate-500"
          }`}
        >
          <Icon name={STEP_ICONS[step.kind] || "list"} size={12} />
        </span>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
            <span className="text-xs font-semibold text-slate-900">
              {STEP_LABELS[step.kind] || step.kind}
            </span>
            {step.toolName && (
              <span className="truncate text-xs text-slate-600">{step.toolName}</span>
            )}
            {typeof step.durationMs === "number" && (
              <span className="text-[10px] tabular-nums text-slate-400">
                {step.durationMs < 1000
                  ? `${step.durationMs}ms`
                  : `${(step.durationMs / 1000).toFixed(1)}s`}
              </span>
            )}
          </div>

          {model && model.calls.length > 0 && (
            <p className="mt-1 text-xs text-slate-500">
              asked for {model.calls.join(", ")}
            </p>
          )}

          {hasBody && (
            <>
              <p
                className={`mt-1 whitespace-pre-wrap break-words text-xs leading-relaxed ${
                  step.isError ? "text-red-600" : "text-slate-600"
                } ${open ? "" : "line-clamp-3"}`}
              >
                {body}
              </p>
              {body.length > 220 && (
                <button
                  onClick={() => setOpen((v) => !v)}
                  className="mt-1 text-[10px] font-semibold uppercase tracking-wider text-blue-600 hover:underline"
                >
                  {open ? "Show less" : "Show more"}
                </button>
              )}
            </>
          )}
        </div>
      </div>
    </li>
  );
};

export default function AgentRunPanel({ agent, onClose, canRun, pushToast }) {
  const [input, setInput] = useState("");
  const [starting, setStarting] = useState(false);
  const [runs, setRuns] = useState([]);
  const [openRun, setOpenRun] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);

  // Held in a ref so the poll effect can read the current run without
  // re-subscribing every time a fresh poll replaces the run object.
  const live = useRef(null);
  live.current = openRun && !openRun.finished ? openRun.id : null;

  // Consecutive failed polls. A ref rather than state: it changes on a timer
  // and must not itself cause a render.
  const misses = useRef(0);
  const [stalled, setStalled] = useState(false);

  const loadHistory = useCallback(async () => {
    try {
      const rows = await agentRuns.listForAgent(agent.id);
      setRuns(rows);
      return rows;
    } catch (e) {
      setError(e.message || "Could not load this agent's runs");
      return [];
    } finally {
      setLoading(false);
    }
  }, [agent.id]);

  useEffect(() => {
    loadHistory();
  }, [loadHistory]);

  // One interval for the panel's lifetime. It does nothing unless there is an
  // open, unfinished run — cheaper and simpler than tearing an interval down
  // and building it back up on every status change.
  useEffect(() => {
    const pull = async () => {
      const id = live.current;
      if (!id) return;
      try {
        const fresh = await agentRuns.get(id);
        // Only if it is still the run on screen: the user may have gone back
        // to the list, or opened another one, while this request was in
        // flight, and overwriting that would yank the panel out from under them.
        setOpenRun((current) => (current && current.id === fresh.id ? fresh : current));
        setRuns((rows) => rows.map((r) => (r.id === fresh.id ? { ...r, ...fresh } : r)));
        misses.current = 0;
        setStalled(false);
      } catch {
        // One failed poll is noise — a dropped connection, a token being
        // refreshed — and the next tick usually succeeds. Several in a row is
        // different: the panel is then showing a snapshot while implying it is
        // live, and the customer's only way to find out is to reload the page.
        // Which is exactly what they should not have to do.
        misses.current += 1;
        if (misses.current >= STALL_AFTER) setStalled(true);
      }
    };

    // Once straight away. Waiting a full interval before the first pull is how
    // a run that has already produced a step looks frozen on open.
    pull();
    const tick = setInterval(pull, POLL_MS);
    return () => clearInterval(tick);
  }, []);

  const openDetail = async (run) => {
    setOpenRun(run);
    try {
      const full = await agentRuns.get(run.id);
      setOpenRun(full);
    } catch (e) {
      setError(e.message || "Could not open that run");
    }
  };

  const start = async () => {
    const text = input.trim();
    if (!text) return;
    setStarting(true);
    setError(null);
    try {
      const run = await agentRuns.start(agent.id, text);
      setInput("");
      setRuns((rows) => [run, ...rows]);
      setOpenRun(run);
      pushToast?.(`${agent.name} is working on it`, "blue");
    } catch (e) {
      setError(e.message || "Could not start the run");
      pushToast?.(e.message || "Could not start the run", "red");
    } finally {
      setStarting(false);
    }
  };

  const cancel = async (run) => {
    try {
      const updated = await agentRuns.cancel(run.id);
      setOpenRun((current) => (current?.id === run.id ? { ...current, ...updated } : current));
      setRuns((rows) => rows.map((r) => (r.id === run.id ? { ...r, ...updated } : r)));
      pushToast?.("Run stopped", "amber");
    } catch (e) {
      pushToast?.(e.message || "Could not stop the run", "red");
    }
  };

  return (
    <ModalPortal layerClass="z-[90] items-center p-4" onClose={onClose}>
      {/* Centred, and sized to its CONTENT rather than to the window.
          As a full-height right-hand drawer this was half the viewport wide
          with the run history stranded against two thirds of a column of
          whitespace — the emptier the agent, the worse it looked, which is
          exactly backwards. max-h caps it on a long history; the flex column
          keeps the header still and scrolls only the middle. */}
      <div className="rw-pop relative z-10 flex max-h-[85vh] w-full max-w-3xl flex-col overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-2xl">
        <header className="flex items-start justify-between gap-4 border-b border-slate-200 px-6 py-5">
          <div className="min-w-0">
            <h2 className="truncate text-base font-semibold text-slate-900">{agent.name}</h2>
            <p className="mt-0.5 text-xs text-slate-500">
              {agent.model ? `${agent.model} · ` : ""}
              {agent.toolCount} tool{agent.toolCount === 1 ? "" : "s"} granted
            </p>
          </div>
          <button
            onClick={onClose}
            aria-label="Close"
            className="rounded-lg border border-slate-200 p-1.5 text-slate-500 transition hover:border-blue-600 hover:text-blue-600"
          >
            <Icon name="x" size={16} />
          </button>
        </header>

        <div className="min-h-0 flex-1 overflow-y-auto px-6 py-5">
          {!agent.enabled && (
            <p className="mb-4 rounded-lg border border-amber-400/40 bg-amber-400/10 px-3 py-2 text-xs text-amber-700">
              This agent is paused. Resume it before asking it to do anything.
            </p>
          )}

          {canRun && agent.enabled && (
            <div className="mb-6">
              <label
                htmlFor="agent-run-input"
                className="text-[10px] font-semibold uppercase tracking-[0.18em] text-slate-500"
              >
                What should it do?
              </label>
              <textarea
                id="agent-run-input"
                rows={3}
                value={input}
                onChange={(e) => setInput(e.target.value)}
                placeholder="e.g. Check disk space on the app servers and clear temp files if any are over 85% full."
                className="mt-1.5 w-full resize-y rounded-xl border border-slate-200 bg-slate-50 px-3.5 py-2.5 text-sm text-slate-900 outline-none transition placeholder:text-slate-400 focus:border-blue-500 focus:bg-white"
              />
              <div className="mt-2 flex items-center justify-between gap-3">
                <p className="text-[11px] leading-relaxed text-slate-500">
                  It can only use the {agent.toolCount} automation
                  {agent.toolCount === 1 ? "" : "s"} it was granted, and anything needing
                  approval will wait for an admin.
                </p>
                <button
                  onClick={start}
                  disabled={starting || !input.trim()}
                  className="inline-flex shrink-0 items-center gap-1.5 rounded-lg bg-blue-600 px-3.5 py-2 text-sm font-semibold text-white shadow-lg shadow-blue-600/40 transition hover:bg-blue-700 disabled:cursor-not-allowed disabled:bg-slate-200 disabled:text-slate-400 disabled:shadow-none"
                >
                  <Icon name={starting ? "refresh" : "play"} size={16} />
                  {starting ? "Starting…" : "Run"}
                </button>
              </div>
            </div>
          )}

          {error && (
            <p className="mb-4 rounded-lg border border-red-400/30 bg-red-400/10 px-3 py-2 text-xs text-red-600">
              {error}
            </p>
          )}

          {openRun ? (
            <section>
              <button
                onClick={() => setOpenRun(null)}
                className="mb-3 inline-flex items-center gap-1 text-xs font-semibold text-blue-600 hover:underline"
              >
                <Icon name="chevron" size={12} className="rotate-180" />
                All runs
              </button>

              <div className="rounded-xl border border-slate-200 bg-slate-50 p-4">
                <div className="flex items-start justify-between gap-3">
                  <p className="min-w-0 flex-1 whitespace-pre-wrap break-words text-sm text-slate-900">
                    {openRun.input}
                  </p>
                  <RunStatus status={openRun.status} />
                </div>
                <p className="mt-2 text-[11px] tabular-nums text-slate-500">
                  {/* createdAt is a database default, so it is absent on the
                      row POST returns and arrives on the first poll. */}
                  {openRun.createdAt ? fmtDate(openRun.createdAt) : "just now"} · step{" "}
                  {openRun.stepCount} of {openRun.maxSteps}
                  {openRun.promptTokens + openRun.completionTokens > 0 &&
                    ` · ${(openRun.promptTokens + openRun.completionTokens).toLocaleString()} tokens`}
                </p>
              </div>

              {openRun.waiting && (
                <p className="mt-3 rounded-lg border border-amber-400/40 bg-amber-400/10 px-3 py-2 text-xs leading-relaxed text-amber-700">
                  This run is waiting for an admin to decide approval
                  {openRun.approvalReference ? ` #${openRun.approvalReference}` : ""}. Decide it
                  on the Approvals page — the run continues on its own afterwards.
                </p>
              )}

              {openRun.output && (
                <div className="mt-4 rounded-xl border border-slate-200 p-4">
                  <p className="text-[10px] font-semibold uppercase tracking-[0.18em] text-slate-500">
                    Answer
                  </p>
                  {/* The model writes markdown. Printed verbatim, its answer
                      arrived as `**No alarms fired**` over `---` rules — the
                      report reading as a transcript of its own formatting
                      rather than as the thing it was asked for. */}
                  <ReportText source={openRun.output} className="mt-2" />
                </div>
              )}

              {openRun.error && (
                <p className="mt-4 rounded-lg border border-red-400/30 bg-red-400/10 px-3 py-2 text-xs leading-relaxed text-red-600">
                  {openRun.error}
                </p>
              )}

              <p className="mt-5 text-[10px] font-semibold uppercase tracking-[0.18em] text-slate-500">
                What it did
              </p>
              {stalled && !openRun.finished && (
                <p className="mt-2 rounded-lg border border-amber-400/40 bg-amber-400/10 px-3 py-2 text-[11px] text-amber-700">
                  Not updating right now — still trying. The run itself is
                  unaffected; this panel will catch up on its own.
                </p>
              )}

              {openRun.steps === null ? (
                <div className="mt-2 h-20 animate-pulse rounded-xl bg-slate-100" />
              ) : openRun.steps.length === 0 ? (
                <p className="mt-2 text-xs text-slate-500">
                  {openRun.finished
                    ? "It finished without recording a step."
                    : // Named as waiting rather than empty. The first model
                      // call can take twenty seconds, and "Nothing yet" on a
                      // motionless panel reads as broken rather than busy.
                      "Working on the first step — this can take a few seconds."}
                </p>
              ) : (
                (() => {
                  // Built once per render, not once per row: every row shares
                  // the same dictionary and the list is re-rendered on poll.
                  const nameFor = toolNamesById(openRun.steps);
                  return (
                    <ul className="mt-1">
                      {openRun.steps.map((s) => (
                        <StepRow key={s.id} step={s} nameFor={nameFor} />
                      ))}
                    </ul>
                  );
                })()
              )}

              {canRun && !openRun.finished && (
                <button
                  onClick={() => cancel(openRun)}
                  className="mt-5 rounded-lg border border-slate-200 px-3 py-1.5 text-xs font-semibold text-slate-700 transition hover:border-red-500 hover:text-red-600"
                >
                  Stop this run
                </button>
              )}
            </section>
          ) : (
            <section>
              <p className="text-[10px] font-semibold uppercase tracking-[0.18em] text-slate-500">
                Recent runs
              </p>
              {loading ? (
                <div className="mt-2 space-y-2">
                  {[0, 1].map((i) => (
                    <div key={i} className="h-14 animate-pulse rounded-xl bg-slate-100" />
                  ))}
                </div>
              ) : runs.length === 0 ? (
                <div className="mt-2 flex flex-col items-center gap-2 rounded-xl border border-dashed border-slate-200 px-4 py-8 text-center">
                  <span className="flex h-9 w-9 items-center justify-center rounded-full bg-slate-100 text-slate-400">
                    <Icon name="chat" size={16} />
                  </span>
                  <p className="text-sm text-slate-500">This agent has not been run yet.</p>
                  <p className="max-w-xs text-xs text-slate-400">
                    Describe what you want it to look into above, and its runs will collect
                    here.
                  </p>
                </div>
              ) : (
                <ul className="mt-2 space-y-2">
                  {runs.map((r) => (
                    <li key={r.id}>
                      <button
                        onClick={() => openDetail(r)}
                        className="w-full rounded-xl border border-slate-200 px-3.5 py-3 text-left transition hover:border-blue-500"
                      >
                        <div className="flex items-start justify-between gap-3">
                          <p className="min-w-0 flex-1 truncate text-sm text-slate-900">
                            {r.input}
                          </p>
                          <RunStatus status={r.status} />
                        </div>
                        <p className="mt-1 text-[11px] tabular-nums text-slate-500">
                          {r.createdAt ? fmtDate(r.createdAt) : "just now"}
                          {r.createdBy ? ` · ${r.createdBy}` : ""}
                        </p>
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </section>
          )}
        </div>
      </div>
    </ModalPortal>
  );
}
