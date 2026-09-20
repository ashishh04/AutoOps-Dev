import React, { useState } from "react";

/**
 * A monitoring source's logo, from `public/logos/`.
 *
 * Filenames there follow two conventions — `datadog.png` and
 * `newrelic-icon.png` — with a handful that follow neither. Rather than keep a
 * hand-written map of 125 entries in sync with a folder, this tries the
 * conventions in order and falls back through `onError`. A source whose logo is
 * simply missing gets a lettered tile, never a broken-image icon.
 */

/**
 * Only the names the conventions cannot reach.
 *
 * Derived by diffing the engine's 124 provider types against the actual
 * contents of `public/logos/` — not guessed. 103 resolve by convention; these
 * 17 do not, and four more (netxms, salesforce, vectordev, zendesk) have no
 * file at all and fall through to the lettered tile on purpose.
 *
 * Case matters. nginx serves these from Linux, so `cloudwatch.png` does NOT
 * find `CloudWatch.png` — a case-insensitive check on a Windows dev box says
 * it does, which is exactly how these got missed the first time.
 */
const OVERRIDES = {
  airflow: "apache-airflow.png",
  aks: "azure-kubernetes-services.png",
  amazonsqs: "Aws-Sqs.png",
  azuremonitoring: "azure-monitor.png",
  bash: "gnubash.png",
  cloudwatch: "CloudWatch.png",
  coralogix: "Coralogix.png",
  databend: "databend.jpeg",
  eks: "Elastic Kubernetes Service.png",
  fluxcd: "flux-cd.png",
  gcpmonitoring: "GCP Monitoring.png",
  gemini: "gemini-color.png",
  gke: "Google Kubernetes Engine.png",
  google_chat: "google-chat.png",
  s3: "amazon-s3.png",
  servicenow: "ServiceNow Icon.png",
  zoom_chat: "zoom-icon.png",
};

const candidates = (type) => {
  const t = String(type || "").toLowerCase();
  const list = [];
  if (OVERRIDES[t]) list.push(OVERRIDES[t]);
  list.push(`${t}-icon.png`, `${t}.png`, `${t}-logo.png`);
  return list.map((f) => `/logos/${encodeURIComponent(f)}`);
};

export default function ProviderLogo({ type, name, className = "h-9 w-9" }) {
  const [attempt, setAttempt] = useState(0);
  const urls = candidates(type);
  const exhausted = attempt >= urls.length;

  if (exhausted) {
    return (
      <div
        className={`flex shrink-0 items-center justify-center rounded-lg bg-slate-100 text-sm font-semibold uppercase text-slate-500 ${className}`}
        aria-hidden="true"
      >
        {String(name || type || "?").slice(0, 2)}
      </div>
    );
  }

  return (
    <img
      src={urls[attempt]}
      alt=""
      aria-hidden="true"
      loading="lazy"
      onError={() => setAttempt((a) => a + 1)}
      className={`shrink-0 rounded-lg object-contain ${className}`}
    />
  );
}
