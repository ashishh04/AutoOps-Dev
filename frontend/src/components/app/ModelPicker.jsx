import React, { useEffect, useMemo, useState } from "react";
import ModalPortal from "./ModalPortal";
import { SmallButton } from "./appui";
import Icon from "../Icon";
import BrandLogo from "./BrandLogo";
import { vendorLogo } from "../../lib/brandLogos";
import { api } from "../../lib/api";

/**
 * Chat models only — an agent is a tool-calling chat loop, so an embedding,
 * image or audio endpoint cannot run one.
 *
 * The server already classifies this and returns {@code modelsByPurpose}, the
 * same field the Models screen counts ("91 chat, 3 embedding, 6 image"). Using
 * it keeps one answer to "can this model hold a conversation" instead of a
 * second, weaker copy here that would drift the moment a vendor names
 * something unexpectedly.
 */
export function chatModels(provider) {
  // UPPERCASE. The server builds this map from `ModelPurpose.name()`, so the
  // key is `CHAT` — the Models screen has always read it that way
  // (`PURPOSE_ORDER` in AiProviders.jsx). This read `.chat`, which is never
  // present, so the lookup silently missed and the fallback below returned
  // EVERY model — embedding and image endpoints included, which is exactly
  // what this function exists to exclude.
  const byPurpose = provider?.modelsByPurpose?.CHAT;
  if (Array.isArray(byPurpose) && byPurpose.length) return byPurpose;
  // Older rows predate the classification; better every model than none.
  return provider?.models || [];
}

/**
 * Switch the model an agent runs on. Two steps, because they are two
 * decisions: WHICH VENDOR gets to see this workspace's infrastructure data,
 * and then which of that vendor's models does the work. Showing every model
 * from every provider at once collapses those into one long list where the
 * vendor — the part with cost and data-residency consequences — is a faint
 * heading someone scrolls past.
 *
 * Offered even for a provider-managed agent: the persona and the tool
 * allow-list stay sealed, but the vendor choice belongs to the customer.
 *
 * Only models this workspace can actually reach are listed. The server refuses
 * any other id rather than falling back to whichever provider happened to be
 * first, so offering a merely plausible one would turn a choice into a
 * run-time failure.
 *
 * Rendered through ModalPortal, never a hand-rolled `fixed inset-0`: this
 * app's page wrapper carries a transform, which makes a bare fixed element
 * position against that box and clip the scrim to the content column.
 */
