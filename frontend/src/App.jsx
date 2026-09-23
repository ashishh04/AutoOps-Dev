import { Routes, Route, Navigate } from "react-router-dom";
import { useStore } from "./store/store";
import Home from "./pages/Home";
import Pricing from "./pages/Pricing";
import Login from "./pages/Login";
import Signup from "./pages/Signup";
import Checkout from "./pages/Checkout";
import Onboarding from "./pages/Onboarding";
import FeaturePage from "./pages/FeaturePage";
import Playground from "./pages/Playground";
import BookDemo from "./pages/BookDemo";
import Docs from "./pages/Docs";
import AuthCallback from "./pages/AuthCallback";
import NotFound from "./pages/NotFound";
import { LogoMark } from "./components/ui";
import AppLayout from "./components/app/AppLayout";
import Projects from "./pages/app/Projects";
import ProjectOverview from "./pages/app/ProjectOverview";
import ProjectIntegrations from "./pages/app/ProjectIntegrations";
import ProjectSettings from "./pages/app/ProjectSettings";
import Library from "./pages/app/Library";
import Inventory from "./pages/app/Inventory";
import ScriptEditor from "./components/app/ScriptEditor";
import Integrations from "./pages/app/Integrations";
import Jobs from "./pages/app/Jobs";
import JobDetail from "./pages/app/JobDetail";
import Nodes from "./pages/app/Nodes";
import Commands from "./pages/app/Commands";
import Executions from "./pages/app/Executions";
import ExecutionDetail from "./pages/app/ExecutionDetail";
import Workflows from "./pages/app/Workflows";
import AgentsHub from "./pages/app/AgentsHub";
import WorkflowDetail from "./pages/app/WorkflowDetail";
import Models from "./pages/app/Models";
import NotificationChannels from "./pages/app/NotificationChannels";
import Schedule from "./pages/app/Schedule";
import Webhooks from "./pages/app/Webhooks";
import Alerts from "./pages/app/Alerts";
import AlertDetail from "./pages/app/AlertDetail";
import AlertProviders from "./pages/app/AlertProviders";
import Incidents from "./pages/app/Incidents";
import IncidentDetail from "./pages/app/IncidentDetail";
import Approvals from "./pages/app/Approvals";
import Audit from "./pages/app/Audit";
import Governance from "./pages/app/Governance";
import KeyStorage from "./pages/app/KeyStorage";
import CreateJob from "./pages/app/CreateJob";
import SetupScm from "./pages/app/SetupScm";
import ComplianceReports from "./pages/app/ComplianceReports";
import AccessControl from "./pages/app/AccessControl";
import Admin from "./pages/app/Admin";
import Settings from "./pages/app/Settings";
import Notifications from "./pages/app/Notifications";
import ClientBilling from "./pages/app/Billing";
import Operator from "./pages/app/Operator";
import Viewer from "./pages/app/Viewer";
import ProviderLayout from "./components/provider/ProviderLayout";
import ProviderDashboard from "./pages/provider/ProviderDashboard";
import Tenants from "./pages/provider/Tenants";
import ProviderFleet from "./pages/provider/ProviderFleet";
import TenantDetail from "./pages/provider/TenantDetail";
import Billing from "./pages/provider/Billing";
import Plans from "./pages/provider/Plans";
import Usage from "./pages/provider/Usage";
import PlatformHealth from "./pages/provider/PlatformHealth";
import ProviderAudit from "./pages/provider/ProviderAudit";
import ProviderSettings from "./pages/provider/ProviderSettings";
import ProviderLibrary from "./pages/provider/Library";
import Broadcasts from "./pages/provider/Broadcasts";
import ProviderNotifications from "./pages/provider/ProviderNotifications";
import ProviderScriptEditor from "./pages/provider/ProviderScriptEditor";
import ProviderWorkflowDesigner from "./pages/provider/ProviderWorkflowDesigner";
import ProviderAgentBuilder from "./pages/provider/ProviderAgentBuilder";

// Full-screen loader shown while the session is being restored from a token.
function BootScreen() {
  return (
    <div className="grid-bg flex min-h-screen items-center justify-center bg-white">
      <div className="flex flex-col items-center gap-4">
        <LogoMark size={40} />
        <p className="animate-pulse text-sm text-slate-500">Loading…</p>
      </div>
    </div>
  );
}

function RequireAuth({ children }) {
  const { session, booting } = useStore();
  if (booting) return <BootScreen />;
  return session.authed ? children : <Navigate to="/login" replace />;
}

// Strict client-side role isolation: a signed-in client only reaches the
// console for their assigned role. Disallowed roles are redirected to their own.
function RequireRole({ allow, children }) {
  const { session, clientRole, booting } = useStore();
  if (booting) return <BootScreen />;
  if (!session.authed) return <Navigate to="/login" replace />;
  if (clientRole !== allow)
    return <Navigate to={`/app/${clientRole}`} replace />;
  return children;
}

