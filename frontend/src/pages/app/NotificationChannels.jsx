/**
 * Notification channels — where this workspace's job, workflow, agent and
 * alert events go.
 *
 * Replaces the hard-coded three-option connector form in Settings. Everything
 * here is generated from what plugin-service reports: the install form comes
 * from each plugin's `fields`, and the event checkboxes from /plugins/events.
 * Adding a provider or an event to the backend needs no change in this file.
 *
 * Two honesty rules the backend enforces and this page reflects:
 *   - Secrets are never returned. A configured field shows a mask, and leaving
 *     it blank on edit keeps the stored value rather than wiping it.
 *   - "Test" makes a REAL call to the third party. A channel that has never
 *     been tested says so instead of showing a tick it did not earn.
 */

import React, { useCallback, useEffect, useMemo, useState } from "react";
import {
  PageHeader,
  Card,
  SmallButton,
  Chip,
  Skeleton,
  ConfirmModal,
} from "../../components/app/appui";
import Icon from "../../components/Icon";
import BrandLogo from "../../components/app/BrandLogo";
import ModalPortal from "../../components/app/ModalPortal";
import { api } from "../../lib/api";
import { channelLogo } from "../../lib/brandLogos";
import { useStore } from "../../store/store";

const CATEGORY_LABEL = { CHAT: "Chat", EMAIL: "Email", TICKETING: "Tickets" };

const SEVERITY_TONE = {
  INFO: "bg-slate-100 text-slate-600",
  WARNING: "bg-amber-50 text-amber-700",
  CRITICAL: "bg-red-50 text-red-700",
};

/** Parked outranks everything: the platform stopped trying, whatever else is true. */
function channelStatus(channel) {
  if (channel.parked)
    return {
      label: "Paused after failures",
      tone: "bg-red-50 text-red-700",
      verified: false,
    };
  if (!channel.enabled)
    return {
      label: "Disabled",
      tone: "bg-slate-100 text-slate-500",
      verified: false,
    };
  if (channel.lastTestOk === true)
    return {
      label: "Verified",
      tone: "bg-emerald-50 text-emerald-700",
      verified: true,
    };
  if (channel.lastTestOk === false)
    return {
      label: "Last test failed",
      tone: "bg-red-50 text-red-700",
      verified: false,
    };
  return {
    label: "Not tested",
    tone: "bg-amber-50 text-amber-700",
    verified: false,
  };
}

/**
 * Display names for what a rule can watch.
 *
 * A fallback, not a catalog — the server publishes the authoritative list at
 * /plugins/target-types, and this only has to render a type that already came
 * back on a saved rule. An unrecognised one falls through to its raw name
 * rather than being hidden, so a type added server-side shows up looking
 * unpolished instead of silently vanishing from the rules table.
 */
const TARGET_PLURAL = {
  JOB: "Jobs",
  WORKFLOW: "Workflows",
  AGENT: "AI Agents",
  ALERT: "Alerts",
};

const singular = (type) =>
  ({ JOB: "job", WORKFLOW: "workflow", AGENT: "agent", ALERT: "alert" })[
    type
  ] || String(type).toLowerCase();

const scopeLabel = (rule) => {
  if (rule.scope === "TARGET") return `One ${singular(rule.targetType)}`;
  if (rule.scope === "PROJECT") return "One project";
  return "Whole workspace";
};

