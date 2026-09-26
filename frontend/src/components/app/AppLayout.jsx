import React, { useState, useEffect, useRef } from "react";
import {
  NavLink,
  Outlet,
  Link,
  useLocation,
  useNavigate,
} from "react-router-dom";
import Icon from "../Icon";
import { LogoMark } from "../ui";
import NotificationsMenu from "../NotificationsMenu";
import UpgradeModal from "./UpgradeModal";
import { useStore, ROLE_LABELS } from "../../store/store";
import { api } from "../../lib/api";
import useClickOutside from "../../lib/useClickOutside";

const initials = (name, email) => {
  const src = String(name || email || "").trim();
  if (!src) return "U";
  const parts = src.split(/\s+/);
  if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase();
  return src.slice(0, 2).toUpperCase();
};

function ProfileMenu() {
  const navigate = useNavigate();
  const { user, workspace, clientRole, logout } = useStore();
  const [open, setOpen] = useState(false);
  const ref = useRef(null);
  useClickOutside(ref, () => setOpen(false), open);
  const name = user?.name || "Account";
  const email = user?.email || "";
  const plan = workspace?.plan;
  return (
    <div className="relative" ref={ref}>
      <button
        onClick={() => setOpen((v) => !v)}
        aria-haspopup="menu"
        aria-expanded={open}
        className="flex items-center gap-2.5 rounded-full border border-slate-200 bg-slate-50 py-1 pl-3 pr-1 transition hover:border-blue-500"
      >
        <span className="hidden text-right leading-tight sm:block">
          <span className="block max-w-[140px] truncate text-sm font-medium text-slate-900">
            {name}
          </span>
          {plan && (
            <span className="block text-[11px] font-medium text-slate-900">
              {plan} plan
            </span>
          )}
        </span>
        <span className="flex h-8 w-8 items-center justify-center rounded-full bg-slate-900 text-xs font-bold text-white">
          {initials(name, email)}
        </span>
      </button>
      {open && (
        <>
          <div
            role="menu"
            className="absolute right-0 z-50 mt-2 w-64 rounded-xl border border-slate-200 bg-[#ffffff] p-1.5 shadow-2xl shadow-slate-300/40"
          >
            <div className="px-3 py-2.5">
              <p className="truncate text-sm font-semibold text-slate-900">
                {name}
              </p>
              {email && (
                <p className="truncate text-xs text-slate-500">{email}</p>
              )}
              <div className="mt-2 flex flex-wrap items-center gap-1.5">
                {plan && (
                  <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[10px] font-semibold text-slate-900">
                    {plan} plan
                  </span>
                )}
              </div>
            </div>
            <div className="my-1 h-px bg-slate-50" />
            <button
              onClick={() => {
                setOpen(false);
                navigate("/app/settings");
              }}
              className="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-left text-sm text-slate-600 transition hover:bg-slate-100"
            >
              <Icon name="gear" size={14} /> Settings
            </button>
            <button
              onClick={() => {
                logout();
                navigate("/login");
              }}
              className="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-left text-sm text-slate-600 transition hover:bg-slate-100"
            >
              <Icon name="logout" size={14} /> Sign out
            </button>
          </div>
        </>
      )}
    </div>
  );
}

const accessLink = (clientRole) => {
  if (clientRole === "operator")
    return { to: "/app/operator", label: "Operator Console", icon: "gauge" };
  if (clientRole === "viewer")
    return { to: "/app/viewer", label: "Viewer Console", icon: "chart" };
  return { to: "/app/admin", label: "Admin Console", icon: "users" };
};

