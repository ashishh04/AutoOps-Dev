import React, { useEffect } from "react";
import { Link, useParams, Navigate } from "react-router-dom";
import Navbar from "../components/Navbar";
import Footer from "../components/Footer";
import Reveal from "../components/Reveal";
import Icon from "../components/Icon";
import { Pill, PrimaryButton, GhostButton } from "../components/ui";
import { FEATURES, getFeature } from "../data/features";

export default function FeaturePage() {
  const { slug } = useParams();
  const feature = getFeature(slug);

  useEffect(() => {
    window.scrollTo(0, 0);
  }, [slug]);

  if (!feature) return <Navigate to="/" replace />;

  const related = FEATURES.filter((f) => f.slug !== slug).slice(0, 3);

  return (
    <div className="min-h-screen bg-white text-slate-700">
      <Navbar />
      <main>
        {/* hero */}
        <section className="grid-bg relative overflow-hidden">
          <div className="pointer-events-none absolute inset-0 -z-10">
            <div className="animate-float-glow absolute left-1/2 top-[-20%] h-[420px] w-[640px] rounded-full bg-slate-100 blur-[150px]" />
          </div>
          <div className="mx-auto max-w-5xl px-6 pb-16 pt-20">
            <Reveal>
              <Link
                to="/#features"
                className="text-sm text-slate-500 transition hover:text-slate-900"
              >
                ← All capabilities
              </Link>
              <div className="mt-6 flex items-center gap-4">
                {feature.customIcon ? (
                  <div className="flex h-16 w-16 items-center justify-center transition drop-shadow-xl">
                    <img
                      src={feature.customIcon}
                      alt=""
                      className="h-full w-full object-contain"
                    />
                  </div>
                ) : (
                  <span className="flex h-14 w-14 items-center justify-center overflow-hidden rounded-2xl bg-gradient-to-br from-slate-200 to-slate-200 p-2 text-slate-900 ring-1 ring-slate-200">
                    <Icon name={feature.icon} size={28} />
                  </span>
                )}
                <Pill>Capability</Pill>
              </div>
              <h1 className="mt-6 max-w-3xl text-4xl font-extrabold tracking-tight text-slate-900 sm:text-5xl">
                {feature.title}
              </h1>
              <p className="mt-3 text-xl font-medium gradient-text">
                {feature.tagline}
              </p>
              <p className="mt-5 max-w-2xl text-base leading-relaxed text-slate-500">
                {feature.description}
              </p>
              <div className="mt-8 flex flex-wrap gap-3">
                <PrimaryButton to="/login">Get Started →</PrimaryButton>
                <GhostButton to="/pricing">View Pricing</GhostButton>
              </div>
            </Reveal>
          </div>
        </section>

        {/* details */}
        <section className="mx-auto max-w-5xl px-6 py-16">
          <div className="grid gap-8 lg:grid-cols-2">
            <Reveal>
              <div className="rounded-2xl border border-slate-200 bg-slate-50 p-8">
                <h2 className="text-lg font-semibold text-slate-900">
                  What you get
                </h2>
                <ul className="mt-5 space-y-3 text-sm text-slate-600">
                  {feature.bullets.map((b) => (
                    <li key={b} className="flex items-start gap-2">
                      <span className="mt-0.5 text-emerald-600">✓</span> {b}
                    </li>
                  ))}
                </ul>
              </div>
            </Reveal>
            <Reveal delay={120}>
              <div className="relative flex h-full flex-col items-center justify-center overflow-hidden rounded-2xl border border-slate-200 bg-gradient-to-br from-slate-200 to-slate-200 p-8 text-center">
                {feature.customImage ? (
                  <img
                    src={feature.customImage}
                    alt=""
                    className="pointer-events-none absolute inset-0 h-full w-full object-cover opacity-20 transition-opacity hover:opacity-40"
                  />
                ) : null}
                <div className="relative z-10">
                  <p className="text-5xl font-extrabold gradient-text">
                    {feature.stat.value}
                  </p>
                  <p className="mt-2 text-sm uppercase tracking-widest text-slate-500">
                    {feature.stat.label}
                  </p>
                </div>
              </div>
            </Reveal>
          </div>
        </section>

        {/* related */}
        <section className="mx-auto max-w-5xl px-6 pb-24">
          <Reveal>
            <h2 className="text-2xl font-bold text-slate-900">
              Explore more capabilities
            </h2>
          </Reveal>
          <div className="mt-8 grid gap-6 sm:grid-cols-3">
            {related.map((r, i) => (
              <Reveal key={r.slug} delay={i * 90}>
                <Link
                  to={`/features/${r.slug}`}
                  className="group block h-full rounded-2xl border border-slate-200 bg-slate-50 p-6 transition duration-300 hover:-translate-y-1.5 hover:border-blue-500 hover:bg-slate-100"
                >
                  {r.customIcon ? (
                    <div className="flex h-12 w-12 items-center justify-center transition group-hover:scale-110 drop-shadow-md">
                      <img
                        src={r.customIcon}
                        alt=""
                        className="h-full w-full object-contain"
                      />
                    </div>
                  ) : (
                    <span className="flex h-10 w-10 items-center justify-center overflow-hidden rounded-xl bg-gradient-to-br from-slate-200 to-slate-200 p-1.5 text-slate-900 transition group-hover:scale-110">
                      <Icon name={r.icon} size={20} />
                    </span>
                  )}
                  <h3 className="mt-4 text-base font-semibold text-slate-900">
                    {r.title}
                  </h3>
                  <p className="mt-1 text-sm text-slate-500">{r.tagline}</p>
                </Link>
              </Reveal>
            ))}
          </div>
        </section>
      </main>
      <Footer />
    </div>
  );
}
