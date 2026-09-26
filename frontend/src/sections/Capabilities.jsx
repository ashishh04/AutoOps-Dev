import React from "react";
import { Link } from "react-router-dom";
import Icon from "../components/Icon";
import Reveal from "../components/Reveal";
import { SectionHeading } from "../components/ui";

// 9 capability cards, each links to its feature page.
// Icons use the crisp duotone glyph set (same language as the Features
// section) inside a clean white square so the whole grid stays aligned.
const CAPS = [
  {
    slug: "real-time-ingestion",
    icon: "bolt",
    t: "Real-time ingestion",
    d: "Stream events and metrics into AutoOps the instant they happen.",
  },
  {
    slug: "sandbox-isolation",
    icon: "shield",
    t: "Sandbox isolation",
    d: "Run untrusted steps in isolated, ephemeral executors.",
  },
  {
    slug: "secure-apis",
    icon: "api",
    t: "Secure APIs",
    d: "Versioned REST API with OpenAPI 3 and scoped tokens.",
  },
  {
    slug: "zero-latency",
    icon: "pulse",
    t: "Zero-latency control",
    d: "Push commands to nodes with zero polling overhead.",
  },
  {
    slug: "intelligent-audit",
    icon: "search",
    t: "Intelligent audit",
    d: "Searchable, attributed audit logs for every action.",
  },
  {
    slug: "composable-workflows",
    icon: "blocks",
    t: "Composable workflows",
    d: "Reuse jobs and templates as building blocks.",
  },
  {
    slug: "automated-runbooks",
    icon: "book",
    t: "Automated runbooks",
    d: "Turn tribal knowledge into one-click operations.",
  },
  {
    slug: "access-control",
    icon: "lock",
    t: "Access control",
    d: "Default-deny RBAC enforced on every controller.",
  },
  {
    slug: "environment-discovery",
    icon: "radar",
    t: "Environment discovery",
    d: "Auto-discover nodes, clusters, and cloud assets.",
  },
];

export default function Capabilities() {
  return (
    <section id="features" className="mx-auto max-w-7xl scroll-mt-24 px-6 py-24">
      <Reveal>
        <SectionHeading
          eyebrow="Capabilities"
          title="One platform, every operational primitive"
          subtitle="The building blocks platform and SRE teams rely on — included out of the box."
        />
      </Reveal>

      <div className="mt-16 grid gap-6 sm:grid-cols-2 lg:grid-cols-3">
        {CAPS.map((c, i) => (
          <Reveal key={c.slug} delay={(i % 3) * 90}>
            <Link
              to={`/features/${c.slug}`}
              className="group relative flex h-full flex-col overflow-hidden rounded-2xl border border-slate-200 bg-slate-50 p-7 transition duration-300 hover:-translate-y-1.5 hover:border-blue-500 hover:bg-slate-100 hover:shadow-xl hover:shadow-slate-300/40"
            >
              {/* header row: icon + number, top-aligned */}
              <div className="flex items-start justify-between">
                <div className="flex h-14 w-14 items-center justify-center rounded-xl border border-slate-200 bg-white text-slate-900 shadow-sm transition group-hover:scale-110 group-hover:border-blue-500">
                  <Icon name={c.icon} size={28} weight="duotone" />
                </div>
                <span className="font-mono text-xs text-slate-400 transition group-hover:text-slate-500">
                  0{i + 1}
                </span>
              </div>

              <h3 className="mt-5 text-lg font-semibold text-slate-900">
                {c.t}
              </h3>
              <p className="mt-2 text-sm leading-relaxed text-slate-500">
                {c.d}
              </p>

              <span className="mt-4 inline-flex items-center gap-1 text-sm font-medium text-slate-900 opacity-0 transition-all duration-300 group-hover:translate-x-1 group-hover:opacity-100">
                Learn more →
              </span>
            </Link>
          </Reveal>
        ))}
      </div>
    </section>
  );
}