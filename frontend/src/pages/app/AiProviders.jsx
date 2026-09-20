/**
 * AI providers — this workspace's own model vendor keys (bring-your-own-key).
 *
 * The body of the Models screen. Keys belong to this workspace, are stored
 * AES-GCM encrypted by core-service, and are never returned to the browser —
 * so a configured vendor shows a status, never its secret.
 *
 * There used to be a sibling screen driving a shared vendor workspace. It was
 * removed: the engine behind workflows is not something a tenant
 * configures, and its workspace token never belongs in a browser.
 *
 * "Test" makes a REAL call against the vendor. A provider that has never been
 * tested says exactly that rather than showing a green tick it did not earn.
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
import { vendorLogo } from "../../lib/brandLogos";
import { useStore } from "../../store/store";

/** Chat first — it is what an agent uses; the rest in decreasing usefulness. */
const PURPOSE_ORDER = ["CHAT", "EMBEDDING", "RERANK", "IMAGE", "AUDIO", "VIDEO"];
const PURPOSE_LABEL = {
  CHAT: "chat",
  EMBEDDING: "embedding",
  RERANK: "rerank",
  IMAGE: "image",
  AUDIO: "audio",
  VIDEO: "video",
};

/**
 * The first connection's model entry for a vendor, for the preview dialog
 * opened from the card. With several connections the card is a summary and
 * the per-connection lists live in the manage dialog — this is only ever the
 * "what does this vendor serve" glance.
 */
function firstEntry(byKind, modelsByProvider, spec) {
  const first = (byKind[spec.kind] || [])[0];
  return first ? modelsByProvider[first.id] : null;
}

/**
 * Verified > configured-but-unproven > failed > not configured.
 *
 * `verified` drives the card's green border as well as the badge, from this
 * one place — a border that could disagree with the badge beside it would be
 * the same fake green light the rest of this screen avoids.
 */
function statusOf(provider) {
  if (!provider)
    return { label: "Not configured", tone: "bg-slate-100 text-slate-500", verified: false };
  if (provider.lastTestOk === true)
    return { label: "Verified", tone: "bg-emerald-50 text-emerald-700", verified: true };
  if (provider.lastTestOk === false)
    return { label: "Failed", tone: "bg-red-50 text-red-700", verified: false };
  return { label: "Not tested", tone: "bg-amber-50 text-amber-700", verified: false };
}

/**
 * @param embedded true when mounted as a tab inside Models.jsx, which already
 *   owns the page header — so this renders its body only.
 */
export default function AiProviders({ embedded = false }) {
  const { pushToast, can } = useStore();
  const canAdmin = can("manageKeys");

  const [catalog, setCatalog] = useState(null);
  const [providers, setProviders] = useState(null);
  const [workspaceModels, setWorkspaceModels] = useState([]);
  const [loadError, setLoadError] = useState(null);
  const [editing, setEditing] = useState(null);
  const [removing, setRemoving] = useState(null);
  const [browsing, setBrowsing] = useState(null);
  const [managing, setManaging] = useState(null);
  const [testing, setTesting] = useState(null);
  const [refreshing, setRefreshing] = useState(false);

  const load = useCallback(() => {
    setLoadError(null);
    Promise.all([
      api.modelProviderCatalog(),
      api.listModelProviders(),
      // Reported by the vendor on the last successful test, cached server-side.
      api.listWorkspaceModels().catch(() => []),
    ])
      .then(([c, p, m]) => {
        setCatalog(c);
        setProviders(p);
        setWorkspaceModels(m);
      })
      .catch((e) => {
        // Honest failure: no fabricated vendor list behind an error.
        setCatalog([]);
        setProviders([]);
        setLoadError(e.message || "Could not load providers");
      });
  }, []);

  useEffect(load, [load]);

  // A vendor can hold SEVERAL connections now — production and sandbox keys,
  // one per cost centre — so this is a list per kind, not a single row.
  const byKind = useMemo(() => {
    const map = {};
    for (const p of providers || []) (map[p.kind] ||= []).push(p);
    return map;
  }, [providers]);

  // Keyed by connection, not by vendor: two Azure connections have two
  // different model lists, and merging them would attribute one key's
  // deployments to the other.
  const modelsByProvider = useMemo(() => {
    const map = {};
    for (const entry of workspaceModels) map[entry.providerId] = entry;
    return map;
  }, [workspaceModels]);

  const configuredCount = (providers || []).length;
  const verifiedCount = (providers || []).filter((p) => p.lastTestOk === true).length;

  /**
   * @param quiet suppress the success toast. Set straight after a save, where
   *              the dialog has already reported the vendor's answer from the
   *              preflight — this run is what stores the badge and caches the
   *              model list, not news. A failure still speaks: disagreeing
   *              with the preflight is exactly what the operator must hear.
   */
  const test = async (provider, quiet = false) => {
    setTesting(provider.id);
    try {
      const result = await api.testModelProvider(provider.id);
      if (!result.ok || !quiet) {
        pushToast(result.message, result.ok ? "green" : "red");
      }
      load();
    } catch (e) {
      pushToast(e.message || "Test failed", "red");
    } finally {
      setTesting(null);
    }
  };

  /**
   * Re-reads every connection's model list. The vendor's list endpoint IS the
   * credential check, so this doubles as a health sweep — which is why the
   * result is reported as a count rather than a flat "done".
   */
  const refreshAll = async () => {
    setRefreshing(true);
    try {
      const result = await api.refreshAllModelProviders();
      pushToast(
        result.total === 0
          ? "No connections to refresh yet"
          : `${result.refreshed} of ${result.total} connection(s) refreshed`,
        result.refreshed === result.total ? "green" : "red",
      );
      load();
    } catch (e) {
      pushToast(e.message || "Could not refresh", "red");
    } finally {
      setRefreshing(false);
    }
  };

  return (
    <div className={embedded ? "" : "animate-fade-up"}>
      {!embedded && (
        <PageHeader
          title="AI Providers"
          subtitle="Connect the AI vendors your team already pays for. Keys are stored encrypted and never shown again."
          actions={
            <SmallButton icon="refresh" onClick={load}>
              Refresh
            </SmallButton>
          }
        />
      )}

      {loadError && (
        <p className="mb-4 rounded-lg bg-red-50 px-3 py-2 text-sm font-medium text-red-700">
          {loadError}
        </p>
      )}

      {catalog !== null && !loadError && (
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
          <p className="text-sm text-slate-500">
            {configuredCount === 0
              ? "No providers connected yet — add a key to make models available to your agents."
              : `${configuredCount} connection${configuredCount === 1 ? "" : "s"} · ${verifiedCount} verified`}
          </p>
          <div className="flex items-center gap-2">
            {/* Two different things, so two buttons: one re-reads this page,
                the other goes back to every vendor for its current models. */}
            {configuredCount > 0 && (
              <SmallButton
                icon="refresh"
                onClick={refreshAll}
                disabled={!canAdmin || refreshing}
              >
                {refreshing ? "Refreshing…" : "Refresh models"}
              </SmallButton>
            )}
            {/* Embedded, the page header (and its Reload) belongs to Models.jsx. */}
            {embedded && (
              <SmallButton icon="refresh" onClick={load}>
                Reload
              </SmallButton>
            )}
          </div>
        </div>
      )}

      {catalog === null && <Skeleton className="h-40 w-full" />}

      {catalog !== null && (
        // auto-rows-fr: every row is sized to the tallest card in the WHOLE
        // grid, not just its own row, so all eleven cards match. Doing it in
        // the grid rather than with a min-height on the card means nothing
        // clips when a vendor's note or field list grows.
        <div className="grid auto-rows-fr gap-3 md:grid-cols-2">
          {catalog.map((spec) => (
            <ProviderCard
              key={spec.kind}
              spec={spec}
              connections={byKind[spec.kind] || []}
              modelsByProvider={modelsByProvider}
              canAdmin={canAdmin}
              onAdd={() => setEditing({ spec, existing: null })}
              onManage={() => setManaging(spec)}
              onBrowseModels={() => setBrowsing(spec)}
            />
          ))}
        </div>
      )}

      {editing && (
        <CredentialsModal
          spec={editing.spec}
          existing={editing.existing}
          // Only a verified list is this key's own; otherwise the modal falls
          // back to the catalog and says so.
          models={
            modelsByProvider[editing.existing?.id]?.verified
              ? modelsByProvider[editing.existing.id].models || []
              : []
          }
          modelsByPurpose={
            modelsByProvider[editing.existing?.id]?.verified
              ? modelsByProvider[editing.existing.id].modelsByPurpose || {}
              : {}
          }
          onClose={() => setEditing(null)}
          onSaved={(saved) => {
            setEditing(null);
            // The dialog only saves a credential the vendor already accepted,
            // so this run is not the proof — it is what writes the verified
            // badge onto the row and caches the real model list the category
            // browser reads. Quiet on success; the dialog already said it.
            if (saved?.id) test(saved, true);
            else load();
          }}
        />
      )}

      {managing && (
        <ConnectionsModal
          spec={managing}
          connections={byKind[managing.kind] || []}
          modelsByProvider={modelsByProvider}
          canAdmin={canAdmin}
          testing={testing}
          onTest={test}
          onAdd={() => {
            setManaging(null);
            setEditing({ spec: managing, existing: null });
          }}
          onReplaceKey={(connection) => {
            setManaging(null);
            setEditing({ spec: managing, existing: connection });
          }}
          onRemove={(connection) => {
            setManaging(null);
            setRemoving(connection);
          }}
          onChanged={load}
          onClose={() => setManaging(null)}
        />
      )}

      {browsing && (
        <ModelsModal
          spec={browsing}
          // An unconfigured vendor has no entry of its own, so it previews the
          // catalog's list — the dialog already labels an unverified one as
          // "common models for this vendor", which is exactly what it is.
          models={firstEntry(byKind, modelsByProvider, browsing)?.models
            || browsing.fallbackModels
            || []}
          modelsByPurpose={
            firstEntry(byKind, modelsByProvider, browsing)?.modelsByPurpose ||
            browsing.fallbackModelsByPurpose ||
            {}
          }
          verified={firstEntry(byKind, modelsByProvider, browsing)?.verified === true}
          onClose={() => setBrowsing(null)}
        />
      )}

      <ConfirmModal
        open={!!removing}
        title="Remove this provider?"
        message={
          removing
            ? `The stored key for ${removing.name} is deleted. Agents using its models stop working until you add a key again.`
            : ""
        }
        confirmLabel="Remove"
        onClose={() => setRemoving(null)}
        onConfirm={async () => {
          const target = removing;
          setRemoving(null);
          try {
            await api.removeModelProvider(target.id);
            pushToast(`${target.name} removed`, "green");
            load();
          } catch (e) {
            pushToast(e.message || "Could not remove the provider", "red");
          }
        }}
      />
    </div>
  );
}