// Sidebar options are filtered per persona so no console shows another's options.
// Exported for tests for the same reason projectNav is: a page with a route and
// no nav entry is unreachable, and that has shipped twice.
export const workspaceNav = (can, clientRole) => {
  const workspace = [
    { to: "/app/projects", label: "Projects", icon: "folder" },
  ];
  if (can("runWorkflow"))
    workspace.push({ to: "/app/library", label: "Library", icon: "book" });
  if (can("deploy"))
    workspace.push({
      to: "/app/integrations",
      label: "Cloud Integrations",
      icon: "cloud",
    });
  const account = [];
  if (can("manageBilling"))
    account.push({
      to: "/app/billing",
      label: "Plan & Billing",
      icon: "scale",
    });
  account.push({
    to: "/app/notifications",
    label: "Notifications",
    icon: "bell",
  });
  // Outbound delivery — Slack, Teams, Outlook, Gmail, GitHub. Distinct from
  // "Notifications" above, which is the in-app inbox you go and read. Rides
  // manageKeys because installing a channel stores a third-party credential.
  if (can("manageKeys"))
    account.push({
      to: "/app/notification-channels",
      label: "Alert Channels",
      icon: "chat",
    });
  // ONE entry: this workspace's own vendor keys. The engine is not exposed in the
  // tenant console at all — it is the workflow engine, not something a tenant
  // configures. Rides manageKeys like the rest of credential management.
  if (can("manageKeys"))
    account.push({ to: "/app/models", label: "Models", icon: "sparkles" });
  account.push({ to: "/app/settings", label: "Settings", icon: "gear" });

  // WORKSPACE-level, not per project, and that is the point rather than a
  // layout preference. A monitoring tool raises alerts about an estate; it has
  // never heard of an AutoOps project. Hanging these three off a project meant
  // connecting the same Datadog account once per project and then guessing
  // which project an alert had landed in before it could be read — and for an
  // alert carrying no AutoOps labels, which is most of them, the honest answer
  // was "none of them". Narrowing to a project is still offered, as a filter on
  // the page.
  const operate = [
    { to: "/app/incidents", label: "Incidents", icon: "pulse", end: true },
    // Directly under Incidents, because it is the thing that MAKES them.
    // Nothing correlates without a rule, so a customer looking at an empty
    // Incidents page needs the next step within reach rather than buried in
    // settings — otherwise they conclude the feature is broken.
    { to: "/app/incidents/rules", label: "Correlation Rules", icon: "blocks" },
    { to: "/app/alerts", label: "Alert Feed", icon: "radar", end: true },
    { to: "/app/alerts/sources", label: "Monitoring Sources", icon: "cube" },
  ];

  return [
    { group: "Workspace", items: workspace },
    { group: "Operate", items: operate },
    { group: "Console", items: [accessLink(clientRole)] },
    { group: "Account", items: account },
  ];
};

// Exported for tests: a page with a route but no nav entry is unreachable, and
// that has shipped twice now. The nav is worth asserting on directly.
export const projectNav = (b, can) =>
  [
    {
      group: "Overview",
      items: [{ to: b, label: "Overview", icon: "dashboard", end: true }],
    },
    {
      group: "Automate",
      items: [
        { to: `${b}/jobs`, label: "Jobs", icon: "list" },
        // ONE automation concept, and it is the workflow. There used to be an
        // "Agents" entry beside this one; every agent in it held exactly ONE
        // tool, and that tool was one workflow — so the same automation was
        // listed twice, under two names, in two databases, and a customer had
        // to learn which of the two to press. An agent that wraps a single
        // workflow is that workflow with a model in front of it deciding
        // whether to call its only tool.
        //
        // The surface came back when that stopped being true. The shipped
        // agents correlate two or three automations across a phase graph, and
        // the judgement between them is the product — an RCA analyst reading
        // an alarm board against a change timeline is not "a workflow with a
        // model in front of it".
        //
        // It is also the only place they CAN appear: an agent's tool workflows
        // are delivered as sealed components and are deliberately absent from
        // the Workflows list, so a customer rolled out an agent would
        // otherwise see nothing at all.
        { to: `${b}/workflows`, label: "Workflows", icon: "blocks" },
        { to: `${b}/agents`, label: "AI Agents", icon: "robot" },
        { to: `${b}/schedule`, label: "Schedule", icon: "clock" },
      ],
    },
    {
      group: "Operate",
      // Incidents, the alert feed and monitoring sources used to sit at the top
      // of this group. They are workspace-level now — see workspaceNav — so a
      // customer connects a monitoring source once instead of once per project.
      items: [
        { to: `${b}/executions`, label: "Executions", icon: "play" },
        { to: `${b}/nodes`, label: "Nodes", icon: "server" },
        { to: `${b}/integrations`, label: "Cloud", icon: "cloud" },
      ],
    },
    {
      group: "Govern",
      items: [
        { to: `${b}/approvals`, label: "Approvals", icon: "check" },
        can("viewAudit") && {
          to: `${b}/audit`,
          label: "Audit Log",
          icon: "trail",
        },
        can("manageGovernance") && {
          to: `${b}/governance`,
          label: "Governance",
          icon: "scale",
        },
        // Same feature family as Governance and gated identically — the page
        // itself handles the plan check and shows an upgrade notice.
        can("manageGovernance") && {
          to: `${b}/compliance`,
          label: "Compliance Reports",
          icon: "shield",
        },
        can("manageKeys") && {
          to: `${b}/keys`,
          label: "Key Storage",
          icon: "key",
        },
      ].filter(Boolean),
    },
    can("manageProject") && {
      group: "Project",
      items: [{ to: `${b}/settings`, label: "Project Settings", icon: "gear" }],
    },
  ].filter(Boolean);

