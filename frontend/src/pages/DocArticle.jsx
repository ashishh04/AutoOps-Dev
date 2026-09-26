import React, { useEffect } from "react";
import { Link, Navigate, useParams } from "react-router-dom";
import Navbar from "../components/Navbar";
import Footer from "../components/Footer";
import Icon from "../components/Icon";
import DocMarkdown, { outline } from "../components/DocMarkdown";
import { DOC_CATEGORIES, getCategory, getDoc, neighbours } from "../data/docs";

/**
 * One documentation page — and, with no page slug, the category's own index.
 *
 * Both live here because they share the whole chrome: the same sidebar, the
 * same breadcrumb, the same footer navigation. Splitting them would duplicate
 * all three to vary one column.
 */
export default function DocArticle() {
  const { category: categorySlug, slug } = useParams();
  const category = getCategory(categorySlug);
  const doc = slug ? getDoc(categorySlug, slug) : null;

  useEffect(() => {
    window.scrollTo(0, 0);
  }, [categorySlug, slug]);

  // An unknown path goes back to the docs index rather than to a 404: the
  // reader asked for documentation and there is some, just not that.
  if (!category) return <Navigate to="/docs" replace />;
  if (slug && !doc) return <Navigate to={`/docs/${categorySlug}`} replace />;

  const { prev, next } = doc
    ? neighbours(categorySlug, slug)
    : { prev: null, next: null };
  const headings = doc ? outline(doc.body) : [];

  return (
    <div className="min-h-screen bg-white">
      <Navbar />

      <main className="mx-auto max-w-7xl px-6 py-12 lg:py-16">
        <nav className="flex flex-wrap items-center gap-2 text-sm text-slate-500">
          <Link to="/docs" className="transition hover:text-slate-900">
            Docs
          </Link>
          <span className="text-slate-300">/</span>
          {doc ? (
            <>
              <Link
                to={`/docs/${category.slug}`}
                className="transition hover:text-slate-900"
              >
                {category.title}
              </Link>
              <span className="text-slate-300">/</span>
              <span className="font-medium text-slate-900">{doc.title}</span>
            </>
          ) : (
            <span className="font-medium text-slate-900">{category.title}</span>
          )}
        </nav>

        <div className="mt-8 grid gap-12 lg:grid-cols-[16rem_minmax(0,1fr)_14rem]">
          {/* every page, always — the docs are small enough to show whole */}
          <aside className="lg:sticky lg:top-24 lg:self-start">
            <nav className="space-y-6">
              {DOC_CATEGORIES.map((c) => (
                <div key={c.slug}>
                  <Link
                    to={`/docs/${c.slug}`}
                    className={`flex items-center gap-2 text-sm font-semibold transition ${
                      c.slug === category.slug
                        ? "text-slate-900"
                        : "text-slate-500 hover:text-slate-900"
                    }`}
                  >
                    <Icon name={c.icon} size={16} />
                    {c.title}
                  </Link>
                  <ul className="mt-2 space-y-1 border-l border-slate-200 pl-3">
                    {c.pageList.map((p) => {
                      const active = doc && p.path === doc.path;
                      return (
                        <li key={p.slug}>
                          <Link
                            to={p.path}
                            className={`-ml-3 block border-l-2 py-1 pl-3 text-sm transition ${
                              active
                                ? "border-blue-500 font-medium text-slate-900"
                                : "border-transparent text-slate-500 hover:border-slate-300 hover:text-slate-900"
                            }`}
                          >
                            {p.title}
                          </Link>
                        </li>
                      );
                    })}
                  </ul>
                </div>
              ))}
            </nav>
          </aside>

          <article className="min-w-0">
            {doc ? (
              <>
                <DocMarkdown source={doc.body} />

                <div className="mt-16 grid gap-4 border-t border-slate-200 pt-8 sm:grid-cols-2">
                  {prev ? (
                    <Link
                      to={prev.path}
                      className="rounded-xl border border-slate-200 p-4 transition hover:border-blue-500 hover:bg-slate-50"
                    >
                      <span className="text-xs uppercase tracking-wide text-slate-400">
                        ← Previous
                      </span>
                      <p className="mt-1 text-sm font-semibold text-slate-900">
                        {prev.title}
                      </p>
                    </Link>
                  ) : (
                    <span />
                  )}
                  {next && (
                    <Link
                      to={next.path}
                      className="rounded-xl border border-slate-200 p-4 text-right transition hover:border-blue-500 hover:bg-slate-50 sm:col-start-2"
                    >
                      <span className="text-xs uppercase tracking-wide text-slate-400">
                        Next →
                      </span>
                      <p className="mt-1 text-sm font-semibold text-slate-900">
                        {next.title}
                      </p>
                    </Link>
                  )}
                </div>
              </>
            ) : (
              <>
                <h1 className="text-3xl font-extrabold tracking-tight text-slate-900">
                  {category.title}
                </h1>
                <p className="mt-4 text-[15px] leading-relaxed text-slate-500">
                  {category.blurb}
                </p>
                <div className="mt-10 grid gap-4 sm:grid-cols-2">
                  {category.pageList.map((p) => (
                    <Link
                      key={p.slug}
                      to={p.path}
                      className="group rounded-2xl border border-slate-200 p-6 transition hover:-translate-y-0.5 hover:border-blue-500 hover:bg-slate-50"
                    >
                      <h2 className="text-base font-semibold text-slate-900">
                        {p.title}
                      </h2>
                      <p className="mt-2 line-clamp-3 text-sm leading-relaxed text-slate-500">
                        {p.summary}
                      </p>
                      <span className="mt-4 inline-block text-sm font-medium text-slate-900 opacity-0 transition group-hover:opacity-100">
                        Read →
                      </span>
                    </Link>
                  ))}
                </div>
              </>
            )}
          </article>

          {/* on this page */}
          <aside className="hidden lg:sticky lg:top-24 lg:block lg:self-start">
            {headings.length > 1 && (
              <>
                <p className="text-xs font-semibold uppercase tracking-wide text-slate-400">
                  On this page
                </p>
                <ul className="mt-3 space-y-2">
                  {headings.map((h) => (
                    <li key={h.id}>
                      <a
                        href={`#${h.id}`}
                        className="block text-sm leading-snug text-slate-500 transition hover:text-slate-900"
                      >
                        {h.text}
                      </a>
                    </li>
                  ))}
                </ul>
              </>
            )}
          </aside>
        </div>
      </main>

      <Footer />
    </div>
  );
}