// Capability guard: blocks client routes the current persona may not see.
function RequireCap({ cap, children }) {
  const { session, booting, can } = useStore();
  if (booting) return <BootScreen />;
  if (!session.authed) return <Navigate to="/login" replace />;
  if (!can(cap)) return <Navigate to="/app" replace />;
  return children;
}

// Provider console is locked to the owner (provider session role).
function RequireProvider({ children }) {
  const { session, booting } = useStore();
  if (booting) return <BootScreen />;
  if (!session.authed) return <Navigate to="/login" replace />;
  if (session.role !== "provider") return <Navigate to="/app" replace />;
  return children;
}

// Send a signed-in client to the console that matches their role.
function RoleHome() {
  const { clientRole } = useStore();
  return <Navigate to={`/app/${clientRole}`} replace />;
}

export default function App() {
  return (
    <Routes>
      {/* Marketing + auth */}
      <Route path="/" element={<Home />} />
      <Route path="/pricing" element={<Pricing />} />
      <Route path="/docs" element={<Docs />} />
      <Route path="/demo" element={<BookDemo />} />
      <Route path="/login" element={<Login />} />
      <Route path="/signup" element={<Signup />} />
      <Route path="/checkout" element={<Checkout />} />
      <Route path="/onboarding" element={<Onboarding />} />
      <Route path="/auth/callback" element={<AuthCallback />} />
      <Route path="/features/:slug" element={<FeaturePage />} />
      <Route path="/playground" element={<Playground />} />

      {/* Client / tenant console (project-first) */}
      <Route
        path="/app"
        element={
          <RequireAuth>
            <AppLayout />
          </RequireAuth>
        }
      >
        <Route index element={<RoleHome />} />
        <Route path="projects" element={<Projects />} />
        <Route
          path="library"
          element={
            <RequireCap cap="runWorkflow">
              <Library />
            </RequireCap>
          }
        />
        {/* What the workspace HAS, as opposed to what the library offers.
            Same capability as the library: seeing your own scripts is not a
            stronger right than browsing the catalog. Declared before
            library/script/:id so neither can shadow the other. */}
        <Route
          path="library/inventory"
          element={
            <RequireCap cap="runWorkflow">
              <Inventory />
            </RequireCap>
          }
        />
        {/* Scripts are the one library type a customer owns outright, so
            admins and operators author and adapt them here. Workflows and
            agents have no such route — they arrive by rollout. */}
        <Route
          path="library/script/new"
          element={
            <RequireCap cap="authorScript">
              <ScriptEditor audience="tenant" />
            </RequireCap>
          }
        />
        <Route
          path="library/script/:id"
          element={
            <RequireCap cap="authorScript">
              <ScriptEditor audience="tenant" />
            </RequireCap>
          }
        />
        <Route
          path="integrations"
          element={
            <RequireCap cap="deploy">
              <Integrations />
            </RequireCap>
          }
        />
        <Route
          path="admin"
          element={
            <RequireRole allow="admin">
              <Admin />
            </RequireRole>
          }
        />
        <Route
          path="operator"
          element={
            <RequireRole allow="operator">
              <Operator />
            </RequireRole>
          }
        />
        <Route
          path="viewer"
          element={
            <RequireRole allow="viewer">
              <Viewer />
            </RequireRole>
          }
        />
        <Route path="settings" element={<Settings />} />
        {/*
          ONE model screen: this workspace's own vendor keys. The engine has no
          surface in the tenant console — it is the engine behind workflows,
          and its workspace token can read and delete every app in the shared
          workspace, so it stays server-side.
        */}
        <Route
          path="models"
          element={
            <RequireCap cap="manageKeys">
              <Models />
            </RequireCap>
          }
        />
        {/* The short-lived separate route; kept so existing links resolve. */}
        <Route path="ai-providers" element={<Navigate to="/app/models" replace />} />
        {/*
          Outbound notification channels and the rules that fire them. Distinct
          from "notifications" below, which is the in-app inbox — this one is
          what reaches Slack, Teams and email. Gated on manageKeys because
          installing a channel stores a third-party credential.
        */}
        <Route
          path="notification-channels"
          element={
            <RequireCap cap="manageKeys">
              <NotificationChannels />
            </RequireCap>
          }
        />
        <Route
          path="billing"
          element={
            <RequireCap cap="manageBilling">
              <ClientBilling />
            </RequireCap>
          }
        />
        <Route path="notifications" element={<Notifications />} />
        <Route path="projects/:pid" element={<ProjectOverview />} />
        <Route path="projects/:pid/jobs" element={<Jobs />} />
        <Route path="projects/:pid/jobs/new" element={<CreateJob />} />
        <Route path="projects/:pid/jobs/:jid/edit" element={<CreateJob />} />
        <Route path="projects/:pid/jobs/:id" element={<JobDetail />} />
        {/* Workflows and agents are DESIGNED BY THE PROVIDER and rolled out to
            a workspace, so the client app has no designer route for either —
            not a hidden one, not a read-only one. The canvas never reaches a
            customer's browser (workflow-service withholds the definition), so
            a route here would have nothing to render. Authoring lives in the
            provider console: /provider/library/workflow/new and /agent/new. */}
        <Route path="projects/:pid/workflows" element={<Workflows />} />
        {/* Read-and-run. There is no authoring route: an agent's persona is
            the provider's product and arrives by rollout. */}
        <Route path="projects/:pid/agents" element={<AgentsHub />} />
        {/* A workflow's results live INSIDE the workflow, the same way a
            job's do. A workflow run and a job run are the same row in the
            same table; to an operator asking "did the 2am automation
            work" they are the same question. */}
        <Route path="projects/:pid/workflows/:id" element={<WorkflowDetail />} />
        <Route path="projects/:pid/executions" element={<Executions />} />
        <Route
          path="projects/:pid/executions/:id"
          element={<ExecutionDetail />}
        />
        <Route path="projects/:pid/nodes" element={<Nodes />} />
        <Route path="projects/:pid/schedule" element={<Schedule />} />
        <Route path="projects/:pid/webhooks" element={<Webhooks />} />
        <Route path="projects/:pid/alerts" element={<Alerts />} />
        {/* Before the :fingerprint route below, or "sources" is parsed as one. */}
        <Route path="projects/:pid/alerts/sources" element={<AlertProviders />} />
        <Route path="projects/:pid/incidents" element={<Incidents />} />
        <Route path="projects/:pid/incidents/:id" element={<IncidentDetail />} />
        {/* Encoded in the link: a fingerprint is opaque and may contain a slash. */}
        <Route path="projects/:pid/alerts/:fingerprint" element={<AlertDetail />} />
        <Route path="projects/:pid/commands" element={<Commands />} />
        <Route path="projects/:pid/approvals" element={<Approvals />} />
        <Route path="projects/:pid/audit" element={<Audit />} />
        <Route path="projects/:pid/governance" element={<Governance />} />
        <Route path="projects/:pid/keys" element={<KeyStorage />} />
        <Route path="projects/:pid/scm" element={<SetupScm />} />
        <Route
          path="projects/:pid/compliance"
          element={<ComplianceReports />}
        />
        <Route path="projects/:pid/access" element={<AccessControl />} />
        <Route
          path="projects/:pid/integrations"
          element={<ProjectIntegrations />}
        />
        <Route path="projects/:pid/settings" element={<ProjectSettings />} />
      </Route>

      {/* Provider / owner super-admin console */}
      <Route
        path="/provider"
        element={
          <RequireProvider>
            <ProviderLayout />
          </RequireProvider>
        }
      >
        <Route index element={<ProviderDashboard />} />
        <Route path="tenants" element={<Tenants />} />
        <Route path="fleet" element={<ProviderFleet />} />
        <Route path="tenants/:id" element={<TenantDetail />} />
        <Route path="library" element={<ProviderLibrary />} />
        {/* The workflow designer, finally. The vendor canvas that used to live
            here was deleted with the engine behind it, and this route stayed
            empty on the correct reasoning that an editor producing definitions
            nothing runs is worse than no editor — which left "New workflow" in
            the library header navigating nowhere.

            What closes it is not a canvas. The runtime publishes its own node
            types and fields at /v1/authoring/schema, derived from the Pydantic
            models that judge the saved definition, and this page renders
            exactly that. There is no palette in the frontend to go stale — the
            failure that has already hit NativeWorkflowService.GRAPH_NODE_TYPES
            twice. */}
        <Route path="library/workflow/new" element={<ProviderWorkflowDesigner />} />
        <Route path="library/workflow/:id" element={<ProviderWorkflowDesigner />} />
        <Route path="library/script/new" element={<ProviderScriptEditor />} />
        <Route path="library/script/:id" element={<ProviderScriptEditor />} />
        {/* The agent builder. Unlike a workflow, an agent has no canvas to
            delete: it is a persona, a model and a closed allow-list of catalog
            workflows, and the row this writes is byte-for-byte the one
            agents/_schema/publish.py writes from a JSON file. Authoring an
            agent therefore no longer needs a checkout — which is what makes
            "roll it out to anyone" true from the console. */}
        <Route path="library/agent/new" element={<ProviderAgentBuilder />} />
        <Route path="library/agent/:id" element={<ProviderAgentBuilder />} />
        {/* Type-scoped views of the same catalog — /library/scripts|workflows|agents.
            Ranked below the two static routes above, so those still win. */}
        <Route path="library/:type" element={<ProviderLibrary />} />
        <Route path="broadcasts" element={<Broadcasts />} />
        <Route path="notifications" element={<ProviderNotifications />} />
        <Route path="billing" element={<Billing />} />
        <Route path="plans" element={<Plans />} />
        <Route path="usage" element={<Usage />} />
        <Route path="health" element={<PlatformHealth />} />
        <Route path="audit" element={<ProviderAudit />} />
        <Route path="settings" element={<ProviderSettings />} />
      </Route>

      <Route path="*" element={<NotFound />} />
    </Routes>
  );
}