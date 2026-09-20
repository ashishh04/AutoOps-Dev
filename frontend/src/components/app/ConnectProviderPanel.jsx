import React, { useState, useEffect } from "react";
import ModalPortal from "./ModalPortal";
import ProviderLogo from "./ProviderLogo";
import Icon from "../Icon";
import { SmallButton, Skeleton } from "./appui";
import SetupMarkdown from "./SetupMarkdown";
import { getProviderSetup } from "../../lib/alerts";

/**
 * Connecting one monitoring source, in two deliberate steps.
 *
 * Instructions BEFORE the form. A connect dialog that opens straight onto
 * "API Key" and "App Key" sends the operator away to hunt for credentials with
 * no idea which permissions they need, and they come back with a key that is
 * missing a scope. Naming the scopes and linking the vendor's docs first turns
 * one round trip into none.
 *
 * Rendered through ModalPortal, never a hand-rolled `fixed inset-0`: page
 * wrappers carry `animate-fade-up`, whose settled transform makes them the
 * containing block for fixed children, so a scrim written in place is clipped
 * to the content column.
 */
/**
 * A value the customer must paste somewhere else. Copy is the primary action;
 * a 200-character signed token is not something anyone retypes, and selecting
 * it by hand inside a scrolling dialog is worse.
 */
function CopyField({ value, secret = false }) {
  const [copied, setCopied] = useState(false);
  const [shown, setShown] = useState(!secret);
  const copy = () =>
    navigator.clipboard?.writeText(value).then(
      () => {
        setCopied(true);
        setTimeout(() => setCopied(false), 1500);
      },
      () => {},
    );
  return (
    <div className="flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-3 py-2">
      <code className="min-w-0 flex-1 truncate font-mono text-xs text-slate-700">
        {shown ? value : "•".repeat(28)}
      </code>
      {secret && (
        <button
          type="button"
          onClick={() => setShown((v) => !v)}
          className="shrink-0 text-slate-400 transition hover:text-slate-700"
          aria-label={shown ? "Hide" : "Reveal"}
        >
          <Icon name="eye" className="h-4 w-4" />
        </button>
      )}
      <button
        type="button"
        onClick={copy}
        className="shrink-0 rounded-md bg-slate-100 px-2 py-1 text-[11px] font-medium text-slate-600 transition hover:bg-slate-200"
      >
        {copied ? "Copied" : "Copy"}
      </button>
    </div>
  );
}