export default function ModelPicker({ agent, onClose, onSaved, pushToast }) {
  const [providers, setProviders] = useState(null);
  const [openProvider, setOpenProvider] = useState(null);
  const [chosen, setChosen] = useState(agent.model || "");
  const [query, setQuery] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);

  useEffect(() => {
    let live = true;
    api
      .listWorkspaceModels()
      .then((rows) => live && setProviders(Array.isArray(rows) ? rows : []))
      .catch((e) => live && setError(e.message || "Could not load models"));
    return () => {
      live = false;
    };
  }, []);

  // Only a verified connection can serve a request, so an unverified one is
  // not offered at all.
  const usable = useMemo(
    () =>
      (providers || [])
        .filter((p) => p.verified)
        .map((p) => ({
          key: p.providerId,
          name: p.providerName,
          kind: p.kind,
          models: chatModels(p),
        }))
        .filter((p) => p.models.length > 0),
    [providers],
  );

  // Open straight into the provider that serves the current model: the common
  // case is switching within a vendor, not changing vendor.
  //
  // Once only. Watching openProvider here would re-run the moment Back set it
  // to null, immediately reopen the same provider, and trap the person on step
  // two with no way of ever reaching the provider list.
  const autoOpened = React.useRef(false);
  useEffect(() => {
    if (autoOpened.current || !usable.length) return;
    autoOpened.current = true;
    if (!agent.model) return;
    const owner = usable.find((p) => p.models.includes(agent.model));
    if (owner) setOpenProvider(owner);
  }, [usable, agent.model]);

  const shown = useMemo(() => {
    if (!openProvider) return [];
    const q = query.trim().toLowerCase();
    return openProvider.models.filter((m) => !q || m.toLowerCase().includes(q));
  }, [openProvider, query]);

  const save = async () => {
    setSaving(true);
    try {
      await api.setAgentModel(agent.id, chosen);
      pushToast(`${agent.name} now runs on ${chosen}`, "emerald");
      onSaved();
    } catch (e) {
      // Shown in the footer rather than as a toast: the person is still
      // looking at the list they chose from, and that is where the answer
      // belongs.
      setError(e.message || "Could not change the model");
      setSaving(false);
    }
  };

  return (
    <ModalPortal onClose={onClose}>
      <div className="animate-fade-up relative z-10 flex max-h-[85vh] w-full max-w-3xl flex-col overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-xl">
        {/* Header and footer sit outside the scroll area, so the agent name
            and the save button are never scrolled out of reach. */}
        <div className="flex items-start justify-between gap-3 border-b border-slate-200 px-6 py-4">
          <div className="flex min-w-0 items-center gap-3">
            {openProvider && (
              <button
                onClick={() => {
                  setOpenProvider(null);
                  setQuery("");
                }}
                aria-label="Back to providers"
                className="rounded-lg p-1.5 text-slate-400 transition hover:bg-slate-100 hover:text-slate-600"
              >
                <Icon name="chevron" size={16} className="rotate-90" />
              </button>
            )}
            <div className="min-w-0">
              <h3 className="text-base font-semibold text-slate-900">
                {openProvider ? openProvider.name : "Choose a provider"}
              </h3>
              <p className="mt-0.5 truncate text-sm text-slate-500">
                {openProvider
                  ? `${openProvider.models.length} models available`
                  : agent.name}
              </p>
            </div>
          </div>
          <button
            onClick={onClose}
            aria-label="Close"
            className="rounded-lg p-1.5 text-slate-400 transition hover:bg-slate-100 hover:text-slate-600"
          >
            <Icon name="x" size={16} />
          </button>
        </div>

        {openProvider && (
          <div className="border-b border-slate-200 px-6 py-3">
            <div className="relative">
              <span className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-slate-400">
                <Icon name="search" size={14} />
              </span>
              <input
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder="Filter models..."
                className="w-full rounded-lg border border-slate-200 py-2 pl-9 pr-3 text-sm outline-none transition focus:border-blue-500"
              />
            </div>
          </div>
        )}

        <div className="min-h-0 flex-1 overflow-y-auto px-6 py-4">
          {providers === null && !error ? (
            <p className="text-sm text-slate-500">Loading providers...</p>
          ) : usable.length === 0 ? (
            <p className="text-sm text-slate-500">
              No verified AI connection in this workspace. Add one under Settings,
              AI Providers, then come back.
            </p>
          ) : !openProvider ? (
            /* Step one: the vendor. This is the decision with cost and
               data-residency consequences, so it gets its own screen. */
            <div className="grid gap-2.5 sm:grid-cols-2">
              {usable.map((p) => {
                const isCurrent = p.models.includes(agent.model);
                return (
                  <button
                    key={p.key}
                    type="button"
                    onClick={() => setOpenProvider(p)}
                    className="flex items-center gap-3 rounded-xl border border-slate-200 p-4 text-left transition hover:-translate-y-0.5 hover:border-blue-500 hover:shadow-sm"
                  >
                    {/* The same mark the Models screen uses. One vendor, one
                        logo — a second set of glyphs here would read as a
                        different product. */}
                    <BrandLogo
                      src={vendorLogo(p.kind)}
                      alt={`${p.name} logo`}
                      className="h-10 w-10 shrink-0"
                    />
                    <span className="min-w-0 flex-1">
                      <span className="flex items-center gap-2">
                        <span className="truncate text-sm font-semibold text-slate-900">
                          {p.name}
                        </span>
                        {isCurrent && (
                          <span className="shrink-0 rounded-full bg-blue-50 px-1.5 py-0.5 text-[10px] font-medium text-blue-600">
                            in use
                          </span>
                        )}
                      </span>
                      <span className="mt-1 block text-xs text-slate-400">
                        {p.models.length} chat models
                      </span>
                    </span>
                    <Icon name="chevron" size={16} className="shrink-0 -rotate-90 text-slate-300" />
                  </button>
                );
              })}
            </div>
          ) : shown.length === 0 ? (
            <p className="text-sm text-slate-500">No model matches that filter.</p>
          ) : (
            /* Step two: the model, within one vendor. Wide rather than tall —
               two columns turn a long scroll into a screen. */
            <div className="grid gap-1.5 sm:grid-cols-2">
              {shown.map((m) => (
                <button
                  key={m}
                  type="button"
                  title={m}
                  onClick={() => setChosen(m)}
                  className={`flex items-center gap-2 rounded-lg border px-3 py-2 text-left transition ${
                    chosen === m
                      ? "border-blue-500 bg-blue-50"
                      : "border-slate-200 hover:border-blue-300 hover:bg-slate-50"
                  }`}
                >
                  <span
                    className={`flex h-3.5 w-3.5 shrink-0 items-center justify-center rounded-full border ${
                      chosen === m ? "border-blue-500 bg-blue-500" : "border-slate-300"
                    }`}
                  >
                    {chosen === m && <Icon name="check" size={9} className="text-white" />}
                  </span>
                  <span className="truncate font-mono text-xs text-slate-700">{m}</span>
                  {m === agent.model && (
                    <span className="ml-auto shrink-0 text-[10px] text-slate-400">now</span>
                  )}
                </button>
              ))}
            </div>
          )}
        </div>

        <div className="flex items-center justify-between gap-3 border-t border-slate-200 bg-slate-50 px-6 py-3">
          <p className="min-w-0 truncate text-xs text-slate-500">
            {error ? (
              <span className="text-red-600">{error}</span>
            ) : chosen ? (
              <>
                Selected <span className="font-mono text-slate-700">{chosen}</span>
              </>
            ) : (
              "Pick a provider, then a model."
            )}
          </p>
          <div className="flex shrink-0 items-center gap-2">
            <SmallButton icon="x" onClick={onClose}>
              Cancel
            </SmallButton>
            <SmallButton
              icon="check"
              disabled={saving || !chosen || chosen === agent.model}
              onClick={save}
            >
              {saving ? "Saving..." : "Use this model"}
            </SmallButton>
          </div>
        </div>
      </div>
    </ModalPortal>
  );
}