export default function NotificationChannels() {
  const { pushToast, can } = useStore();
  const canAdmin = can("manageKeys");

  const [catalog, setCatalog] = useState(null);
  const [channels, setChannels] = useState(null);
  const [rules, setRules] = useState(null);
  const [events, setEvents] = useState([]);
  // What a rule can watch, read from the server. Falls back to the two that
  // have always existed rather than to nothing — an empty list would leave the
  // "Watch" dropdown blank and the form unusable over a catalog hiccup.
  const [targetTypes, setTargetTypes] = useState([
    { value: "JOB", label: "Jobs", identifiesTargets: true },
    { value: "WORKFLOW", label: "Workflows", identifiesTargets: true },
  ]);
  const [deliveries, setDeliveries] = useState([]);
  const [loadError, setLoadError] = useState(null);

  const [installing, setInstalling] = useState(null);
  const [editing, setEditing] = useState(null);
  const [removing, setRemoving] = useState(null);
  const [removingRule, setRemovingRule] = useState(null);
  const [addingRule, setAddingRule] = useState(false);
  const [testing, setTesting] = useState(null);

  const load = useCallback(() => {
    setLoadError(null);
    Promise.all([
      api.pluginCatalog(),
      api.listInstallations(),
      api.listNotificationRules(),
      api.pluginEvents().catch(() => []),
      api.pluginDeliveries(50).catch(() => []),
      api.pluginTargetTypes().catch(() => null),
    ])
      .then(([c, i, r, e, d, t]) => {
        setCatalog(c);
        setChannels(i);
        setRules(r);
        setEvents(e);
        setDeliveries(d);
        if (Array.isArray(t) && t.length > 0) setTargetTypes(t);
      })
      .catch((err) => {
        // Honest failure: an empty page and the reason, never invented rows.
        setCatalog([]);
        setChannels([]);
        setRules([]);
        setLoadError(err.message || "Could not load notification channels");
      });
  }, []);

  useEffect(load, [load]);

  const channelsById = useMemo(() => {
    const map = {};
    for (const c of channels || []) map[c.id] = c;
    return map;
  }, [channels]);

  const test = async (channel) => {
    setTesting(channel.id);
    try {
      const result = await api.testInstallation(channel.id);
      pushToast(
        result.detail || (result.ok ? "Connection works" : "Connection failed"),
        result.ok ? "green" : "red",
      );
      load();
    } catch (e) {
      pushToast(e.message || "Test failed", "red");
    } finally {
      setTesting(null);
    }
  };

  const toggle = async (channel) => {
    try {
      await (channel.enabled
        ? api.disableInstallation(channel.id)
        : api.enableInstallation(channel.id));
      load();
    } catch (e) {
      pushToast(e.message || "Could not change this channel", "red");
    }
  };

  return (
    <div className="animate-fade-up">
      <PageHeader
        title="Notifications"
        subtitle="Send job, workflow, agent and alert events to Slack, Teams, Outlook, Gmail, GitHub or any webhook. Credentials are stored encrypted and never shown again."
        actions={
          <SmallButton icon="refresh" onClick={load}>
            Refresh
          </SmallButton>
        }
      />

      {loadError && (
        <p className="mb-4 rounded-lg bg-red-50 px-3 py-2 text-sm font-medium text-red-700">
          {loadError}
        </p>
      )}

      {/* ---------------- channels ---------------- */}
      <h2 className="mb-2 text-sm font-semibold text-slate-900">Channels</h2>
      {channels === null && !loadError ? (
        <div className="grid gap-3 sm:grid-cols-2">
          <Skeleton className="h-32" />
          <Skeleton className="h-32" />
        </div>
      ) : channels.length === 0 ? (
        <Card className="mb-6 text-center">
          <p className="text-sm font-medium text-slate-700">No channels yet</p>
          <p className="mx-auto mt-1 max-w-md text-sm text-slate-500">
            Add one below, then create a rule telling AutoOps which events to
            send through it. Nothing is sent until both exist.
          </p>
        </Card>
      ) : (
        <div className="mb-6 grid auto-rows-fr gap-3 sm:grid-cols-2">
          {channels.map((channel) => {
            const status = channelStatus(channel);
            return (
              // Green edge on a channel the provider accepted on a real send.
              // Deliberately tied to the same `verified` the badge reads, so a
              // parked or disabled channel keeps the plain border however well
              // its last test went.
              //
              // `!` is load-bearing — see the note on the same border in
              // AiProviders: without it Card's grey wins the cascade.
              <Card
                key={channel.id}
                className={`flex h-full flex-col p-4 ${
                  status.verified ? "!border-emerald-300 !bg-emerald-50/30" : ""
                }`}
              >
                <div className="flex items-start justify-between gap-2">
                  <div className="flex min-w-0 items-start gap-3">
                    <BrandLogo
                      src={channelLogo(channel.pluginKey)}
                      alt={`${channel.pluginName} logo`}
                      fallbackIcon="bell"
                    />
                    <div className="min-w-0">
                      <p className="truncate text-sm font-semibold text-slate-900">
                        {channel.displayName}
                      </p>
                      <p className="mt-0.5 text-xs text-slate-500">
                        {channel.pluginName}
                      </p>
                    </div>
                  </div>
                  <span
                    className={`shrink-0 rounded-full px-2 py-0.5 text-[11px] font-semibold ${status.tone}`}
                  >
                    {status.label}
                  </span>
                </div>

                {channel.parked && (
                  <p className="mt-2 rounded-lg bg-red-50 px-2.5 py-1.5 text-xs text-red-700">
                    Delivery stopped after {channel.consecutiveFailures}{" "}
                    failures in a row. Fix the settings and run Test to switch
                    it back on.
                  </p>
                )}
                {channel.lastTestDetail && !channel.parked && (
                  <p className="mt-2 line-clamp-2 text-xs text-slate-500">
                    {channel.lastTestDetail}
                  </p>
                )}

                <p className="mb-3 mt-2 text-xs text-slate-400">
                  {channel.ruleCount === 0
                    ? "No rules — this channel receives nothing yet"
                    : `${channel.ruleCount} rule${channel.ruleCount === 1 ? "" : "s"}`}
                </p>

                {/* mt-auto: a parked channel's warning is three lines and a
                    healthy one's is none, so without this the action rows sit
                    at different heights across the grid. */}
                <div className="mt-auto flex flex-wrap items-center gap-2 border-t border-slate-100 pt-3">
                  <SmallButton
                    icon="check"
                    onClick={() => test(channel)}
                    disabled={!canAdmin || testing === channel.id}
                  >
                    {testing === channel.id ? "Testing…" : "Test"}
                  </SmallButton>
                  <SmallButton
                    icon="pencil"
                    onClick={() => setEditing(channel)}
                    disabled={!canAdmin}
                  >
                    Edit
                  </SmallButton>
                  <SmallButton
                    icon={channel.enabled ? "stop" : "play"}
                    onClick={() => toggle(channel)}
                    disabled={!canAdmin}
                  >
                    {channel.enabled ? "Disable" : "Enable"}
                  </SmallButton>
                  <button
                    type="button"
                    onClick={() => setRemoving(channel)}
                    disabled={!canAdmin}
                    className="rounded-lg px-2.5 py-2 text-sm font-semibold text-slate-500 transition hover:text-red-600 disabled:opacity-40"
                  >
                    Remove
                  </button>
                </div>
              </Card>
            );
          })}
        </div>
      )}

      {/* ---------------- catalog ---------------- */}
      <h2 className="mb-2 text-sm font-semibold text-slate-900">
        Add a channel
      </h2>
      {/* auto-rows-fr + h-full: one summary runs to two lines and another to
          one, which used to leave the Add buttons on different lines within
          the same row. Now every tile is the same height and they line up. */}
      <div className="mb-6 grid auto-rows-fr gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {(catalog || []).map((plugin) => (
          <Card key={plugin.key} className="flex h-full flex-col p-4">
            <div className="flex items-center gap-3">
              <BrandLogo
                src={channelLogo(plugin.key)}
                alt={`${plugin.displayName} logo`}
                fallbackIcon="bell"
              />
              <p className="min-w-0 flex-1 truncate text-sm font-semibold text-slate-900">
                {plugin.displayName}
              </p>
              <Chip>{CATEGORY_LABEL[plugin.category] || plugin.category}</Chip>
            </div>
            {/* mb-3 as well as the footer's mt-auto: on the ONE tallest tile
                mt-auto collapses to zero, and the summary would touch the
                divider. */}
            <p className="mb-3 mt-2 text-xs leading-relaxed text-slate-500">
              {plugin.summary}
            </p>
            <div className="mt-auto flex items-center justify-between border-t border-slate-100 pt-3">
              <span className="text-xs text-slate-400">
                {plugin.installedCount > 0
                  ? `${plugin.installedCount} configured`
                  : "Not configured"}
              </span>
              <SmallButton
                icon="plus"
                onClick={() => setInstalling(plugin)}
                disabled={!canAdmin}
              >
                Add
              </SmallButton>
            </div>
          </Card>
        ))}
      </div>

      {/* ---------------- rules ---------------- */}
      <div className="mb-2 flex items-center justify-between">
        <h2 className="text-sm font-semibold text-slate-900">Rules</h2>
        <SmallButton
          icon="plus"
          onClick={() => setAddingRule(true)}
          disabled={!canAdmin || (channels || []).length === 0}
        >
          New rule
        </SmallButton>
      </div>
      {rules === null && !loadError ? (
        <Skeleton className="mb-6 h-24" />
      ) : rules.length === 0 ? (
        <Card className="mb-6 text-center">
          <p className="text-sm font-medium text-slate-700">No rules yet</p>
          <p className="mx-auto mt-1 max-w-md text-sm text-slate-500">
            A rule connects events to a channel. Without one, nothing is sent —
            even to a channel that tests green.
          </p>
        </Card>
      ) : (
        <Card className="mb-6 overflow-x-auto p-0">
          <table className="w-full min-w-[640px] text-left text-sm">
            <thead className="border-b border-slate-100 text-xs font-semibold text-slate-500">
              <tr>
                <th className="px-4 py-2.5">Channel</th>
                <th className="px-4 py-2.5">Watches</th>
                <th className="px-4 py-2.5">Scope</th>
                <th className="px-4 py-2.5">Events</th>
                <th className="px-4 py-2.5" />
              </tr>
            </thead>
            <tbody>
              {rules.map((rule) => (
                <tr
                  key={rule.id}
                  className="border-b border-slate-50 last:border-0"
                >
                  <td className="px-4 py-2.5 font-medium text-slate-900">
                    {rule.installationName}
                  </td>
                  <td className="px-4 py-2.5 text-slate-600">
                    {TARGET_PLURAL[rule.targetType] || rule.targetType}
                  </td>
                  <td className="px-4 py-2.5 text-slate-600">
                    {scopeLabel(rule)}
                  </td>
                  <td className="px-4 py-2.5">
                    <div className="flex flex-wrap gap-1">
                      {rule.events.map((e) => (
                        <span
                          key={e}
                          className="rounded-full bg-slate-100 px-2 py-0.5 text-[11px] font-semibold text-slate-600"
                        >
                          {e}
                        </span>
                      ))}
                    </div>
                  </td>
                  <td className="px-4 py-2.5 text-right">
                    <button
                      type="button"
                      onClick={() => setRemovingRule(rule)}
                      disabled={!canAdmin}
                      className="text-xs font-semibold text-slate-400 transition hover:text-red-600 disabled:opacity-40"
                    >
                      Delete
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}

      {/* ---------------- delivery log ---------------- */}
      <h2 className="mb-2 text-sm font-semibold text-slate-900">
        Recent deliveries
      </h2>
      {deliveries.length === 0 ? (
        <Card className="text-center">
          <p className="text-sm text-slate-500">
            Nothing delivered yet. Every attempt is recorded here — successes
            included — so a missing alert can be traced.
          </p>
        </Card>
      ) : (
        <Card className="overflow-x-auto p-0">
          <table className="w-full min-w-[640px] text-left text-sm">
            <thead className="border-b border-slate-100 text-xs font-semibold text-slate-500">
              <tr>
                <th className="px-4 py-2.5">When</th>
                <th className="px-4 py-2.5">Channel</th>
                <th className="px-4 py-2.5">Event</th>
                <th className="px-4 py-2.5">Target</th>
                <th className="px-4 py-2.5">Result</th>
              </tr>
            </thead>
            <tbody>
              {deliveries.map((row) => (
                <tr
                  key={row.id}
                  className="border-b border-slate-50 last:border-0"
                >
                  <td className="whitespace-nowrap px-4 py-2.5 text-xs text-slate-500">
                    {new Date(row.attemptedAt).toLocaleString()}
                  </td>
                  <td className="px-4 py-2.5 text-slate-600">
                    {channelsById[row.installationId]?.displayName ||
                      row.pluginKey}
                  </td>
                  <td className="px-4 py-2.5 text-slate-600">
                    {row.connectionTest ? "Connection test" : row.event}
                  </td>
                  <td className="px-4 py-2.5 text-slate-600">
                    {row.targetName || "—"}
                  </td>
                  <td className="px-4 py-2.5">
                    <span
                      className={`rounded-full px-2 py-0.5 text-[11px] font-semibold ${
                        row.ok
                          ? "bg-emerald-50 text-emerald-700"
                          : "bg-red-50 text-red-700"
                      }`}
                      title={row.detail || ""}
                    >
                      {row.ok ? "Delivered" : "Failed"}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}

      {installing && (
        <ChannelModal
          plugin={installing}
          onClose={() => setInstalling(null)}
          onSaved={() => {
            setInstalling(null);
            load();
          }}
        />
      )}

      {editing && (
        <ChannelModal
          plugin={(catalog || []).find((p) => p.key === editing.pluginKey)}
          existing={editing}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            load();
          }}
        />
      )}

      {addingRule && (
        <RuleModal
          channels={channels || []}
          events={events}
          targetTypes={targetTypes}
          onClose={() => setAddingRule(false)}
          onSaved={() => {
            setAddingRule(false);
            load();
          }}
        />
      )}

      {removing && (
        <ConfirmModal
          title={`Remove ${removing.displayName}?`}
          message="Its rules are deleted with it and those events stop being sent. The delivery log is kept."
          confirmLabel="Remove"
          onCancel={() => setRemoving(null)}
          onConfirm={async () => {
            try {
              await api.removeInstallation(removing.id);
              pushToast("Channel removed", "green");
            } catch (e) {
              pushToast(e.message || "Could not remove it", "red");
            } finally {
              setRemoving(null);
              load();
            }
          }}
        />
      )}

      {removingRule && (
        <ConfirmModal
          title="Delete this rule?"
          message="These events stop reaching that channel. The channel itself stays."
          confirmLabel="Delete"
          onCancel={() => setRemovingRule(null)}
          onConfirm={async () => {
            try {
              await api.removeNotificationRule(removingRule.id);
              pushToast("Rule deleted", "green");
            } catch (e) {
              pushToast(e.message || "Could not delete it", "red");
            } finally {
              setRemovingRule(null);
              load();
            }
          }}
        />
      )}
    </div>
  );
}

/**
 * Install/edit form, generated entirely from the plugin's field spec.
 *
 * On edit, secret fields start blank and are only sent when the user types
 * something — that is what lets the backend keep the stored value, since it
 * never handed the real one to this browser to resubmit.
 */
function ChannelModal({ plugin, existing, onClose, onSaved }) {
  const { pushToast } = useStore();
  const fields = plugin?.fields || [];
  const [displayName, setDisplayName] = useState(existing?.displayName || "");
  const [values, setValues] = useState(() =>
    Object.fromEntries(
      fields.map((f) => [
        f.name,
        // Non-secrets round-trip so an edit does not silently clear them.
        f.type === "SECRET" ? "" : (existing?.config?.[f.name] ?? ""),
      ]),
    ),
  );
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  if (!plugin) return null;

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const config = {};
      for (const f of fields) {
        const v = (values[f.name] ?? "").trim();
        if (v) config[f.name] = v;
      }
      if (existing) {
        await api.updateInstallation(existing.id, {
          pluginKey: plugin.key,
          displayName: displayName.trim(),
          config,
        });
        pushToast("Channel updated — run Test to verify it", "green");
      } else {
        await api.installPlugin({
          pluginKey: plugin.key,
          displayName: displayName.trim(),
          config,
        });
        pushToast(
          `${plugin.displayName} added — run Test to verify it`,
          "green",
        );
      }
      onSaved();
    } catch (err) {
      setError(err.message || "Could not save this channel");
    } finally {
      setBusy(false);
    }
  };

  return (
    <ModalPortal layerClass="z-[95] items-center p-4" onClose={onClose}>
      <form
        onSubmit={submit}
        className="relative flex max-h-[90vh] w-full max-w-md flex-col overflow-hidden rounded-xl bg-white shadow-xl ring-1 ring-slate-200/80 animate-fade-up"
      >
        <div className="border-b border-slate-100 bg-gradient-to-br from-slate-50 to-white px-4 py-3.5">
          <h3 className="text-[15px] font-semibold leading-snug text-slate-900">
            {existing
              ? `Edit ${existing.displayName}`
              : `Add ${plugin.displayName}`}
          </h3>
          <p className="mt-0.5 text-xs text-slate-500">
            Stored encrypted by AutoOps and never returned to this browser.
            {existing && " Leave a secret blank to keep the saved value."}
          </p>
        </div>

        <div className="space-y-3.5 overflow-y-auto px-4 py-4">
          <div>
            <label className="mb-1.5 flex items-center gap-1.5 text-xs font-semibold text-slate-700">
              Name <span className="text-red-500">*</span>
            </label>
            <input
              type="text"
              value={displayName}
              placeholder="Ops alerts"
              onChange={(e) => setDisplayName(e.target.value)}
              className="w-full rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-900 outline-none transition focus:border-slate-300 focus:ring-2 focus:ring-slate-300"
            />
            <p className="mt-1 text-[11px] text-slate-400">
              How this channel appears in rules. Must be unique in this
              workspace.
            </p>
          </div>

          {fields.map((f) => (
            <div key={f.name}>
              <label className="mb-1.5 flex items-center gap-1.5 text-xs font-semibold text-slate-700">
                {f.label}
                {f.required && <span className="text-red-500">*</span>}
              </label>
              <input
                type={f.type === "SECRET" ? "password" : "text"}
                value={values[f.name] ?? ""}
                placeholder={
                  existing && f.type === "SECRET" && existing.config?.[f.name]
                    ? "Saved — type to replace"
                    : f.placeholder || ""
                }
                autoComplete="off"
                onChange={(e) =>
                  setValues((v) => ({ ...v, [f.name]: e.target.value }))
                }
                className="w-full rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-900 outline-none transition focus:border-slate-300 focus:ring-2 focus:ring-slate-300"
              />
              {f.help && (
                <p className="mt-1 text-[11px] text-slate-400">{f.help}</p>
              )}
            </div>
          ))}

          {plugin.setupUrl && (
            <a
              href={plugin.setupUrl}
              target="_blank"
              rel="noreferrer"
              className="inline-flex items-center gap-1 text-xs font-semibold text-blue-600 hover:underline"
            >
              Where do I get this? <Icon name="arrow-right" size={12} />
            </a>
          )}

          {error && (
            <p className="rounded-lg bg-red-50 px-3 py-2 text-xs font-medium text-red-700">
              {error}
            </p>
          )}
        </div>

        <div className="flex items-center justify-end gap-2 border-t border-slate-100 px-4 py-3">
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
            disabled={busy}
          >
            {busy ? "Saving…" : "Save"}
          </SmallButton>
        </div>
      </form>
    </ModalPortal>
  );
}

/** Which console resource backs each kind of target. */
const TARGET_RESOURCE = { JOB: "jobs", WORKFLOW: "workflows", AGENT: "agents" };

/**
 * Builds a rule: which events, for what, through which channel.
 *
 * <h2>Not every event fits every target</h2>
 * An alert never stalls and a job never parks on an approval. The server says
 * which is which — each event carries an `appliesTo` list — and this form only
 * offers the ones that can actually happen. Offering the rest would let someone
 * save a rule that never fires, which reads as a broken channel rather than as
 * a rule they mis-wrote. The server refuses them too; this is so nobody has to
 * find out that way.
 */
function RuleModal({ channels, events, targetTypes, onClose, onSaved }) {
  const { pushToast } = useStore();
  const [installationId, setInstallationId] = useState(channels[0]?.id ?? "");
  const [targetType, setTargetType] = useState("JOB");
  const [scope, setScope] = useState("ALL");
  const [projectId, setProjectId] = useState("");
  const [targetId, setTargetId] = useState("");
  const [selected, setSelected] = useState(() => new Set());
  const [minSeverity, setMinSeverity] = useState("");
  const [projects, setProjects] = useState([]);
  const [targets, setTargets] = useState([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  const applicable = events.filter(
    (e) => !e.appliesTo || e.appliesTo.includes(targetType),
  );
  const identifiesTargets =
    targetTypes.find((t) => t.value === targetType)?.identifiesTargets ?? true;

  useEffect(() => {
    api
      .listProjects()
      .then(setProjects)
      .catch(() => setProjects([]));
  }, []);

  // Reset the selection whenever the kind of target changes, rather than
  // filtering it. Keeping the overlap would silently drop the events someone
  // had already ticked and leave a rule narrower than the one they thought
  // they were writing.
  useEffect(() => {
    const defaults = events
      .filter((e) => !e.appliesTo || e.appliesTo.includes(targetType))
      // What a person almost always wants to hear about: something broke, or
      // something is blocked waiting on them.
      .filter((e) =>
        ["FAILED", "MISSED", "AWAITING_APPROVAL", "TRIGGERED"].includes(
          e.value,
        ),
      )
      .map((e) => e.value);
    setSelected(new Set(defaults));
    // An alert rule cannot name one alert — there is no id to name.
    if (!identifiesTargets) {
      setScope((current) => (current === "TARGET" ? "ALL" : current));
      setTargetId("");
    }
  }, [targetType, events, identifiesTargets]);

  // Targets depend on both the project and which kind we are watching.
  useEffect(() => {
    if (scope !== "TARGET" || !projectId || !TARGET_RESOURCE[targetType]) {
      setTargets([]);
      return;
    }
    api
      .list(TARGET_RESOURCE[targetType], projectId)
      .then((rows) => setTargets(rows || []))
      .catch(() => setTargets([]));
    setTargetId("");
  }, [scope, projectId, targetType]);

  const toggleEvent = (value) =>
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(value)) next.delete(value);
      else next.add(value);
      return next;
    });

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.createNotificationRule({
        installationId: Number(installationId),
        targetType,
        // Scope widens as these go null; the backend drops projectId when a
        // specific target is named, so send only what this scope means.
        targetId: scope === "TARGET" && targetId ? Number(targetId) : null,
        projectId: scope === "PROJECT" && projectId ? Number(projectId) : null,
        events: [...selected],
        // Empty means no floor at all, which is what every rule written before
        // this control existed means. Sent as null rather than "" so it cannot
        // be read as a severity nobody chose.
        minSeverity: minSeverity || null,
      });
      pushToast("Rule created", "green");
      onSaved();
    } catch (err) {
      setError(err.message || "Could not create this rule");
    } finally {
      setBusy(false);
    }
  };

  const selectClass =
    "w-full rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-900 outline-none transition focus:border-slate-300 focus:ring-2 focus:ring-slate-300";

  return (
    <ModalPortal layerClass="z-[95] items-center p-4" onClose={onClose}>
      <form
        onSubmit={submit}
        className="relative flex max-h-[90vh] w-full max-w-lg flex-col overflow-hidden rounded-xl bg-white shadow-xl ring-1 ring-slate-200/80 animate-fade-up"
      >
        <div className="border-b border-slate-100 bg-gradient-to-br from-slate-50 to-white px-4 py-3.5">
          <h3 className="text-[15px] font-semibold leading-snug text-slate-900">
            New notification rule
          </h3>
          <p className="mt-0.5 text-xs text-slate-500">
            Choose what to watch and which events are worth interrupting someone
            for.
          </p>
        </div>

        <div className="space-y-3.5 overflow-y-auto px-4 py-4">
          <div>
            <label className="mb-1.5 block text-xs font-semibold text-slate-700">
              Send through
            </label>
            <select
              value={installationId}
              onChange={(e) => setInstallationId(e.target.value)}
              className={selectClass}
            >
              {channels.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.displayName} ({c.pluginName})
                </option>
              ))}
            </select>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="mb-1.5 block text-xs font-semibold text-slate-700">
                Watch
              </label>
              <select
                value={targetType}
                aria-label="Watch"
                onChange={(e) => setTargetType(e.target.value)}
                className={selectClass}
              >
                {targetTypes.map((t) => (
                  <option key={t.value} value={t.value}>
                    {t.label}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label className="mb-1.5 block text-xs font-semibold text-slate-700">
                Scope
              </label>
              <select
                value={scope}
                aria-label="Scope"
                onChange={(e) => setScope(e.target.value)}
                className={selectClass}
              >
                <option value="ALL">Whole workspace</option>
                <option value="PROJECT">One project</option>
                {/* Absent for alerts. An alert is identified by a fingerprint
                    its monitoring tool chose, not by an id a customer could
                    pick from a list, so there is nothing to offer here. */}
                {identifiesTargets && (
                  <option value="TARGET">One {singular(targetType)}</option>
                )}
              </select>
            </div>
          </div>

          {scope === "ALL" && (
            <p className="rounded-lg bg-slate-50 px-3 py-2 text-[11px] text-slate-500">
              Covers everything in the workspace, including{" "}
              {(TARGET_PLURAL[targetType] || targetType).toLowerCase()} added
              later. A rule written per-target never covers the one someone adds
              next week.
            </p>
          )}

          {scope !== "ALL" && (
            <div>
              <label className="mb-1.5 block text-xs font-semibold text-slate-700">
                Project
              </label>
              <select
                value={projectId}
                onChange={(e) => setProjectId(e.target.value)}
                className={selectClass}
              >
                <option value="">Select a project…</option>
                {projects.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name}
                  </option>
                ))}
              </select>
            </div>
          )}

          {scope === "TARGET" && (
            <div>
              <label className="mb-1.5 block text-xs font-semibold text-slate-700">
                {singular(targetType).replace(/^./, (c) => c.toUpperCase())}
              </label>
              <select
                value={targetId}
                onChange={(e) => setTargetId(e.target.value)}
                disabled={!projectId}
                className={selectClass}
              >
                <option value="">
                  {projectId ? "Select…" : "Choose a project first"}
                </option>
                {targets.map((t) => (
                  <option key={t.id} value={t.id}>
                    {t.name}
                  </option>
                ))}
              </select>
              {projectId && targets.length === 0 && (
                <p className="mt-1 text-[11px] text-slate-400">
                  This project has no{" "}
                  {(TARGET_PLURAL[targetType] || targetType).toLowerCase()} yet.
                </p>
              )}
            </div>
          )}

          <div>
            <label className="mb-1.5 block text-xs font-semibold text-slate-700">
              Notify me when
            </label>
            <div className="space-y-1.5">
              {applicable.map((event) => (
                <label
                  key={event.value}
                  className="flex cursor-pointer items-start gap-2.5 rounded-lg px-2 py-1.5 transition hover:bg-slate-50"
                >
                  <input
                    type="checkbox"
                    checked={selected.has(event.value)}
                    onChange={() => toggleEvent(event.value)}
                    className="mt-0.5 h-4 w-4 rounded border-slate-300"
                  />
                  <span className="min-w-0">
                    <span className="flex items-center gap-1.5">
                      <span className="text-sm font-medium text-slate-800">
                        {event.label}
                      </span>
                      <span
                        className={`rounded-full px-1.5 py-0.5 text-[10px] font-semibold ${
                          SEVERITY_TONE[event.severity] || SEVERITY_TONE.INFO
                        }`}
                      >
                        {event.severity}
                      </span>
                    </span>
                    <span className="block text-[11px] leading-relaxed text-slate-500">
                      {event.description}
                    </span>
                  </span>
                </label>
              ))}
              {applicable.length === 0 && (
                <p className="text-xs text-slate-400">
                  {events.length === 0
                    ? "The event list could not be loaded."
                    : `Nothing to watch for ${(TARGET_PLURAL[targetType] || targetType).toLowerCase()} yet.`}
                </p>
              )}
            </div>
          </div>

          {/*
            Matters for alerts and barely at all for runs. A workspace takes
            hundreds of alerts a day, most of them informational — a rule that
            cannot say "critical only" is a rule nobody can afford to leave on,
            and a channel people mute is worse than one they never had.
          */}
          {targetType === "ALERT" && (
            <div>
              <label className="mb-1.5 block text-xs font-semibold text-slate-700">
                Only if at least
              </label>
              <select
                value={minSeverity}
                aria-label="Only if at least"
                onChange={(e) => setMinSeverity(e.target.value)}
                className={selectClass}
              >
                <option value="">Any severity</option>
                <option value="WARNING">Warning</option>
                <option value="CRITICAL">Critical</option>
              </select>
              <p className="mt-1 text-[11px] leading-relaxed text-slate-400">
                Uses the severity your monitoring tool set on the alert, not
                AutoOps&rsquo;s opinion of it.
              </p>
            </div>
          )}

          {error && (
            <p className="rounded-lg bg-red-50 px-3 py-2 text-xs font-medium text-red-700">
              {error}
            </p>
          )}
        </div>

        <div className="flex items-center justify-end gap-2 border-t border-slate-100 px-4 py-3">
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
            disabled={busy || selected.size === 0 || !installationId}
          >
            {busy ? "Creating…" : "Create rule"}
          </SmallButton>
        </div>
      </form>
    </ModalPortal>
  );
}
