// The docs site, sourced from the repository's own `docs/` folder.
//
// The markdown files are the single copy: they are readable on their own in the
// repo AND are what /docs renders, so a page cannot be updated in one place and
// stale in the other. Vite inlines them at build time, which is why there is no
// fetch and no loading state anywhere in the docs UI.
//
// Order is explicit here rather than alphabetical — "Quickstart" has to come
// before "Core Concepts" and no sort produces that.

// One pattern per category rather than `docs/**/*.md`: the glob INLINES every
// file it matches into the bundle, and docs/governance/ is a separate set that
// ships as PDFs. A recursive pattern would quietly ship it to every visitor.
const SOURCES = import.meta.glob(
  [
    "../../../docs/getting-started/*.md",
    "../../../docs/architecture/*.md",
    "../../../docs/workflows/*.md",
    "../../../docs/security/*.md",
    "../../../docs/integrations/*.md",
    "../../../docs/api/*.md",
  ],
  { query: "?raw", import: "default", eager: true },
);

// Icon names come from components/Icon.jsx.
export const CATEGORIES = [
  {
    slug: "getting-started",
    title: "Getting Started",
    icon: "rocket",
    blurb: "Stand the platform up and run your first automation.",
    pages: [
      "quickstart",
      "installation-and-setup",
      "core-concepts",
      "first-workflow",
    ],
  },
  {
    slug: "architecture",
    title: "Architecture",
    icon: "layers",
    blurb: "How the platform is put together, and why.",
    pages: [
      "execution-engine",
      "event-bus-and-streams",
      "state-management",
      "high-availability",
    ],
  },
  {
    slug: "workflows",
    title: "Workflows",
    icon: "blocks",
    blurb:
      "Authoring graphs, triggering them, and what happens when they fail.",
    pages: [
      "dag-syntax",
      "triggers-and-schedules",
      "approval-gates",
      "handling-failures",
    ],
  },
  {
    slug: "security",
    title: "Security & RBAC",
    icon: "shield",
    blurb: "Identity, authorization, secrets and the audit trail.",
    pages: [
      "authentication-oidc",
      "role-based-access",
      "vault-integrations",
      "audit-logs",
    ],
  },
  {
    slug: "integrations",
    title: "Integrations",
    icon: "puzzle",
    blurb: "Cloud accounts, clusters, chat and inbound events.",
    pages: ["aws-and-gcp", "kubernetes", "slack-and-teams", "custom-webhooks"],
  },
  {
    slug: "api",
    title: "API Reference",
    icon: "api",
    blurb: "The HTTP surface, and how to build a client against it.",
    pages: ["rest-api", "authentication", "rate-limits", "websockets"],
  },
];

// The title is the first `# Heading`; the summary is the first paragraph after
// it. Deriving both from the prose means a page cannot carry a heading that
/**
 * Line endings, settled once, at the boundary.
 *
 * `.gitattributes` sets `* text=auto`, so these files are checked out with CRLF
 * on Windows and LF on Linux. Every consumer downstream splits on "\n", which
 * would leave a stray "\r" on the end of every line: a heading id becomes
 * `a-section-`, a table cell keeps an invisible character, and a `# Title`
 * carries one into the card. The build even produces different output on
 * different machines from identical sources.
 *
 * Normalising here means nothing below this line has to know.
 */
const normalize = (text) => String(text).replace(/\r\n?/g, "\n");

// disagrees with its own card, and it keeps front matter out of files that are
// also read directly on disk.
function parse(markdown) {
  const lines = String(markdown).split("\n");
  let title = "";
  let summary = "";
  let i = 0;

  while (i < lines.length && !title) {
    const heading = lines[i].match(/^#\s+(.*)$/);
    if (heading) title = heading[1].trim();
    i += 1;
  }

  const paragraph = [];
  while (i < lines.length) {
    const line = lines[i];
    if (!line.trim()) {
      if (paragraph.length) break;
    } else if (line.startsWith("#")) {
      break;
    } else {
      paragraph.push(line.trim());
    }
    i += 1;
  }
  summary = paragraph.join(" ");

  return { title, summary };
}

function build() {
  const byCategory = new Map();

  for (const category of CATEGORIES) {
    const pages = category.pages
      .map((slug) => {
        const key = `../../../docs/${category.slug}/${slug}.md`;
        const raw = SOURCES[key];
        // A catalogue entry with no file is a build-time authoring mistake, not
        // a runtime condition. Dropping it silently would hide a broken link
        // behind a card that renders fine.
        if (!raw) {
          console.warn(`[docs] no markdown at ${key}`);
          return null;
        }
        const body = normalize(raw);
        const { title, summary } = parse(body);
        return {
          slug,
          category: category.slug,
          categoryTitle: category.title,
          path: `/docs/${category.slug}/${slug}`,
          title: title || slug,
          summary,
          body,
        };
      })
      .filter(Boolean);
    byCategory.set(category.slug, { ...category, pageList: pages });
  }

  return byCategory;
}

const INDEX = build();

export const DOC_CATEGORIES = CATEGORIES.map((c) => INDEX.get(c.slug));

export const DOC_PAGES = DOC_CATEGORIES.flatMap((c) => c.pageList);

export function getCategory(slug) {
  return INDEX.get(slug) || null;
}

export function getDoc(categorySlug, pageSlug) {
  const category = INDEX.get(categorySlug);
  if (!category) return null;
  return category.pageList.find((p) => p.slug === pageSlug) || null;
}

/** Previous/next across the whole set, so a reader can walk it end to end. */
export function neighbours(categorySlug, pageSlug) {
  const at = DOC_PAGES.findIndex(
    (p) => p.category === categorySlug && p.slug === pageSlug,
  );
  if (at < 0) return { prev: null, next: null };
  return {
    prev: at > 0 ? DOC_PAGES[at - 1] : null,
    next: at < DOC_PAGES.length - 1 ? DOC_PAGES[at + 1] : null,
  };
}

/**
 * Substring search over title, summary and body.
 *
 * Deliberately not an index: 24 pages is small enough that scanning them is
 * faster than the machinery would be, and a real index is a dependency plus a
 * build step for a search box nobody has complained about.
 */
export function searchDocs(query) {
  const q = String(query || "")
    .trim()
    .toLowerCase();
  if (q.length < 2) return [];

  return DOC_PAGES.map((page) => {
    const title = page.title.toLowerCase();
    const summary = page.summary.toLowerCase();
    const body = page.body.toLowerCase();

    let score = 0;
    if (title.includes(q)) score += 100;
    if (title.startsWith(q)) score += 50;
    if (summary.includes(q)) score += 20;

    const hits = body.split(q).length - 1;
    score += Math.min(hits, 10);

    if (!score) return null;

    // The line the term appears on, as context under the result.
    let excerpt = page.summary;
    const line = page.body
      .split("\n")
      .find((l) => l.toLowerCase().includes(q) && !l.startsWith("#"));
    if (line && line.trim()) excerpt = line.trim().replace(/^[-*>|]\s*/, "");

    return { ...page, score, excerpt };
  })
    .filter(Boolean)
    .sort((a, b) => b.score - a.score)
    .slice(0, 8);
}
