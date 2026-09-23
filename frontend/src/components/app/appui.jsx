import React from "react";
import Icon from "../Icon";

// ...rest so a Card can be made interactive (onClick, role, aria-*) without a
// wrapper div stealing the hover/group styling that lives on the card itself.
/**
 * A panel, optionally with a heading.
 *
 * `title` is DESTRUCTURED rather than left in `...rest`, and that is the whole
 * change. Spread onto the div it became the HTML `title` attribute — a tooltip
 * — so every `<Card title="Steps">` rendered a panel with no visible heading
 * and a label that only appeared if you hovered and waited. Eleven of them
 * across the fleet view and the workflow designer.
 *
 * It went unnoticed because a test asserting `getByText("Tenants with no
 * signal")` passed: the fleet page also has a StatCard carrying that exact
 * label, so the assertion was satisfied by a different element than the one it
 * was written for.
 */
export const Card = ({ children, className = "", title, ...rest }) => (
  <div
    className={`rounded-2xl border border-slate-200 bg-slate-50 ${className}`}
    {...rest}
  >
    {title && (
      <h3 className="border-b border-slate-200 px-4 py-3 text-sm font-semibold text-slate-900">
        {title}
      </h3>
    )}
    {children}
  </div>
);

export const PageHeader = ({ title, subtitle, actions }) => (
  <div className="mb-7 flex flex-wrap items-end justify-between gap-4">
    <div>
      <h1 className="text-2xl font-bold tracking-tight text-slate-900">
        {title}
      </h1>
      {subtitle && <p className="mt-1 text-sm text-slate-500">{subtitle}</p>}
    </div>
    {actions && (
      <div className="flex flex-wrap items-center gap-2">{actions}</div>
    )}
  </div>
);

const TONES = {
  cyan: "from-slate-200 to-slate-200 text-slate-900",
  emerald: "from-slate-200 to-slate-200 text-emerald-600",
  amber: "from-amber-400/20 to-amber-400/5 text-amber-600",
  violet: "from-slate-200 to-slate-200 text-violet-600",
  // For counters where a non-zero value is bad news (failures, outages).
  red: "from-red-400/20 to-red-400/5 text-red-600",
};

export const StatCard = ({
  label,
  value,
  delta,
  deltaUp = true,
  icon = "chart",
  tone = "cyan",
  // Neutral note under the figure. Unlike `delta` it carries no up/down
  // meaning, so it suits explaining an absent value ("nothing measured yet")
  // rather than a movement. Accepts a node so it can hold a link.
  hint,
}) => (
  <Card className="group p-5 transition duration-300 hover:-translate-y-1 hover:border-blue-500">
    <div className="flex items-start justify-between">
      <p className="text-xs font-medium text-slate-500">{label}</p>
      <span
        className={`flex h-9 w-9 items-center justify-center rounded-lg bg-gradient-to-br ${TONES[tone]} transition group-hover:scale-110`}
      >
        <Icon name={icon} size={18} />
      </span>
    </div>
    <p className="mt-3 text-2xl font-bold text-slate-900">{value}</p>
    {delta && (
      <p
        className={`mt-1 text-xs ${deltaUp ? "text-emerald-600" : "text-red-600"}`}
      >
        {deltaUp ? "▲" : "▼"} {delta}
      </p>
    )}
    {hint && <div className="mt-1 text-xs leading-relaxed text-slate-500">{hint}</div>}
  </Card>
);

