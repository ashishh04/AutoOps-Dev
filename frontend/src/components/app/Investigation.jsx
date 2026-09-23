import React, { useState } from "react";
import { Card, SmallButton } from "./appui";
import Icon from "../Icon";
import SetupMarkdown from "./SetupMarkdown";

const fmtDuration = (ms) =>
  ms == null ? null : ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}s`;

/**
 * One thing the engine did, and whether it worked.
 *
 * Collapsed by default because a tool result can be a whole log file, and the
 * point of the list is the shape of the investigation rather than its raw
 * output.
 */
function ToolCall({ call }) {
  const [open, setOpen] = useState(false);
  return (
    <div
      className={`rounded-lg border-l-2 bg-slate-50 ${call.succeeded ? "border-l-slate-300" : "border-l-amber-400"}`}
    >
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className="flex w-full items-start gap-3 p-3 text-left"
      >
        <div className="min-w-0 flex-1">
          <div className="font-mono text-xs font-medium text-slate-800">
            {call.tool || "tool"}
          </div>
          {call.description && (
            <div className="mt-0.5 break-words font-mono text-xs text-slate-500">
              {call.description}
            </div>
          )}
        </div>
        {!call.succeeded && (
          <span className="shrink-0 text-xs font-medium text-amber-600">
            failed
          </span>
        )}
        {call.output && (
          <Icon
            name="chevron"
            className={`h-4 w-4 shrink-0 text-slate-400 transition ${open ? "rotate-90" : ""}`}
          />
        )}
      </button>
      {open && call.output && (
        <pre className="overflow-x-auto border-t border-slate-200 px-3 py-2 text-[11px] leading-relaxed text-slate-600">
          {call.output}
        </pre>
      )}
    </div>
  );
}

/**
 * The investigation: what was concluded, and what it actually looked at.
 *
 * The tool list is not decoration. The reference run in the design this follows
 * could not reach the cluster — every kubectl failed — and still produced a
 * confident-sounding list of "possible root causes". The only way a reader can
 * tell an evidenced finding from a plausible story is by seeing which commands
 * ran and which ones worked. That is why failures are marked rather than hidden,
 * and why the disclaimer sits under the analysis rather than in a tooltip.
 */
export default function Investigation({
  investigation,
  engine,
  enabled,
  busy,
  error,
  onRun,
  onAsk,
}) {
  const [question, setQuestion] = useState("");

  if (!enabled) {
    return (
      <Card className="flex items-start gap-3 p-5 text-sm text-slate-500">
        <Icon name="sparkles" className="mt-0.5 h-4 w-4 shrink-0" />
        <p>
          Investigation is not switched on for this platform. Once it is,
          AutoOps can analyse an incident's evidence and propose a root cause.
        </p>
      </Card>
    );
  }

  if (!investigation) {
    return (
      <Card className="p-6 text-center">
        <Icon name="sparkles" className="mx-auto h-6 w-6 text-indigo-500" />
        <h3 className="mt-3 text-sm font-semibold text-slate-900">
          Investigate this incident
        </h3>
        <p className="mx-auto mt-1 max-w-md text-sm text-slate-500">
          Reads the correlated alerts, runs read-only checks against your
          estate, and proposes a root cause — showing every command it ran.
        </p>
        {error && (
          <div className="mx-auto mt-4 flex max-w-md items-start gap-2 rounded-lg bg-red-50 p-3 text-left text-sm text-red-700 ring-1 ring-inset ring-red-600/15">
            <Icon name="warning" className="mt-0.5 h-4 w-4 shrink-0" />
            {error}
          </div>
        )}
        <div className="mt-4">
          <SmallButton variant="primary" onClick={onRun} disabled={busy}>
            {busy ? "Investigating…" : "Run investigation"}
          </SmallButton>
        </div>
        {busy && (
          <p className="mt-2 text-xs text-slate-400">
            This takes up to a few minutes and runs once — the result is saved.
          </p>
        )}
      </Card>
    );
  }

  const allFailed =
    investigation.toolCalls?.length > 0 &&
    investigation.toolCalls.every((c) => !c.succeeded);

  return (
    <Card className="space-y-5 p-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h3 className="flex items-center gap-2 text-sm font-semibold text-slate-900">
          <Icon name="sparkles" className="h-4 w-4 text-indigo-500" />
          Investigation
        </h3>
        <div className="flex items-center gap-3 text-xs text-slate-400">
          {/* Which engine ran it. An AWS incident is investigated by the
              analyst that can read CloudTrail; a cluster incident by the one
              that can reach the cluster. The reader should know which, because
              it tells them what the conclusion could possibly be based on. */}
          {engine && (
            <span className="rounded-full bg-slate-100 px-2 py-0.5 font-medium text-slate-600">
              {engine === "agent" ? "AWS analyst" : "Cluster engine"}
            </span>
          )}
          {investigation.askedAt && (
            <span>{new Date(investigation.askedAt).toLocaleString()}</span>
          )}
          {fmtDuration(investigation.tookMs) && (
            <span>{fmtDuration(investigation.tookMs)}</span>
          )}
          {investigation.costUsd != null && (
            <span>${investigation.costUsd.toFixed(4)}</span>
          )}
          <SmallButton onClick={onRun} disabled={busy}>
            {busy ? "Running…" : "Re-run"}
          </SmallButton>
        </div>
      </div>

      {/* Stated up front, not buried. An analysis built entirely on failed
          commands is a guess, and the reader deserves to know before they read
          the conclusion rather than after. */}
      {allFailed && (
        <div className="flex items-start gap-2.5 rounded-lg bg-amber-50 p-3 text-sm text-amber-900 ring-1 ring-inset ring-amber-600/15">
          <Icon name="warning" className="mt-0.5 h-4 w-4 shrink-0" />
          <p>
            Every check this investigation tried to run failed, so nothing below
            is evidenced — it is reasoning from the alert text alone.
          </p>
        </div>
      )}

      <SetupMarkdown source={investigation.analysis} />

      <p className="border-l-2 border-slate-200 pl-3 text-xs italic text-slate-500">
        A proposal, not a verdict — confirm it against the evidence before
        acting.
      </p>

      {investigation.toolCalls?.length > 0 && (
        <section>
          <h4 className="mb-2 flex items-center gap-2 text-xs font-semibold uppercase tracking-wider text-slate-500">
            What it looked at
            <span className="rounded-full bg-slate-100 px-1.5 py-0.5 text-[10px] text-slate-600">
              {investigation.toolCalls.length}
            </span>
          </h4>
          <div className="space-y-2">
            {investigation.toolCalls.map((c, i) => (
              <ToolCall key={i} call={c} />
            ))}
          </div>
        </section>
      )}

      {onAsk && (
        <section className="border-t border-slate-200 pt-4">
          <h4 className="mb-2 text-xs font-semibold uppercase tracking-wider text-slate-500">
            Ask a follow-up
          </h4>
          {investigation.followUps?.length > 0 && (
            <div className="mb-3 flex flex-wrap gap-2">
              {investigation.followUps.map((f, i) => (
                <button
                  key={i}
                  onClick={() => onAsk(f)}
                  disabled={busy}
                  className="rounded-full border border-slate-200 px-3 py-1 text-xs text-slate-600 transition hover:border-indigo-300 hover:text-indigo-700 disabled:opacity-50"
                >
                  {f}
                </button>
              ))}
            </div>
          )}
          <div className="flex gap-2">
            <input
              value={question}
              onChange={(e) => setQuestion(e.target.value)}
              placeholder="Why is the memory limit set that low?"
              className="min-w-0 flex-1 rounded-lg border border-slate-200 px-3 py-2 text-sm outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
            />
            <SmallButton
              onClick={() => {
                if (question.trim()) {
                  onAsk(question.trim());
                  setQuestion("");
                }
              }}
              disabled={busy || !question.trim()}
            >
              Ask
            </SmallButton>
          </div>
          <p className="mt-1.5 text-xs text-slate-400">
            Each question runs the engine again.
          </p>
        </section>
      )}
    </Card>
  );
}
