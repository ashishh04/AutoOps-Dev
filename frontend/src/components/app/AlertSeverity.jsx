import React from "react";

/**
 * Severity, shared by the alerts list and the alert detail page.
 *
 * It lives here rather than in either page because the two must agree. A
 * `critical` that is red in the table and amber on the page it opens is worse
 * than no colour at all — the operator learns to distrust the colour.
 */

/** Worst first. Anything unrecognised sorts AFTER everything known. */
export const SEVERITY_ORDER = ["critical", "high", "warning", "info", "low"];

export const severityRank = (value) => {
  const i = SEVERITY_ORDER.indexOf(String(value || "").toLowerCase());
  return i === -1 ? SEVERITY_ORDER.length : i;
};

const TONES = {
  critical: {
    chip: "bg-red-50 text-red-700 ring-red-600/20",
    rail: "bg-red-500",
    wash: "from-red-500/[0.07]",
    iconBg: "bg-red-100",
    iconFg: "text-red-600",
  },
  high: {
    chip: "bg-orange-50 text-orange-700 ring-orange-600/20",
    rail: "bg-orange-500",
    wash: "from-orange-500/[0.07]",
    iconBg: "bg-orange-100",
    iconFg: "text-orange-600",
  },
  warning: {
    chip: "bg-amber-50 text-amber-700 ring-amber-600/20",
    rail: "bg-amber-500",
    wash: "from-amber-500/[0.07]",
    iconBg: "bg-amber-100",
    iconFg: "text-amber-600",
  },
  info: {
    chip: "bg-sky-50 text-sky-700 ring-sky-600/20",
    rail: "bg-sky-500",
    wash: "from-sky-500/[0.07]",
    iconBg: "bg-sky-100",
    iconFg: "text-sky-600",
  },
  low: {
    chip: "bg-slate-50 text-slate-600 ring-slate-500/20",
    rail: "bg-slate-400",
    wash: "from-slate-500/[0.07]",
    iconBg: "bg-slate-100",
    iconFg: "text-slate-500",
  },
};

const FALLBACK = TONES.low;

/**
 * A severity nobody anticipated ("sev1", "P2") gets the muted treatment rather
 * than a guess. Painting an unknown string red would cry wolf; painting it
 * loudly green would hide a real one. Muted is the honest answer.
 */
export const severityTone = (value) =>
  TONES[String(value || "").toLowerCase()] || FALLBACK;

export const SeverityChip = ({ value, className = "" }) => (
  <span
    className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold uppercase tracking-wide ring-1 ring-inset ${severityTone(value).chip} ${className}`}
  >
    {value || "unknown"}
  </span>
);
