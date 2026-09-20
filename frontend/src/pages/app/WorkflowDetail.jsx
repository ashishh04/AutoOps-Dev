import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import {
  PageHeader,
  Card,
  StatusBadge,
  SmallButton,
  Skeleton,
} from "../../components/app/appui";
import Icon from "../../components/Icon";
import RunInputsDialog from "../../components/app/RunInputsDialog";
import { api } from "../../lib/api";
import { useStore } from "../../store/store";
import { fmtDate, fmtDuration, badgeStatus } from "../../lib/format";

/**
 * One workflow, and what it has actually done.
 *
 * <p>Modelled on JobDetail deliberately. A workflow run and a job run are the
 * same row in the same table, and to an operator asking "did the 2am automation
 * work" they are the same question — so the answer should not live somewhere
 * else, or look different, depending on which kind it was. Before this the
 * Workflows page was a list you could only press Run on: a workflow's results
 * were reachable only through the project-wide Executions page, filtered by
 * hand.
 *
 * <p>The run history is scoped SERVER-side by {targetType, targetId}. The runs
 * endpoint returns the newest 200 for a project, so narrowing in the browser
 * would silently drop a quiet workflow's history as soon as a busier one filled
 * the cap.
 */
export default function WorkflowDetail() {
  const { pid, id } = useParams();
  const navigate = useNavigate();
  const { can, pushToast } = useStore();
  const [workflow, setWorkflow] = useState(null);
  const [runs, setRuns] = useState([]);
  const [loading, setLoading] = useState(true);
  const [notFound, setNotFound] = useState(false);
  const [selected, setSelected] = useState(null);
  const [detail, setDetail] = useState(null);
  const [prompt, setPrompt] = useState(null);
  const [starting, setStarting] = useState(false);
  // Which pane of the result card is showing. Declared with the other
  // hooks: below the loading/notFound early returns it would be called
  // conditionally, which breaks the rules of hooks.
  const [pane, setPane] = useState("result");
  const canRun = can("runWorkflow");
  const b = `/app/projects/${pid}`;

  const load = async () => {
    setLoading(true);
    setNotFound(false);
    try {
      const w = await api.get("workflows", id);
      if (!w) {
        setNotFound(true);
        return;
      }
      setWorkflow(w);
      const mine = await api.list("executions", pid, {
        targetType: "WORKFLOW",
        targetId: id,
      });
      setRuns(mine || []);
      setSelected((mine || [])[0] || null);
    } catch {
      setNotFound(true);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id, pid]);

  // The runs LIST omits the log (summaries stay light); the full log only comes
  // from the run detail endpoint. Poll it while the run is still going, so a
  // workflow that takes two minutes shows its nodes arriving rather than a
  // motionless spinner.
  const selectedId = selected?.id;
  useEffect(() => {
    if (!selectedId) {
      setDetail(null);
      return undefined;
    }
    let alive = true;
    let timer;
    const fetchDetail = async () => {
      try {
        const d = await api.get("executions", selectedId);
        if (!alive) return;
        setDetail(d);
        setRuns((rs) =>
          rs.map((r) =>
            r.id === d.id
              ? { ...r, status: d.status, durationMs: d.durationMs, finishedAt: d.finishedAt }
              : r,
          ),
        );
        if (["queued", "running"].includes(badgeStatus(d.status))) {
          timer = setTimeout(fetchDetail, 3000);
        }
      } catch {
        /* keep whatever we already show */
      }
    };
    setDetail(null);
    fetchDetail();
    return () => {
      alive = false;
      clearTimeout(timer);
    };
  }, [selectedId]);

  const runWorkflow = async (inputs) => {
    setStarting(true);
    try {
      const res = await api.runWorkflow(id, inputs);
      setPrompt(null);
      if (res?.approvalRequired) {
        pushToast("Approval requested — an admin must approve this run", "amber");
      } else {
        pushToast("Workflow run started", "cyan");
      }
      load();
    } catch (e) {
      pushToast(e.message || "Could not run workflow", "red");
    } finally {
      setStarting(false);
    }
  };

  const startRun = async () => {
    setStarting(true);
    try {
      // Fail-open, as on the list page: readiness improves the message, it is
      // not a gate. An unreachable check must never stop a run.
      let state = null;
      try {
        state = await api.workflowReadiness(id);
      } catch (readinessError) {
        console.warn("Readiness check unavailable; running anyway", readinessError);
      }
      if (state && state.ready === false) {
        const first = (state.blockers || [])[0];
        pushToast(first ? `${first.title} — ${first.detail}` : "This workflow is not ready", "amber");
        return;
      }
      const fields = await api.workflowInputs(id);
      if (Array.isArray(fields) && fields.length > 0) {
        setPrompt(fields);
      } else {
        await runWorkflow();
      }
    } catch (e) {
      pushToast(e.message || "Could not read this workflow's inputs", "red");
    } finally {
      setStarting(false);
    }
  };

  if (loading)
    return (
      <div className="animate-fade-up">
        <Skeleton className="h-4 w-24" />
        <Skeleton className="mt-4 h-8 w-72" />
        <Card className="mt-6 h-40 p-6">
          <Skeleton className="h-5 w-40" />
        </Card>
      </div>
    );

  if (notFound || !workflow)
    return (
      <div className="animate-fade-up">
        <PageHeader title="Workflow not found" subtitle="This workflow isn’t available." />
        <Card className="p-10 text-center text-sm text-slate-500">
          Nothing here.{" "}
          <Link to={`${b}/workflows`} className="text-slate-900 hover:underline">
            Back to workflows
          </Link>
        </Card>
      </div>
    );

  const current = detail || selected;
  const running = current && ["queued", "running"].includes(badgeStatus(current.status));

  // The DELIVERABLE first — the report the person asked for. The engine's
  // trace is a second tab, not a preamble: a customer wanting a meeting
  // summary should not have to scroll past node timings to reach it.
  //
  // Falls back to the log for runs that predate the split, which have their
  // report inside it and nothing in `output`. Those render exactly as they
  // always did rather than looking empty.
  const deliverable = current?.output;
  const trace = current?.log;
  const hasDeliverable = !!(deliverable && deliverable.trim());
  const shown = pane === "result"
    ? (hasDeliverable
        ? deliverable
        : current?.error
          || (running ? "Running…" : trace || "This run produced no document."))
    : (trace || "No trace recorded.");

  const stats = [
    { k: "Status", v: <StatusBadge status={workflow.active ? "success" : "paused"} /> },
    { k: "Steps", v: workflow.nodeCount ?? "—" },
    // Null means never run. Showing 0% would claim it fails every time, which
    // is the same lie as 100% — just in the other direction.
    {
      k: "Success rate",
      v: workflow.successRate == null ? "—" : workflow.successRate + "%",
    },
    { k: "Total runs", v: workflow.runsTotal ?? runs.length },
    { k: "Last run", v: fmtDate(workflow.lastRunAt) },
  ];

  return (
    <div className="animate-fade-up">
      <PageHeader
        title={workflow.name}
        subtitle={workflow.description || "Automation workflow"}
        actions={
          <>
            <SmallButton icon="chevron" onClick={() => navigate(`${b}/workflows`)}>
              All workflows
            </SmallButton>
            {canRun && (
              <SmallButton icon="play" variant="primary" onClick={startRun} disabled={starting}>
                {starting ? "Starting…" : "Run"}
              </SmallButton>
            )}
          </>
        }
      />

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
        {stats.map((s) => (
          <Card key={s.k} className="p-4">
            <p className="text-[11px] uppercase tracking-wide text-slate-500">{s.k}</p>
            <div className="mt-1 text-sm font-medium text-slate-900">{s.v}</div>
          </Card>
        ))}
      </div>

      <div className="mt-6 grid gap-6 lg:grid-cols-3">
        <Card className="p-0 lg:col-span-1">
          <h3 className="border-b border-slate-100 px-5 py-4 text-sm font-semibold text-slate-900">
            Run history
          </h3>
          {runs.length === 0 ? (
            <p className="px-5 py-6 text-sm text-slate-500">
              No runs yet. Press Run to start one.
            </p>
          ) : (
            <ul className="max-h-[28rem] divide-y divide-slate-100 overflow-y-auto">
              {runs.map((r) => (
                <li key={r.id}>
                  <button
                    onClick={() => setSelected(r)}
                    className={`flex w-full items-center justify-between gap-3 px-5 py-3 text-left transition hover:bg-slate-50 ${
                      current?.id === r.id ? "bg-slate-50" : ""
                    }`}
                  >
                    <span className="min-w-0">
                      <span className="flex items-center gap-2">
                        <StatusBadge status={badgeStatus(r.status)} />
                        <span className="font-mono text-[11px] text-slate-400">#{r.id}</span>
                      </span>
                      <span className="mt-1 block truncate text-[11px] text-slate-500">
                        {fmtDate(r.startedAt || r.createdAt)} · {r.by || "—"}
                      </span>
                    </span>
                    <span className="shrink-0 font-mono text-[11px] text-slate-400">
                      {fmtDuration(r.durationMs)}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </Card>

        <Card className="p-0 lg:col-span-2">
          <div className="flex items-center justify-between border-b border-slate-100 px-5 py-4">
            <div className="flex items-center gap-1">
              {["result", "trace"].map((k) => (
                <button
                  key={k}
                  onClick={() => setPane(k)}
                  className={`rounded-lg px-3 py-1.5 text-xs font-medium transition ${
                    pane === k
                      ? "bg-slate-900 text-white"
                      : "text-slate-500 hover:bg-slate-100 hover:text-slate-900"
                  }`}
                >
                  {k === "result" ? "Result" : "Run trace"}
                </button>
              ))}
            </div>
            {current && (
              <span className="flex items-center gap-2 text-[11px] text-slate-500">
                {running && <Icon name="refresh" size={13} className="animate-spin" />}
                <span className="font-mono">#{current.id}</span>
                {current.stepCompleted != null && current.stepTotal != null
                  ? `${current.stepCompleted}/${current.stepTotal}`
                  : null}
              </span>
            )}
          </div>
          {!current ? (
            <p className="px-5 py-10 text-center text-sm text-slate-500">
              Select a run to see what it produced.
            </p>
          ) : (
            <pre
              className={`max-h-[28rem] overflow-auto whitespace-pre-wrap break-words px-5 py-4 leading-relaxed ${
                pane === "result" && hasDeliverable
                  ? "text-[13px] text-slate-800"
                  : "font-mono text-[12px] text-slate-700"
              }`}
            >
              {shown}
            </pre>
          )}
        </Card>
      </div>

      {prompt && (
        <RunInputsDialog
          title={workflow.name}
          fields={prompt}
          busy={starting}
          onCancel={() => setPrompt(null)}
          onRun={(inputs) => runWorkflow(inputs)}
        />
      )}
    </div>
  );
}