/**
 * One vendor, summarising however many connections the workspace holds to it.
 *
 * The card stays a summary on purpose. Per-connection detail — its own key,
 * its own test result, its own declared models — lives in the manage dialog,
 * because a vendor with four connections would otherwise be four times the
 * height of its neighbours in the grid.
 */
function ProviderCard({
  spec,
  connections,
  modelsByProvider,
  canAdmin,
  onAdd,
  onManage,
  onBrowseModels,
}) {
  const connected = connections.length > 0;
  // Worth naming on the card only when the name carries information: several
  // connections to tell apart, or one the operator deliberately renamed.
  const namedConnections =
    connections.length > 1
      ? connections
      : connections.filter((c) => c.name && c.name !== spec.displayName);
  // Verified if ANY connection is: the badge answers "can this workspace
  // reach the vendor", and one working key is enough for that to be true.
  const anyVerified = connections.some((c) => c.lastTestOk === true);
  const allFailed = connected && connections.every((c) => c.lastTestOk === false);
  const status = !connected
    ? statusOf(null)
    : anyVerified
      ? statusOf({ lastTestOk: true })
      : allFailed
        ? statusOf({ lastTestOk: false })
        : statusOf({});

  const entries = connections.map((c) => modelsByProvider[c.id]).filter(Boolean);
  // Deliberately NOT anyVerified. That says the key works; this says the list
  // below came from the vendor rather than from the catalog's guesses. A
  // scope-limited key can be accepted while listing stays unavailable, and
  // captioning the catalog's suggestions "available" would be a claim we did
  // not earn.
  const listFromVendor = entries.some((e) => e.verified);
  // Deduplicated across connections: two Azure keys on the same resource
  // report the same deployments, and counting them twice would be a lie.
  const shown = connected
    ? [...new Set(entries.flatMap((e) => e.models || []))]
    : spec.fallbackModels || [];
  const shownByPurpose = connected
    ? entries.reduce((acc, entry) => {
        for (const [purpose, ids] of Object.entries(entry.modelsByPurpose || {})) {
          acc[purpose] = [...new Set([...(acc[purpose] || []), ...ids])];
        }
        return acc;
      }, {})
    : spec.fallbackModelsByPurpose || {};

  // A vendor answers "list models" with everything the account can reach, and
  // most of it is not something an agent can talk to. Showing the split makes
  // that legible at a glance instead of hiding it behind a single count.
  const buckets = PURPOSE_ORDER.filter((p) => (shownByPurpose[p] || []).length > 0);
  const failed = allFailed;
  const browsable = !failed && shown.length > 0;

  let detail;
  if (failed) {
    // The reason beats the count: a rejected key is the one thing on this
    // card worth reading, and it is why there is no separate note row.
    detail = connections[0].lastTestNote || "The last test failed";
  } else if (shown.length === 0) {
    detail = "No models listed yet";
  } else if (!connected) {
    detail = `${shown.length} common model${shown.length === 1 ? "" : "s"}`;
  } else if (listFromVendor) {
    detail = `${shown.length} model${shown.length === 1 ? "" : "s"} available`;
  } else {
    // An untested key's list is the catalog's, not this vendor's — calling it
    // "available" would be a claim we have not earned.
    detail = `${shown.length} likely model${shown.length === 1 ? "" : "s"} — not verified`;
  }

  return (
    // h-full inside an auto-rows-fr grid: every card is the same height. The
    // content is then anchored at both ends — header at the top, buttons at
    // the bottom — so a row of cards reads as one row, not eleven offsets.
    //
    // The green edge is earned: it appears only once the vendor itself has
    // accepted the key on a real call, so the grid can be read at a glance
    // for "which of these actually work".
    //
    // `!` is load-bearing. Tailwind emits .border-emerald-300 BEFORE
    // .border-slate-200 (its colours sort alphabetically), so at equal
    // specificity Card's own border colour would win and the edge would
    // silently stay grey.
    <Card
      className={`flex h-full flex-col p-4 ${
        status.verified ? "!border-emerald-300 !bg-emerald-50/30" : ""
      }`}
    >
      {/* Kept to a single line so the logo, the vendor name and the status
          badge sit on one baseline in every card. */}
      <div className="flex items-center gap-3">
        <BrandLogo src={vendorLogo(spec.kind)} alt={`${spec.displayName} logo`} />
        <p className="min-w-0 flex-1 truncate text-sm font-semibold text-slate-900">
          {spec.displayName}
        </p>
        <span
          className={`shrink-0 rounded-full px-2 py-0.5 text-[10px] font-bold uppercase tracking-wide ${status.tone}`}
        >
          {status.label}
        </span>
      </div>

      {/* Connection names once there is a choice to make between them —
          otherwise the field list, which answers "what will this form ask
          me for". A lone connection carrying the vendor's own name is not a
          choice, and a chip repeating the title above it is just noise. */}
      <div className="mt-3 flex flex-wrap gap-1">
        {namedConnections.length > 0
          ? namedConnections.map((c) => <Chip key={c.id}>{c.name}</Chip>)
          : spec.fields.map((f) => <Chip key={f.key}>{f.label}</Chip>)}
      </div>

      {/* Pinned to the bottom, and ONE line whether the vendor returned 5
          models or 119 — the list itself opens in a dialog. Every card has
          this row, so they are all the same height and the buttons land on
          the same baseline with no block of dead space above them. */}
      <div className="mt-auto border-t border-slate-100 pt-3">
        {browsable ? (
          <button
            type="button"
            onClick={onBrowseModels}
            className="flex w-full items-baseline gap-2 text-left transition hover:opacity-70"
          >
            <span className="shrink-0 text-xs font-semibold text-slate-600">
              {detail}
            </span>
            {buckets.length > 0 && (
              <span className="min-w-0 flex-1 truncate text-[11px] text-slate-400">
                {buckets
                  .map((p) => `${shownByPurpose[p].length} ${PURPOSE_LABEL[p]}`)
                  .join(" · ")}
              </span>
            )}
            <Icon name="chevron" size={14} className="ml-auto shrink-0 text-slate-400" />
          </button>
        ) : (
          <p
            className={`truncate text-xs font-semibold ${
              failed ? "text-red-600" : "text-slate-400"
            }`}
            title={detail}
          >
            {detail}
          </p>
        )}

        <div className="mt-3 flex flex-wrap items-center gap-2">
          <SmallButton icon="key" onClick={onAdd} disabled={!canAdmin}>
            {connected ? "Add another key" : "Add key"}
          </SmallButton>
          {connected && (
            <SmallButton icon="gear" onClick={onManage} disabled={!canAdmin}>
              Manage
            </SmallButton>
          )}
        </div>
      </div>
    </Card>
  );
}

