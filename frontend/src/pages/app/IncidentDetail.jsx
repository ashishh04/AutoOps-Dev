import React, { useState, useEffect, useCallback } from "react";
import { useParams, useNavigate } from "react-router-dom";
import { Card, StatusBadge, SmallButton, Skeleton } from "../../components/app/appui";
import Icon from "../../components/Icon";
import { SeverityChip, severityTone } from "../../components/app/AlertSeverity";
import Investigation from "../../components/app/Investigation";
import { useStore } from "../../store/store";
import {
  getIncident,
  getIncidentCapabilities,
  setIncidentStatus,
  commentOnIncident,
  assignIncident,
  investigateIncident,
  getInvestigation,
} from "../../lib/incidents";

const Meta = ({ label, children }) => (
  <div className="min-w-0">
    <div className="text-xs font-medium uppercase tracking-wider text-slate-500">
      {label}
    </div>
    <div className="mt-1 break-words text-sm text-slate-800">
      {children || <span className="text-slate-400">—</span>}
    </div>
  </div>
);

export default function IncidentDetail() {
  const { pid, id } = useParams();
  const navigate = useNavigate();
  const { pushToast } = useStore();

  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [investigating, setInvestigating] = useState(false);
  const [investigationError, setInvestigationError] = useState(null);
  const [canInvestigate, setCanInvestigate] = useState(false);
  const [assignee, setAssignee] = useState("");
  const [comment, setComment] = useState("");

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    getIncident(id, pid)
      .then((d) => {
        setData(d);
        setLoading(false);
      })
      .catch((e) => {
        setError(
          e.status === 404
            ? "That incident is not available in this workspace."
            : e.message || "Could not load this incident",
        );
        setLoading(false);
      });
  }, [id, pid]);

  useEffect(() => {
    load();
    getIncidentCapabilities().then((c) => setCanInvestigate(!!c?.investigation));
  }, [load]);

  const act = async (fn, ok) => {
    setBusy(true);
    try {
      await fn();
      pushToast(ok, "emerald");
      load();
    } catch (e) {
      pushToast(e.message || "That did not work", "red");
    } finally {
      setBusy(false);
    }
  };

  /**
   * The AWS analyst runs for minutes and answers `running`; the cluster engine
   * answers in one call. Both land in the same panel, so the difference is a
   * poll rather than two code paths the reader has to hold in their head.
   */
  const runInvestigation = async (question) => {
    setInvestigating(true);
    setInvestigationError(null);
    try {
      let result = await investigateIncident(id, pid, { question });
      let waited = 0;
      while (result?.status === "running") {
        // 4s, and a ceiling. A run that has not finished in ten minutes has
        // almost certainly stalled, and a page that polls forever is worse
        // than one that admits it stopped watching.
        if (waited > 600000) {
          setInvestigationError(
            "The investigation is taking longer than expected. It is still running — reopen this incident to check.",
          );
          break;
        }
        await new Promise((r) => setTimeout(r, 4000));
        waited += 4000;
        result = await getInvestigation(id, pid);
      }
      if (result?.status === "failed") {
        setInvestigationError(result.message || "The investigation did not complete");
      } else if (result?.status === "complete") {
        setData((d) => ({ ...d, investigation: result.investigation, engine: result.engine }));
      }
    } catch (e) {
      setInvestigationError(e.message || "The investigation could not be completed");
    } finally {
      setInvestigating(false);
    }
  };

  const incident = data?.incident;
  const tone = severityTone(incident?.severity);

  return (
    <div className="animate-fade-up">
      <div className="mb-5">
        <button
          onClick={() => navigate(`/app/projects/${pid}/incidents`)}
          className="inline-flex items-center gap-1.5 text-sm font-medium text-slate-500 transition hover:text-slate-900"
        >
          <Icon name="chevron" className="h-4 w-4 rotate-180" />
          All incidents
        </button>
      </div>

      {loading && (
        <Card className="space-y-4 p-6">
          <Skeleton className="h-6 w-2/5" />
          <Skeleton className="h-4 w-3/5" />
        </Card>
      )}

      {!loading && error && (
        <Card className="flex items-center gap-3 p-6 text-sm text-slate-600">
          <Icon name="warning" className="h-5 w-5 shrink-0 text-amber-500" />
          {error}
        </Card>
      )}

      {!loading && !error && incident && (
        <div className="space-y-5">
          <div className="relative overflow-hidden rounded-2xl border border-slate-200 bg-white">
            <div className={`absolute inset-y-0 left-0 w-1.5 ${tone.rail}`} />
            <div
              className={`pointer-events-none absolute inset-0 bg-gradient-to-r ${tone.wash} to-transparent`}
            />
            <div className="relative p-6 pl-8">
              <div className="flex flex-wrap items-center gap-2">
                <SeverityChip value={incident.severity} />
                <StatusBadge status={incident.status} />
              </div>
              <h1 className="mt-2.5 text-2xl font-bold tracking-tight text-slate-900">
                {incident.name || "(unnamed incident)"}
              </h1>
              {incident.summary && (
                <p className="mt-1.5 text-sm leading-relaxed text-slate-600">
                  {incident.summary}
                </p>
              )}
              <div className="mt-4 flex flex-wrap gap-2">
                <SmallButton
                  onClick={() =>
                    act(
                      () => setIncidentStatus(id, pid, "acknowledged"),
                      "Incident acknowledged",
                    )
                  }
                  disabled={busy || incident.status === "acknowledged"}
                >
                  Acknowledge
                </SmallButton>
                <SmallButton
                  variant="primary"
                  onClick={() =>
                    act(
                      () => setIncidentStatus(id, pid, "resolved"),
                      "Incident resolved",
                    )
                  }
                  disabled={busy || incident.status === "resolved"}
                >
                  Resolve
                </SmallButton>
              </div>
            </div>
          </div>

          <Card className="grid grid-cols-2 gap-x-8 gap-y-5 p-6 lg:grid-cols-4">
            <Meta label="Services">{(incident.services || []).join(", ")}</Meta>
            <Meta label="Sources">{(incident.sources || []).join(", ")}</Meta>
            <Meta label="Alerts">{incident.alertCount ?? 0}</Meta>
            <Meta label="Assignee">{incident.assignee}</Meta>
            <Meta label="Started">
              {incident.startedAt
                ? new Date(incident.startedAt).toLocaleString()
                : null}
            </Meta>
            <Meta label="Last seen">
              {incident.lastSeenAt
                ? new Date(incident.lastSeenAt).toLocaleString()
                : null}
            </Meta>
            {/* Correlation's decision, named. A grouping nobody can explain is
                a grouping nobody trusts. */}
            <Meta label="Correlated by">{incident.correlatedBy}</Meta>
          </Card>

          <Card className="p-6">
            <h3 className="text-sm font-semibold text-slate-900">Evidence</h3>
            <p className="mt-1 text-sm text-slate-500">
              The alerts correlated into this incident. Grouping is the alert
              plane's decision, not the console's.
            </p>
            <div className="mt-4 divide-y divide-slate-100">
              {(data.evidence || []).length === 0 && (
                <p className="py-3 text-sm text-slate-400">
                  No alerts are currently linked to this incident.
                </p>
              )}
              {(data.evidence || []).map((a) => (
                <div
                  key={a.fingerprint}
                  className="flex items-start gap-4 py-3"
                >
                  <div className="w-20 shrink-0">
                    <SeverityChip value={a.severity} />
                  </div>
                  <div className="min-w-0 flex-1">
                    <div className="truncate text-sm font-medium text-slate-900">
                      {a.name}
                    </div>
                    {a.description && (
                      <div className="truncate text-xs text-slate-500">
                        {a.description}
                      </div>
                    )}
                  </div>
                  <div className="hidden shrink-0 text-xs text-slate-500 sm:block">
                    {a.service || "—"}
                  </div>
                  <div className="shrink-0 text-xs text-slate-400">
                    {a.receivedAt
                      ? new Date(a.receivedAt).toLocaleString()
                      : "—"}
                  </div>
                </div>
              ))}
            </div>
          </Card>

          <Investigation
            investigation={data.investigation}
            engine={data.engine}
            enabled={canInvestigate}
            busy={investigating}
            error={investigationError}
            onRun={() => runInvestigation(null)}
            onAsk={(q) => runInvestigation(q)}
          />

          <Card className="p-6">
            <h3 className="text-sm font-semibold text-slate-900">
              Add to the timeline
            </h3>
            <p className="mt-1 text-sm text-slate-500">
              Comments are recorded against the incident's activity history.
            </p>
            <textarea
              value={comment}
              onChange={(e) => setComment(e.target.value)}
              rows={3}
              placeholder="What did you find?"
              className="mt-3 w-full rounded-lg border border-slate-200 p-3 text-sm outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
            />
            <div className="mt-3 flex flex-wrap items-center gap-2">
              <SmallButton
                onClick={() =>
                  act(async () => {
                    await commentOnIncident(id, pid, comment);
                    setComment("");
                  }, "Comment added")
                }
                disabled={busy || !comment.trim()}
              >
                Add comment
              </SmallButton>
              <div className="ml-auto flex items-center gap-2">
                <input
                  value={assignee}
                  onChange={(e) => setAssignee(e.target.value)}
                  placeholder="Assign an owner"
                  className="w-48 rounded-lg border border-slate-200 px-3 py-1.5 text-sm outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
                />
                <SmallButton
                  onClick={() =>
                    act(async () => {
                      await assignIncident(id, pid, assignee);
                      setAssignee("");
                    }, "Incident assigned")
                  }
                  disabled={busy || !assignee.trim()}
                >
                  Assign
                </SmallButton>
              </div>
            </div>
          </Card>
        </div>
      )}
    </div>
  );
}
