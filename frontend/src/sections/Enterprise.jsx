import React from "react";
import Reveal from "../components/Reveal";
import { Pill } from "../components/ui";

const ITEMS = [
  {
    t: "Multi-tenancy",
    d: "Isolated tenants with per-tenant data scoping, cloud policies, and quotas.",
  },
  {
    t: "SSO & OAuth2",
    d: "Keycloak-backed identity with signed-claim roles — no client-side trust.",
  },
  {
    t: "Usage metering",
    d: "Idempotent usage events with daily and monthly rollups for billing.",
  },
  {
    t: "Quota guardrails",
    d: "Central quota enforcement across executions, users, storage, and compliance.",
  },
];

export default function Enterprise() {
  return (
    <section id="solutions" className="relative mx-auto max-w-7xl scroll-mt-24 px-6 py-24">
      <Reveal>
        <div className="overflow-hidden rounded-3xl border border-slate-200 bg-gradient-to-br from-slate-200 via-transparent to-slate-200 p-10 sm:p-14">
          <div className="max-w-2xl">
            <Pill>Enterprise-ready</Pill>
            <h2 className="mt-5 text-4xl font-bold leading-tight tracking-tight text-slate-900 sm:text-5xl">
              Built for teams that can't afford downtime
            </h2>
            <p className="mt-5 text-base leading-relaxed text-slate-500">
              AutoOps brings true multi-tenancy, server-authoritative security,
              and usage-based commercialization — so you can run it as an
              internal platform or offer it as a SaaS to your own customers.
            </p>
          </div>
          <div className="mt-12 grid gap-6 sm:grid-cols-2">
            {ITEMS.map((it, i) => (
              <Reveal key={it.t} delay={(i % 2) * 90}>
                <div className="rounded-2xl border border-slate-200 bg-slate-50 p-6 transition duration-300 hover:-translate-y-1 hover:border-blue-500">
                  <h3 className="text-lg font-semibold text-slate-900">
                    {it.t}
                  </h3>
                  <p className="mt-2 text-sm leading-relaxed text-slate-500">
                    {it.d}
                  </p>
                </div>
              </Reveal>
            ))}
          </div>
        </div>
      </Reveal>
    </section>
  );
}