/** "4 minutes ago" — vague on purpose; the exact second is never the point. */
function ago(iso) {
  if (!iso) return null;
  const seconds = Math.round((Date.now() - new Date(iso).getTime()) / 1000);
  if (!Number.isFinite(seconds)) return null;
  if (seconds < 60) return "just now";
  const units = [
    ["minute", 60],
    ["hour", 3600],
    ["day", 86400],
    ["week", 604800],
  ];
  let label = "minute";
  let size = 60;
  for (const [unit, unitSeconds] of units) {
    if (seconds >= unitSeconds) {
      label = unit;
      size = unitSeconds;
    }
  }
  const n = Math.floor(seconds / size);
  return `${n} ${label}${n === 1 ? "" : "s"} ago`;
}

/**
 * Everything about one vendor's connections that will not fit on a card.
 *
 * Each connection is independent — its own credential, its own test outcome,
 * its own model list and its own declared models — so this is a list of
 * self-contained panels rather than one form. That is the whole reason the
 * one-row-per-vendor rule was dropped: a production and a sandbox key are not
 * two versions of one thing.
 */
function ConnectionsModal({
  spec,
  connections,
  modelsByProvider,
  canAdmin,
  testing,
  onTest,
  onAdd,
  onReplaceKey,
  onRemove,
  onChanged,
  onClose,
}) {
  return (
    <ModalPortal layerClass="z-[95] items-center p-4" onClose={onClose}>
      <div className="relative flex max-h-[88vh] w-full max-w-4xl flex-col overflow-hidden rounded-xl bg-white shadow-xl ring-1 ring-slate-200/80 animate-fade-up">
        <div className="flex items-start gap-3 border-b border-slate-100 bg-gradient-to-br from-slate-50 to-white px-4 py-3.5">
          <BrandLogo src={vendorLogo(spec.kind)} alt={`${spec.displayName} logo`} />
          <div className="min-w-0 flex-1">
            <h3 className="text-[15px] font-semibold leading-snug text-slate-900">
              {spec.displayName} connections
            </h3>
            <p className="mt-0.5 text-xs text-slate-500">
              {connections.length} connection{connections.length === 1 ? "" : "s"}.
              Each one has its own key, its own models and its own defaults.
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="shrink-0 rounded-lg p-1 text-slate-400 transition hover:text-slate-700"
          >
            <Icon name="x" size={16} />
          </button>
        </div>

        <div className="flex-1 space-y-3 overflow-y-auto px-4 py-4">
          {connections.map((connection) => (
            <ConnectionPanel
              key={connection.id}
              spec={spec}
              connection={connection}
              entry={modelsByProvider[connection.id]}
              canAdmin={canAdmin}
              testing={testing === connection.id}
              onTest={() => onTest(connection)}
              onReplaceKey={() => onReplaceKey(connection)}
              onRemove={() => onRemove(connection)}
              onChanged={onChanged}
            />
          ))}
        </div>

        <div className="flex items-center justify-between gap-2 border-t border-slate-100 px-4 py-3">
          <SmallButton icon="key" onClick={onAdd} disabled={!canAdmin}>
            Add another key
          </SmallButton>
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg px-3 py-2 text-sm font-semibold text-slate-500 transition hover:text-slate-800"
          >
            Done
          </button>
        </div>
      </div>
    </ModalPortal>
  );
}

