/**
 * Renders the governance policy set in docs/governance/ to distributable PDFs.
 *
 * The markdown files are the source of truth — they are reviewable, diffable
 * and readable without a build step, which is the point of a transparency
 * document. This script is only a renderer: it must never add a claim that is
 * not in the markdown.
 *
 *   node scripts/policy-pdfs.mjs          (or: npm run policy-pdfs)
 *
 * Output: docs/governance/pdf/*.pdf, plus a combined pack.
 */

import { readFileSync, mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import puppeteer from "puppeteer";

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = join(HERE, "..", "..");
const SRC = join(REPO, "docs", "governance");
const OUT = join(SRC, "pdf");

/** Bump when the CONTENT changes, not when this renderer does. */
const REVISION = "1.0";
const ISSUED = "2026-09-24";

// Order is the reading order: the boundary first, then the rules, then the
// detail, then the split of duties. A reader who stops after the first document
// has still read the part that prevents a misunderstanding.
const DOCUMENTS = [
  {
    slug: "scope-and-limitations",
    file: "scope-and-limitations.md",
    subtitle: "What an AutoOps compliance report is, and what it is not",
    classification: "Public",
  },
  {
    slug: "governance-policy",
    file: "governance-policy.md",
    subtitle:
      "The five live policies, their thresholds, and what each enforces",
    classification: "Public",
  },
  {
    slug: "control-mapping",
    file: "control-mapping.md",
    subtitle:
      "Every check, every pass/warn/fail condition, mapped to SOC 2, ISO 27001, HIPAA, PCI DSS and GDPR",
    classification: "Public",
  },
  {
    slug: "shared-responsibility",
    file: "shared-responsibility.md",
    subtitle:
      "Which obligations are the platform's, and which are the customer's",
    classification: "Public",
  },
];

// The line that keeps this set honest. It goes on every cover page, and it is
// the reason these documents can be handed to a customer at all.
const DISCLAIMER =
  "AutoOps holds no SOC 2, ISO 27001, HIPAA, PCI DSS or GDPR certification. " +
  "This document describes implemented platform behaviour, verifiable in source. " +
  "It is not an audit, an attestation, or a certificate, and no independent " +
  "assessor has examined the controls it describes.";

// ---------------------------------------------------------------- markdown

const escapeHtml = (s) =>
  String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

const INLINE = /(\[[^\]]+\]\([^)]+\)|\*\*[^*]+\*\*|`[^`]+`|\*[^*\s][^*]*\*)/g;

function inline(text) {
  return String(text)
    .split(INLINE)
    .filter(Boolean)
    .map((part) => {
      const link = part.match(/^\[([^\]]+)\]\(([^)]+)\)$/);
      // Cross-document links become plain emphasis: a PDF handed over on its
      // own cannot resolve ../api/x.md, and a dead blue link reads as a defect.
      if (link) return `<em>${escapeHtml(link[1])}</em>`;
      if (/^\*\*[^*]+\*\*$/.test(part))
        return `<strong>${escapeHtml(part.slice(2, -2))}</strong>`;
      if (/^`[^`]+`$/.test(part))
        return `<code>${escapeHtml(part.slice(1, -1))}</code>`;
      if (/^\*[^*\s][^*]*\*$/.test(part))
        return `<em>${escapeHtml(part.slice(1, -1))}</em>`;
      return escapeHtml(part);
    })
    .join("");
}

const isTableRow = (l) => l.trim().startsWith("|") && l.trim().endsWith("|");
const splitRow = (l) =>
  l
    .trim()
    .replace(/^\||\|$/g, "")
    .split("|")
    .map((c) => c.trim());

