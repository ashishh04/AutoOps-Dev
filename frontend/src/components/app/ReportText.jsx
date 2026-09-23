import React from "react";

/**
 * Renders an agent's or a workflow's written report as something a person
 * would want to read.
 *
 * <p>Models write in markdown whether or not anyone asked them to, and until
 * now we printed that verbatim: a report opened with `**No alarms fired**`,
 * section dividers arrived as `---`, and every section label carried its own
 * asterisks. That reads as a dump of the model's own notation rather than as
 * an answer — the deliverable looked like metadata about a deliverable.
 *
 * <p>So the notation is consumed rather than displayed: headings become
 * headings, bold becomes bold, bullets become bullets, and a line that is
 * nothing but a bold phrase — how models actually write section labels — is
 * promoted to a heading instead of shouting mid-paragraph. Nothing here goes
 * near `dangerouslySetInnerHTML`; the text comes from a model, so it is parsed
 * into React nodes and never interpreted as HTML.
 *
 * <p>Deliberately not a full markdown implementation and deliberately not a
 * dependency. It covers what these reports actually contain, and anything it
 * does not recognise falls through as prose — the correct failure, since the
 * worst case is the text the reader would have seen anyway.
 */

const INLINE =
  /(\*\*[^*]+\*\*|__[^_]+__|`[^`]+`|\[[^\]]+\]\((?:https?:\/\/|\/)[^)]+\))/g;

function inline(text, keyBase) {
  return String(text)
    .split(INLINE)
    .filter(Boolean)
    .map((part, i) => {
      const key = `${keyBase}-${i}`;
      const link = part.match(/^\[([^\]]+)\]\(([^)]+)\)$/);
      if (link) {
        return (
          <a
            key={key}
            href={link[2]}
            target="_blank"
            rel="noreferrer noopener"
            className="font-medium text-blue-600 underline decoration-blue-300 underline-offset-2 hover:text-blue-700"
          >
            {link[1]}
          </a>
        );
      }
      if (/^\*\*[^*]+\*\*$/.test(part) || /^__[^_]+__$/.test(part)) {
        return (
          <strong key={key} className="font-semibold text-slate-900">
            {part.slice(2, -2)}
          </strong>
        );
      }
      if (/^`[^`]+`$/.test(part)) {
        return (
          <code
            key={key}
            className="rounded bg-slate-100 px-1 py-0.5 font-mono text-[0.85em] text-slate-800"
          >
            {part.slice(1, -1)}
          </code>
        );
      }
      return <React.Fragment key={key}>{part}</React.Fragment>;
    });
}

/** A line that is one bold phrase is a section label, not a shout. */
const BOLD_LINE = /^\s*(?:\*\*|__)(.+?)(?:\*\*|__)\s*:?\s*$/;
const HEADING = /^\s*(#{1,6})\s+(.*)$/;
const RULE = /^\s*(?:-{3,}|\*{3,}|_{3,})\s*$/;
const BULLET = /^(\s*)[-*+•]\s+(.*)$/;
const NUMBERED = /^(\s*)(\d{1,3})[.)]\s+(.*)$/;
const TABLE_ROW = /^\s*\|.*\|\s*$/;
const TABLE_DIVIDER = /^\s*\|[\s:|-]+\|\s*$/;
const CONTINUATION = /^\s{2,}\S/;

const cells = (row) =>
  row
    .trim()
    .replace(/^\||\|$/g, "")
    .split("|")
    .map((c) => c.trim());