/** One connection: its status, its defaults, and the models it declares. */
function ConnectionPanel({
  spec,
  connection,
  entry,
  canAdmin,
  testing,
  onTest,
  onReplaceKey,
  onRemove,
  onChanged,
}) {
  const { pushToast } = useStore();
  const status = statusOf(connection);
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);

  const models = entry?.models || [];
  const grouped = entry?.modelsByPurpose || spec.fallbackModelsByPurpose || {};
  const declared = entry?.declaredModels || [];
  const refreshedAt = ago(connection.modelsRefreshedAt);

  const [defaults, setDefaults] = useState({
    defaultModel: connection.defaultModel || "",
    defaultEmbeddingModel: connection.defaultEmbeddingModel || "",
    defaultRerankModel: connection.defaultRerankModel || "",
  });

  const choicesFor = (purpose, current) => {
    const seen = [];
    for (const m of [...(current ? [current] : []), ...(grouped[purpose] || [])]) {
      if (m && !seen.includes(m)) seen.push(m);
    }
    return seen;
  };

  const saveDefaults = async () => {
    setBusy(true);
    try {
      await api.setModelProviderDefaults(connection.id, {
        defaultModel: defaults.defaultModel.trim() || null,
        defaultEmbeddingModel: defaults.defaultEmbeddingModel.trim() || null,
        defaultRerankModel: defaults.defaultRerankModel.trim() || null,
      });
      pushToast(`${connection.name} defaults saved`, "green");
      onChanged();
    } catch (e) {
      pushToast(e.message || "Could not save the defaults", "red");
    } finally {
      setBusy(false);
    }
  };

  const refresh = async () => {
    setBusy(true);
    try {
      const result = await api.refreshModelProvider(connection.id);
      pushToast(result.message, result.ok ? "green" : "red");
      onChanged();
    } catch (e) {
      pushToast(e.message || "Could not refresh", "red");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div
      className={`rounded-xl border p-3 ${
        status.verified ? "border-emerald-300 bg-emerald-50/30" : "border-slate-200"
      }`}
    >
      <div className="flex flex-wrap items-center gap-2">
        <p className="min-w-0 flex-1 truncate text-sm font-semibold text-slate-900">
          {connection.name}
        </p>
        <span
          className={`shrink-0 rounded-full px-2 py-0.5 text-[10px] font-bold uppercase tracking-wide ${status.tone}`}
        >
          {status.label}
        </span>
      </div>

      <p className="mt-1 text-[11px] text-slate-400">
        {models.length} model{models.length === 1 ? "" : "s"}
        {declared.length > 0 && ` · ${declared.length} declared`}
        {/* How old this list is. A picker built on a four-week-old cache
            should say so rather than presenting it as current. */}
        {refreshedAt ? ` · refreshed ${refreshedAt}` : " · never refreshed"}
      </p>

      {connection.lastTestOk === false && connection.lastTestNote && (
        <p className="mt-2 text-xs leading-relaxed text-red-600">
          {connection.lastTestNote}
        </p>
      )}

      <div className="mt-3 flex flex-wrap items-center gap-2">
        <SmallButton icon="check" onClick={onTest} disabled={!canAdmin || testing || busy}>
          {testing ? "Testing…" : "Test"}
        </SmallButton>
        <SmallButton icon="refresh" onClick={refresh} disabled={!canAdmin || busy}>
          Refresh models
        </SmallButton>
        <SmallButton icon="key" onClick={onReplaceKey} disabled={!canAdmin}>
          Replace key
        </SmallButton>
        <button
          type="button"
          onClick={() => setOpen((o) => !o)}
          className="rounded-lg px-2.5 py-2 text-sm font-semibold text-slate-600 transition hover:text-slate-900"
        >
          {open ? "Hide settings" : "Settings"}
        </button>
        <button
          type="button"
          onClick={onRemove}
          disabled={!canAdmin}
          className="ml-auto rounded-lg px-2.5 py-2 text-sm font-semibold text-slate-500 transition hover:text-red-600 disabled:opacity-40"
        >
          Remove
        </button>
      </div>

      {open && (
        <div className="mt-3 grid gap-x-6 gap-y-4 border-t border-slate-200 pt-3 lg:grid-cols-2">
          {/* Three pickers side by side rather than stacked: this panel sits
              inside a dialog that already scrolls, and stacking them pushed
              the declared-model list below the fold on every connection. */}
          <div className="space-y-3.5">
            <ModelPicker
              id={`chat-${connection.id}`}
              label="Default model"
              choices={choicesFor("CHAT", defaults.defaultModel)}
              recommended={spec.defaultModel}
              value={defaults.defaultModel}
              onChange={(v) => setDefaults((d) => ({ ...d, defaultModel: v }))}
              hint="What an agent runs on unless it names another."
            />
            <ModelPicker
              id={`embedding-${connection.id}`}
              label="Default embedding model"
              choices={choicesFor("EMBEDDING", defaults.defaultEmbeddingModel)}
              value={defaults.defaultEmbeddingModel}
              onChange={(v) => setDefaults((d) => ({ ...d, defaultEmbeddingModel: v }))}
              hint="Retrieval only — text into vectors, never chat."
            />
            <ModelPicker
              id={`rerank-${connection.id}`}
              label="Default rerank model"
              choices={choicesFor("RERANK", defaults.defaultRerankModel)}
              value={defaults.defaultRerankModel}
              onChange={(v) => setDefaults((d) => ({ ...d, defaultRerankModel: v }))}
              hint="Re-scores retrieved passages before the model sees them."
            />
            <SmallButton
              icon="check"
              variant="primary"
              onClick={saveDefaults}
              disabled={!canAdmin || busy}
            >
              {busy ? "Saving…" : "Save defaults"}
            </SmallButton>
          </div>

          <div className="lg:border-l lg:border-slate-200 lg:pl-6">
            <DeclaredModels
              spec={spec}
              connection={connection}
              declared={declared}
              canAdmin={canAdmin}
              onChanged={onChanged}
            />
          </div>
        </div>
      )}
    </div>
  );
}

/**
 * Models the tenant declares by hand.
 *
 * Only useful where the vendor's models are things the tenant created and
 * named — an Azure deployment, a ModelArts id, a SageMaker endpoint — which
 * is why the catalog marks those vendors and this section leads with the
 * reason rather than an unexplained form. Everywhere else the vendor's own
 * list is authoritative, and typing an id by hand is a way to make a typo.
 */