const BADGE = {
  success: "border-emerald-400/30 bg-emerald-400/10 text-emerald-600",
  healthy: "border-emerald-400/30 bg-emerald-400/10 text-emerald-600",
  active: "border-emerald-400/30 bg-emerald-400/10 text-emerald-600",
  enabled: "border-emerald-400/30 bg-emerald-400/10 text-emerald-600",
  running: "border-slate-300 bg-slate-100 text-slate-900",
  failed: "border-red-400/30 bg-red-400/10 text-red-600",
  offline: "border-red-400/30 bg-red-400/10 text-red-600",
  error: "border-red-400/30 bg-red-400/10 text-red-600",
  queued: "border-slate-400/30 bg-slate-400/10 text-slate-600",
  disabled: "border-slate-400/30 bg-slate-400/10 text-slate-500",
  paused: "border-slate-400/30 bg-slate-400/10 text-slate-500",
  cancelled: "border-slate-400/30 bg-slate-400/10 text-slate-500",
  scheduled: "border-amber-400/30 bg-amber-400/10 text-amber-600",
  pending: "border-amber-400/30 bg-amber-400/10 text-amber-600",
  approved: "border-emerald-400/30 bg-emerald-400/10 text-emerald-600",
  rejected: "border-red-400/30 bg-red-400/10 text-red-600",
  draft: "border-slate-400/30 bg-slate-400/10 text-slate-600",
  trial: "border-amber-400/30 bg-amber-400/10 text-amber-600",
  suspended: "border-red-400/30 bg-red-400/10 text-red-600",
  overdue: "border-red-400/30 bg-red-400/10 text-red-600",
  // Alert-plane statuses. Without these, "firing" fell through to the grey
  // `queued` default and a live incident looked as calm as a queued job.
  firing: "border-red-400/30 bg-red-400/10 text-red-600",
  resolved: "border-emerald-400/30 bg-emerald-400/10 text-emerald-600",
  acknowledged: "border-sky-400/30 bg-sky-400/10 text-sky-600",
  suppressed: "border-slate-400/30 bg-slate-400/10 text-slate-500",
  paid: "border-emerald-400/30 bg-emerald-400/10 text-emerald-600",
};

