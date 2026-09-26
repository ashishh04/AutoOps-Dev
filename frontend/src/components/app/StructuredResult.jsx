import React from "react";
import Icon from "../Icon";

/**
 * A workflow result that arrived as machine output, rendered for a person.
 *
 * <h2>Why this exists</h2>
 * Several workflows are dual-purpose: an agent calls them as a tool, and a
 * customer can also run them directly. Their output is written for the agent —
 * {@code window_hours=168 events_total=7 runs_failed=0} — because terse,
 * unambiguous key/value costs a model few tokens and cannot be misread. Shown
 * to a customer on the Result pane, the same string reads as debug output that
 * escaped into the product.
 *
 * <p>The fix is presentation, not data. Rewriting the workflow to emit prose
 * would either cost an LLM call on what is a plain data lookup, or force one
 * format to serve two audiences badly. The numbers are already there and
 * already correct; they were just never laid out.
 *
 * <h2>What it will not do</h2>
 * It renders what it was given and nothing else. No derived percentages, no
 * invented trend, no "healthy" verdict the workflow did not state — a summary
 * that editorialises is a summary that can be wrong in a way the underlying
 * data was not. Anything it cannot parse confidently falls through to the
 * caller's own renderer rather than being forced into tiles.
 */

/** `key=value` pairs, values optionally quoted. Anchored to avoid matching prose. */
const PAIR = /([a-z][a-z0-9_]*)=("[^"]*"|\S+)/gi;

/** A leading `SOMETHING (3)` banner, which several of these outputs open with. */
const BANNER = /^([A-Z][A-Z \-]{2,})\s*\((\d+)\)\s*/;

/**
 * Keys the reader already knows, because they navigated here.
 *
 * <p>These outputs are written for an agent, which genuinely needs to be told
 * which tenant and project it is reasoning about — it has no address bar. A
 * customer has one. Echoing an internal tenant slug back at the person whose
 * tenant it is adds nothing and reads like leaked plumbing.
 *
 * <p>Dropped from the LAYOUT only. The workflow still emits them, the agent
 * still receives them, and nothing about the run record changes.
 */
const ALREADY_KNOWN = new Set([
  "tenant",
  "tenant_id",
  "tenantid",
  "project",
  "project_id",
  "projectid",
]);

/**
 * Splits the raw text into the parts we can lay out.
 *
 * <p>Returns null when this does not look like structured output at all, which
 * is the common case — most workflows emit a written report and must keep
 * being rendered as one.
 */
