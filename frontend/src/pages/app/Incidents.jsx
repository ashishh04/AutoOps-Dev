import React, { useState, useEffect, useCallback } from "react";
import { useParams, useNavigate } from "react-router-dom";
import {
  PageHeader,
  Table,
  StatusBadge,
  SmallButton,
  Card,
} from "../../components/app/appui";
import Icon from "../../components/Icon";
import { SeverityChip, severityRank } from "../../components/app/AlertSeverity";
import { listIncidents } from "../../lib/incidents";

const STATUSES = [
  { key: "firing", label: "Firing" },
  { key: "acknowledged", label: "Acknowledged" },
  { key: "resolved", label: "Resolved" },
  { key: "", label: "All" },
];

/**
 * What the alert feed became.
 *
 * An incident is a correlation decision, not something the console invents —
 * which is why "Correlated by" is a column rather than a detail. A grouping
 * nobody can explain is a grouping nobody trusts, and the first question of any
 * correlated view is "why are these one thing?".
 */
export default function Incidents() {
  const { pid } = useParams();
  const navigate = useNavigate();
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [status, setStatus] = useState("firing");

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    listIncidents({ projectId: pid, status })
      .then((list) => {
        setRows(
          [...list].sort(
            (a, b) => severityRank(a.severity) - severityRank(b.severity),
          ),
        );
        setLoading(false);
      })
      .catch((e) => {
        setError(e.message || "Could not load incidents");
        setLoading(false);
      });
  }, [pid, status]);

  useEffect(() => {
    load();
  }, [load]);

  return (
    <div className="animate-fade-up">
      <PageHeader
        title="Incidents"
        subtitle="Correlated from the alert feed — grouped, deduplicated, and investigable"
        actions={
          <div className="flex items-center gap-2">
            <SmallButton icon="refresh" onClick={load}>
              Refresh
            </SmallButton>
            <SmallButton
              icon="radar"
              onClick={() => navigate(`/app/projects/${pid}/alerts`)}
            >
              Alert feed
            </SmallButton>
          </div>
        }
      />

      <Card className="mb-4 flex flex-wrap items-center gap-1.5 p-3">
        {STATUSES.map((s) => (
          <button
            key={s.key || "all"}
            onClick={() => setStatus(s.key)}
            className={`rounded-full px-3 py-1 text-xs font-medium transition ${
              status === s.key
                ? "bg-slate-900 text-white"
                : "bg-slate-100 text-slate-600 hover:bg-slate-200"
            }`}
          >
            {s.label}
          </button>
        ))}
      </Card>

      <Table
        loading={loading}
        error={error}
        onRetry={load}
        rows={rows}
        onRowClick={(r) =>
          navigate(`/app/projects/${pid}/incidents/${encodeURIComponent(r.id)}`)
        }
        /*
         * Same rule as the alert feed: silence is not health. No incidents can
         * equally mean nothing is correlated yet, and telling someone their
         * estate is fine on the strength of an empty table is the one lie
         * these screens must never tell.
         */
        empty="No incidents match this filter. Incidents appear once correlated alerts arrive."
        columns={[
          {
            key: "severity",
            label: "Severity",
            render: (r) => <SeverityChip value={r.severity} />,
          },
          {
            key: "name",
            label: "Incident",
            render: (r) => (
              <div className="min-w-0">
                <div className="truncate font-medium text-slate-900">
                  {r.name || "(unnamed incident)"}
                </div>
                <div className="flex items-center gap-2 text-xs text-slate-500">
                  <span className="truncate">
                    {(r.services || []).join(", ") || "—"}
                  </span>
                  {r.investigated && (
                    <span className="inline-flex shrink-0 items-center gap-1 rounded-full bg-indigo-50 px-1.5 py-0.5 font-medium text-indigo-700">
                      <Icon name="sparkles" className="h-3 w-3" />
                      Investigated
                    </span>
                  )}
                </div>
              </div>
            ),
          },
          {
            key: "alertCount",
            label: "Alerts",
            render: (r) => (
              <span className="tabular-nums text-slate-600">
                {r.alertCount ?? 0}
              </span>
            ),
          },
          {
            key: "correlatedBy",
            label: "Correlated by",
            render: (r) => (
              <span className="truncate text-xs text-slate-500">
                {r.correlatedBy || "—"}
              </span>
            ),
          },
          {
            key: "assignee",
            label: "Assignee",
            render: (r) => (
              <span className="text-slate-600">{r.assignee || "—"}</span>
            ),
          },
          {
            key: "status",
            label: "Status",
            render: (r) => <StatusBadge status={r.status} />,
          },
          {
            key: "lastSeenAt",
            label: "Last seen",
            render: (r) => (
              <span className="whitespace-nowrap text-slate-500">
                {r.lastSeenAt ? new Date(r.lastSeenAt).toLocaleString() : "—"}
              </span>
            ),
          },
          {
            key: "go",
            label: "",
            render: () => (
              <div className="flex justify-end text-slate-400">
                <Icon name="chevron" className="h-4 w-4" />
              </div>
            ),
          },
        ]}
      />
    </div>
  );
}
