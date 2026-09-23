import React, { useCallback, useEffect, useMemo, useState } from "react";
import {
  PageHeader,
  Card,
  StatCard,
  StatusBadge,
  SmallButton,
  Chip,
} from "../../components/app/appui";
import Icon from "../../components/Icon";
import { api } from "../../lib/api";

/**
 * The fleet: every tenant at once.
 *
 * NOT the tenant pages with a wider filter, and the difference decides the
 * layout. A customer asks "what is wrong with my system" and wants a list of
 * alerts. A provider asks "which of my customers is on fire, and which one has
 * quietly stopped sending me anything" — and the second half of that is
 * answered by an ABSENCE, which no per-tenant view can show.
 *
 * So the most important panel here is the one listing tenants with NO signal.
 * The rollup endpoints can only report tenants that appear in the data; a
 * tenant with nothing connected produces no row at all. Joining that against
 * the tenant directory is what turns silence into a name, and it happens here
 * rather than in a service because it spans two of them.
 */

const REFRESH_MS = 30000;

/** Age of the freshest signal, in words. Null when there has never been one. */
const ageOf = (iso) => {
  if (!iso) return null;
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return null;
  const mins = Math.max(0, Math.round((Date.now() - then) / 60000));
  if (mins < 60) return `${mins}m ago`;
  const hours = Math.round(mins / 60);
  if (hours < 48) return `${hours}h ago`;
  return `${Math.round(hours / 24)}d ago`;
};

/**
 * A tenant is SILENT when the directory knows it and no alert ever mentioned
 * it. That is the provider's most actionable row and the one a customer can
 * never see, because from inside a tenant "no alerts" looks like good news.
 */
const silentTenants = (directory, rollup) => {
  const sending = new Set((rollup?.tenants || []).map((t) => t.tenant_id));
  return (directory || []).filter((t) => !sending.has(t.tenantId));
};