function markdownToHtml(source) {
  // CRLF: .gitattributes checks these files out with Windows line endings, and
  // every branch below splits on "\n".
  const lines = String(source).replace(/\r\n?/g, "\n").split("\n");
  const out = [];
  let i = 0;
  let bullets = [];
  let numbers = [];

  const flush = () => {
    if (bullets.length) {
      out.push(
        `<ul>${bullets.map((b) => `<li>${inline(b)}</li>`).join("")}</ul>`,
      );
      bullets = [];
    }
    if (numbers.length) {
      out.push(
        `<ol>${numbers.map((b) => `<li>${inline(b)}</li>`).join("")}</ol>`,
      );
      numbers = [];
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
      out.push(`<pre><code>${escapeHtml(body.join("\n"))}</code></pre>`);
      continue;
    }

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
      out.push(
        `<table><thead><tr>${head
          .map((c) => `<th>${inline(c)}</th>`)
          .join("")}</tr></thead><tbody>${rows
          .map(
            (r) => `<tr>${r.map((c) => `<td>${inline(c)}</td>`).join("")}</tr>`,
          )
          .join("")}</tbody></table>`,
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
      const paragraphs = body
        .join("\n")
        .split(/\n\s*\n/)
        .map((p) => `<p>${inline(p.replace(/\n/g, " "))}</p>`)
        .join("");
      out.push(`<blockquote>${paragraphs}</blockquote>`);
      continue;
    }

    if (/^-{3,}\s*$/.test(line)) {
      flush();
      out.push("<hr/>");
      i += 1;
      continue;
    }

    const heading = line.match(/^(#{1,4})\s+(.*)$/);
    if (heading) {
      flush();
      const level = heading[1].length;
      out.push(`<h${level}>${inline(heading[2].trim())}</h${level}>`);
      i += 1;
      continue;
    }

    const bullet = line.match(/^\s*[-*]\s+(.*)$/);
    if (bullet) {
      if (numbers.length) flush();
      bullets.push(bullet[1]);
      i += 1;
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

    flush();
    // The sources are hard-wrapped at 80 columns for reading on disk; one
    // source line is not one paragraph.
    const paragraph = [line.trim()];
    i += 1;
    while (
      i < lines.length &&
      lines[i].trim() &&
      !lines[i].startsWith("#") &&
      !lines[i].trimStart().startsWith("```") &&
      !lines[i].trimStart().startsWith(">") &&
      !/^-{3,}\s*$/.test(lines[i]) &&
      !isTableRow(lines[i]) &&
      !/^\s*[-*]\s+/.test(lines[i]) &&
      !/^\s*\d+\.\s+/.test(lines[i])
    ) {
      paragraph.push(lines[i].trim());
      i += 1;
    }
    out.push(`<p>${inline(paragraph.join(" "))}</p>`);
  }

  flush();
  return out.join("\n");
}

/** The first `# Heading`, and the body with that heading removed. */
function splitTitle(markdown) {
  const normalized = String(markdown).replace(/\r\n?/g, "\n");
  const match = normalized.match(/^#\s+(.*)$/m);
  const title = match ? match[1].trim() : "Untitled";
  const body = match
    ? normalized.replace(match[0], "").trimStart()
    : normalized;
  return { title, body };
}

// ---------------------------------------------------------------- template

const CSS = `
  @page { size: A4; margin: 20mm 18mm 22mm 18mm; }
  * { box-sizing: border-box; }
  body {
    font-family: -apple-system, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
    color: #0f172a; font-size: 10.5pt; line-height: 1.55; margin: 0;
    -webkit-print-color-adjust: exact; print-color-adjust: exact;
  }
  .cover { page-break-after: always; padding-top: 28mm; }
  .wordmark { font-size: 20pt; font-weight: 800; letter-spacing: -0.02em; }
  .wordmark span { color: #2563eb; }
  .cover h1 {
    font-size: 30pt; font-weight: 800; line-height: 1.12;
    letter-spacing: -0.02em; margin: 26mm 0 0 0; border: 0; padding: 0;
  }
  .cover .subtitle { font-size: 13pt; color: #475569; margin-top: 6mm; max-width: 145mm; }
  .cover .rule { height: 3px; width: 26mm; background: #2563eb; margin: 9mm 0; }
  .meta { width: 100%; border-collapse: collapse; margin-top: 4mm; max-width: 120mm; }
  .meta td { padding: 2.4mm 0; border-bottom: 1px solid #e2e8f0; font-size: 9.5pt; }
  .meta td:first-child {
    color: #64748b; text-transform: uppercase; letter-spacing: 0.06em;
    font-size: 8pt; width: 38mm;
  }
  .notice {
    margin-top: 12mm; border: 1px solid #fcd34d; background: #fffbeb;
    border-left: 4px solid #f59e0b; padding: 5mm 6mm; font-size: 9.5pt;
    line-height: 1.5; color: #422006; max-width: 150mm;
  }
  .notice strong { display: block; margin-bottom: 1.5mm; font-size: 10pt; color: #78350f; }

  h1 { font-size: 17pt; font-weight: 800; margin: 0 0 4mm 0; letter-spacing: -0.01em; }
  h2 {
    font-size: 13pt; font-weight: 700; margin: 9mm 0 3mm 0;
    padding-top: 3mm; border-top: 1px solid #e2e8f0; page-break-after: avoid;
  }
  h3 { font-size: 11pt; font-weight: 700; margin: 6mm 0 2mm 0; page-break-after: avoid; }
  h4 { font-size: 10pt; font-weight: 700; margin: 5mm 0 1.5mm 0; page-break-after: avoid; }
  p { margin: 0 0 3mm 0; }
  ul, ol { margin: 0 0 3mm 0; padding-left: 6mm; }
  li { margin-bottom: 1.4mm; }
  hr { border: 0; border-top: 1px solid #e2e8f0; margin: 6mm 0; }
  a { color: #1d4ed8; }
  strong { font-weight: 700; }
  code {
    font-family: "SFMono-Regular", Consolas, "Liberation Mono", monospace;
    font-size: 8.8pt; background: #f1f5f9; padding: 0.4mm 1.2mm;
    border-radius: 2px; border: 1px solid #e2e8f0;
  }
  pre {
    background: #0f172a; color: #e2e8f0; padding: 4mm 5mm; border-radius: 3px;
    font-size: 8.6pt; line-height: 1.45; overflow: hidden;
    page-break-inside: avoid; margin: 0 0 4mm 0;
  }
  pre code { background: none; border: 0; color: inherit; padding: 0; font-size: inherit; }
  table {
    width: 100%; border-collapse: collapse; margin: 0 0 4mm 0;
    font-size: 9pt; page-break-inside: avoid;
  }
  th {
    text-align: left; background: #f8fafc; border-bottom: 1.5px solid #cbd5e1;
    padding: 2.2mm 2.5mm; font-size: 8pt; text-transform: uppercase;
    letter-spacing: 0.05em; color: #475569;
  }
  td { padding: 2.2mm 2.5mm; border-bottom: 1px solid #e2e8f0; vertical-align: top; }
  blockquote {
    margin: 0 0 4mm 0; padding: 3mm 5mm; background: #f8fafc;
    border-left: 3px solid #94a3b8; page-break-inside: avoid;
  }
  blockquote p:last-child { margin-bottom: 0; }
`;

function coverHtml(doc, title) {
  return `<section class="cover">
    <div class="wordmark">Auto<span>Ops</span></div>
    <h1>${escapeHtml(title)}</h1>
    <div class="rule"></div>
    <p class="subtitle">${escapeHtml(doc.subtitle)}</p>
    <table class="meta">
      <tr><td>Document</td><td>${escapeHtml(doc.slug)}</td></tr>
      <tr><td>Revision</td><td>${REVISION}</td></tr>
      <tr><td>Issued</td><td>${ISSUED}</td></tr>
      <tr><td>Classification</td><td>${escapeHtml(doc.classification)}</td></tr>
      <tr><td>Owner</td><td>AutoOps Platform Engineering</td></tr>
      <tr><td>Source</td><td>docs/governance/${escapeHtml(doc.file)}</td></tr>
    </table>
    <div class="notice">
      <strong>This document is not a certification</strong>
      ${escapeHtml(DISCLAIMER)}
    </div>
  </section>`;
}

const page = (title, inner) =>
  `<!doctype html><html><head><meta charset="utf-8"/><title>${escapeHtml(
    title,
  )}</title><style>${CSS}</style></head><body>${inner}</body></html>`;

const footer = (label) => `
  <div style="width:100%;font-size:7.5pt;color:#94a3b8;
              font-family:-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;
              padding:0 18mm;display:flex;justify-content:space-between;">
    <span>${escapeHtml(label)} &middot; Revision ${REVISION} &middot; ${ISSUED}</span>
    <span>Page <span class="pageNumber"></span> of <span class="totalPages"></span></span>
  </div>`;

const HEADER = '<div style="display:none"></div>';

// ---------------------------------------------------------------- render

async function main() {
  mkdirSync(OUT, { recursive: true });

  const loaded = DOCUMENTS.map((doc) => {
    const raw = readFileSync(join(SRC, doc.file), "utf8");
    const { title, body } = splitTitle(raw);
    return { ...doc, title, html: markdownToHtml(body) };
  });

  const browser = await puppeteer.launch({ args: ["--no-sandbox"] });
  const tab = await browser.newPage();

  const emit = async (html, label, file) => {
    await tab.setContent(html, { waitUntil: "load" });
    const pdf = await tab.pdf({
      format: "A4",
      printBackground: true,
      displayHeaderFooter: true,
      headerTemplate: HEADER,
      footerTemplate: footer(label),
      margin: { top: "20mm", bottom: "22mm", left: "18mm", right: "18mm" },
    });
    writeFileSync(join(OUT, file), pdf);
    console.log(`  ${file}  (${Math.round(pdf.length / 1024)} KB)`);
  };

  console.log("Rendering governance policy set:");
  for (const doc of loaded) {
    await emit(
      page(
        doc.title,
        coverHtml(doc, doc.title) +
          `<h1>${escapeHtml(doc.title)}</h1>` +
          doc.html,
      ),
      `AutoOps — ${doc.title}`,
      `autoops-${doc.slug}.pdf`,
    );
  }

  // One file to hand over, because a security review asks for "the pack" and
  // four attachments is how one of them gets read without the boundary.
  const packCover = coverHtml(
    {
      slug: "governance-pack",
      file: "governance/*.md",
      subtitle:
        "The complete governance, control and responsibility documentation set",
      classification: "Public",
    },
    "Governance & Compliance Pack",
  );
  const contents = `<h1>Governance &amp; Compliance Pack</h1>
    <p>This pack contains the four governance documents in their reading order.
    The first defines the boundary of every claim in the other three.</p>
    <table><thead><tr><th>#</th><th>Document</th><th>Answers</th></tr></thead><tbody>
    ${loaded
      .map(
        (d, n) =>
          `<tr><td>${n + 1}</td><td><strong>${escapeHtml(
            d.title,
          )}</strong></td><td>${escapeHtml(d.subtitle)}</td></tr>`,
      )
      .join("")}
    </tbody></table>`;
  const packBody = loaded
    .map(
      (d) =>
        `<div style="page-break-before:always"><h1>${escapeHtml(d.title)}</h1>${d.html}</div>`,
    )
    .join("");
  await emit(
    page(
      "AutoOps Governance & Compliance Pack",
      packCover + contents + packBody,
    ),
    "AutoOps — Governance & Compliance Pack",
    "autoops-governance-pack.pdf",
  );

  await browser.close();
  console.log(`\nWritten to docs/governance/pdf/`);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