export const StatusBadge = ({ status }) => (
  <span
    className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-0.5 text-xs font-medium capitalize ${BADGE[status] || BADGE.queued}`}
  >
    <span
      className={`h-1.5 w-1.5 rounded-full bg-current ${status === "running" || status === "firing" ? "animate-pulse-dot" : ""}`}
    />
    {status}
  </span>
);

/**
 * Health of a cloud connection at a glance: green when it is connected with
 * no known problem, red when it cannot actually be used — the provider
 * rejected the credentials, or none are stored.
 *
 * A connection that simply has not been verified yet stays GREEN on purpose.
 * Huawei, Oracle and M365 have no live provider check at all, so they can
 * never go verified; painting "unverified" red would mark healthy
 * integrations as broken forever. The tooltip always states which of the
 * three it is.
 */
export const CloudHealthBadge = ({ connection }) => {
  const { hasCredentials, lastVerifiedOk, lastVerifiedMessage } = connection;
  const broken = !hasCredentials || lastVerifiedOk === false;
  const label = !hasCredentials
    ? "No credentials"
    : lastVerifiedOk === false
      ? "Failed"
      : "Connected";
  const title = !hasCredentials
    ? "Stored without credentials — job steps cannot use this integration"
    : lastVerifiedOk === false
      ? lastVerifiedMessage || "The provider rejected these credentials"
      : lastVerifiedOk === true
        ? lastVerifiedMessage || "Verified with the provider"
        : "Connected — not checked against the provider yet";
  return (
    <span
      title={title}
      className={`inline-flex items-center gap-1.5 whitespace-nowrap rounded-full border px-2.5 py-0.5 text-xs font-medium ${
        broken ? BADGE.failed : BADGE.success
      }`}
    >
      <span className="h-1.5 w-1.5 rounded-full bg-current" />
      {label}
    </span>
  );
};

export const Toolbar = ({ placeholder = "Search…", value, onChange, right }) => (
  <div className="mb-5 flex flex-wrap items-center justify-between gap-3">
    <div className="relative w-full max-w-xs">
      <span className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-slate-500">
        <Icon name="search" size={16} />
      </span>
      <input
        value={value}
        onChange={onChange}
        placeholder={placeholder}
        className="w-full rounded-lg border border-slate-200 bg-slate-50 py-2 pl-9 pr-3 text-sm text-slate-900 outline-none transition placeholder:text-slate-600 focus:border-slate-300 focus:ring-2 focus:ring-slate-300"
      />
    </div>
    {right && <div className="flex items-center gap-2">{right}</div>}
  </div>
);

export const Skeleton = ({ className = "" }) => (
  <span className={`block animate-pulse rounded bg-slate-50 ${className}`} />
);

export const Table = ({
  columns,
  rows = [],
  empty = "No records yet.",
  loading = false,
  loadingRows = 4,
  error = null,
  className = "",
  onRetry,
  onRowClick,
}) => (
  <Card className={`overflow-hidden ${className}`}>
    <div className="overflow-x-auto">
      <table className="w-full min-w-[560px] text-left text-sm">
        <thead>
          <tr className="border-b border-slate-200 text-xs uppercase tracking-wider text-slate-500">
            {columns.map((c) => (
              <th
                key={c.key}
                className={`whitespace-nowrap px-5 py-3 font-medium ${c.thClass || ""}`}
              >
                {c.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {loading &&
            Array.from({ length: loadingRows }).map((_, i) => (
              <tr
                key={`sk${i}`}
                className="border-b border-slate-200 last:border-0"
              >
                {columns.map((c) => (
                  <td key={c.key} className="px-5 py-3.5">
                    <Skeleton className={`h-4 ${i % 2 ? "w-2/3" : "w-4/5"}`} />
                  </td>
                ))}
              </tr>
            ))}
          {!loading && error && (
            <tr>
              <td colSpan={columns.length} className="px-5 py-12 text-center">
                <div className="flex flex-col items-center gap-2 text-sm text-red-600">
                  <Icon name="shield" size={20} />
                  <span>
                    {typeof error === "string"
                      ? error
                      : "Something went wrong while loading."}
                  </span>
                  {onRetry && (
                    <button
                      onClick={onRetry}
                      className="mt-1 rounded-lg border border-slate-200 px-3 py-1.5 text-xs text-slate-700 transition hover:border-blue-500"
                    >
                      Try again
                    </button>
                  )}
                </div>
              </td>
            </tr>
          )}
          {!loading && !error && rows.length === 0 && (
            <tr>
              <td
                colSpan={columns.length}
                className="px-5 py-12 text-center text-slate-500"
              >
                {empty}
              </td>
            </tr>
          )}
          {!loading &&
            !error &&
            rows.map((r, i) => (
              <tr
                key={r.id || i}
                onClick={onRowClick ? () => onRowClick(r) : undefined}
                className={`border-b border-slate-200 transition last:border-0 hover:bg-slate-100 ${onRowClick ? "cursor-pointer" : ""}`}
              >
                {columns.map((c) => (
                  <td
                    key={c.key}
                    className={`px-5 py-3.5 align-middle ${c.tdClass || "text-slate-600"}`}
                  >
                    {c.render ? c.render(r) : r[c.key]}
                  </td>
                ))}
              </tr>
            ))}
        </tbody>
      </table>
    </div>
  </Card>
);

export const Chip = ({ children }) => (
  <span className="inline-flex items-center rounded-md border border-slate-200 bg-slate-50 px-2 py-0.5 text-xs text-slate-600">
    {children}
  </span>
);

export const SmallButton = ({ children, icon, variant = "ghost", ...rest }) => {
  // whitespace-nowrap and shrink-0 are load-bearing, not polish. In a narrow
  // table cell the label wrapped — "Roll out" became "Roll" over "out" — which
  // doubled the height of every row in the catalog and pushed the column into
  // a horizontal scrollbar. A button is as wide as its label; it is the column
  // that should give.
  const base =
    "inline-flex shrink-0 items-center gap-1.5 whitespace-nowrap rounded-lg px-3.5 py-2 text-sm font-semibold transition duration-300 disabled:cursor-not-allowed disabled:opacity-40";
  const styles =
    variant === "primary"
      ? "bg-blue-600 text-white shadow-lg shadow-blue-600/40 hover:bg-blue-700"
      : "border border-slate-200 bg-slate-50 text-slate-900 hover:border-blue-600 hover:bg-blue-600 hover:text-white";
  return (
    <button className={`${base} ${styles}`} {...rest}>
      {icon && <Icon name={icon} size={16} />}
      {children}
    </button>
  );
};

import { createPortal } from "react-dom";

export const ConfirmModal = ({
  open,
  title,
  message,
  confirmLabel = "Confirm",
  cancelLabel = "Cancel",
  tone = "danger",
  onConfirm,
  onClose,
}) => {
  if (!open) return null;
  const danger = tone === "danger";
  return createPortal(
    <div className="fixed inset-0 z-[90] flex items-center justify-center p-4">
      <div
        className="absolute inset-0 bg-slate-900/25 backdrop-blur-md"
        onClick={onClose}
      />
      <div className="rw-pop relative w-full max-w-md rounded-2xl border border-slate-200 bg-[#ffffff] p-6 shadow-2xl">
        <div className="flex items-start gap-3">
          <span
            className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-xl border ${danger ? "border-red-400/30 bg-red-400/10 text-red-600" : "border-slate-300 bg-slate-100 text-slate-900"}`}
          >
            <Icon name={danger ? "trash" : "shield"} size={20} />
          </span>
          <div className="min-w-0">
            <h2 className="text-base font-semibold text-slate-900">{title}</h2>
            {message && (
              <p className="mt-1 text-sm leading-relaxed text-slate-500">
                {message}
              </p>
            )}
          </div>
        </div>
        <div className="mt-6 flex justify-end gap-2">
          <button
            onClick={onClose}
            className="rounded-lg border border-slate-200 px-4 py-2 text-sm font-semibold text-slate-900 transition hover:border-blue-600 hover:bg-blue-600 hover:text-white"
          >
            {cancelLabel}
          </button>
          <button
            onClick={onConfirm}
            className={`rounded-lg px-4 py-2 text-sm font-semibold transition ${danger ? "bg-red-500/90 text-white hover:bg-red-600" : "bg-blue-600 text-white hover:bg-blue-700"}`}
          >
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>,
    document.body
  );
};

