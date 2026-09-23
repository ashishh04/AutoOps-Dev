import React, { useState, useEffect, useRef } from "react";
import { NavLink, Outlet, Link, useNavigate } from "react-router-dom";
import Icon from "../Icon";
import { LogoMark } from "../ui";
import NotificationsMenu from "../NotificationsMenu";
import { api } from "../../lib/api";
import { useStore } from "../../store/store";

const NAV = [
  {
    group: "Overview",
    items: [
      { to: "/provider", label: "Dashboard", icon: "dashboard", end: true },
      { to: "/provider/tenants", label: "Tenants", icon: "users" },
      // Every tenant at once. Sits beside Tenants rather than under
      // Library because it is an operational view, not a catalog one.
      { to: "/provider/fleet", label: "Fleet", icon: "pulse" },
      { to: "/provider/notifications", label: "Notifications", icon: "bell" },
    ],
  },
  {
    group: "Content",
    items: [
      // Library is the "everything" view; the three below are the same catalog
      // scoped to one type. `end` keeps Library from lighting up on its children.
      { to: "/provider/library", label: "Library", icon: "book", end: true },
      { to: "/provider/library/scripts", label: "Scripts", icon: "terminal", sub: true },
      { to: "/provider/library/workflows", label: "Workflows", icon: "blocks", sub: true },
      { to: "/provider/library/agents", label: "Agents", icon: "robot", sub: true },
      { to: "/provider/broadcasts", label: "Broadcasts", icon: "bell" },
    ],
  },
  {
    group: "Revenue",
    items: [
      { to: "/provider/billing", label: "Billing", icon: "chart" },
      { to: "/provider/plans", label: "Plans & Quotas", icon: "scale" },
    ],
  },
  {
    group: "Operations",
    items: [
      { to: "/provider/usage", label: "Usage", icon: "gauge" },
      { to: "/provider/health", label: "Platform Health", icon: "pulse" },
    ],
  },
  {
    group: "Trust",
    items: [
      { to: "/provider/audit", label: "Audit Log", icon: "trail" },
      { to: "/provider/settings", label: "Settings", icon: "gear" },
    ],
  },
];

const linkClass =
  (sub = false) =>
  ({ isActive }) =>
    `group flex items-center gap-3 rounded-lg py-2 text-sm transition duration-200 ${
      sub ? "ml-3 border-l border-slate-200 pl-4 pr-3" : "px-3"
    } ${
      isActive
        ? "bg-violet-400/10 font-semibold text-slate-900 ring-1 ring-inset ring-violet-400/30"
        : "text-slate-500 hover:bg-slate-100 hover:text-slate-900"
    }`;

function SidebarContent() {
  const navigate = useNavigate();
  const { logout } = useStore();
  const doLogout = () => {
    logout();
    navigate("/login");
  };
  return (
    <div className="flex h-full flex-col">
      <Link to="/" className="flex items-center gap-2.5 px-5 py-5">
        <LogoMark size={28} />
        <span className="rounded-full bg-violet-400/10 px-2 py-0.5 text-[10px] font-semibold uppercase tracking-[0.2em] text-violet-600">
          Provider
        </span>
      </Link>
      <nav className="no-scrollbar flex-1 space-y-5 overflow-y-auto px-3 py-3">
        {NAV.map((sec) => (
          <div key={sec.group}>
            <p className="px-3 pb-1.5 text-[10px] font-semibold uppercase tracking-[0.18em] text-slate-600">
              {sec.group}
            </p>
            <div className="space-y-0.5">
              {sec.items.map((it) => (
                <NavLink
                  key={it.to}
                  to={it.to}
                  end={it.end}
                  className={linkClass(it.sub)}
                >
                  <Icon name={it.icon} size={it.sub ? 16 : 18} />
                  {it.label}
                </NavLink>
              ))}
            </div>
          </div>
        ))}
      </nav>
      <div className="border-t border-slate-200 p-3">
        <div className="flex items-center gap-3 rounded-lg px-2 py-2">
          <span className="flex h-8 w-8 items-center justify-center rounded-full bg-gradient-to-br from-slate-900 to-slate-900 text-xs font-bold text-white">
            YN
          </span>
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-medium text-slate-900">You</p>
            <p className="truncate text-xs text-slate-500">Super Admin</p>
          </div>
          <button
            onClick={doLogout}
            className="text-slate-500 transition hover:text-slate-900"
          >
            <Icon name="logout" size={18} />
          </button>
        </div>
      </div>
    </div>
  );
}

