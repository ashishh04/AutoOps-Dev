import React, { useEffect, useState } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { Logo, PrimaryButton } from "./ui";

// `hash` entries are sections of the landing page; `to` entries are routes.
//
// Every hash here must match an `id` on a section in pages/Home.jsx. "Solutions"
// used to point at the hero, which meant clicking it from the top of the page
// did nothing visible at all.
const LINKS = [
  { label: "Product", hash: "product" },
  { label: "Capabilities", hash: "features" },
  { label: "Solutions", hash: "solutions" },
  { label: "Pricing", to: "/pricing" },
  { label: "Docs", to: "/docs" },
];

export default function Navbar() {
  const [open, setOpen] = useState(false);
  const [scrolled, setScrolled] = useState(false);
  const { pathname } = useLocation();
  const navigate = useNavigate();

  useEffect(() => setOpen(false), [pathname]);
  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 8);
    onScroll();
    window.addEventListener("scroll", onScroll);
    return () => window.removeEventListener("scroll", onScroll);
  }, []);

  // A plain <a href="/#x"> reloads the whole SPA from any route other than the
  // landing page, and does nothing at all when the hash is already set. Both
  // are handled here: scroll when the section is on screen, route when it is
  // not, and keep the URL shareable either way.
  const goToSection = (event, hash) => {
    event.preventDefault();
    setOpen(false);
    if (pathname === "/") {
      document
        .getElementById(hash)
        ?.scrollIntoView({ behavior: "smooth", block: "start" });
      window.history.replaceState(null, "", `/#${hash}`);
      return;
    }
    navigate(`/#${hash}`);
  };

  const desktopLink =
    "relative text-sm text-slate-600 transition hover:text-slate-900 after:absolute after:-bottom-1 after:left-0 after:h-px after:w-0 after:bg-slate-900 after:transition-all hover:after:w-full";

  return (
    <header
      className={`sticky top-0 z-50 border-b transition-colors duration-300 ${
        scrolled
          ? "border-slate-200 bg-slate-50 backdrop-blur-md"
          : "border-transparent bg-slate-50 backdrop-blur-sm"
      }`}
    >
      <nav className="mx-auto flex max-w-7xl items-center justify-between px-6 py-4">
        <Logo />

        <div className="hidden items-center gap-8 md:flex">
          {LINKS.map((l) =>
            l.to ? (
              <Link key={l.label} to={l.to} className={desktopLink}>
                {l.label}
              </Link>
            ) : (
              <a
                key={l.label}
                href={`/#${l.hash}`}
                onClick={(e) => goToSection(e, l.hash)}
                className={desktopLink}
              >
                {l.label}
              </a>
            ),
          )}
        </div>

        <div className="hidden items-center gap-3 md:flex">
          <Link
            to="/demo"
            className="text-sm font-medium text-slate-600 transition hover:text-slate-900"
          >
            Book a demo
          </Link>
          <Link
            to="/login"
            className="ml-2 text-sm font-medium text-slate-600 transition hover:text-slate-900"
          >
            Sign In
          </Link>
          <PrimaryButton to="/signup" className="px-5 py-2">
            Get Started
          </PrimaryButton>
        </div>

        <button
          onClick={() => setOpen((v) => !v)}
          className="text-slate-900 md:hidden"
          aria-label="Menu"
          aria-expanded={open}
        >
          <svg
            width="24"
            height="24"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
          >
            <path d="M4 6h16M4 12h16M4 18h16" />
          </svg>
        </button>
      </nav>

      {open && (
        <div className="border-t border-slate-200 bg-white px-6 py-4 md:hidden">
          {LINKS.map((l) =>
            l.to ? (
              <Link
                key={l.label}
                to={l.to}
                className="block py-2 text-slate-600"
              >
                {l.label}
              </Link>
            ) : (
              <a
                key={l.label}
                href={`/#${l.hash}`}
                onClick={(e) => goToSection(e, l.hash)}
                className="block py-2 text-slate-600"
              >
                {l.label}
              </a>
            ),
          )}
          <PrimaryButton to="/login" className="mt-3 w-full">
            Get Started
          </PrimaryButton>
        </div>
      )}
    </header>
  );
}