export const Pagination = ({ page, pageSize, totalItems, onPageChange }) => {
  const totalPages = Math.ceil(totalItems / pageSize) || 1;
  if (totalPages <= 1) return null;

  const firstItem = (page - 1) * pageSize + 1;
  const lastItem = Math.min(page * pageSize, totalItems);

  // Same hover language as SmallButton, plus a disabled state that actually
  // reads as disabled - opacity alone left the button looking half-active.
  const navButton =
    "inline-flex items-center gap-1.5 rounded-lg border border-slate-200 bg-white px-3 py-1.5 font-medium text-slate-600 shadow-sm transition duration-300 " +
    "hover:border-blue-600 hover:bg-blue-600 hover:text-white " +
    "focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-600 " +
    "disabled:cursor-not-allowed disabled:border-slate-200 disabled:bg-slate-100 disabled:text-slate-400 disabled:shadow-none " +
    "disabled:hover:border-slate-200 disabled:hover:bg-slate-100 disabled:hover:text-slate-400";

  return (
    // mt-3 matters: a Table is itself a rounded Card, so a flush footer card
    // put two borders and two corner radii against each other and the seam
    // read as one broken box rather than two elements.
    <Card
      role="navigation"
      aria-label="Pagination"
      className="mt-3 flex flex-col gap-3 px-5 py-3 text-sm sm:flex-row sm:items-center sm:justify-between"
    >
      <p className="text-slate-500">
        Showing{" "}
        <span className="font-semibold tabular-nums text-slate-900">
          {firstItem}
        </span>{" "}
        to{" "}
        <span className="font-semibold tabular-nums text-slate-900">
          {lastItem}
        </span>{" "}
        of{" "}
        <span className="font-semibold tabular-nums text-slate-900">
          {totalItems}
        </span>{" "}
        entries
      </p>

      <div className="flex items-center gap-2 self-end sm:self-auto">
        <button
          type="button"
          disabled={page === 1}
          onClick={() => onPageChange(page - 1)}
          className={navButton}
          aria-label="Previous page"
        >
          <Icon name="chevron" size={14} className="rotate-180" />
          Previous
        </button>

        {/* tabular-nums so the label does not jitter as the page count changes */}
        <span className="whitespace-nowrap px-2 text-xs font-medium tabular-nums text-slate-500">
          Page {page} of {totalPages}
        </span>

        <button
          type="button"
          disabled={page === totalPages}
          onClick={() => onPageChange(page + 1)}
          className={navButton}
          aria-label="Next page"
        >
          Next
          <Icon name="chevron" size={14} />
        </button>
      </div>
    </Card>
  );
};
