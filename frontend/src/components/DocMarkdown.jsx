import React from "react";
import { Link } from "react-router-dom";
import { CodeBlock } from "./app/SetupMarkdown";

/**
 * The markdown renderer for full documentation pages.
 *
 * Separate from `SetupMarkdown` because the two have different jobs:
 * that one renders a few lines inside a dialog and flattens every heading to
 * one size, while a docs page needs a real heading hierarchy, tables, numbered
 * lists and blockquotes. Its `CodeBlock` — with copy-to-clipboard — is reused
 * rather than reimplemented.
 *
 * Still no parser dependency and still no `dangerouslySetInnerHTML`: this
 * builds React nodes from text we author ourselves, and anything it does not
 * recognise renders as a plain paragraph rather than disappearing.
 */

const INLINE = /(\[[^\]]+\]\([^)]+\)|\*\*[^*]+\*\*|`[^`]+`|\*[^*\s][^*]*\*)/g;

// A link between two markdown files becomes a console route: ../api/x.md ->
// /docs/api/x. Rewriting here rather than in the files keeps the sources
// readable on disk and on GitHub, where the relative paths are the correct ones.
function toRoute(href) {
  if (!href.endsWith(".md")) return null;
  const clean = href.replace(/\.md$/, "").replace(/^\.\//, "");
  const parts = clean.split("/").filter((p) => p && p !== "..");
  if (parts.length === 1) return null; // same-folder link, needs its category
  return `/docs/${parts.slice(-2).join("/")}`;
}

function inline(text, keyBase) {
  return String(text)
    .split(INLINE)
    .filter(Boolean)
    .map((part, i) => {
      const key = `${keyBase}-${i}`;

      const link = part.match(/^\[([^\]]+)\]\(([^)]+)\)$/);
      if (link) {
        const [, label, href] = link;
        const route = toRoute(href);
        if (route) {
          return (
            <Link
              key={key}
              to={route}
              className="font-medium text-blue-600 underline decoration-blue-200 underline-offset-2 transition hover:text-blue-700"
            >
              {label}
            </Link>
          );
        }
        if (href.startsWith("#") || href.startsWith("/")) {
          return (
            <a
              key={key}
              href={href}
              className="font-medium text-blue-600 underline decoration-blue-200 underline-offset-2 hover:text-blue-700"
            >
              {label}
            </a>
          );
        }
        return (
          <a
            key={key}
            href={href}
            target="_blank"
            rel="noreferrer noopener"
            className="font-medium text-blue-600 underline decoration-blue-200 underline-offset-2 hover:text-blue-700"
          >
            {label}
          </a>
        );
      }

      if (/^\*\*[^*]+\*\*$/.test(part)) {
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
            className="rounded bg-slate-100 px-1.5 py-0.5 font-mono text-[0.85em] text-slate-800 ring-1 ring-slate-200"
          >
            {part.slice(1, -1)}
          </code>
        );
      }
      if (/^\*[^*\s][^*]*\*$/.test(part)) {
        return (
          <em key={key} className="italic">
            {part.slice(1, -1)}
          </em>
        );
      }
      return <React.Fragment key={key}>{part}</React.Fragment>;
    });
}

/** `## Some heading` -> `some-heading`, so the on-page nav can link to it. */
export function slugifyHeading(text) {
  return String(text)
    .toLowerCase()
    .replace(/`/g, "")
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-|-$/g, "");
}

const HEADING_CLASS = {
  1: "mt-0 text-3xl font-extrabold tracking-tight text-slate-900",
  2: "mt-12 scroll-mt-28 border-t border-slate-200 pt-8 text-2xl font-bold tracking-tight text-slate-900",
  3: "mt-8 scroll-mt-28 text-lg font-semibold text-slate-900",
  4: "mt-6 scroll-mt-28 text-base font-semibold text-slate-900",
};

function isTableRow(line) {
  return line.trim().startsWith("|") && line.trim().endsWith("|");
}

function splitRow(line) {
  return line
    .trim()
    .replace(/^\||\|$/g, "")
    .split("|")
    .map((c) => c.trim());
}

/** Headings, so a page can render its own contents list. */
export function outline(source) {
  return String(source)
    .split("\n")
    .reduce(
      (acc, line) => {
        if (line.trimStart().startsWith("```")) acc.inCode = !acc.inCode;
        if (acc.inCode) return acc;
        const m = line.match(/^(##)\s+(.*)$/);
        if (m) acc.items.push({ text: m[2].trim(), id: slugifyHeading(m[2]) });
        return acc;
      },
      { items: [], inCode: false },
    ).items;
}

export default function DocMarkdown({ source }) {
  if (!source) return null;

  const blocks = [];
  // CRLF, in case a caller hands over a file straight off a Windows checkout:
  // every branch below splits on "\n", and a trailing "\r" would end up inside
  // headings, table cells and anchor ids.
  const lines = String(source).replace(/\r\n?/g, "\n").split("\n");
  let i = 0;
  let bullets = [];
  let numbers = [];

  const flush = () => {
    if (bullets.length) {
      const items = bullets;
      bullets = [];
      blocks.push(
        <ul key={`ul-${blocks.length}`} className="mt-4 space-y-2">
          {items.map((item, n) => (
            <li
              key={n}
              className="flex gap-3 text-[15px] leading-relaxed text-slate-600"
            >
              <span className="mt-2.5 h-1.5 w-1.5 shrink-0 rounded-full bg-slate-400" />
              <span className="min-w-0">
                {inline(item, `li-${blocks.length}-${n}`)}
              </span>
            </li>
          ))}
        </ul>,
      );
    }
    if (numbers.length) {
      const items = numbers;
      numbers = [];
      blocks.push(
        <ol key={`ol-${blocks.length}`} className="mt-4 space-y-2">
          {items.map((item, n) => (
            <li
              key={n}
              className="flex gap-3 text-[15px] leading-relaxed text-slate-600"
            >
              <span className="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-slate-100 text-xs font-semibold text-slate-600">
                {n + 1}
              </span>
              <span className="min-w-0">
                {inline(item, `oli-${blocks.length}-${n}`)}
              </span>
            </li>
          ))}
        </ol>,
      );
    }
  };

  while (i < lines.length) {
    const line = lines[i];

    if (line.trimStart().startsWith("```")) {
      flush();
      const body = [];
      i += 1;
      while (i < lines.length && !lines[i].trimStart().startsWith("```")) {
        body.push(lines[i]);
        i += 1;
      }
      i += 1;
      blocks.push(
        <div key={`code-${blocks.length}`} className="mt-5">
          <CodeBlock code={body.join("\n")} />
        </div>,
      );
      continue;
    }

    // A table needs its header row and the separator under it; anything else
    // starting with a pipe is just a paragraph that happens to.
    if (
      isTableRow(line) &&
      isTableRow(lines[i + 1] || "") &&
      /^[\s|:-]+$/.test(lines[i + 1])
    ) {
      flush();
      const head = splitRow(line);
      i += 2;
      const rows = [];
      while (i < lines.length && isTableRow(lines[i])) {
        rows.push(splitRow(lines[i]));
        i += 1;
      }
      blocks.push(
        <div
          key={`table-${blocks.length}`}
          className="mt-6 overflow-x-auto rounded-xl border border-slate-200"
        >
          <table className="w-full border-collapse text-left text-sm">
            <thead className="bg-slate-50">
              <tr>
                {head.map((cell, n) => (
                  <th
                    key={n}
                    className="px-4 py-3 font-semibold text-slate-900"
                  >
                    {inline(cell, `th-${blocks.length}-${n}`)}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map((row, r) => (
                <tr key={r} className="border-t border-slate-200 align-top">
                  {row.map((cell, c) => (
                    <td key={c} className="px-4 py-3 text-slate-600">
                      {inline(cell, `td-${blocks.length}-${r}-${c}`)}
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

    if (line.trimStart().startsWith(">")) {
      flush();
      const body = [];
      while (i < lines.length && lines[i].trimStart().startsWith(">")) {
        body.push(lines[i].replace(/^\s*>\s?/, ""));
        i += 1;
      }
      blocks.push(
        <blockquote
          key={`quote-${blocks.length}`}
          className="mt-6 rounded-r-xl border-l-4 border-amber-400 bg-amber-50/60 px-5 py-4 text-[15px] leading-relaxed text-slate-700"
        >
          {body
            .join("\n")
            .split(/\n\s*\n/)
            .map((p, n) => (
              <p key={n} className={n ? "mt-3" : ""}>
                {inline(p.replace(/\n/g, " "), `q-${blocks.length}-${n}`)}
              </p>
            ))}
        </blockquote>,
      );
      continue;
    }

    const heading = line.match(/^(#{1,4})\s+(.*)$/);
    if (heading) {
      flush();
      const level = heading[1].length;
      const text = heading[2].trim();
      const Tag = `h${level}`;
      blocks.push(
        <Tag
          key={`h-${blocks.length}`}
          id={slugifyHeading(text)}
          className={HEADING_CLASS[level]}
        >
          {inline(text, `h-${blocks.length}`)}
        </Tag>,
      );
      i += 1;
      continue;
    }

    const bullet = line.match(/^\s*[-*]\s+(.*)$/);
    if (bullet) {
      if (numbers.length) flush();
      bullets.push(bullet[1]);
      i += 1;
      // A wrapped bullet continues on an indented line with no marker.
      while (
        i < lines.length &&
        /^\s{2,}\S/.test(lines[i]) &&
        !/^\s*[-*]\s+/.test(lines[i]) &&
        !lines[i].trimStart().startsWith("```")
      ) {
        bullets[bullets.length - 1] += ` ${lines[i].trim()}`;
        i += 1;
      }
      continue;
    }

    const numbered = line.match(/^\s*\d+\.\s+(.*)$/);
    if (numbered) {
      if (bullets.length) flush();
      numbers.push(numbered[1]);
      i += 1;
      while (
        i < lines.length &&
        /^\s{2,}\S/.test(lines[i]) &&
        !/^\s*\d+\.\s+/.test(lines[i]) &&
        !lines[i].trimStart().startsWith("```")
      ) {
        numbers[numbers.length - 1] += ` ${lines[i].trim()}`;
        i += 1;
      }
      continue;
    }

    if (!line.trim()) {
      flush();
      i += 1;
      continue;
    }

    // Paragraphs are re-joined across soft wraps: the sources are hard-wrapped
    // at 80 columns for reading on disk, and rendering each line as its own
    // paragraph would look like a poem.
    flush();
    const paragraph = [line.trim()];
    i += 1;
    while (
      i < lines.length &&
      lines[i].trim() &&
      !lines[i].startsWith("#") &&
      !lines[i].trimStart().startsWith("```") &&
      !lines[i].trimStart().startsWith(">") &&
      !isTableRow(lines[i]) &&
      !/^\s*[-*]\s+/.test(lines[i]) &&
      !/^\s*\d+\.\s+/.test(lines[i])
    ) {
      paragraph.push(lines[i].trim());
      i += 1;
    }
    blocks.push(
      <p
        key={`p-${blocks.length}`}
        className="mt-4 text-[15px] leading-relaxed text-slate-600"
      >
        {inline(paragraph.join(" "), `p-${blocks.length}`)}
      </p>,
    );
  }

  flush();

  return <div className="doc-body">{blocks}</div>;
}