function DeclaredModels({ spec, connection, declared, canAdmin, onChanged }) {
  const { pushToast } = useStore();
  const [rows, setRows] = useState(null);
  const [adding, setAdding] = useState(false);
  const [form, setForm] = useState({
    modelName: "",
    baseModel: "",
    purpose: "CHAT",
    apiVersion: "",
  });
  const [busy, setBusy] = useState(false);

  const reload = useCallback(() => {
    api
      .listModelDeployments(connection.id)
      .then(setRows)
      .catch(() => setRows([]));
  }, [connection.id]);

  useEffect(reload, [reload]);

  const add = async (e) => {
    e.preventDefault();
    setBusy(true);
    try {
      await api.saveModelDeployment(connection.id, {
        modelName: form.modelName.trim(),
        baseModel: form.baseModel.trim() || null,
        purpose: form.purpose,
        apiVersion: form.apiVersion.trim() || null,
      });
      pushToast(`${form.modelName.trim()} added`, "green");
      setForm({ modelName: "", baseModel: "", purpose: "CHAT", apiVersion: "" });
      setAdding(false);
      reload();
      onChanged();
    } catch (err) {
      pushToast(err.message || "Could not add the model", "red");
    } finally {
      setBusy(false);
    }
  };

  const remove = async (row) => {
    try {
      await api.removeModelDeployment(connection.id, row.id);
      pushToast(`${row.modelName} removed`, "green");
      reload();
      onChanged();
    } catch (err) {
      pushToast(err.message || "Could not remove the model", "red");
    }
  };

  const list = rows ?? declared.map((m) => ({ id: m, modelName: m, purpose: "CHAT" }));

  return (
    // Stacked below the pickers on a narrow screen, so it keeps a divider
    // there; beside them on a wide one, where the column rule already separates.
    <div className="border-t border-slate-200 pt-3 lg:border-t-0 lg:pt-0">
      <p className="text-xs font-semibold text-slate-700">Your own models</p>
      <p className="mt-0.5 text-[11px] leading-relaxed text-slate-400">
        {spec.declaresModels
          ? spec.modelHint ||
            "This vendor serves models you deployed and named, so they cannot be discovered — add them here."
          : `${spec.displayName} publishes its own list, so you rarely need this. Add a model only for something that list will not include, such as a fine-tune.`}
      </p>

      {list.length > 0 && (
        <ul className="mt-2 space-y-1">
          {list.map((row) => (
            <li
              key={row.id}
              className="flex items-center gap-2 rounded-lg bg-slate-50 px-2.5 py-1.5"
            >
              <span className="min-w-0 flex-1 truncate font-mono text-[11px] text-slate-700">
                {row.modelName}
              </span>
              <Chip>{PURPOSE_LABEL[row.purpose] || "chat"}</Chip>
              {row.baseModel && (
                <span className="hidden shrink-0 text-[11px] text-slate-400 sm:inline">
                  {row.baseModel}
                </span>
              )}
              {rows && (
                <button
                  type="button"
                  onClick={() => remove(row)}
                  disabled={!canAdmin}
                  aria-label={`Remove ${row.modelName}`}
                  className="shrink-0 rounded p-1 text-slate-400 transition hover:text-red-600 disabled:opacity-40"
                >
                  <Icon name="trash" size={12} />
                </button>
              )}
            </li>
          ))}
        </ul>
      )}

      {!adding ? (
        <SmallButton
          icon="plus"
          onClick={() => setAdding(true)}
          disabled={!canAdmin}
          className="mt-2"
        >
          Add model
        </SmallButton>
      ) : (
        <form
          onSubmit={add}
          autoComplete="off"
          className="mt-2 grid gap-2.5 rounded-lg bg-slate-50 p-2.5 sm:grid-cols-2"
        >
          <div>
            <label
              htmlFor={`model-name-${connection.id}`}
              className="mb-1 block text-[11px] font-semibold text-slate-700"
            >
              Model name <span className="text-red-500">*</span>
            </label>
            <input
              id={`model-name-${connection.id}`}
              type="text"
              required
              value={form.modelName}
              placeholder={
                spec.kind === "AZURE_OPENAI"
                  ? "Your deployment name"
                  : "The id you call this model by"
              }
              autoComplete="off"
              onChange={(e) => setForm((f) => ({ ...f, modelName: e.target.value }))}
              className="w-full rounded-lg border border-slate-200 bg-white px-2.5 py-1.5 text-sm text-slate-900 outline-none transition focus:border-slate-300 focus:ring-2 focus:ring-slate-300"
            />
          </div>

          <div>
            <label
              htmlFor={`model-purpose-${connection.id}`}
              className="mb-1 block text-[11px] font-semibold text-slate-700"
            >
              Model type <span className="text-red-500">*</span>
            </label>
            <select
              id={`model-purpose-${connection.id}`}
              value={form.purpose}
              onChange={(e) => setForm((f) => ({ ...f, purpose: e.target.value }))}
              className="w-full rounded-lg border border-slate-200 bg-white px-2.5 py-1.5 text-sm text-slate-900 outline-none transition focus:border-slate-300 focus:ring-2 focus:ring-slate-300"
            >
              {PURPOSE_ORDER.map((p) => (
                <option key={p} value={p}>
                  {PURPOSE_LABEL[p]}
                </option>
              ))}
            </select>
          </div>

          <div>
            <label
              htmlFor={`model-base-${connection.id}`}
              className="mb-1 block text-[11px] font-semibold text-slate-700"
            >
              Base model
            </label>
            <input
              id={`model-base-${connection.id}`}
              type="text"
              value={form.baseModel}
              placeholder="The published model underneath, e.g. gpt-4o"
              autoComplete="off"
              onChange={(e) => setForm((f) => ({ ...f, baseModel: e.target.value }))}
              className="w-full rounded-lg border border-slate-200 bg-white px-2.5 py-1.5 text-sm text-slate-900 outline-none transition focus:border-slate-300 focus:ring-2 focus:ring-slate-300"
            />
          </div>

          {spec.kind === "AZURE_OPENAI" && (
            <div>
              <label
                htmlFor={`model-version-${connection.id}`}
                className="mb-1 block text-[11px] font-semibold text-slate-700"
              >
                API version
              </label>
              <input
                id={`model-version-${connection.id}`}
                type="text"
                value={form.apiVersion}
                placeholder="Only if this deployment pins one"
                autoComplete="off"
                onChange={(e) => setForm((f) => ({ ...f, apiVersion: e.target.value }))}
                className="w-full rounded-lg border border-slate-200 bg-white px-2.5 py-1.5 text-sm text-slate-900 outline-none transition focus:border-slate-300 focus:ring-2 focus:ring-slate-300"
              />
            </div>
          )}

          {/* Declared, not inferred: no naming rule can read a name its
              operator invented — "prod-embed-v2" would classify as chat. */}
          <p className="text-[11px] leading-relaxed text-slate-400 sm:col-span-2">
            Nothing can infer the type from a name you chose, so it is asked
            rather than guessed.
          </p>

          <div className="flex items-center gap-2 sm:col-span-2">
            <SmallButton icon="check" variant="primary" type="submit" disabled={busy}>
              {busy ? "Adding…" : "Add"}
            </SmallButton>
            <button
              type="button"
              onClick={() => setAdding(false)}
              className="rounded-lg px-2.5 py-1.5 text-xs font-semibold text-slate-500 transition hover:text-slate-800"
            >
              Cancel
            </button>
          </div>
        </form>
      )}
    </div>
  );
}