const linkClass = ({ isActive }) =>
  `group flex items-center gap-3 rounded-lg px-3 py-2 text-sm transition duration-200 ${
    isActive
      ? "bg-slate-50 font-semibold text-slate-900"
      : "text-slate-500 hover:bg-slate-100 hover:text-slate-900"
  }`;

// Sidebar project pill: a real switcher — click opens the project list
// (synced from the store), not just a link back to /app/projects.
function SidebarProjectSwitcher({ project }) {
  const navigate = useNavigate();
  const { projects } = useStore();
  const [open, setOpen] = useState(false);
  const ref = useRef(null);
  useClickOutside(ref, () => setOpen(false), open);
  return (
    <div className="relative" ref={ref}>
      <button
        onClick={() => setOpen((v) => !v)}
        aria-haspopup="listbox"
        aria-expanded={open}
        className="flex w-full items-center justify-between rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-700 transition hover:border-blue-500"
      >
        <span className="flex items-center gap-2 truncate">
          <span className="flex h-5 w-5 items-center justify-center rounded bg-gradient-to-br from-slate-200 to-slate-200 text-slate-900">
            <Icon name="folder" size={12} />
          </span>
          <span className="truncate">{project.name}</span>
        </span>
        <Icon
          name="chevron"
          size={14}
          className={`text-slate-500 transition ${open ? "-rotate-90" : "rotate-90"}`}
        />
      </button>
      {open && (
        <>
          <div
            role="listbox"
            className="absolute left-0 right-0 z-50 mt-1 rounded-xl border border-slate-200 bg-[#ffffff] p-1.5 shadow-2xl shadow-slate-300/40"
          >
            <p className="px-2 py-1 text-[10px] font-semibold uppercase tracking-[0.18em] text-slate-600">
              Switch project
            </p>
            {projects.map((p) => (
              <button
                key={p.id}
                onClick={() => {
                  setOpen(false);
                  navigate(`/app/projects/${p.id}`);
                }}
                className={`flex w-full items-center justify-between rounded-lg px-2.5 py-2 text-left text-sm transition hover:bg-slate-100 ${String(p.id) === String(project.id) ? "text-slate-900" : "text-slate-600"}`}
              >
                <span className="flex items-center gap-2 truncate">
                  <span className="flex h-5 w-5 items-center justify-center rounded bg-slate-50 text-slate-900">
                    <Icon name="folder" size={12} />
                  </span>
                  <span className="truncate">{p.name}</span>
                </span>
                {String(p.id) === String(project.id) && (
                  <Icon name="check" size={14} className="text-emerald-600" />
                )}
              </button>
            ))}
            <div className="my-1 h-px bg-slate-50" />
            <button
              onClick={() => {
                setOpen(false);
                navigate("/app/projects");
              }}
              className="flex w-full items-center gap-2 rounded-lg px-2.5 py-2 text-left text-sm text-slate-600 transition hover:bg-slate-100"
            >
              <Icon name="list" size={14} /> All projects
            </button>
          </div>
        </>
      )}
    </div>
  );
}

