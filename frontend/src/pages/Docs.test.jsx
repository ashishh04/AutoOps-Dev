import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it } from "vitest";
import Docs from "./Docs";
import DocArticle from "./DocArticle";
import { CATEGORIES, DOC_PAGES, getDoc, searchDocs } from "../data/docs";
import DocMarkdown from "../components/DocMarkdown";

// DocMarkdown turns links between markdown files into router links, so even a
// bare render of it needs a router around it.
const renderMarkdown = (source) =>
  render(
    <MemoryRouter>
      <DocMarkdown source={source} />
    </MemoryRouter>,
  );

// The docs are markdown files in /docs that Vite inlines at build time. These
// tests exist because that pipeline can break silently: a renamed file leaves a
// card whose links all 404, and nothing else in the app would notice.

function renderAt(path) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/docs" element={<Docs />} />
        <Route path="/docs/:category" element={<DocArticle />} />
        <Route path="/docs/:category/:slug" element={<DocArticle />} />
        <Route path="*" element={<p>elsewhere</p>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe("the docs catalogue", () => {
  it("has a markdown file behind every catalogue entry", () => {
    const declared = CATEGORIES.reduce((n, c) => n + c.pages.length, 0);
    expect(DOC_PAGES).toHaveLength(declared);
  });

  it("takes each page's title and summary from its own prose", () => {
    const quickstart = getDoc("getting-started", "quickstart");
    expect(quickstart.title).toBe("Quickstart Guide");
    // The summary is the first paragraph, re-joined across the source's hard
    // wraps rather than truncated at the first newline.
    expect(quickstart.summary).toMatch(/Bring the whole AutoOps platform up/);
    expect(quickstart.summary).not.toMatch(/\n/);
  });

  it("finds a page by a word that only appears in its body", () => {
    const hits = searchDocs("kubeconfig");
    expect(hits.length).toBeGreaterThan(0);
    expect(hits.map((h) => h.slug)).toContain("kubernetes");
  });

  it("ranks a title match above a body mention", () => {
    const hits = searchDocs("rate limits");
    expect(hits[0].slug).toBe("rate-limits");
  });

  it("ignores a query too short to mean anything", () => {
    expect(searchDocs("a")).toEqual([]);
  });

  // `.gitattributes` sets `* text=auto`, so these files arrive with CRLF on a
  // Windows checkout and LF on Linux. Every title below was parsed from one of
  // the two — this asserts the answer does not depend on which.
  it("reads a page the same however the file was checked out", () => {
    for (const page of DOC_PAGES) {
      expect(page.title).not.toMatch(/[\r\n]/);
      expect(page.title).not.toBe(page.slug);
      expect(page.body).not.toMatch(/\r/);
    }
  });
});

describe("the docs landing page", () => {
  it("links every card entry at its real page rather than at #", () => {
    renderAt("/docs");
    const link = screen.getByRole("link", { name: "Installation & Setup" });
    expect(link).toHaveAttribute(
      "href",
      "/docs/getting-started/installation-and-setup",
    );
  });

  it("points View all at the category, not nowhere", () => {
    renderAt("/docs");
    const viewAll = screen.getAllByRole("link", { name: /View all/ });
    expect(viewAll[0]).toHaveAttribute("href", "/docs/getting-started");
  });

  it("searches as you type", async () => {
    const user = userEvent.setup();
    renderAt("/docs");
    await user.type(screen.getByLabelText("Search the docs"), "approval gates");
    const results = await screen.findAllByRole("link", {
      name: /Approval Gates/,
    });
    expect(results[0]).toHaveAttribute(
      "href",
      "/docs/workflows/approval-gates",
    );
  });

  it("says so when nothing matches, instead of showing an empty box", async () => {
    const user = userEvent.setup();
    renderAt("/docs");
    await user.type(screen.getByLabelText("Search the docs"), "zzzzqqq");
    expect(await screen.findByText(/Nothing matches/)).toBeInTheDocument();
  });
});

describe("a docs page", () => {
  it("renders the markdown, not the markdown syntax", () => {
    const { container } = renderAt("/docs/workflows/dag-syntax");
    expect(
      screen.getByRole("heading", { level: 1, name: "DAG Syntax" }),
    ).toBeInTheDocument();
    const article = container.querySelector(".doc-body");
    expect(article.textContent).not.toMatch(/\*\*/);
    expect(article.textContent).not.toMatch(/^#{1,4}\s/m);
  });

  it("offers the next page, so the set can be read end to end", () => {
    renderAt("/docs/getting-started/quickstart");
    // Scoped to the footer nav: the sidebar lists every page too.
    const next = screen.getByText("Next →").closest("a");
    expect(next).toHaveAttribute(
      "href",
      "/docs/getting-started/installation-and-setup",
    );
  });

  it("sends an unknown page back to its category rather than to a dead end", () => {
    renderAt("/docs/getting-started/no-such-page");
    expect(
      screen.getByRole("heading", { level: 1, name: "Getting Started" }),
    ).toBeInTheDocument();
  });

  it("sends an unknown category back to the docs index", () => {
    renderAt("/docs/not-a-category");
    expect(
      screen.getByRole("heading", { level: 1, name: "Documentation" }),
    ).toBeInTheDocument();
  });
});

describe("DocMarkdown", () => {
  const SOURCE = `# Title

An intro paragraph that is hard-wrapped
across two source lines.

## A section

| Mode | Meaning |
|---|---|
| \`rundeck\` | The **default** |

- a bullet
  that wraps
- another

1. first
2. second

> A callout.

\`\`\`
echo hello
\`\`\`

See [Rate Limits](../api/rate-limits.md).
`;

  it("joins a hard-wrapped paragraph back into one", () => {
    renderMarkdown(SOURCE);
    expect(
      screen.getByText(
        "An intro paragraph that is hard-wrapped across two source lines.",
      ),
    ).toBeInTheDocument();
  });

  it("renders a table as a table", () => {
    renderMarkdown(SOURCE);
    const table = screen.getByRole("table");
    expect(within(table).getByText("rundeck").tagName).toBe("CODE");
    expect(within(table).getByText("default").tagName).toBe("STRONG");
  });

  it("keeps a wrapped bullet as one item", () => {
    renderMarkdown(SOURCE);
    expect(screen.getByText("a bullet that wraps")).toBeInTheDocument();
  });

  it("renders ordered and unordered lists distinctly", () => {
    const { container } = renderMarkdown(SOURCE);
    expect(container.querySelectorAll("ul")).toHaveLength(1);
    expect(container.querySelectorAll("ol")).toHaveLength(1);
  });

  it("rewrites a link between markdown files into a console route", () => {
    renderMarkdown(SOURCE);
    expect(screen.getByRole("link", { name: "Rate Limits" })).toHaveAttribute(
      "href",
      "/docs/api/rate-limits",
    );
  });

  it("gives each section an anchor the contents list can reach", () => {
    const { container } = renderMarkdown(SOURCE);
    expect(container.querySelector("#a-section")).not.toBeNull();
  });

  it("does not mistake a fenced line for a heading", () => {
    render(<DocMarkdown source={"# T\n\n```\n# not a heading\n```\n"} />);
    expect(screen.queryByRole("heading", { name: "not a heading" })).toBeNull();
  });
});