export default function ReportText({ source, className = "" }) {
  const text = String(source ?? "");
  if (!text.trim()) return null;

  const lines = text.split("\n");
  const blocks = [];
  let i = 0;

  const key = (kind) => `${kind}-${blocks.length}`;

  while (i < lines.length) {
    const line = lines[i];

    if (!line.trim()) {
      i += 1;
      continue;
    }

    // Fenced code stays monospaced and unwrapped — it is usually a command or
    // a slice of real output, and reflowing it would change what it says.
    if (line.trimStart().startsWith("```")) {
      const body = [];
      i += 1;
      while (i < lines.length && !lines[i].trimStart().startsWith("```")) {
        body.push(lines[i]);
        i += 1;
      }
      i += 1;
      blocks.push(
        <pre
          key={key("code")}
          className="overflow-x-auto rounded-xl bg-slate-900 p-3.5 font-mono text-[12px] leading-relaxed text-slate-100"
        >
          <code>{body.join("\n")}</code>
        </pre>,
      );
      continue;
    }

    if (RULE.test(line)) {
      // The model's own divider. Kept as breathing room rather than a drawn
      // line: a report with four rules through it looks ruled, not organised.
      blocks.push(<div key={key("rule")} className="h-1" />);
      i += 1;
      continue;
    }

    const heading = line.match(HEADING);
    const boldLine = heading ? null : line.match(BOLD_LINE);
    if (heading || boldLine) {
      const level = heading ? heading[1].length : 3;
      const label = (heading ? heading[2] : boldLine[1]).replace(/:$/, "");
      blocks.push(
        <h4
          key={key("h")}
          className={`${blocks.length === 0 ? "" : "pt-2"} ${
            level <= 2
              ? "text-[15px] font-semibold text-slate-900"
              : "text-sm font-semibold text-slate-900"
          }`}
        >
          {inline(label, key("h"))}
        </h4>,
      );
      i += 1;
      continue;
    }

    if (TABLE_ROW.test(line)) {
      const rows = [];
      while (i < lines.length && TABLE_ROW.test(lines[i])) {
        if (!TABLE_DIVIDER.test(lines[i])) rows.push(cells(lines[i]));
        i += 1;
      }
      const [head, ...body] = rows;
      blocks.push(
        <div key={key("table")} className="overflow-x-auto">
          <table className="w-full border-collapse text-left text-[13px]">
            <thead>
              <tr>
                {(head || []).map((c, n) => (
                  <th
                    key={n}
                    className="border-b border-slate-200 px-2 py-1.5 font-semibold text-slate-900"
                  >
                    {inline(c, `th-${n}`)}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {body.map((row, r) => (
                <tr key={r}>
                  {row.map((c, n) => (
                    <td
                      key={n}
                      className="border-b border-slate-100 px-2 py-1.5 align-top text-slate-700"
                    >
                      {inline(c, `td-${r}-${n}`)}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>,
      );
      continue;
    }

    const bullet = line.match(BULLET);
    const numbered = bullet ? null : line.match(NUMBERED);
    if (bullet || numbered) {
      const ordered = !!numbered;
      const pattern = ordered ? NUMBERED : BULLET;
      const items = [];
      while (i < lines.length) {
        const m = lines[i].match(pattern);
        if (!m) {
          // An indented follow-on line belongs to the item above it, not to a
          // new paragraph after the list.
          if (items.length && CONTINUATION.test(lines[i]) && lines[i].trim()) {
            items[items.length - 1].text += ` ${lines[i].trim()}`;
            i += 1;
            continue;
          }
          break;
        }
        items.push({
          depth: Math.min(2, Math.floor(m[1].length / 2)),
          text: ordered ? m[3] : m[2],
          marker: ordered ? m[2] : null,
        });
        i += 1;
      }
      blocks.push(
        <ul key={key("list")} className="space-y-1.5">
          {items.map((item, n) => (
            <li
              key={n}
              className="flex gap-2 text-[13px] leading-relaxed text-slate-700"
              style={item.depth ? { paddingLeft: `${item.depth * 0.9}rem` } : undefined}
            >
              {ordered ? (
                <span className="shrink-0 font-semibold tabular-nums text-slate-400">
                  {item.marker}.
                </span>
              ) : (
                <span className="mt-[0.55em] h-1 w-1 shrink-0 rounded-full bg-slate-400" />
              )}
              <span className="min-w-0 break-words">{inline(item.text, `li-${n}`)}</span>
            </li>
          ))}
        </ul>,
      );
      continue;
    }

    // Everything else is prose. Consecutive lines are one paragraph: models
    // hard-wrap their output, and honouring every newline as a break is part
    // of what makes a written answer look like console output.
    const para = [];
    while (
      i < lines.length &&
      lines[i].trim() &&
      !RULE.test(lines[i]) &&
      !HEADING.test(lines[i]) &&
      !BOLD_LINE.test(lines[i]) &&
      !BULLET.test(lines[i]) &&
      !NUMBERED.test(lines[i]) &&
      !TABLE_ROW.test(lines[i]) &&
      !lines[i].trimStart().startsWith("```")
    ) {
      para.push(lines[i].trim());
      i += 1;
    }
    blocks.push(
      <p key={key("p")} className="break-words text-[13px] leading-relaxed text-slate-700">
        {inline(para.join(" "), key("p"))}
      </p>,
    );
  }

  return <div className={`space-y-2.5 ${className}`}>{blocks}</div>;
}