/**
 * The vendor's models, browsable by category.
 *
 * Lives in a dialog rather than inside the card: AWS returns 119 ids, and an
 * inline list of that length made one card three times the height of its
 * neighbours. Categories are tabs because the whole point of the split is
 * answering "what can I actually chat with?" without reading 119 lines — and
 * a search box, because at that size scanning is not a plan either.
 */
function ModelsModal({ spec, models, modelsByPurpose, verified, onClose }) {
  const buckets = PURPOSE_ORDER.filter((p) => (modelsByPurpose[p] || []).length > 0);
  const [purpose, setPurpose] = useState("ALL");
  const [query, setQuery] = useState("");

  const shown = useMemo(() => {
    const source = purpose === "ALL" ? models : modelsByPurpose[purpose] || [];
    const needle = query.trim().toLowerCase();
    return needle ? source.filter((m) => m.toLowerCase().includes(needle)) : source;
  }, [purpose, query, models, modelsByPurpose]);

  const tabs = [{ key: "ALL", label: "All", count: models.length }].concat(
    buckets.map((p) => ({
      key: p,
      label: PURPOSE_LABEL[p],
      count: modelsByPurpose[p].length,
    })),
  );

  return (
    <ModalPortal layerClass="z-[95] items-center p-4" onClose={onClose}>
      <div className="relative flex max-h-[80vh] w-full max-w-lg flex-col overflow-hidden rounded-xl bg-white shadow-xl ring-1 ring-slate-200/80 animate-fade-up">
        <div className="flex items-start gap-3 border-b border-slate-100 bg-gradient-to-br from-slate-50 to-white px-4 py-3.5">
          <BrandLogo src={vendorLogo(spec.kind)} alt={`${spec.displayName} logo`} />
          <div className="min-w-0 flex-1">
            <h3 className="text-[15px] font-semibold leading-snug text-slate-900">
              {spec.displayName} models
            </h3>
            <p className="mt-0.5 text-xs text-slate-500">
              {verified
                ? `${models.length} reported by ${spec.displayName} on the last successful test.`
                : "Not verified — these are common models for this vendor. Run Test to load the real list."}
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="shrink-0 rounded-lg p-1 text-slate-400 transition hover:text-slate-700"
          >
            <Icon name="x" size={16} />
          </button>
        </div>

        <div className="border-b border-slate-100 px-4 py-3">
          <div className="mb-2.5 flex flex-wrap gap-1.5">
            {tabs.map((t) => (
              <button
                key={t.key}
                type="button"
                onClick={() => setPurpose(t.key)}
                className={`rounded-full px-2.5 py-1 text-[11px] font-semibold capitalize transition ${
                  purpose === t.key
                    ? "bg-slate-900 text-white"
                    : "bg-slate-100 text-slate-600 hover:bg-slate-200"
                }`}
              >
                {t.label} · {t.count}
              </button>
            ))}
          </div>
          <input
            type="search"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Filter by name…"
            aria-label="Filter models"
            className="w-full rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-900 outline-none transition focus:border-slate-300 focus:ring-2 focus:ring-slate-300"
          />
        </div>

        <div className="flex-1 overflow-y-auto px-4 py-3">
          {shown.length === 0 ? (
            <p className="py-6 text-center text-sm text-slate-500">
              No model here matches “{query}”.
            </p>
          ) : (
            <ul className="space-y-1">
              {shown.map((m) => (
                <li
                  key={m}
                  className="break-all rounded-md bg-slate-50 px-2 py-1.5 font-mono text-[11px] text-slate-600"
                >
                  {m}
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
    </ModalPortal>
  );
}

const CUSTOM_MODEL = "__custom__";
const CUSTOM_VALUE = "__other__";

/**
 * One credential input, rendered from the vendor's own field spec.
 *
 * A field the vendor publishes a closed list for — today that is the AWS and
 * Huawei region — becomes a dropdown, so nobody has to recall whether Sydney
 * is `ap-southeast-2`. The list is never the whole truth for long, though:
 * cloud vendors add regions between our releases, so "Other" stays available
 * rather than locking a tenant out of a region they are entitled to.
 */
function CredentialField({ field, value, onChange }) {
  const options = field.options || [];
  const [custom, setCustom] = useState(false);
  const inputId = `cred-${field.key}`;

  return (
    <div>
      <label
        htmlFor={inputId}
        className="mb-1.5 flex items-center gap-1.5 text-xs font-semibold text-slate-700"
      >
        {field.label}
        {field.required && <span className="text-red-500">*</span>}
      </label>

      {options.length > 0 && !custom ? (
        <select
          id={inputId}
          value={value}
          onChange={(e) => {
            const picked = e.target.value;
            if (picked === CUSTOM_VALUE) {
              setCustom(true);
              onChange("");
              return;
            }
            onChange(picked);
          }}
          className="w-full rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-900 outline-none transition focus:border-slate-300 focus:ring-2 focus:ring-slate-300"
        >
          <option value="">Select a {field.label.toLowerCase()}…</option>
          {options.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
          <option value={CUSTOM_VALUE}>Other — enter it manually…</option>
        </select>
      ) : (
        <input
          id={inputId}
          type={field.secret ? "password" : "text"}
          value={value}
          placeholder={field.placeholder || ""}
          // Chrome matches on the field's name, so "projectId" got filled with
          // a saved email and the AK box with a saved password — a credential
          // form silently pre-filled with the WRONG credential. Prefixing the
          // name breaks that match, and "new-password" is the one value Chrome
          // honours for "do not offer a stored secret here".
          name={`autoops-${field.key}`}
          autoComplete={field.secret ? "new-password" : "off"}
          data-lpignore="true"
          data-form-type="other"
          onChange={(e) => onChange(e.target.value)}
          className="w-full rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-900 outline-none transition focus:border-slate-300 focus:ring-2 focus:ring-slate-300"
        />
      )}

      {options.length > 0 && custom && (
        <button
          type="button"
          onClick={() => {
            setCustom(false);
            onChange("");
          }}
          className="mt-1.5 text-[11px] font-semibold text-blue-600 hover:underline"
        >
          Back to the list
        </button>
      )}
    </div>
  );
}

/**
 * One model choice, scoped to a purpose.
 *
 * The list handed in is already filtered — a chat picker never sees an
 * embedding model — so the only judgement here is presentation: a dropdown
 * when there is something to pick, a text box when there is not, and "Other"
 * for a model the vendor has not listed (an Azure deployment, a private
 * ModelArts id, an Ollama pull).
 */
function ModelPicker({ id, label, choices, recommended, value, onChange, hint }) {
  // A saved model the list no longer knows about opens straight into the text
  // box, already filled — not blanked.
  const [custom, setCustom] = useState(() => !!value && !choices.includes(value));
  const asText = custom || choices.length === 0;

  return (
    <div>
      <label htmlFor={id} className="mb-1.5 block text-xs font-semibold text-slate-700">
        {label} <span className="font-normal text-slate-400">(optional)</span>
      </label>
      {choices.length > 0 && (
        <select
          id={custom ? undefined : id}
          value={custom ? CUSTOM_MODEL : value}
          onChange={(e) => {
            const picked = e.target.value;
            setCustom(picked === CUSTOM_MODEL);
            if (picked === CUSTOM_MODEL) onChange("");
            else onChange(picked);
          }}
          className="w-full rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-900 outline-none transition focus:border-slate-300 focus:ring-2 focus:ring-slate-300"
        >
          <option value="">Leave blank to choose per agent</option>
          {choices.map((m) => (
            <option key={m} value={m}>
              {m}
              {m === recommended ? "  (recommended)" : ""}
            </option>
          ))}
          <option value={CUSTOM_MODEL}>Other — enter a model id…</option>
        </select>
      )}
      {asText && (
        <input
          type="text"
          id={id}
          value={value}
          placeholder={recommended || "Leave blank to choose per agent"}
          autoComplete="off"
          onChange={(e) => onChange(e.target.value)}
          className={`w-full rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-900 outline-none transition focus:border-slate-300 focus:ring-2 focus:ring-slate-300 ${
            choices.length > 0 ? "mt-2" : ""
          }`}
        />
      )}
      <p className="mt-1.5 text-[11px] leading-relaxed text-slate-400">{hint}</p>
    </div>
  );
}

/**
 * Form generated from the vendor's own field spec, so adding a vendor to the
 * backend catalog needs no change here.
 *
 * @param models model ids this vendor reported on its last successful test.
 *   Empty until then, in which case the picker falls back to the catalog's
 *   short list for the vendor — a hint, never a claim about this key.
 * @param modelsByPurpose those same ids split by what each model is for. A
 *   vendor answers "list models" with everything the account can reach —
 *   AWS returns 119, of which 15 embed and 14 draw — so each picker offers
 *   only the models it can actually use.
 */
function CredentialsModal({
  spec,
  existing,
  models = [],
  modelsByPurpose = {},
  onClose,
  onSaved,
}) {
  const { pushToast } = useStore();

  // The vendor may accept more than one kind of credential (Azure takes an
  // API key OR an Entra ID service principal), and the two need entirely
  // different fields — so the method is picked first and the form follows it.
  const methods = spec.authMethods?.length
    ? spec.authMethods
    : [{ code: "API_KEY", label: "API key", fields: spec.fields }];
  const [authMethod, setAuthMethod] = useState(
    existing?.authMethod || methods[0].code,
  );
  const fields = (methods.find((m) => m.code === authMethod) || methods[0]).fields;

  const [values, setValues] = useState({});
  const [name, setName] = useState(existing?.name || spec.displayName);

  // Live > catalog. Whatever is already saved stays selectable even if the
  // vendor has since stopped listing it, so re-opening the form never
  // silently drops the model this workspace is running on.
  const live = models.length > 0;
  const grouped = live ? modelsByPurpose : spec.fallbackModelsByPurpose || {};

  const choicesFor = (purpose, current) => {
    const seen = [];
    for (const m of [...(current ? [current] : []), ...(grouped[purpose] || [])]) {
      if (m && !seen.includes(m)) seen.push(m);
    }
    return seen;
  };
  const chatChoices = useMemo(
    () => choicesFor("CHAT", existing?.defaultModel),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [existing?.defaultModel, grouped],
  );
  const embeddingChoices = useMemo(
    () => choicesFor("EMBEDDING", existing?.defaultEmbeddingModel),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [existing?.defaultEmbeddingModel, grouped],
  );
  const rerankChoices = useMemo(
    () => choicesFor("RERANK", existing?.defaultRerankModel),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [existing?.defaultRerankModel, grouped],
  );

  const [defaultModel, setDefaultModel] = useState(existing?.defaultModel || "");
  const [embeddingModel, setEmbeddingModel] = useState(
    existing?.defaultEmbeddingModel || "",
  );
  const [rerankModel, setRerankModel] = useState(existing?.defaultRerankModel || "");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  // idle → verifying → verified (nothing stored yet) → saving. The same
  // sequence Cloud Integrations uses, for the same reason: a key the vendor
  // rejects should never become a stored connection that looks configured and
  // has to be cleaned up. Any edit to the credentials drops back to idle,
  // because the previous answer was about a different key.
  const [verifyState, setVerifyState] = useState("idle");
  const [verified, setVerified] = useState(null);

  const configFromForm = () => {
    const config = {};
    // Only the ACTIVE method's fields: sending a stale apiKey alongside a
    // service principal would store a credential that claims to be both.
    for (const f of fields) {
      const v = (values[f.key] ?? "").trim();
      if (v) config[f.key] = v;
    }
    return config;
  };

  const setField = (key, value) => {
    setValues((v) => ({ ...v, [key]: value }));
    if (verifyState !== "idle") {
      setVerifyState("idle");
      setVerified(null);
    }
  };

  const verify = async () => {
    setVerifyState("verifying");
    setError(null);
    try {
      const outcome = await api.verifyModelCredentials({
        kind: spec.kind,
        authMethod,
        config: JSON.stringify(configFromForm()),
        defaultModel: defaultModel.trim() || null,
      });
      setVerified(outcome);
      setVerifyState(outcome?.ok ? "verified" : "idle");
      if (!outcome?.ok) {
        setError(outcome?.message || `${spec.displayName} rejected this credential`);
      }
    } catch (err) {
      setVerifyState("idle");
      setError(err.message || "Could not reach the provider");
    }
  };

  const submit = async (e) => {
    e.preventDefault();
    // The button is disabled until verified, but a stray Enter would still
    // submit the form — so the guard lives here rather than only on the button.
    if (verifyState !== "verified") {
      verify();
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const saved = await api.saveModelProvider({
        // Present = replace this connection's credential. Absent = add another
        // connection to the same vendor, which the server refuses if the name
        // is taken rather than overwriting a working key.
        id: existing?.id ?? null,
        kind: spec.kind,
        name: name.trim() || spec.displayName,
        authMethod,
        config: JSON.stringify(configFromForm()),
        defaultModel: defaultModel.trim() || null,
        defaultEmbeddingModel: embeddingModel.trim() || null,
        defaultRerankModel: rerankModel.trim() || null,
      });
      pushToast(`${spec.displayName} verified and saved`, "green");
      onSaved(saved);
    } catch (err) {
      setError(err.message || "Could not save these credentials");
    } finally {
      setBusy(false);
    }
  };

  return (
    <ModalPortal layerClass="z-[95] items-center p-4" onClose={onClose}>
      <form
        onSubmit={submit}
        autoComplete="off"
        // Wide and capped, not tall and unbounded. The AK/SK vendors have four
        // credential fields on top of three model pickers, which in one narrow
        // column ran past the bottom of the viewport — so Save was off-screen
        // and the form read as though it had no way to submit.
        className="relative flex max-h-[88vh] w-full max-w-3xl flex-col overflow-hidden rounded-xl bg-white shadow-xl ring-1 ring-slate-200/80 animate-fade-up"
      >
        <div className="flex items-start gap-3 border-b border-slate-100 bg-gradient-to-br from-slate-50 to-white px-4 py-3.5">
          <BrandLogo src={vendorLogo(spec.kind)} alt={`${spec.displayName} logo`} />
          <div className="min-w-0">
            <h3 className="text-[15px] font-semibold leading-snug text-slate-900">
              {spec.displayName} credentials
            </h3>
            <p className="mt-0.5 text-xs text-slate-500">
              Stored encrypted by AutoOps and never returned to this browser.
              {existing && " Saving replaces the current key."}
            </p>
          </div>
        </div>

        {/* Two columns: what the vendor needs on the left, what to run on the
            right. They are independent decisions — one is "prove who I am",
            the other "what should agents use" — so side by side reads better
            than one stacked on the other, and halves the height. */}
        <div className="grid flex-1 gap-x-6 gap-y-4 overflow-y-auto px-5 py-4 md:grid-cols-2">
          <div className="space-y-3.5">
            <p className="text-[11px] font-bold uppercase tracking-wide text-slate-400">
              Credentials
            </p>

            <div>
              <label
                htmlFor="connection-name"
                className="mb-1.5 block text-xs font-semibold text-slate-700"
              >
                Connection name
              </label>
              <input
                id="connection-name"
                type="text"
                value={name}
                placeholder={spec.displayName}
                autoComplete="off"
                onChange={(e) => setName(e.target.value)}
                className="w-full rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-900 outline-none transition focus:border-slate-300 focus:ring-2 focus:ring-slate-300"
              />
              <p className="mt-1.5 text-[11px] leading-relaxed text-slate-400">
                Tells this key apart from another for the same vendor —
                “Production”, “Sandbox”.
              </p>
            </div>

            {/* Only where the vendor genuinely offers a choice. One method is
                not a decision, and rendering it as one is noise. */}
            {methods.length > 1 && (
              <div>
                <label
                  htmlFor="auth-method"
                  className="mb-1.5 block text-xs font-semibold text-slate-700"
                >
                  Authentication method <span className="text-red-500">*</span>
                </label>
                <select
                  id="auth-method"
                  value={authMethod}
                  onChange={(e) => {
                    setAuthMethod(e.target.value);
                    // The two methods share almost no fields; carrying values
                    // across would leave a half-filled form that looks complete.
                    setValues({});
                    setVerifyState("idle");
                    setVerified(null);
                  }}
                  className="w-full rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-900 outline-none transition focus:border-slate-300 focus:ring-2 focus:ring-slate-300"
                >
                  {methods.map((m) => (
                    <option key={m.code} value={m.code}>
                      {m.label}
                    </option>
                  ))}
                </select>
              </div>
            )}

            {fields.map((f) => (
              <CredentialField
                key={`${authMethod}-${f.key}`}
                field={f}
                value={values[f.key] ?? ""}
                onChange={(next) => setField(f.key, next)}
              />
            ))}

            {spec.docsUrl && (
              <a
                href={spec.docsUrl}
                target="_blank"
                rel="noreferrer"
                className="inline-flex items-center gap-1 text-xs font-semibold text-blue-600 hover:underline"
              >
                Where to find this <Icon name="arrow-right" size={12} />
              </a>
            )}
          </div>

          <div className="space-y-3.5 md:border-l md:border-slate-100 md:pl-6">
            <p className="text-[11px] font-bold uppercase tracking-wide text-slate-400">
              Default models <span className="font-medium normal-case">(all optional)</span>
            </p>

            <ModelPicker
              id="default-model"
              label="Default model"
              choices={chatChoices}
              recommended={spec.defaultModel}
              value={defaultModel}
              onChange={setDefaultModel}
              /* Say which list this is, so a stale suggestion is never mistaken
                 for something the vendor just confirmed. */
              hint={
                live
                  ? `From ${spec.displayName}'s last successful test.`
                  : "Common models for this vendor — Test to load the real list."
              }
            />

            <ModelPicker
              id="default-embedding-model"
              label="Default embedding model"
              choices={embeddingChoices}
              value={embeddingModel}
              onChange={setEmbeddingModel}
              hint={
                embeddingChoices.length > 0
                  ? "Retrieval only — text into vectors, never chat."
                  : `${spec.displayName} lists no embedding model. Enter one if you have it.`
              }
            />

            <ModelPicker
              id="default-rerank-model"
              label="Default rerank model"
              choices={rerankChoices}
              value={rerankModel}
              onChange={setRerankModel}
              hint={
                rerankChoices.length > 0
                  ? "Re-scores retrieved passages before the model sees them."
                  : `${spec.displayName} lists no reranker. Retrieval works without one.`
              }
            />

            {/* The vendor's own caveat, once — it applied to all three pickers
                and repeating it three times was most of this column's height. */}
            {spec.modelHint && (
              <p className="rounded-lg bg-slate-50 px-3 py-2 text-[11px] leading-relaxed text-slate-500">
                {spec.modelHint}
              </p>
            )}
          </div>

          {error && (
            <p className="rounded-lg bg-red-50 px-3 py-2 text-xs font-medium text-red-700 md:col-span-2">
              {error}
            </p>
          )}
        </div>

        {/* Outside the scroll area: Save stays put however long the form is. */}
        <div className="flex shrink-0 flex-wrap items-center gap-2 border-t border-slate-100 px-5 py-3">
          {/* What the vendor said, in the footer beside the button that asked
              — the model count is the evidence the credential really reaches
              this account, and it is what the category browser will show. */}
          <p className="min-w-0 flex-1 text-xs leading-relaxed">
            {verifyState === "verifying" ? (
              <span className="text-slate-500">
                Checking with {spec.displayName}…
              </span>
            ) : verifyState === "verified" ? (
              <span className="font-medium text-emerald-700">
                ✓ {spec.displayName} accepted this credential
                {verified?.models?.length
                  ? ` — ${verified.models.length} model${verified.models.length === 1 ? "" : "s"} visible`
                  : ""}
              </span>
            ) : (
              <span className="text-slate-400">
                Nothing is stored until {spec.displayName} confirms the
                credential.
              </span>
            )}
          </p>
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg px-3 py-2 text-sm font-semibold text-slate-500 transition hover:text-slate-800"
          >
            Cancel
          </button>
          {verifyState === "verified" ? (
            <SmallButton icon="check" variant="primary" type="submit" disabled={busy}>
              {busy ? "Saving…" : "Save"}
            </SmallButton>
          ) : (
            <SmallButton
              icon="pulse"
              variant="primary"
              type="submit"
              disabled={verifyState === "verifying"}
            >
              {verifyState === "verifying" ? "Verifying…" : "Verify"}
            </SmallButton>
          )}
        </div>
      </form>
    </ModalPortal>
  );
}