function SidebarContent({ inProject, project }) {
  const navigate = useNavigate();
  const { can, logout, clientRole, user, workspace } = useStore();
  const b = inProject ? `/app/projects/${project.id}` : null;
  const nav = inProject ? projectNav(b, can) : workspaceNav(can, clientRole);
  return (
    <div className="flex h-full flex-col">
      <Link to="/" className="flex items-center px-5 py-5">
        <LogoMark size={30} />
      </Link>
      <div className="px-3 pb-2">
        {inProject ? (
          <SidebarProjectSwitcher project={project} />
        ) : (
          <div className="flex items-center justify-between rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-600">
            <span className="flex items-center gap-2 truncate">
              <span className="h-2 w-2 shrink-0 rounded-full bg-emerald-400" />{" "}
              <span className="truncate">{workspace?.name || "Workspace"}</span>
            </span>
            <span className="shrink-0 rounded-full bg-slate-100 px-2 py-0.5 text-[10px] font-semibold text-slate-900">
              {workspace?.plan || "Free"}
            </span>
          </div>
        )}
      </div>
      <nav className="no-scrollbar flex-1 space-y-5 overflow-y-auto px-3 py-3">
        {nav.map((sec) => (
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
                  className={linkClass}
                >
                  <Icon name={it.icon} size={18} />
                  {it.label}
                </NavLink>
              ))}
            </div>
          </div>
        ))}
      </nav>
      <div className="border-t border-slate-200 p-3">
        <div className="flex items-center gap-3 rounded-lg px-2 py-2">
          <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-slate-900 text-xs font-bold text-white">
            {initials(user?.name, user?.email)}
          </span>
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-medium text-slate-900">
              {user?.name || "Signed in"}
            </p>
            <p className="truncate text-xs text-slate-500">
              {user?.email || ROLE_LABELS[clientRole]}
            </p>
          </div>
          <button
            onClick={() => {
              logout();
              navigate("/login");
            }}
            className="text-slate-500 transition hover:text-slate-900"
            aria-label="Log out"
          >
            <Icon name="logout" size={18} />
          </button>
        </div>
      </div>
    </div>
  );
}

function ProjectSwitcher({ currentId }) {
  const navigate = useNavigate();
  const { projects } = useStore();
  const [open, setOpen] = useState(false);
  const ref = useRef(null);
  useClickOutside(ref, () => setOpen(false), open);
  // Route ids are strings, backend ids are numbers — compare as strings.
  const current = projects.find((p) => String(p.id) === String(currentId));
  return (
    <div className="relative" ref={ref}>
      <button
        onClick={() => setOpen((v) => !v)}
        aria-haspopup="listbox"
        aria-expanded={open}
        className="flex shrink-0 items-center gap-1.5 rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-700 transition hover:border-blue-500"
      >
        <span className="flex h-5 w-5 items-center justify-center rounded bg-gradient-to-br from-slate-200 to-slate-200 text-slate-900">
          <Icon name="folder" size={12} />
        </span>
        <span className="hidden max-w-[140px] truncate sm:inline">
          {current ? current.name : "Project"}
        </span>
        <Icon name="chevron" size={14} className="rotate-90 text-slate-500" />
      </button>
      {open && (
        <>
          <div
            role="listbox"
            className="absolute left-0 z-50 mt-1 w-60 rounded-xl border border-slate-200 bg-[#ffffff] p-1.5 shadow-2xl shadow-slate-300/40"
          >
            <p className="px-2 py-1 text-[10px] font-semibold uppercase tracking-[0.18em] text-slate-600">
              Switch project
            </p>
            {projects.map((p) => (
              <button
                key={p.id}
                onClick={() => {
                  setOpen(false);
                  navigate(`/app/projects/${p.id}`);
                }}
                className={`flex w-full items-center justify-between rounded-lg px-2.5 py-2 text-left text-sm transition hover:bg-slate-100 ${String(p.id) === String(currentId) ? "text-slate-900" : "text-slate-600"}`}
              >
                <span className="flex items-center gap-2 truncate">
                  <span className="flex h-5 w-5 items-center justify-center rounded bg-slate-50 text-slate-900">
                    <Icon name="folder" size={12} />
                  </span>
                  <span className="truncate">{p.name}</span>
                </span>
                {String(p.id) === String(currentId) && (
                  <Icon name="check" size={14} className="text-emerald-600" />
                )}
              </button>
            ))}
            <div className="my-1 h-px bg-slate-50" />
            <button
              onClick={() => {
                setOpen(false);
                navigate("/app/projects");
              }}
              className="flex w-full items-center gap-2 rounded-lg px-2.5 py-2 text-left text-sm text-slate-600 transition hover:bg-slate-100"
            >
              <Icon name="list" size={14} /> All projects
            </button>
          </div>
        </>
      )}
    </div>
  );
}

