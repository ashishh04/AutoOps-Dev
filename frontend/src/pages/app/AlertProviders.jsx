import React, { useState, useEffect, useCallback, useMemo } from "react";
import { useParams, useNavigate } from "react-router-dom";
import { PageHeader, Card, SmallButton, Skeleton, ConfirmModal } from "../../components/app/appui";
import Icon from "../../components/Icon";
import ProviderLogo from "../../components/app/ProviderLogo";
import ConnectProviderPanel from "../../components/app/ConnectProviderPanel";
import { useStore } from "../../store/store";
import {
  listProviderTypes,
  listConnectedProviders,
  connectProvider,
  disconnectProvider,
} from "../../lib/alerts";

/**
 * Where a customer hooks their own monitoring up to AutoOps.
 *
 * The full catalog is shown — 124 sources — because many are two-way and
 * filtering on one tag hid ones people actually wanted. With that many, the tag
 * chips are not decoration: "Alerts" is the default view because raising alerts
 * is what this page is for, and everything else is one click away.
 */

const TAGS = [
  { key: "alert", label: "Alerts" },
  { key: "data", label: "Data" },
  { key: "topology", label: "Topology" },
  { key: "incident", label: "Incidents" },
  { key: "messaging", label: "Messaging" },
  { key: "ticketing", label: "Ticketing" },
];

