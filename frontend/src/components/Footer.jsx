import React from "react";
import { Link } from "react-router-dom";
import {
  XLogo,
  InstagramLogo,
  YoutubeLogo,
  LinkedinLogo,
} from "@phosphor-icons/react";
import { Logo } from "./ui";

const COLS = [
  {
    h: "Product",
    links: [
      { label: "Pricing & Plans", to: "/pricing" },
      { label: "Workflow Designer", to: "/features/composable-workflows" },
      { label: "Executions", to: "/features/execution-audit-trails" },
    ],
  },
  {
    h: "Company",
    links: [
      { label: "About us", href: "#" },
      { label: "Careers", href: "#" },
    ],
  },
  {
    h: "Resources",
    links: [
      { label: "Documentation", to: "/docs" },
      { label: "Quickstart Guide", to: "/docs/getting-started/quickstart" },
      { label: "API Reference", to: "/docs/api/rest-api" },
      { label: "Security & RBAC", to: "/docs/security/authentication-oidc" },
      // Terms, Cookie Policy and Privacy Policy still have no page to point at.
      // They are left off rather than linked to `#`, which scrolls to the top
      // and reads as a broken link — worse than an absent one.
    ],
  },
];

const SOCIAL = [
  { Icon: XLogo, href: "#", label: "X", color: "text-slate-900" },
  {
    Icon: InstagramLogo,
    href: "#",
    label: "Instagram",
    color: "text-pink-500",
  },
  { Icon: YoutubeLogo, href: "#", label: "YouTube", color: "text-red-500" },
  { Icon: LinkedinLogo, href: "#", label: "LinkedIn", color: "text-blue-600" },
];

export default function Footer() {
  return (
    <footer
      id="docs"
      className="relative overflow-hidden border-t border-slate-200 bg-slate-50"
    >
      {/* giant faint watermark */}
      <div
        aria-hidden="true"
        className="pointer-events-none absolute inset-x-0 bottom-[-3rem] select-none text-center text-[9rem] font-extrabold leading-none tracking-tighter text-slate-200/50 sm:text-[13rem] lg:text-[16rem]"
      >
        AutoOps
      </div>

      <div className="relative mx-auto max-w-7xl px-6 py-16">
        <div className="grid gap-10 lg:grid-cols-5">
          <div className="lg:col-span-2">
            <Logo />
            <p className="mt-4 text-sm text-slate-500">
              © {new Date().getFullYear()} AutoOps — All rights reserved.
            </p>
          </div>

          {COLS.map((c) => (
            <div key={c.h}>
              <h4 className="text-sm font-semibold text-slate-900">{c.h}</h4>
              <ul className="mt-4 space-y-2.5">
                {c.links.map((l) => (
                  <li key={l.label}>
                    {l.to ? (
                      <Link
                        to={l.to}
                        className="text-sm text-slate-500 transition hover:text-slate-900"
                      >
                        {l.label}
                      </Link>
                    ) : (
                      <a
                        href={l.href}
                        className="text-sm text-slate-500 transition hover:text-slate-900"
                      >
                        {l.label}
                      </a>
                    )}
                  </li>
                ))}
              </ul>
            </div>
          ))}

          <div>
            <h4 className="text-sm font-semibold text-slate-900">Social</h4>
            <div className="mt-4 flex gap-4">
              {SOCIAL.map((s) => (
                <a
                  key={s.label}
                  href={s.href}
                  aria-label={s.label}
                  className={`${s.color} transition hover:scale-110`}
                >
                  <s.Icon size={22} weight="fill" />
                </a>
              ))}
            </div>
          </div>
        </div>
      </div>
    </footer>
  );
}