export default function ConnectProviderPanel({ provider, projectId, onClose, onSubmit }) {
  const [step, setStep] = useState("intro");
  const [name, setName] = useState("");
  const [values, setValues] = useState(() =>
    Object.fromEntries(
      (provider.fields || []).map((f) => [f.name, f.defaultValue || ""]),
    ),
  );
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [setup, setSetup] = useState(null);
  const [loadingSetup, setLoadingSetup] = useState(true);

  useEffect(() => {
    let alive = true;
    setLoadingSetup(true);
    getProviderSetup(provider.type, projectId)
      .then((s) => alive && setSetup(s))
      .catch(() => alive && setSetup(null))
      .finally(() => alive && setLoadingSetup(false));
    return () => {
      alive = false;
    };
  }, [provider.type, projectId]);

  // A source with no credentials to enter is configured ENTIRELY by following
  // the guide. Sending someone straight from "Continue" to "Connected" would
  // claim a working integration that sends nothing.
  const hasGuide = Boolean(setup?.instructions);
  const steps = hasGuide ? ["intro", "setup", "form"] : ["intro", "form"];
  const at = steps.indexOf(step);
  const next = () => setStep(steps[Math.min(at + 1, steps.length - 1)]);
  const back = () => setStep(steps[Math.max(at - 1, 0)]);
  const last = at === steps.length - 1;

  const mandatory = (provider.scopes || []).filter((s) => s.mandatory);
  const optional = (provider.scopes || []).filter((s) => !s.mandatory);

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await onSubmit({ name: name.trim() || provider.displayName, config: values });
    } catch (err) {
      setError(err.message || "Could not connect this source");
      setBusy(false);
    }
  };

  return (
    <ModalPortal onClose={busy ? undefined : onClose} layerClass="z-[100] items-center p-4">
      {/* Height follows the CONTENT, capped at the viewport. A fixed h-full
          sheet left Airflow — which needs no credentials at all — as one line
          of text above 600px of empty white. */}
      <div className="relative flex max-h-[85vh] w-full max-w-xl flex-col overflow-hidden rounded-2xl bg-white shadow-2xl">
        <header className="flex shrink-0 items-start gap-4 border-b border-slate-200 p-5">
          <ProviderLogo
            type={provider.type}
            name={provider.displayName}
            className="h-11 w-11"
          />
          <div className="min-w-0 flex-1">
            <h2 className="text-lg font-semibold text-slate-900">
              Connect {provider.displayName}
            </h2>
            <p className="mt-0.5 text-sm text-slate-500">
              {step === "intro"
                ? "What you'll need before you start"
                : step === "setup"
                  ? `Point ${provider.displayName} at AutoOps`
                  : "Name this connection"}
            </p>
          </div>
          <button
            onClick={onClose}
            disabled={busy}
            aria-label="Close"
            className="rounded-lg p-1.5 text-slate-400 transition hover:bg-slate-100 hover:text-slate-700"
          >
            <Icon name="x" className="h-5 w-5" />
          </button>
        </header>

        <div className="min-h-0 flex-1 overflow-y-auto p-5">
          {loadingSetup ? (
            <div className="space-y-3">
              <Skeleton className="h-4 w-3/4" />
              <Skeleton className="h-4 w-1/2" />
              <Skeleton className="h-24 w-full rounded-xl" />
            </div>
          ) : step === "intro" ? (
            <div className="space-y-6">
              {provider.description && (
                <p className="text-sm leading-relaxed text-slate-600">
                  {provider.description}
                </p>
              )}

              {provider.supportsWebhook && (
                <div className="flex gap-3 rounded-xl bg-emerald-50 p-4 ring-1 ring-inset ring-emerald-600/15">
                  <Icon name="bolt" className="h-5 w-5 shrink-0 text-emerald-600" />
                  <p className="text-sm text-emerald-900">
                    Once connected, AutoOps sets up the webhook in{" "}
                    {provider.displayName} for you — alerts start arriving
                    without anything else to configure there.
                  </p>
                </div>
              )}

              {mandatory.length > 0 && (
                <section>
                  <h3 className="text-xs font-semibold uppercase tracking-wider text-slate-500">
                    Permissions required
                  </h3>
                  <ul className="mt-3 space-y-2.5">
                    {mandatory.map((s) => (
                      <li key={s.name} className="flex gap-3">
                        <Icon
                          name="check"
                          className="mt-0.5 h-4 w-4 shrink-0 text-emerald-600"
                        />
                        <div className="min-w-0">
                          <div className="font-mono text-xs text-slate-800">
                            {s.name}
                          </div>
                          {s.description && (
                            <div className="text-sm text-slate-500">
                              {s.description}
                            </div>
                          )}
                        </div>
                      </li>
                    ))}
                  </ul>
                </section>
              )}

              {optional.length > 0 && (
                <section>
                  <h3 className="text-xs font-semibold uppercase tracking-wider text-slate-500">
                    Optional — enables more detail
                  </h3>
                  <ul className="mt-3 space-y-1.5">
                    {optional.map((s) => (
                      <li key={s.name} className="text-sm text-slate-500">
                        <span className="font-mono text-xs text-slate-700">
                          {s.name}
                        </span>
                        {s.description ? ` — ${s.description}` : ""}
                      </li>
                    ))}
                  </ul>
                </section>
              )}

              <section>
                <h3 className="text-xs font-semibold uppercase tracking-wider text-slate-500">
                  You'll be asked for
                </h3>
                <ul className="mt-3 space-y-1.5">
                  {(provider.fields || [])
                    .filter((f) => f.required)
                    .map((f) => (
                      <li
                        key={f.name}
                        className="flex items-center gap-2 text-sm text-slate-700"
                      >
                        <span className="h-1 w-1 rounded-full bg-slate-400" />
                        {f.label}
                      </li>
                    ))}
                  {!(provider.fields || []).some((f) => f.required) && (
                    <li className="text-sm text-slate-500">
                      Nothing — this source needs no credentials. Give the
                      connection a name on the next step and you're done.
                    </li>
                  )}
                </ul>
              </section>
            </div>
          ) : step === "setup" ? (
            <div className="space-y-5">
              <div className="rounded-xl bg-slate-50 p-4 ring-1 ring-inset ring-slate-200">
                <p className="text-sm text-slate-600">
                  {provider.displayName} sends alerts to AutoOps. Use these two
                  values wherever it asks for a webhook.
                </p>
                <dl className="mt-3 space-y-3">
                  <div>
                    <dt className="text-xs font-medium uppercase tracking-wider text-slate-500">
                      Webhook URL
                    </dt>
                    <dd className="mt-1">
                      <CopyField value={setup.ingestUrl} />
                    </dd>
                  </div>
                  <div>
                    <dt className="text-xs font-medium uppercase tracking-wider text-slate-500">
                      API key — send as the X-API-KEY header
                    </dt>
                    <dd className="mt-1">
                      <CopyField value={setup.ingestKey} secret />
                    </dd>
                  </div>
                </dl>
              </div>
              <SetupMarkdown source={setup.instructions} />
            </div>
          ) : (
            <form id="connect-form" onSubmit={submit} className="space-y-5">
              <div>
                <label className="block text-sm font-medium text-slate-700">
                  Name this connection
                </label>
                <input
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder={`e.g. Production ${provider.displayName}`}
                  className="mt-1.5 w-full rounded-lg border border-slate-200 px-3 py-2 text-sm text-slate-900 outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
                />
                <p className="mt-1 text-xs text-slate-500">
                  Only you see this. Useful when the same tool is connected for
                  more than one environment.
                </p>
              </div>

              {(provider.fields || []).map((f) => (
                <div key={f.name}>
                  <label className="block text-sm font-medium text-slate-700">
                    {f.label}
                    {f.required && <span className="ml-1 text-red-500">*</span>}
                  </label>
                  <input
                    type={f.sensitive ? "password" : "text"}
                    required={f.required}
                    autoComplete={f.sensitive ? "new-password" : "off"}
                    value={values[f.name] ?? ""}
                    onChange={(e) =>
                      setValues((v) => ({ ...v, [f.name]: e.target.value }))
                    }
                    className="mt-1.5 w-full rounded-lg border border-slate-200 px-3 py-2 text-sm text-slate-900 outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
                  />
                  {f.hint && (
                    <p className="mt-1 text-xs text-slate-500">
                      {/^https?:\/\//.test(f.hint) ? (
                        <a
                          href={f.hint}
                          target="_blank"
                          rel="noreferrer noopener"
                          className="text-indigo-600 hover:text-indigo-700"
                        >
                          Where do I find this?
                        </a>
                      ) : (
                        f.hint
                      )}
                    </p>
                  )}
                </div>
              ))}

              {error && (
                <div className="flex gap-2.5 rounded-lg bg-red-50 p-3 text-sm text-red-700 ring-1 ring-inset ring-red-600/15">
                  <Icon name="warning" className="mt-0.5 h-4 w-4 shrink-0" />
                  {error}
                </div>
              )}
            </form>
          )}
        </div>

        <footer className="flex shrink-0 items-center justify-between gap-3 border-t border-slate-200 bg-slate-50 p-4">
          <SmallButton onClick={at === 0 ? onClose : back} disabled={busy}>
            {at === 0 ? "Cancel" : "Back"}
          </SmallButton>
          <div className="flex items-center gap-3">
            {steps.length > 2 && (
              <span className="text-xs text-slate-400">
                Step {at + 1} of {steps.length}
              </span>
            )}
            {last ? (
              <SmallButton
                variant="primary"
                type="submit"
                form="connect-form"
                disabled={busy}
              >
                {busy ? "Connecting…" : "Finish"}
              </SmallButton>
            ) : (
              <SmallButton variant="primary" onClick={next} disabled={loadingSetup}>
                Continue
              </SmallButton>
            )}
          </div>
        </footer>
      </div>
    </ModalPortal>
  );
}
