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
import {
  SEVERITY_ORDER,
  SeverityChip,
  severityRank,
} from "../../components/app/AlertSeverity";
import { listAlerts } from "../../lib/alerts";

/**
 * What is currently wrong, from every monitoring source wired into this
 * project. Read-only: acknowledging and resolving live in the tool that raised
 * the alert, and the platform holds a read-only credential so that stays true.
 */
export default function Alerts() {
  const { pid } = useParams();
  const navigate = useNavigate();
  const [alerts, setAlerts] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [status, setStatus] = useState("firing");
  const [severity, setSeverity] = useState("");

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    listAlerts({ projectId: pid, status, severity })
      .then((rows) => {
        // Worst first. An alerts page sorted by time buries the critical one
        // under a hundred infos, which is the opposite of its job.
        const sorted = [...rows].sort(
          (a, b) => severityRank(a.severity) - severityRank(b.severity),
        );
        setAlerts(sorted);
        setLoading(false);
      })
      .catch((e) => {
        setError(e.message || "Could not load alerts");
        setLoading(false);
      });
  }, [pid, status, severity]);

  useEffect(() => {
    load();
  }, [load]);

  return (
    <div className="animate-fade-up">
      <PageHeader
        title="Alerts"
        subtitle="What your monitoring is reporting right now, deduplicated across sources"
        actions={
          <div className="flex items-center gap-2">
            <SmallButton icon="refresh" onClick={load}>
              Refresh
            </SmallButton>
            <SmallButton
              icon="plus"
              variant="primary"
              onClick={() => navigate(`/app/projects/${pid}/alerts/sources`)}
            >
              Connect a source
            </SmallButton>
          </div>
        }
      />

      <Card className="mb-4 flex flex-wrap items-center gap-3 p-4">
        <label className="text-xs font-medium uppercase tracking-wider text-slate-500">
          Status
        </label>
        <select
          value={status}
          onChange={(e) => setStatus(e.target.value)}
          className="rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-sm text-slate-700"
        >
          <option value="firing">Firing</option>
          <option value="acknowledged">Acknowledged</option>
          <option value="resolved">Resolved</option>
          <option value="suppressed">Suppressed</option>
          <option value="">Any</option>
        </select>
        <label className="ml-2 text-xs font-medium uppercase tracking-wider text-slate-500">
          Severity
        </label>
        <select
          value={severity}
          onChange={(e) => setSeverity(e.target.value)}
          className="rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-sm text-slate-700"
        >
          <option value="">Any</option>
          {SEVERITY_ORDER.map((s) => (
            <option key={s} value={s} className="capitalize">
              {s}
            </option>
          ))}
        </select>
      </Card>

      <Table
        loading={loading}
        error={error}
        onRetry={load}
        rows={alerts}
        onRowClick={(r) =>
          navigate(
            `/app/projects/${pid}/alerts/${encodeURIComponent(r.fingerprint)}`,
          )
        }
        /*
         * Deliberately NOT "You're all clear" or a green tick. An empty alerts
         * page means nothing has been RECEIVED AND MATCHED to this project —
         * which is also exactly what an unconnected source looks like. Telling
         * someone their estate is healthy on the strength of silence is the one
         * lie this screen must never tell.
         */
        empty="No alerts are matched to this project. Connect a monitoring source and its alerts will appear here."
        columns={[
          {
            key: "severity",
            label: "Severity",
            render: (r) => <SeverityChip value={r.severity} />,
          },
          {
            key: "name",
            label: "Alert",
            render: (r) => (
              <div className="min-w-0">
                <div className="truncate font-medium text-slate-900">
                  {r.name || "(unnamed)"}
                </div>
                {r.description && (
                  <div className="truncate text-xs text-slate-500">
                    {r.description}
                  </div>
                )}
              </div>
            ),
          },
          {
            key: "source",
            label: "Source",
            render: (r) => (
              <span className="text-slate-600">
                {(r.source || []).join(", ") || "—"}
              </span>
            ),
          },
          {
            key: "service",
            label: "Service",
            render: (r) => (
              <span className="text-slate-600">{r.service || "—"}</span>
            ),
          },
          {
            key: "status",
            label: "Status",
            render: (r) => <StatusBadge status={r.status} />,
          },
          {
            key: "receivedAt",
            label: "Last seen",
            render: (r) => (
              <span className="whitespace-nowrap text-slate-500">
                {r.receivedAt ? new Date(r.receivedAt).toLocaleString() : "—"}
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