export function parseStructured(raw) {
  if (!raw || typeof raw !== "string") return null;
  const text = raw.trim();
  if (!text) return null;

  // A JSON object or array, with or without a `JSON ` prefix. Taken from the
  // first brace so a banner line before it does not defeat the parse.
  let json = null;
  const jsonAt = text.search(/[[{]/);
  if (jsonAt >= 0) {
    const candidate = text.slice(jsonAt);
    try {
      json = JSON.parse(candidate);
    } catch {
      json = null;
    }
  }

  const head = jsonAt >= 0 ? text.slice(0, jsonAt) : text;

  let banner = null;
  let rest = head;
  const m = head.match(BANNER);
  if (m) {
    // Trimmed: the character class swallows the space before the bracket,
    // which would otherwise render as a gap before the label.
    banner = {
      label: titleise(m[1].trim().toLowerCase()),
      count: Number(m[2]),
    };
    rest = head.slice(m[0].length);
  }

  const pairs = [];
  let hit;
  PAIR.lastIndex = 0;
  while ((hit = PAIR.exec(rest)) !== null) {
    pairs.push([hit[1], hit[2].replace(/^"|"$/g, "")]);
  }

  // The guard that keeps written reports out of here. A paragraph of prose can
  // contain one incidental `x=1`; three or more pairs, a banner, or parsed
  // JSON is the signal that this was written for a machine.
  if (pairs.length < 3 && !banner && !json) return null;

  // Prose that merely mentions a pair or two, with far more words than data.
  const words = rest.split(/\s+/).filter(Boolean).length;
  if (!banner && !json && words > pairs.length * 3) return null;

  // `JSON` is the marker separating the prose head from the payload, not a
  // sentence. Left in, it rendered as a stray one-word paragraph under the
  // table — which looks exactly like a rendering bug, because it was one.
  const leftover = rest
    .replace(PAIR, "")
    .replace(/\bJSON\b/gi, "")
    .replace(/\s+/g, " ")
    .trim();

  return { banner, pairs, json, leftover };
}

const titleise = (key) =>
  key
    .replace(/_/g, " ")
    .replace(/\b\w/g, (c) => c.toUpperCase())
    .replace(/\bId\b/g, "ID")
    .replace(/\bUtc\b/g, "UTC");

const NUMERIC = /^-?\d+(\.\d+)?$/;

/** An ISO timestamp becomes something a person can read; anything else is left alone. */
function humanValue(value) {
  if (/^\d{4}-\d{2}-\d{2}T[\d:.]+Z?$/.test(value)) {
    const d = new Date(value);
    if (!Number.isNaN(d.getTime())) {
      return d.toLocaleString(undefined, {
        day: "numeric",
        month: "short",
        year: "numeric",
        hour: "2-digit",
        minute: "2-digit",
      });
    }
  }
  return value;
}

/**
 * Colour carries meaning or it is noise.
 *
 * <p>Only a failure count that is actually non-zero goes red, and only a
 * success count that is actually non-zero goes green. A red "0 failed" tile
 * trains people to ignore red, which costs exactly when it matters.
 */
function tone(key, value) {
  const n = NUMERIC.test(value) ? Number(value) : null;
  if (/fail|error|breach|missed|stalled/i.test(key)) {
    return n !== null && n > 0 ? "text-red-600" : "text-slate-400";
  }
  if (/success|succeeded|passed|resolved/i.test(key)) {
    return n !== null && n > 0 ? "text-emerald-600" : "text-slate-400";
  }
  if (n !== null && n === 0) return "text-slate-400";
  return "text-slate-900";
}

/** Scalar counts read as tiles; everything else as a labelled line. */
const isMetric = (key, value) => NUMERIC.test(value);

export default function StructuredResult({ data }) {
  const { banner, pairs, json, leftover } = data;
  const shown = pairs.filter(([k]) => !ALREADY_KNOWN.has(k.toLowerCase()));
  const metrics = shown.filter(([k, v]) => isMetric(k, v));
  const details = shown.filter(([k, v]) => !isMetric(k, v));

  // Prefer a populated collection, but fall back to an empty one rather than
  // to null. Requiring a non-empty array is what hid the "nothing matched"
  // message below: an empty result is an ANSWER, and dropping it leaves a
  // blank pane that reads as a rendering failure.
  const collections =
    json && typeof json === "object" && !Array.isArray(json)
      ? Object.values(json).filter(Array.isArray)
      : [];
  const rows = Array.isArray(json)
    ? json
    : (collections.find((v) => v.length > 0) ?? collections[0] ?? null);

  return (
    <div className="space-y-5">
      {banner && (
        <div className="flex items-baseline gap-3">
          <span className="text-3xl font-semibold tabular-nums text-slate-900">
            {banner.count}
          </span>
          <span className="text-sm font-medium uppercase tracking-wide text-slate-500">
            {banner.label}
          </span>
        </div>
      )}

      {metrics.length > 0 && (
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
          {metrics.map(([k, v]) => (
            <div
              key={k}
              className="rounded-xl border border-slate-200 bg-white px-4 py-3"
            >
              <div className="text-[10px] font-semibold uppercase tracking-wider text-slate-500">
                {titleise(k)}
              </div>
              <div
                className={`mt-1 text-2xl font-semibold tabular-nums ${tone(k, v)}`}
              >
                {v}
              </div>
            </div>
          ))}
        </div>
      )}

      {details.length > 0 && (
        <dl className="divide-y divide-slate-100 rounded-xl border border-slate-200 bg-white">
          {details.map(([k, v]) => (
            <div
              key={k}
              className="flex items-baseline justify-between gap-4 px-4 py-2.5"
            >
              <dt className="text-xs font-medium text-slate-500">
                {titleise(k)}
              </dt>
              <dd className="min-w-0 truncate text-right text-sm text-slate-800">
                {humanValue(v)}
              </dd>
            </div>
          ))}
        </dl>
      )}

      {rows && rows.length > 0 && <Rows rows={rows} />}

      {/* A parsed-but-empty collection is a real answer and says so plainly,
          rather than leaving a blank space that reads as a rendering failure. */}
      {rows && rows.length === 0 && (
        <p className="rounded-xl border border-dashed border-slate-200 px-4 py-6 text-center text-sm text-slate-500">
          Nothing matched in this window.
        </p>
      )}

      {leftover && (
        <p className="text-sm leading-relaxed text-slate-600">{leftover}</p>
      )}
    </div>
  );
}

/** A list of objects as a table, capped so one huge result cannot wedge the pane. */
function Rows({ rows }) {
  const LIMIT = 50;
  const shown = rows.slice(0, LIMIT);
  const columns = [
    ...new Set(
      shown.flatMap((r) => (r && typeof r === "object" ? Object.keys(r) : [])),
    ),
  ].slice(0, 6);

  if (columns.length === 0) {
    return (
      <ul className="space-y-1">
        {shown.map((r, i) => (
          <li key={i} className="text-sm text-slate-700">
            {String(r)}
          </li>
        ))}
      </ul>
    );
  }

  return (
    <div className="overflow-hidden rounded-xl border border-slate-200">
      <div className="overflow-x-auto">
        <table className="w-full text-left text-sm">
          <thead className="bg-slate-50">
            <tr>
              {columns.map((c) => (
                <th
                  key={c}
                  className="whitespace-nowrap px-4 py-2 text-[10px] font-semibold uppercase tracking-wider text-slate-500"
                >
                  {titleise(c)}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {shown.map((r, i) => (
              <tr key={i} className="hover:bg-slate-50">
                {columns.map((c) => (
                  <td key={c} className="px-4 py-2 text-slate-700">
                    {formatCell(r?.[c])}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {rows.length > LIMIT && (
        // Said, not silently cut. A table that stops without saying so is a
        // list the reader believes is complete.
        <p className="border-t border-slate-100 bg-slate-50 px-4 py-2 text-[11px] text-slate-500">
          <Icon name="info" size={12} className="mr-1 inline" />
          Showing {LIMIT} of {rows.length}.
        </p>
      )}
    </div>
  );
}

function formatCell(value) {
  if (value === null || value === undefined || value === "") {
    return <span className="text-slate-300">—</span>;
  }
  if (typeof value === "boolean") return value ? "Yes" : "No";
  if (typeof value === "object") return JSON.stringify(value);
  return humanValue(String(value));
}
