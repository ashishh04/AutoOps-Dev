/**
 * Splits a long run output into readable pages.
 *
 * <p>A report or a log is one unbounded blob, and a scroll bar is the only
 * thing that ever told you how much of it there was. Paging it gives the
 * reader a position — "2 of 5" — and stops a 900-line trace from turning the
 * result card into a mouse-wheel endurance test.
 *
 * <p>Pages break on a blank line rather than on a line count, so a heading
 * never ends up on one page with its paragraph on the next, and never inside a
 * fenced code block, which would split a command in half. When the text has no
 * blank lines to break on, a hard cap stops a single page growing without
 * bound.
 */

/** Lines per page before the splitter starts looking for a break. */
export const DEFAULT_PAGE_LINES = 45;

const trimBlankEnds = (text) => text.replace(/^\n+/, "").replace(/\n+$/, "");

export function textPages(source, linesPerPage = DEFAULT_PAGE_LINES) {
  const text = String(source ?? "");
  const lines = text.split("\n");
  if (lines.length <= linesPerPage) return [text];

  const pages = [];
  let current = [];
  let fenced = false;

  for (const line of lines) {
    current.push(line);
    if (line.trimStart().startsWith("```")) fenced = !fenced;
    if (fenced) continue;

    const atBreak = current.length >= linesPerPage && line.trim() === "";
    // A wall of text with no paragraph breaks still has to be paged somewhere.
    const overflowing = current.length >= linesPerPage * 2;
    if (atBreak || overflowing) {
      pages.push(trimBlankEnds(current.join("\n")));
      current = [];
    }
  }

  const tail = trimBlankEnds(current.join("\n"));
  if (tail) pages.push(tail);
  return pages.length ? pages : [text];
}

/** Clamps a page number to a list that may have shrunk under it. */
export const clampPage = (page, totalPages) =>
  Math.min(Math.max(1, page), Math.max(1, totalPages));
