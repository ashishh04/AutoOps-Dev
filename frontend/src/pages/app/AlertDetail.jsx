import React, { useState, useEffect } from "react";
import { useParams, useNavigate } from "react-router-dom";
import { useAlertScope } from "../../lib/alertScope";
import {
  Card,
  StatusBadge,
  SmallButton,
  Skeleton,
} from "../../components/app/appui";
import Icon from "../../components/Icon";
import { SeverityChip, severityTone } from "../../components/app/AlertSeverity";
import { getAlert } from "../../lib/alerts";

/** "3 hours ago" — the question an operator is actually asking. */
const since = (iso) => {
  if (!iso) return null;
  const ms = Date.now() - new Date(iso).getTime();
  if (Number.isNaN(ms)) return null;
  const mins = Math.round(ms / 60000);
  if (mins < 1) return "just now";
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.round(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  return `${Math.round(hrs / 24)}d ago`;
};

const Meta = ({ icon, label, value, mono = false }) => (
  <div className="flex items-start gap-3">
    <div className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-slate-100 text-slate-500">
      <Icon name={icon} className="h-4 w-4" />
    </div>
    <div className="min-w-0">
      <div className="text-xs font-medium uppercase tracking-wider text-slate-500">
        {label}
      </div>
      <div
        className={`mt-0.5 break-words text-sm text-slate-800 ${mono ? "font-mono text-xs text-slate-500" : ""}`}
      >
        {value || <span className="text-slate-400">—</span>}
      </div>
    </div>
  </div>
);

export default function AlertDetail() {
  const { fingerprint } = useParams();
  const scope = useAlertScope();
  const pid = scope.projectId;
  const navigate = useNavigate();
  const [alert, setAlert] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    setLoading(true);
    setError(null);
    getAlert(fingerprint, pid)
      .then((a) => {
        setAlert(a);
        setLoading(false);
      })
      .catch((e) => {
        // A 404 here is also the answer for an alert belonging to another
        // workspace — alert-service refuses to distinguish the two so that a
        // fingerprint cannot be probed for existence. The copy has to hold for
        // both readings.
        setError(
          e.status === 404
            ? "That alert is not available in this workspace."
            : e.message || "Could not load this alert",
        );
        setLoading(false);
      });
  }, [fingerprint, pid]);

  const tone = severityTone(alert?.severity);

  return (
    <div className="animate-fade-up">
      <div className="mb-5 flex items-center justify-between gap-4">
        <button
          onClick={() => navigate(scope.link("/alerts"))}
          className="inline-flex items-center gap-1.5 text-sm font-medium text-slate-500 transition hover:text-slate-900"
        >
          <Icon name="chevron" className="h-4 w-4 rotate-180" />
          Alerts
        </button>
        {alert?.url && (
          <SmallButton
            icon="eye"
            onClick={() => window.open(alert.url, "_blank", "noopener")}
          >
            Open in source
          </SmallButton>
        )}
      </div>

      {loading && (
        <Card className="space-y-4 p-6">
          <Skeleton className="h-6 w-2/5" />
          <Skeleton className="h-4 w-3/5" />
          <Skeleton className="h-4 w-1/4" />
        </Card>
      )}

      {!loading && error && (
        <Card className="flex items-center gap-3 p-6 text-sm text-slate-600">
          <Icon name="warning" className="h-5 w-5 shrink-0 text-amber-500" />
          {error}
        </Card>
      )}

      {!loading && !error && alert && (
        <div className="space-y-5">
          {/* Hero. The severity rail and wash are the fastest read on the
              page — colour before anyone has parsed a word of the title. */}
          <div className="relative overflow-hidden rounded-2xl border border-slate-200 bg-white">
            <div className={`absolute inset-y-0 left-0 w-1.5 ${tone.rail}`} />
            <div
              className={`pointer-events-none absolute inset-0 bg-gradient-to-r ${tone.wash} to-transparent`}
            />
            <div className="relative flex gap-4 p-6 pl-8">
              <div
                className={`flex h-12 w-12 shrink-0 items-center justify-center rounded-xl ${tone.iconBg}`}
              >
                <Icon name="warning" className={`h-6 w-6 ${tone.iconFg}`} />
              </div>
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  <SeverityChip value={alert.severity} />
                  <StatusBadge status={alert.status} />
                  {since(alert.receivedAt) && (
                    <span className="text-xs text-slate-500">
                      last seen {since(alert.receivedAt)}
                    </span>
                  )}
                </div>
                <h1 className="mt-2.5 text-2xl font-bold tracking-tight text-slate-900">
                  {alert.name || "(unnamed alert)"}
                </h1>
                {alert.description && (
                  <p className="mt-1.5 text-sm leading-relaxed text-slate-600">
                    {alert.description}
                  </p>
                )}
              </div>
            </div>
          </div>

          <Card className="grid grid-cols-1 gap-x-8 gap-y-5 p-6 sm:grid-cols-2 lg:grid-cols-3">
            <Meta icon="server" label="Service" value={alert.service} />
            <Meta icon="cloud" label="Environment" value={alert.environment} />
            <Meta
              icon="radar"
              label="Source"
              value={(alert.source || []).join(", ")}
            />
            <Meta
              icon="clock"
              label="First seen"
              value={
                alert.startedAt
                  ? new Date(alert.startedAt).toLocaleString()
                  : null
              }
            />
            <Meta
              icon="pulse"
              label="Last seen"
              value={
                alert.receivedAt
                  ? new Date(alert.receivedAt).toLocaleString()
                  : null
              }
            />
            <Meta
              icon="key"
              label="Fingerprint"
              value={alert.fingerprint}
              mono
            />
          </Card>

          {/* Read-only is a product decision, not a missing feature, so the
              page says so rather than leaving someone hunting for a button. */}
          <div className="flex items-start gap-2.5 px-1 text-xs text-slate-500">
            <Icon name="lock" className="mt-0.5 h-3.5 w-3.5 shrink-0" />
            <p>
              AutoOps reads this alert and never writes back to it.
              Acknowledging and resolving happen in the tool that raised it
              {alert.url ? " — use Open in source." : "."}
            </p>
          </div>
        </div>
      )}
    </div>
  );
}