export default function AppLayout() {
  const [open, setOpen] = useState(false);
  const [notifications, setNotifications] = useState([]);
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const {
    session,
    stopImpersonation,
    clientRole,
    projects,
    refreshProjects,
    refreshWorkspace,
  } = useStore();

  useEffect(() => {
    // Backend rows → bell-menu shape (tone/icon/relative time).
    const timeAgo = (iso) => {
      const s = Math.floor((Date.now() - new Date(iso).getTime()) / 1000);
      if (isNaN(s)) return "";
      if (s < 60) return "just now";
      if (s < 3600) return `${Math.floor(s / 60)}m ago`;
      if (s < 86400) return `${Math.floor(s / 3600)}h ago`;
      return `${Math.floor(s / 86400)}d ago`;
    };
    api
      .listNotifications()
      .then((rows) =>
        setNotifications(
          (Array.isArray(rows) ? rows : []).map((n) => ({
            id: n.id,
            title: n.title,
            read: !!n.read,
            link: n.link || "/app/notifications",
            channel: n.kind === "PROVIDER" ? "provider" : "internal",
            tone:
              n.kind === "ALERT"
                ? "red"
                : n.kind === "PROVIDER"
                  ? "violet"
                  : "cyan",
            icon: n.kind === "ALERT" ? "shield" : "bell",
            time: timeAgo(n.createdAt),
          })),
        ),
      )
      .catch(() => {});
    // Self-heal stale session state (plan badge, workspace name) on entry.
    refreshWorkspace();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const m = pathname.match(/^\/app\/projects\/([^/]+)/);
  const pid = m ? m[1] : null;

  // Keep the project list (switcher, sidebar name) in sync whenever the
  // active project changes — names update without a full reload.
  useEffect(() => {
    refreshProjects();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pid]);

  // Route ids are strings, backend ids are numbers — compare as strings.
  const project = pid
    ? projects.find((p) => String(p.id) === String(pid)) || {
        id: pid,
        name: "Project",
      }
    : null;
  const inProject = !!pid;
  const impersonating = session && session.impersonating;
  return (
    <div className="min-h-screen bg-white text-slate-700">
      {/* Global subscription-gate prompt (fed by api.js gate denials). */}
      <UpgradeModal />
      <aside className="fixed inset-y-0 left-0 hidden w-64 border-r border-slate-200 bg-slate-50 lg:block">
        <SidebarContent inProject={inProject} project={project} />
      </aside>
      {open && (
        <div className="fixed inset-0 z-50 lg:hidden">
          <div
            className="absolute inset-0 bg-slate-900/25 backdrop-blur-md"
            onClick={() => setOpen(false)}
          />
          <aside className="absolute inset-y-0 left-0 w-64 border-r border-slate-200 bg-white">
            <SidebarContent inProject={inProject} project={project} />
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
          {inProject && <ProjectSwitcher currentId={pid} />}
          <div className="relative hidden w-full max-w-sm sm:block">
            <span className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-slate-500">
              <Icon name="search" size={16} />
            </span>
            <input
              placeholder="Search jobs, executions, nodes…"
              className="w-full rounded-lg border border-slate-200 bg-slate-50 py-2 pl-9 pr-3 text-sm text-slate-900 outline-none transition placeholder:text-slate-600 focus:border-slate-300 focus:ring-2 focus:ring-slate-300"
            />
          </div>
          <div className="ml-auto flex items-center gap-3">
            <NotificationsMenu
              items={notifications}
              viewAllTo="/app/notifications"
              accent="cyan"
            />
            <ProfileMenu />
          </div>
        </header>
        {impersonating && (
          <div className="flex items-center justify-between gap-2 border-b border-violet-200 bg-violet-50 px-5 py-2 text-xs text-violet-700">
            <span className="flex items-center gap-2">
              <Icon name="shield" size={14} /> Viewing as tenant{" "}
              <b className="mx-1">{impersonating.name}</b> · provider
              impersonation
            </span>
            <button
              onClick={() => {
                stopImpersonation();
                navigate("/provider/tenants");
              }}
              className="rounded-md border border-violet-300 px-2 py-0.5 font-medium transition hover:bg-violet-100"
            >
              Exit
            </button>
          </div>
        )}
        {clientRole === "viewer" && (
          <div className="flex items-center gap-2 border-b border-amber-200 bg-amber-50 px-5 py-2 text-xs text-amber-700">
            <Icon name="lock" size={13} /> Read-only access — your role is
            Viewer. Contact a workspace admin if you need edit permissions.
          </div>
        )}
        <main className="animate-fade-in px-5 py-7 sm:px-8">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
