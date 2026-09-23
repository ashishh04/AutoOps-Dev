import { describe, expect, it } from "vitest";
import { textPages, clampPage, DEFAULT_PAGE_LINES } from "./textPages";

const lines = (n, word = "line") =>
  Array.from({ length: n }, (_, i) => `${word} ${i}`).join("\n");

describe("textPages", () => {
  it("leaves a short output as a single page", () => {
    expect(textPages("one\ntwo")).toEqual(["one\ntwo"]);
  });

  it("breaks on a blank line so a heading keeps its paragraph", () => {
    const text = `${lines(DEFAULT_PAGE_LINES)}\n\nHeading\nits paragraph`;
    const pages = textPages(text);

    expect(pages).toHaveLength(2);
    expect(pages[1]).toBe("Heading\nits paragraph");
  });

  it("still pages text that never offers a blank line", () => {
    const pages = textPages(lines(DEFAULT_PAGE_LINES * 3));
    expect(pages.length).toBeGreaterThan(1);
    // Nothing is dropped on the way through.
    expect(pages.join("\n").split("\n")).toHaveLength(DEFAULT_PAGE_LINES * 3);
  });

  it("never splits a fenced block in half", () => {
    const fence = ["```", ...Array.from({ length: 60 }, (_, i) => `cmd ${i}`), "```"].join("\n");
    const pages = textPages(`${lines(DEFAULT_PAGE_LINES)}\n\n${fence}`);

    for (const page of pages) {
      const fences = (page.match(/```/g) || []).length;
      expect(fences % 2).toBe(0);
    }
  });

  it("treats an absent output as one empty page", () => {
    expect(textPages(null)).toEqual([""]);
  });
});

describe("clampPage", () => {
  it("holds a page number inside a list that shrank under it", () => {
    expect(clampPage(9, 3)).toBe(3);
    expect(clampPage(0, 3)).toBe(1);
    expect(clampPage(2, 0)).toBe(1);
  });
});
