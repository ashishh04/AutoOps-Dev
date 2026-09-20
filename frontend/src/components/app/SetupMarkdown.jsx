import React, { useState } from "react";
import Icon from "../Icon";

/**
 * A deliberately small markdown renderer for setup guides.
 *
 * Covers only what these guides actually use — headings, bold, bullets, links,
 * inline code and fenced blocks — rather than pulling in a parser for one
 * dialog. It builds React nodes; nothing here goes near
 * `dangerouslySetInnerHTML`, because this text originates upstream and is only
 * rewritten by us, not authored by us.
 */

const INLINE = /(\[[^\]]+\]\([^)]+\)|\*\*[^*]+\*\*|`[^`]+`)/g;

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
            className="text-indigo-600 underline decoration-indigo-300 hover:text-indigo-700"
          >
            {link[1]}
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
            className="rounded bg-slate-100 px-1 py-0.5 font-mono text-[0.85em] text-slate-800"
          >
            {part.slice(1, -1)}
          </code>
        );
      }
      return <React.Fragment key={key}>{part}</React.Fragment>;
    });
}

function CodeBlock({ code }) {
  const [copied, setCopied] = useState(false);
  const copy = () => {
    navigator.clipboard?.writeText(code).then(
      () => {
        setCopied(true);
        setTimeout(() => setCopied(false), 1500);
      },
      () => {},
    );
  };
  return (
    <div className="group relative">
      <pre className="overflow-x-auto rounded-xl bg-slate-900 p-4 text-xs leading-relaxed text-slate-100">
        <code>{code}</code>
      </pre>
      {/* These snippets are meant to be pasted into a DAG or a config file.
          Making someone select 30 lines by hand is the difference between
          "followed the guide" and "gave up". */}
      <button
        type="button"
        onClick={copy}
        className="absolute right-2 top-2 rounded-lg bg-slate-700/80 px-2 py-1 text-[11px] font-medium text-slate-100 opacity-0 transition group-hover:opacity-100"
      >
        {copied ? "Copied" : "Copy"}
      </button>
    </div>
  );
}

export default function SetupMarkdown({ source }) {
  if (!source) return null;
  const blocks = [];
  const lines = String(source).split("\n");
  let i = 0;
  let list = [];

  const flushList = () => {
    if (list.length) {
      blocks.push(
        <ul key={`ul-${blocks.length}`} className="space-y-1.5 pl-1">
          {list.map((item, n) => (
            <li key={n} className="flex gap-2 text-sm text-slate-600">
              <span className="mt-2 h-1 w-1 shrink-0 rounded-full bg-slate-400" />
              <span className="min-w-0 break-words">
                {inline(item, `li-${blocks.length}-${n}`)}
              </span>
            </li>
          ))}
        </ul>,
      );
      list = [];
    }
  };

  while (i < lines.length) {
    const line = lines[i];

    if (line.trimStart().startsWith("```")) {
      flushList();
      const body = [];
      i += 1;
      while (i < lines.length && !lines[i].trimStart().startsWith("```")) {
        body.push(lines[i]);
        i += 1;
      }
      i += 1;
      blocks.push(<CodeBlock key={`code-${blocks.length}`} code={body.join("\n")} />);
      continue;
    }

    const heading = line.match(/^(#{1,6})\s+(.*)$/);
    if (heading) {
      flushList();
      blocks.push(
        <h4
          key={`h-${blocks.length}`}
          className="pt-1 text-sm font-semibold text-slate-900"
        >
          {inline(heading[2], `h-${blocks.length}`)}
        </h4>,
      );
      i += 1;
      continue;
    }

    const bullet = line.match(/^\s*[-*]\s+(.*)$/);
    if (bullet) {
      list.push(bullet[1]);
      i += 1;
      continue;
    }

    if (!line.trim()) {
      flushList();
      i += 1;
      continue;
    }

    flushList();
    blocks.push(
      <p
        key={`p-${blocks.length}`}
        className="break-words text-sm leading-relaxed text-slate-600"
      >
        {inline(line, `p-${blocks.length}`)}
      </p>,
    );
    i += 1;
  }
  flushList();

  return <div className="space-y-3">{blocks}</div>;
}

export { CodeBlock };
