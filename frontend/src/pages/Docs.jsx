import React, { useMemo, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { PrimaryButton } from "../components/ui";
import Icon from "../components/Icon";
import Footer from "../components/Footer";
import Navbar from "../components/Navbar";
import { DOC_CATEGORIES, searchDocs } from "../data/docs";

export default function Docs() {
  const [query, setQuery] = useState("");
  const navigate = useNavigate();
  const results = useMemo(() => searchDocs(query), [query]);
  const searching = query.trim().length >= 2;

  return (
    <div className="min-h-screen bg-white">
      <Navbar />

      <main className="mx-auto max-w-7xl px-6 py-16 lg:py-24">
        <div className="mb-16 text-center">
          <h1 className="text-4xl font-extrabold tracking-tight text-slate-900 sm:text-5xl">
            Documentation
          </h1>
          <p className="mx-auto mt-6 max-w-2xl text-lg text-slate-500">
            Everything you need to deploy, configure, and scale AutoOps. Explore
            our guides, API reference, and architectural deep-dives.
          </p>

          <div className="mx-auto mt-10 max-w-xl">
            {/* the icon is positioned against the INPUT, not against the block
                below it — the results list would otherwise drag it down */}
            <div className="relative">
              <Icon
                name="search"
                size={20}
                className="pointer-events-none absolute left-4 top-1/2 -translate-y-1/2 text-slate-500"
              />
              <input
                type="text"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter" && results[0])
                    navigate(results[0].path);
                  if (e.key === "Escape") setQuery("");
                }}
                placeholder="Search the docs..."
                aria-label="Search the docs"
                className="w-full rounded-xl border border-slate-200 bg-slate-50 py-4 pl-12 pr-4 text-slate-900 outline-none transition focus:border-slate-300 focus:bg-slate-50 focus:ring-2 focus:ring-slate-300"
              />
            </div>

            {searching && (
              <div className="mt-3 overflow-hidden rounded-xl border border-slate-200 bg-white text-left shadow-lg">
                {results.length === 0 ? (
                  <p className="px-5 py-6 text-sm text-slate-500">
                    Nothing matches “{query.trim()}”.
                  </p>
                ) : (
                  results.map((r) => (
                    <Link
                      key={r.path}
                      to={r.path}
                      className="block border-b border-slate-100 px-5 py-4 transition last:border-0 hover:bg-slate-50"
                    >
                      <div className="flex items-baseline justify-between gap-4">
                        <span className="text-sm font-semibold text-slate-900">
                          {r.title}
                        </span>
                        <span className="shrink-0 text-xs uppercase tracking-wide text-slate-400">
                          {r.categoryTitle}
                        </span>
                      </div>
                      <p className="mt-1 line-clamp-2 text-sm text-slate-500">
                        {r.excerpt}
                      </p>
                    </Link>
                  ))
                )}
              </div>
            )}
          </div>
        </div>

        <div className="grid gap-8 md:grid-cols-2 lg:grid-cols-3">
          {DOC_CATEGORIES.map((c) => (
            <div
              key={c.slug}
              className="flex flex-col rounded-2xl border border-slate-200 bg-slate-50 p-8 transition hover:border-blue-500 hover:bg-slate-100"
            >
              <div className="flex items-center gap-4">
                <span className="flex h-12 w-12 items-center justify-center rounded-xl bg-slate-100 text-slate-900 ring-1 ring-slate-300">
                  <Icon name={c.icon} size={24} />
                </span>
                <h3 className="text-xl font-bold text-slate-900">{c.title}</h3>
              </div>
              <ul className="mt-6 space-y-3">
                {c.pageList.map((page) => (
                  <li key={page.slug}>
                    <Link
                      to={page.path}
                      className="text-sm font-medium text-slate-500 transition hover:text-slate-900"
                    >
                      {page.title}
                    </Link>
                  </li>
                ))}
              </ul>
              <div className="mt-6 border-t border-slate-200 pt-4">
                <Link
                  to={`/docs/${c.slug}`}
                  className="text-sm font-semibold text-slate-900 transition hover:text-blue-600"
                >
                  View all →
                </Link>
              </div>
            </div>
          ))}
        </div>

        <div className="mt-20 rounded-2xl bg-gradient-to-br from-slate-200 to-slate-200 p-8 text-center sm:p-12">
          <h2 className="text-2xl font-bold text-slate-900">
            Can&apos;t find what you&apos;re looking for?
          </h2>
          <p className="mt-3 text-slate-500">
            Talk to us about your estate, or start with the quickstart and have
            something running in fifteen minutes.
          </p>
          <div className="mt-8 flex flex-col items-center justify-center gap-4 sm:flex-row">
            <PrimaryButton to="/demo">Contact Support</PrimaryButton>
            <Link
              to="/docs/getting-started/quickstart"
              className="text-sm font-semibold text-slate-900 transition hover:text-blue-600"
            >
              Read the Quickstart →
            </Link>
          </div>
        </div>
      </main>

      <Footer />
    </div>
  );
}
