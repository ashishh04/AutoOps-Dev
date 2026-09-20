import React, { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import {
  PageHeader,
  Card,
  SmallButton,
  Chip,
} from "../../components/app/appui";
import Icon from "../../components/Icon";
import { api } from "../../lib/api";
import { useStore } from "../../store/store";
import { planAllows } from "../../lib/entitlements";
import JobOptionsTab from "../../components/app/job/JobOptionsTab";
import JobNodesTab from "../../components/app/job/JobNodesTab";
import JobScheduleTab from "../../components/app/job/JobScheduleTab";
import JobNotificationsTab from "../../components/app/job/JobNotificationsTab";
import JobExecutionTab from "../../components/app/job/JobExecutionTab";

// The full authoring surface. Order follows the sequence an author actually
// works in: what it is, what it does, where it runs, when, who hears about it,
// and how it behaves around the edges.
const TABS = [
  "Details",
  "Options",
  "Workflow",
  "Nodes",
  "Schedule",
  "Notifications",
  "Execution",
];

// The typed step palette from the AutoOps designer.
const STEP_TYPES = [
  { id: "agent", label: "Agent Command", icon: "robot", category: "AI Agent" },
  { id: "command", label: "Command", icon: "terminal", category: "System" },
  // Runs a script this workspace holds in its library, by reference. The body
  // is never copied into the job: core-service resolves the reference when the
  // run is queued, so the job always runs the current script and the run's
  // snapshot records exactly which one it was.
  {
    id: "library",
    label: "Library Script",
    icon: "book",
    category: "Scripting",
  },
  { id: "script", label: "Script", icon: "doc", category: "Scripting" },
  {
    id: "powershell",
    label: "PowerShell",
    icon: "terminal",
    category: "Scripting",
  },
  {
    id: "pyscript",
    label: "Python Script",
    icon: "doc",
    category: "Scripting",
  },
  { id: "ssh", label: "SSH Command", icon: "terminal", category: "System" },
  {
    id: "terraform",
    label: "Terraform",
    icon: "cube",
    category: "Infrastructure",
    premium: true,
  },
  {
    id: "kubernetes",
    label: "Kubernetes",
    icon: "k8s",
    category: "Infrastructure",
    premium: true,
  },
  {
    id: "awslambda",
    label: "AWS Lambda",
    icon: "cloud",
    category: "Serverless",
    premium: true,
  },
  {
    id: "azurefn",
    label: "Azure Function",
    icon: "azure",
    category: "Serverless",
    premium: true,
  },
  { id: "rest", label: "REST API", icon: "api", category: "Integration" },
  { id: "test", label: "Test Node", icon: "bolt", category: "Testing" },
];

const inputCls =
  "w-full rounded-lg border border-slate-200 bg-slate-50 px-4 py-2.5 text-sm text-slate-900 outline-none transition focus:border-slate-300 focus:ring-2 focus:ring-slate-300";

// What to type into each step — these run for real on the job runner.
const STEP_HINTS = {
  command: "Shell command, e.g.  df -h && systemctl status app",
  agent: "Command run on the job agent, e.g.  uptime",
  script: "Multi-line shell script…",
  powershell: "PowerShell (pwsh). Cross-platform modules — AWS.Tools, Az, PowerCLI, Microsoft.Graph — are available.",
  pyscript: "Python code…  print('hello')",
  ssh: "user@host command, e.g.  deploy@10.0.0.5 systemctl restart app",
  rest: "URL or METHOD URL, e.g.  POST https://api.example.com/deploy",
  terraform: "Terraform HCL (main.tf). Runs init + apply with your cloud integration's credentials.",
  kubernetes: "kubectl args, e.g.  get pods -A   — or  apply  with a manifest on the next lines",
  awslambda:
    "Function name or ARN on line 1, JSON payload on the lines after. Signed with your AWS integration's credentials.",
  azurefn:
    "Function URL on line 1 (optionally METHOD URL), JSON body after. Uses functionKey from your AZURE integration if set.",
};

export default function CreateJob() {
  const { pid, jid } = useParams();
  const navigate = useNavigate();
  const { workspace, pushToast } = useStore();
  const plan = workspace?.plan;
  const advanced = planAllows(plan, "advancedSteps");
  const b = `/app/projects/${pid}`;

  const [tab, setTab] = useState("Details");
  // The scripts this workspace owns — what a "Library Script" step can point
  // at. Catalog rows are deliberately absent: a reference resolves against
  // owned rows only, so offering one here would produce a step that fails at
  // run time with "not in this workspace". Import it first.
  const [scripts, setScripts] = useState([]);
  const [scriptsError, setScriptsError] = useState(null);
  const [name, setName] = useState("");
  const [group, setGroup] = useState("");
  const [description, setDescription] = useState("");
  const [requiresApproval, setRequiresApproval] = useState(false);
  const [steps, setSteps] = useState([]);
  const [options, setOptions] = useState([]);
  const [workflow, setWorkflow] = useState({ strategy: "node-first", keepgoing: false });
  const [nodes, setNodes] = useState({
    dispatch: false,
    filter: "",
    threadcount: 1,
    keepgoing: false,
    rankAttribute: "",
    rankOrder: "ascending",
  });
  const [schedule, setSchedule] = useState("");
  const [timezone, setTimezone] = useState("UTC");
  const [notifications, setNotifications] = useState([]);
  const [execution, setExecution] = useState({
    timeoutSeconds: null,
    retries: 0,
    retryDelaySeconds: 0,
    logLimit: null,
    logLimitAction: "halt",
    multipleExecutions: false,
    logLevel: "INFO",
  });
  const [logFilters, setLogFilters] = useState([]);
  const [channels, setChannels] = useState([]);
  const [channelsError, setChannelsError] = useState(null);
  const [busy, setBusy] = useState(false);
  const fileInputRef = React.useRef(null);
  const isEdit = !!jid;

  useEffect(() => {
    if (isEdit) {
      setBusy(true);
      api.get("jobs", jid).then((job) => {
        setName(job.name || "");
        setGroup(job.group || "");
        setDescription(job.description || "");
        setRequiresApproval(!!job.requiresApproval);
        setSteps(job.steps || []);
        setOptions(job.options || []);
        if (job.workflow) setWorkflow(job.workflow);
        if (job.nodes) setNodes(job.nodes);
        setSchedule(job.schedule || "");
        setTimezone(job.scheduleTimezone || "UTC");
        setNotifications(job.notifications || []);
        if (job.execution) setExecution(job.execution);
        setLogFilters(job.logFilters || []);
      }).catch(e => {
        pushToast(e.message || "Failed to load job", "red");
      }).finally(() => {
        setBusy(false);
      });
    }
  }, [jid, isEdit, pushToast]);

  useEffect(() => {
    // Scripts this workspace owns — authored here, or imported from the
    // catalog and adapted. Failure is quiet: the picker below shows an empty
    // state that points at the library, which is more useful than a toast
    // about a list the author may not even be about to use.
    api
      .listLibrary()
      .then((rows) =>
        setScripts(
          // Lower-cased before comparing: the API serialises the enum with
          // `name().toLowerCase()`, so this arrives as "script". Comparing
          // against "SCRIPT" matched nothing and every workspace looked empty
          // however many scripts it owned.
          (rows || []).filter(
            (r) => r.owned && String(r.type).toLowerCase() === "script",
          ),
        ),
      )
      .catch((e) => {
        // Not silent. The picker's empty state says "import one from the
        // library", which is a lie when the list simply failed to load — and
        // it sends the author off to fix a problem they do not have.
        setScripts([]);
        setScriptsError(e.message || "Could not load your scripts");
      });
  }, []);

  useEffect(() => {
    // The workspace's installed alert channels, for the Notifications tab.
    //
    // DISABLED channels are kept in the list rather than filtered out. A
    // customer who installed Slack and turned it off would otherwise open this
    // tab, see nothing, and conclude the integration had vanished — the tab
    // labels them instead, which is the honest version.
    api
      .listInstallations()
      .then((rows) =>
        setChannels(
          (rows || []).map((c) => ({
            id: c.id,
            name: c.displayName,
            kind: c.pluginKey,
            enabled: c.enabled,
          })),
        ),
      )
      .catch((e) => {
        // Distinguish "none installed" from "could not load" — the second is
        // not the customer's configuration problem and must not read like one.
        setChannels([]);
        setChannelsError(e.message || "Could not load alert channels");
      });
  }, []);

  const handleFileUpload = (e) => {
    const file = e.target.files[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = (event) => {
      try {
        const data = JSON.parse(event.target.result);
        if (data.name) setName(data.name);
        if (data.group) setGroup(data.group);
        if (data.description) setDescription(data.description);
        if (data.steps && Array.isArray(data.steps)) setSteps(data.steps);
        pushToast("Definition uploaded successfully", "emerald");
      } catch (err) {
        pushToast("Invalid JSON file", "red");
      }
    };
    reader.readAsText(file);
    e.target.value = null;
  };

  const addStep = (t) => {
    if (t.premium && !advanced) {
      pushToast(`${t.label} requires the Business plan`, "amber");
      return;
    }
    setSteps((s) => [...s, { ...t, key: t.id + "-" + (s.length + 1) }]);
  };
  const removeStep = (key) => setSteps((s) => s.filter((x) => x.key !== key));
  const updateStepValue = (key, value) => setSteps((s) => s.map((x) => (x.key === key ? { ...x, value } : x)));
  const updateStepConnection = (key, connection) =>
    setSteps((s) => s.map((x) => (x.key === key ? { ...x, connection } : x)));
  const updateStepField = (key, field, val) =>
    setSteps((s) => s.map((x) => (x.key === key ? { ...x, [field]: val } : x)));

  const save = async () => {
    if (!name.trim()) {
      pushToast("Job name is required", "red");
      setTab("Details");
      return;
    }
    setBusy(true);
    const payload = {
      projectId: pid,
      name: name.trim(),
      group: group.trim(),
      description: description.trim(),
      requiresApproval,
      steps,
      stepCount: steps.length,
      options,
      workflow,
      nodes,
      schedule,
      scheduleTimezone: timezone,
      notifications,
      execution,
      logFilters,
    };
    try {
      if (isEdit) {
        await api.update("jobs", jid, payload);
        pushToast("Job updated", "emerald");
      } else {
        await api.create("jobs", payload);
        pushToast("Job created", "emerald");
      }
      navigate(`${b}/jobs`);
    } catch (e) {
      pushToast(e.message || `Could not ${isEdit ? "update" : "create"} job`, "red");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="animate-fade-up">
      <Link
        to={`${b}/jobs`}
        className="text-sm text-slate-500 transition hover:text-slate-900"
      >
        ← Jobs
      </Link>
      <PageHeader
        title={isEdit ? "Edit Job" : "Create New Job"}
        subtitle="Define a reusable, parameterized automation job"
        actions={
          <div className="flex items-center gap-2">
            <input
              type="file"
              accept=".json"
              className="hidden"
              ref={fileInputRef}
              onChange={handleFileUpload}
            />
            <Link to={`${b}/jobs`}>
              <SmallButton>Cancel</SmallButton>
            </Link>
            <SmallButton icon="doc" onClick={() => fileInputRef.current?.click()}>
              Upload Definition
            </SmallButton>
            <SmallButton
              variant="primary"
              onClick={save}
              disabled={busy}
            >
              {busy ? "Saving..." : (isEdit ? "Save Changes" : "Create Job")}
            </SmallButton>
          </div>
        }
      />

      <div className="grid gap-6 lg:grid-cols-[220px_1fr]">
        <div className="flex gap-1 overflow-x-auto lg:flex-col">
          {TABS.map((t) => (
            <button
              key={t}
              onClick={() => setTab(t)}
              className={`whitespace-nowrap rounded-lg px-3 py-2 text-left text-sm font-medium transition ${tab === t ? "bg-slate-900 text-white" : "text-slate-500 hover:bg-slate-100 hover:text-slate-900"}`}
            >
              {t}
            </button>
          ))}
        </div>

        <div>
          {tab === "Details" && (
            <Card className="p-6">
              <h3 className="mb-4 text-sm font-semibold text-slate-900">
                Job details
              </h3>
              <label className="mb-1.5 block text-xs font-medium text-slate-500">
                Job name
              </label>
              <input
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="System Diagnostics Check"
                className={inputCls + " mb-4"}
              />
              <label className="mb-1.5 block text-xs font-medium text-slate-500">
                Group
              </label>
              <input
                value={group}
                onChange={(e) => setGroup(e.target.value)}
                placeholder="maintenance"
                className={inputCls + " mb-4"}
              />
              <label className="mb-1.5 block text-xs font-medium text-slate-500">
                Description
              </label>
              <textarea
                rows={3}
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder="What does this job do?"
                className={inputCls}
              />
              <label className="mt-4 flex cursor-pointer items-start gap-3 rounded-lg border border-slate-200 bg-slate-50 p-4">
                <input
                  type="checkbox"
                  checked={requiresApproval}
                  onChange={(e) => setRequiresApproval(e.target.checked)}
                  className="mt-0.5 h-4 w-4 accent-blue-600"
                />
                <span>
                  <span className="block text-sm font-medium text-slate-900">
                    Require admin approval to run
                  </span>
                  <span className="block text-xs text-slate-500">
                    When an operator runs this job it becomes a pending request
                    on the Approvals page; an admin must approve it before it
                    executes. Admin runs and cron schedules are not gated.
                  </span>
                </span>
              </label>
            </Card>
          )}

          {tab === "Options" && (
            <JobOptionsTab options={options} onChange={setOptions} />
          )}

          {tab === "Nodes" && <JobNodesTab nodes={nodes} onChange={setNodes} />}

          {tab === "Schedule" && (
            <JobScheduleTab
              schedule={schedule}
              timezone={timezone}
              onChange={({ schedule: next, timezone: tz }) => {
                setSchedule(next);
                setTimezone(tz || "UTC");
              }}
            />
          )}

          {tab === "Notifications" && (
            <JobNotificationsTab
              notifications={notifications}
              channels={channels}
              channelsError={channelsError}
              onChange={setNotifications}
            />
          )}

          {tab === "Execution" && (
            <JobExecutionTab
              execution={execution}
              logFilters={logFilters}
              onChange={setExecution}
              onFiltersChange={setLogFilters}
            />
          )}

          {tab === "Workflow" && (
            <div className="space-y-6">
              <Card className="p-6">
                <h3 className="mb-4 text-sm font-semibold text-slate-900">
                  If a step fails
                </h3>
                <div className="space-y-2">
                  <label className="flex cursor-pointer items-start gap-2 text-sm text-slate-700">
                    <input
                      type="radio"
                      name="keepgoing"
                      checked={!workflow.keepgoing}
                      onChange={() => setWorkflow({ ...workflow, keepgoing: false })}
                      className="mt-0.5 h-4 w-4 accent-blue-600"
                    />
                    <span>
                      Stop at the failed step
                      <span className="mt-0.5 block text-[11px] text-slate-500">
                        Nothing after it runs. The safe default for anything that
                        changes state in order.
                      </span>
                    </span>
                  </label>
                  <label className="flex cursor-pointer items-start gap-2 text-sm text-slate-700">
                    <input
                      type="radio"
                      name="keepgoing"
                      checked={!!workflow.keepgoing}
                      onChange={() => setWorkflow({ ...workflow, keepgoing: true })}
                      className="mt-0.5 h-4 w-4 accent-blue-600"
                    />
                    <span>
                      Run the remaining steps, then fail
                      <span className="mt-0.5 block text-[11px] text-slate-500">
                        Finishes the list and still reports failure — useful when
                        the steps are independent checks.
                      </span>
                    </span>
                  </label>
                </div>

                {nodes.dispatch && (
                  <div className="mt-5 border-t border-slate-100 pt-5">
                    <label className="mb-1.5 block text-xs font-medium text-slate-500">
                      Order across the fleet
                    </label>
                    <select
                      value={workflow.strategy || "node-first"}
                      onChange={(e) =>
                        setWorkflow({ ...workflow, strategy: e.target.value })
                      }
                      className="w-full max-w-sm rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-900 outline-none transition focus:border-slate-300 focus:ring-2 focus:ring-slate-300"
                    >
                      <option value="node-first">
                        Node first — finish a node, then move on
                      </option>
                      <option value="step-first">
                        Step first — run each step on every node before the next
                      </option>
                      <option value="parallel">Parallel — all at once</option>
                    </select>
                    <p className="mt-1.5 text-[11px] leading-relaxed text-slate-500">
                      {workflow.strategy === "step-first"
                        ? "Every node reaches the same point together — the right shape for a rolling change you may need to stop."
                        : workflow.strategy === "parallel"
                          ? "Fastest, and the hardest to interrupt cleanly."
                          : "One machine is fully done before the next is touched, so a failure leaves the rest untouched."}
                    </p>
                  </div>
                )}
              </Card>

              <Card className="p-6">
                <div className="mb-4 flex items-center justify-between">
                  <h3 className="text-sm font-semibold text-slate-900">
                    Steps
                  </h3>
                  <Chip>{steps.length} steps</Chip>
                </div>
                {steps.length === 0 ? (
                  <p className="rounded-lg border border-dashed border-slate-200 px-4 py-8 text-center text-sm text-slate-500">
                    No steps yet. Add a step from the palette below.
                  </p>
                ) : (
                  <ol className="space-y-3">
                    {steps.map((st, i) => (
                      <li
                        key={st.key}
                        className="flex flex-col gap-3 rounded-lg border border-slate-200 bg-slate-50 p-3"
                      >
                        <div className="flex items-center justify-between">
                          <span className="flex items-center gap-2.5 text-sm text-slate-700">
                            <span className="flex h-7 w-7 items-center justify-center rounded-md border border-slate-200 bg-white text-slate-900">
                              <Icon name={st.icon} size={14} />
                            </span>
                            <span className="font-mono text-xs text-slate-400">
                              {i + 1}.
                            </span>
                            {st.label}
                            <span className="text-[11px] text-slate-400">
                              {st.category}
                            </span>
                          </span>
                          <button
                            onClick={() => removeStep(st.key)}
                            className="text-slate-400 transition hover:text-red-600"
                            aria-label="Remove step"
                          >
                            <Icon name="trash" size={14} />
                          </button>
                        </div>
                        <div className="ml-9 mr-1 space-y-2">
                          {st.id === "library" ? (
                            // A reference, not a body. The script stays in the
                            // library and this step names it; core-service
                            // resolves it when the run is queued.
                            scriptsError ? (
                              <p className="rounded-md border border-dashed border-red-200 bg-red-50 px-3 py-2.5 text-[11px] leading-relaxed text-red-800">
                                Could not load your scripts — {scriptsError}.
                                This is not the same as having none; reload the
                                page rather than importing anything.
                              </p>
                            ) : scripts.length === 0 ? (
                              <p className="rounded-md border border-dashed border-amber-200 bg-amber-50 px-3 py-2.5 text-[11px] leading-relaxed text-amber-800">
                                This workspace has no scripts yet. Import one
                                from the{" "}
                                <Link
                                  to="/app/library"
                                  className="font-medium underline"
                                >
                                  library
                                </Link>{" "}
                                — or write your own — and it will appear here.
                              </p>
                            ) : (
                              <>
                                <select
                                  aria-label="Library script"
                                  value={st.libraryItemId || ""}
                                  onChange={(e) => {
                                    const id = e.target.value;
                                    const picked = scripts.find(
                                      (x) => String(x.id) === id,
                                    );
                                    updateStepField(
                                      st.key,
                                      "libraryItemId",
                                      id ? Number(id) : undefined,
                                    );
                                    // Name the step after the script unless the
                                    // author has already renamed it themselves.
                                    if (picked && !st.labelEdited) {
                                      updateStepField(
                                        st.key,
                                        "label",
                                        picked.title,
                                      );
                                    }
                                  }}
                                  className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm text-slate-800 outline-none focus:border-slate-300 focus:ring-1 focus:ring-slate-300"
                                >
                                  <option value="">Choose a script…</option>
                                  {scripts.map((sc) => (
                                    <option key={sc.id} value={sc.id}>
                                      {sc.category} — {sc.title}
                                    </option>
                                  ))}
                                </select>
                                {st.libraryItemId ? (
                                  // Library scripts are param() scripts — 119 of
                                  // the catalog's 213 declare a mandatory
                                  // parameter, and pwsh runs -NonInteractive, so
                                  // a missing one fails the step rather than
                                  // prompting.
                                  <>
                                    <input
                                      type="text"
                                      aria-label="Script arguments"
                                      placeholder="Arguments, e.g.  -Endpoint example.com -WarnDays 30"
                                      value={st.args || ""}
                                      onChange={(e) =>
                                        updateStepField(st.key, "args", e.target.value)
                                      }
                                      className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm font-mono text-slate-800 outline-none focus:border-slate-300 focus:ring-1 focus:ring-slate-300"
                                    />
                                    <p className="text-[11px] leading-relaxed text-slate-500">
                                      Passed to the script as parameters. Quote a
                                      value containing spaces. Job inputs written
                                      as <code>{"{{Name}}"}</code> are substituted
                                      before the run starts.
                                    </p>
                                  </>
                                ) : (
                                  <p className="text-[11px] text-slate-500">
                                    A step with no script chosen stops the run
                                    before anything executes, rather than being
                                    skipped.
                                  </p>
                                )}
                              </>
                            )
                          ) : ["script", "powershell", "pyscript", "terraform", "kubernetes", "awslambda", "azurefn"].includes(st.id) ? (
                            <textarea
                              placeholder={STEP_HINTS[st.id] || `Enter ${st.label.toLowerCase()}...`}
                              value={st.value || ""}
                              onChange={(e) => updateStepValue(st.key, e.target.value)}
                              rows={["terraform", "kubernetes", "powershell"].includes(st.id) ? 6 : 3}
                              className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm font-mono text-slate-800 outline-none focus:border-slate-300 focus:ring-1 focus:ring-slate-300"
                            />
                          ) : (
                            <input
                              type="text"
                              placeholder={STEP_HINTS[st.id] || `Enter ${st.label.toLowerCase()} config...`}
                              value={st.value || ""}
                              onChange={(e) => updateStepValue(st.key, e.target.value)}
                              className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm font-mono text-slate-800 outline-none focus:border-slate-300 focus:ring-1 focus:ring-slate-300"
                            />
                          )}
                          {["terraform", "kubernetes", "awslambda", "azurefn"].includes(st.id) && (
                            // Binds the step to a named Cloud Integration; left
                            // blank, the tenant's single matching one is used.
                            <input
                              type="text"
                              placeholder="Cloud integration name (optional — uses your only matching integration)"
                              value={st.connection || ""}
                              onChange={(e) => updateStepConnection(st.key, e.target.value)}
                              className="w-full rounded-md border border-slate-200 px-3 py-2 text-xs text-slate-700 outline-none focus:border-slate-300 focus:ring-1 focus:ring-slate-300"
                            />
                          )}
                          {/* Reliability policy — the same knobs Rundeck exposes. */}
                          <div className="flex flex-wrap items-center gap-4 pt-1 text-xs text-slate-600">
                            <label className="flex items-center gap-1.5">
                              Retries
                              <select
                                value={st.retries ?? 0}
                                onChange={(e) =>
                                  updateStepField(st.key, "retries", Number(e.target.value))
                                }
                                className="rounded-md border border-slate-200 bg-white px-2 py-1 text-xs text-slate-800 outline-none focus:border-slate-300"
                              >
                                {[0, 1, 2, 3, 4, 5].map((n) => (
                                  <option key={n} value={n}>
                                    {n}
                                  </option>
                                ))}
                              </select>
                            </label>
                            <label className="flex items-center gap-1.5">
                              <input
                                type="checkbox"
                                checked={!!st.continueOnError}
                                onChange={(e) =>
                                  updateStepField(st.key, "continueOnError", e.target.checked)
                                }
                                className="h-3.5 w-3.5 rounded border-slate-300"
                              />
                              Continue on error
                            </label>
                          </div>
                        </div>
                      </li>
                    ))}
                  </ol>
                )}
              </Card>

              <Card className="p-6">
                <h3 className="mb-4 text-sm font-semibold text-slate-900">
                  Add a Step
                </h3>
                <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
                  {STEP_TYPES.map((t) => {
                    const locked = t.premium && !advanced;
                    return (
                      <button
                        key={t.id}
                        onClick={() => addStep(t)}
                        className="group flex items-start gap-3 rounded-xl border border-slate-200 bg-white p-3.5 text-left transition hover:-translate-y-0.5 hover:border-blue-500 hover:bg-slate-50"
                      >
                        <span className="flex h-9 w-9 items-center justify-center rounded-lg bg-slate-100 text-slate-900">
                          <Icon name={t.icon} size={18} />
                        </span>
                        <span className="min-w-0">
                          <span className="flex items-center gap-1.5 text-sm font-medium text-slate-900">
                            {t.label}
                            {locked && (
                              <Icon
                                name="lock"
                                size={11}
                                className="text-slate-400"
                              />
                            )}
                          </span>
                          <span className="block text-[11px] text-slate-500">
                            {t.category}
                          </span>
                        </span>
                      </button>
                    );
                  })}
                </div>
              </Card>
            </div>
          )}


        </div>
      </div>
    </div>
  );
}