function ProfileMenu() {
  const [open, setOpen] = useState(false);
  const ref = useRef(null);
  const navigate = useNavigate();
  const { logout } = useStore();
  useEffect(() => {
    const h = (e) => {
      if (ref.current && !ref.current.contains(e.target)) setOpen(false);
    };
    document.addEventListener("mousedown", h);
    return () => document.removeEventListener("mousedown", h);
  }, []);
  const doLogout = () => {
    logout();
    navigate("/login");
  };
  return (
    <div className="relative" ref={ref}>
      <button
        onClick={() => setOpen((o) => !o)}
        className="flex h-8 w-8 items-center justify-center rounded-full bg-gradient-to-br from-slate-900 to-slate-900 text-xs font-bold text-white ring-2 ring-transparent transition hover:ring-slate-200"
        aria-label="Account"
      >
        YN
      </button>
      {open && (
        <div className="absolute right-0 mt-2 w-52 origin-top-right overflow-hidden rounded-xl border border-slate-200 bg-[#ffffff] shadow-2xl shadow-slate-300/40">
          <div className="border-b border-slate-200 px-4 py-3">
            <p className="text-sm font-medium text-slate-900">You</p>
            <p className="text-xs text-slate-500">Super Admin</p>
          </div>
          <div className="p-1.5">
            <Link
              to="/provider/settings"
              onClick={() => setOpen(false)}
              className="flex items-center gap-2.5 rounded-lg px-3 py-2 text-sm text-slate-700 transition hover:bg-slate-100"
            >
              <Icon name="gear" size={16} /> Settings
            </Link>
            <Link
              to="/provider/notifications"
              onClick={() => setOpen(false)}
              className="flex items-center gap-2.5 rounded-lg px-3 py-2 text-sm text-slate-700 transition hover:bg-slate-100"
            >
              <Icon name="bell" size={16} /> Notifications
            </Link>
            <button
              onClick={doLogout}
              className="flex w-full items-center gap-2.5 rounded-lg px-3 py-2 text-left text-sm text-red-600 transition hover:bg-red-50"
            >
              <Icon name="logout" size={16} /> Sign out
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

export default function ProviderLayout() {
  const [open, setOpen] = useState(false);
  const [notifications, setNotifications] = useState([]);

  useEffect(() => {
    api.listNotifications()
      .then((rows) => setNotifications(Array.isArray(rows) ? rows : []))
      .catch(() => {});
  }, []);

  return (
    <div className="min-h-screen bg-white text-slate-700">
      <aside className="fixed inset-y-0 left-0 hidden w-64 border-r border-slate-200 bg-slate-50 lg:block">
        <SidebarContent />
      </aside>
      {open && (
        <div className="fixed inset-0 z-50 lg:hidden">
          <div
            className="absolute inset-0 bg-slate-900/25 backdrop-blur-md"
            onClick={() => setOpen(false)}
          />
          <aside className="absolute inset-y-0 left-0 w-64 border-r border-slate-200 bg-white">
            <SidebarContent />
          </aside>
        </div>
      )}
      <div className="lg:pl-64">
        <header className="sticky top-0 z-40 flex items-center gap-4 border-b border-slate-200 bg-slate-50 px-5 py-3 backdrop-blur-md">
          <button
            onClick={() => setOpen(true)}
            className="text-slate-600 lg:hidden"
            aria-label="Open menu"
          >
            <Icon name="list" size={22} />
          </button>
          <span className="inline-flex items-center gap-2 rounded-full border border-violet-400/30 bg-violet-400/10 px-3 py-1 text-xs font-medium text-violet-700">
            <Icon name="shield" size={13} /> Provider workspace · internal
          </span>
          <div className="ml-auto flex items-center gap-3">
            <NotificationsMenu
              items={notifications}
              viewAllTo="/provider/notifications"
              accent="violet"
            />
            <ProfileMenu />
          </div>
        </header>
        <main className="animate-fade-in px-5 py-7 sm:px-8">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