export default function ProviderFleet() {
  const [alerts, setAlerts] = useState(null);
  const [incidents, setIncidents] = useState(null);
  const [agents, setAgents] = useState(null);
  const [directory, setDirectory] = useState([]);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    try {
      // Fetched together and failed together. A half-loaded fleet view invites
      // exactly the wrong conclusion — an empty incident panel beside a healthy
      // alert panel reads as "nothing is wrong" rather than "this did not load".
      const [a, i, g, d] = await Promise.all([
        api.providerFleetAlerts(),
        api.providerFleetIncidents(),
        api.providerFleetAgents(7),
        api.providerTenantsMerged().catch(() => []),
      ]);
      setAlerts(a);
      setIncidents(i);
      setAgents(g);
      setDirectory(d || []);
      setError(null);
    } catch (e) {
      setError(e?.message || "Could not load the fleet view.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
    const timer = setInterval(load, REFRESH_MS);
    return () => clearInterval(timer);
  }, [load]);

  const silent = useMemo(
    () => silentTenants(directory, alerts),
    [directory, alerts],
  );

  // Agent rows joined to their attribution counters. `agent_ref` is the join
  // key on BOTH sides deliberately — the display name and the runtime ref
  // differ, and confusing them refused every verdict in this platform once.
  const agentRows = useMemo(() => {
    const byKey = new Map(
      (agents?.attribution || []).map((r) => [`${r.tenant_id}|${r.agent_ref}`, r]),
    );
    const findings = new Map(
      (agents?.findings || []).map((r) => [`${r.tenant_id}|${r.agent_ref}`, r]),
    );
    return (agents?.agents || []).map((row) => {
      const key = `${row.tenant_id}|${row.agent_ref}`;
      return { ...row, attribution: byKey.get(key) || null, found: findings.get(key) || null };
    });
  }, [agents]);

  if (loading) {
    return (
      <div>
        <PageHeader title="Fleet" subtitle="Every tenant at once." />
        <Card><div className="p-6 text-sm text-slate-500">Loading…</div></Card>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="Fleet"
        subtitle="Every tenant at once — who is noisy, who is silent, and whether their agents are working."
        actions={<SmallButton onClick={load}><Icon name="refresh" /> Refresh</SmallButton>}
      />

      {error && (
        <Card>
          <div className="p-4 text-sm text-rose-600" role="alert">{error}</div>
        </Card>
      )}

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="Tenants sending alerts" value={alerts?.tenant_count ?? 0} />
        <StatCard label="Open incidents" value={incidents?.open_count ?? 0} />
        <StatCard
          label="Unassigned incidents"
          value={incidents?.unassigned_count ?? 0}
          tone={incidents?.unassigned_count ? "warn" : undefined}
        />
        {/* The headline number of the whole page. */}
        <StatCard
          label="Tenants with no signal"
          value={silent.length}
          tone={silent.length ? "warn" : undefined}
        />
      </div>

      {/* Deliberately FIRST. It is the finding nobody else in the platform can
          surface: a customer who is paying and sending nothing. */}
      <Card title="Tenants with no signal">
        {silent.length === 0 ? (
          <div className="p-4 text-sm text-slate-500">
            Every tenant in the directory has sent at least one alert.
          </div>
        ) : (
          <div className="p-4 space-y-2">
            <p className="text-sm text-slate-600">
              These tenants exist in the directory and have never appeared in the alert
              stream. From inside their own console this looks like a quiet estate.
            </p>
            <div className="flex flex-wrap gap-2">
              {silent.map((t) => (
                <Chip key={t.tenantId}>{t.name || t.tenantId}</Chip>
              ))}
            </div>
          </div>
        )}
      </Card>

      <Card title="Alert volume by tenant">
        {(alerts?.tenants || []).length === 0 ? (
          <div className="p-4 text-sm text-slate-500">No alerts in the window.</div>
        ) : (
          <table className="w-full text-sm">
            <thead className="text-left text-slate-500">
              <tr>
                <th className="p-3">Tenant</th>
                <th className="p-3">Firing</th>
                <th className="p-3">Critical</th>
                <th className="p-3">Total</th>
                <th className="p-3">Last alert</th>
              </tr>
            </thead>
            <tbody>
              {alerts.tenants.map((t) => (
                <tr key={t.tenant_id} className="border-t border-slate-100">
                  <td className="p-3 font-medium">{t.tenant_id}</td>
                  <td className="p-3">{t.firing}</td>
                  <td className="p-3">{t.critical}</td>
                  <td className="p-3 text-slate-500">{t.alerts}</td>
                  <td className="p-3 text-slate-500">
                    {ageOf(t.last_received_at) || "—"}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {alerts?.truncated && (
          // Said out loud rather than shown as a smaller number. A truncated
          // rollup makes a busy tenant look quiet.
          <div className="p-3 text-xs text-amber-700">
            The alert window was truncated, so these counts are a floor rather than a total.
          </div>
        )}
        {alerts?.unattributed_alerts > 0 && (
          <div className="p-3 text-xs text-amber-700">
            {alerts.unattributed_alerts} alert(s) arrived without a tenant label — a
            connected source is not stamping them, and they belong to no tenant's view.
          </div>
        )}
      </Card>

      <Card title="Open incidents across tenants">
        {(incidents?.incidents || []).length === 0 ? (
          <div className="p-4 text-sm text-slate-500">Nothing open.</div>
        ) : (
          <table className="w-full text-sm">
            <thead className="text-left text-slate-500">
              <tr>
                <th className="p-3">Incident</th>
                <th className="p-3">Severity</th>
                <th className="p-3">Assignee</th>
                <th className="p-3">Alerts</th>
                <th className="p-3">Started</th>
              </tr>
            </thead>
            <tbody>
              {incidents.incidents.map((i) => (
                <tr key={i.id} className="border-t border-slate-100">
                  <td className="p-3 font-medium">{i.name || i.id}</td>
                  <td className="p-3"><StatusBadge status={i.severity} /></td>
                  <td className="p-3">
                    {i.assignee || <span className="text-amber-700">unassigned</span>}
                  </td>
                  <td className="p-3 text-slate-500">{i.alert_count ?? "—"}</td>
                  <td className="p-3 text-slate-500">{ageOf(i.started_at) || "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      <Card title={`Agent health (last ${agents?.window_days ?? 7} days)`}>
        {agentRows.length === 0 ? (
          <div className="p-4 text-sm text-slate-500">No agents rolled out.</div>
        ) : (
          <table className="w-full text-sm">
            <thead className="text-left text-slate-500">
              <tr>
                <th className="p-3">Tenant</th>
                <th className="p-3">Agent</th>
                <th className="p-3">Runs</th>
                <th className="p-3">Failed</th>
                <th className="p-3">Verdicts</th>
                <th className="p-3">Open findings</th>
                <th className="p-3">Last run</th>
              </tr>
            </thead>
            <tbody>
              {agentRows.map((row) => (
                <tr key={`${row.tenant_id}|${row.agent_ref}`} className="border-t border-slate-100">
                  <td className="p-3">{row.tenant_id}</td>
                  <td className="p-3 font-medium">{row.agent_name}</td>
                  <td className="p-3">{row.runs}</td>
                  <td className="p-3">
                    {row.failed_runs > 0
                      ? <span className="text-rose-600">{row.failed_runs}</span>
                      : row.failed_runs}
                  </td>
                  {/* attributed is shown BESIDE the problem counters on
                      purpose: several of them are zero when healthy and zero
                      when the pipeline is dead, and only this number separates
                      the two. */}
                  <td className="p-3 text-slate-500">
                    {row.attribution ? row.attribution.attributed : 0}
                  </td>
                  <td className="p-3">{row.found ? row.found.open_findings : 0}</td>
                  <td className="p-3 text-slate-500">{ageOf(row.last_run_at) || "never"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
    </div>
  );
}