export default function AlertProviders() {
  const { pid } = useParams();
  const navigate = useNavigate();
  const { pushToast } = useStore();

  const [types, setTypes] = useState([]);
  const [connected, setConnected] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [query, setQuery] = useState("");
  const [tag, setTag] = useState("alert");
  const [chosen, setChosen] = useState(null);
  const [removing, setRemoving] = useState(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    Promise.all([listProviderTypes(), listConnectedProviders(pid)])
      .then(([t, c]) => {
        setTypes(t);
        setConnected(c);
        setLoading(false);
      })
      .catch((e) => {
        setError(e.message || "Could not load monitoring sources");
        setLoading(false);
      });
  }, [pid]);

  useEffect(() => {
    load();
  }, [load]);

  const counts = useMemo(() => {
    const c = {};
    TAGS.forEach((t) => {
      c[t.key] = types.filter((p) => (p.tags || []).includes(t.key)).length;
    });
    return c;
  }, [types]);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    // A search means "find this thing", so it looks across the WHOLE catalog.
    // Leaving the tag filter on would hide the one source someone just typed
    // the name of, which reads as "you don't support it".
    const base = q || !tag ? types : types.filter((t) => (t.tags || []).includes(tag));
    if (!q) return base;
    return base.filter(
      (t) =>
        t.displayName?.toLowerCase().includes(q) ||
        t.type?.toLowerCase().includes(q) ||
        (t.categories || []).some((c) => c.toLowerCase().includes(q)),
    );
  }, [types, query, tag]);

  const submit = async ({ name, config }) => {
    await connectProvider(pid, { type: chosen.type, name, config });
    pushToast(`${chosen.displayName} connected`, "emerald");
    setChosen(null);
    load();
  };

  const remove = async () => {
    if (!removing) return;
    setBusy(true);
    try {
      await disconnectProvider(pid, removing.id);
      pushToast("Source disconnected", "emerald");
      load();
    } catch (e) {
      pushToast(e.message || "Could not disconnect", "red");
    } finally {
      setBusy(false);
      setRemoving(null);
    }
  };

  return (
    <div className="animate-fade-up">
      <PageHeader
        title="Monitoring Sources"
        subtitle="Connect the tools that already watch your estate — their alerts land in AutoOps"
        actions={
          <SmallButton icon="radar" onClick={() => navigate(`/app/projects/${pid}/alerts`)}>
            View alerts
          </SmallButton>
        }
      />

      {error && (
        <Card className="mb-5 flex items-center justify-between gap-4 p-5">
          <span className="flex items-center gap-3 text-sm text-slate-600">
            <Icon name="warning" className="h-5 w-5 text-amber-500" />
            {error}
          </span>
          <SmallButton onClick={load}>Try again</SmallButton>
        </Card>
      )}

      {connected.length > 0 && (
        <section className="mb-8">
          <h2 className="mb-3 text-xs font-semibold uppercase tracking-wider text-slate-500">
            Connected
          </h2>
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {connected.map((c) => (
              <Card key={c.id} className="flex items-center gap-3 bg-white p-4">
                <ProviderLogo type={c.type} name={c.label} />
                <div className="min-w-0 flex-1">
                  <div className="truncate text-sm font-medium text-slate-900">
                    {c.label}
                  </div>
                  <div className="truncate text-xs text-slate-500">
                    {/* "Connected" only means credentials were accepted once.
                        Whether anything is ARRIVING is the real question. */}
                    {c.lastAlertAt
                      ? `Last alert ${new Date(c.lastAlertAt).toLocaleString()}`
                      : "No alerts received yet"}
                  </div>
                </div>
                <button
                  onClick={() => setRemoving(c)}
                  aria-label={`Disconnect ${c.label}`}
                  className="rounded-lg p-1.5 text-slate-400 transition hover:bg-red-50 hover:text-red-600"
                >
                  <Icon name="trash" className="h-4 w-4" />
                </button>
              </Card>
            ))}
          </div>
        </section>
      )}

      <div className="mb-4 flex flex-wrap items-center justify-between gap-4">
        <div className="flex flex-wrap items-center gap-1.5">
          {[{ key: "", label: "All" }, ...TAGS].map((t) => (
            <button
              key={t.key || "all"}
              onClick={() => setTag(t.key)}
              className={`rounded-full px-3 py-1 text-xs font-medium transition ${
                tag === t.key
                  ? "bg-slate-900 text-white"
                  : "bg-slate-100 text-slate-600 hover:bg-slate-200"
              }`}
            >
              {t.label}
              {t.key && counts[t.key] ? ` ${counts[t.key]}` : ""}
            </button>
          ))}
        </div>
        <div className="relative w-full max-w-xs">
          <Icon
            name="search"
            className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400"
          />
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search sources…"
            className="w-full rounded-lg border border-slate-200 py-2 pl-9 pr-3 text-sm outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
          />
        </div>
      </div>

      {loading ? (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
          {Array.from({ length: 8 }).map((_, i) => (
            <Card key={i} className="p-4">
              <Skeleton className="h-9 w-9 rounded-lg" />
              <Skeleton className="mt-3 h-4 w-2/3" />
            </Card>
          ))}
        </div>
      ) : filtered.length === 0 ? (
        <Card className="p-8 text-center text-sm text-slate-500">
          {types.length === 0
            ? "No monitoring sources are available to connect."
            : query
              ? `Nothing matches “${query}”.`
              : "No sources carry that tag."}
        </Card>
      ) : (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
          {filtered.map((t) => (
            <button
              key={t.type}
              onClick={() => !t.comingSoon && setChosen(t)}
              disabled={t.comingSoon}
              className="group flex flex-col items-start gap-3 rounded-2xl border border-slate-200 bg-white p-4 text-left transition hover:border-indigo-300 hover:shadow-md disabled:cursor-not-allowed disabled:opacity-50 disabled:hover:border-slate-200 disabled:hover:shadow-none"
            >
              <div className="flex w-full items-start justify-between gap-2">
                <ProviderLogo type={t.type} name={t.displayName} />
                {t.supportsWebhook && !t.comingSoon && (
                  <span
                    title="AutoOps can set the webhook up for you"
                    className="rounded-full bg-emerald-50 px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-emerald-700 ring-1 ring-inset ring-emerald-600/20"
                  >
                    Auto
                  </span>
                )}
              </div>
              <div className="min-w-0">
                <div className="truncate text-sm font-semibold text-slate-900 group-hover:text-indigo-700">
                  {t.displayName}
                </div>
                <div className="truncate text-xs text-slate-500">
                  {t.comingSoon
                    ? "Coming soon"
                    : (t.categories || []).join(" · ") || "Monitoring"}
                </div>
              </div>
            </button>
          ))}
        </div>
      )}

      {chosen && (
        <ConnectProviderPanel
          provider={chosen}
          projectId={pid}
          onClose={() => setChosen(null)}
          onSubmit={submit}
        />
      )}

      {removing && (
        <ConfirmModal
          open
          title={`Disconnect ${removing.label}?`}
          message="Alerts already received are kept. No new ones will arrive from this source."
          confirmLabel={busy ? "Disconnecting…" : "Disconnect"}
          onConfirm={remove}
          onClose={() => setRemoving(null)}
        />
      )}
    </div>
  );
}
