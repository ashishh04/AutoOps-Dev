import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import {
  PageHeader,
  Card,
  StatusBadge,
  SmallButton,
  Skeleton,
  PanelPager,
} from "../../components/app/appui";
import Icon from "../../components/Icon";
import RunInputsDialog from "../../components/app/RunInputsDialog";
import ReportText from "../../components/app/ReportText";
import { api } from "../../lib/api";
import { useStore } from "../../store/store";
import { fmtDate, fmtDuration, badgeStatus } from "../../lib/format";
import { textPages, clampPage } from "../../lib/textPages";

/** Runs per page in the history column. Six fills the card without scrolling. */
const RUNS_PER_PAGE = 6;

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
  const [runPage, setRunPage] = useState(1);
  const [outputPage, setOutputPage] = useState(1);
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
      // A reload puts the newest run at the top and selects it, so the reader
      // belongs on the first page with it.
      setRunPage(1);
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

  // A different run, or a different pane, starts at its own first page.
  useEffect(() => {
    setOutputPage(1);
  }, [selectedId, pane]);

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

  // The detail is only this run's detail if its id says so. `detail || selected`
  // was fine on paper — the effect nulls it on every change of selection — but
  // that null lands AFTER the render that the click caused, so for a frame and
  // then for the whole width of the fetch, the newly selected run was shown
  // with the PREVIOUS run's report and the previous run's id under it. Picking
  // a failed run and reading the successful one's output is not a flicker; it
  // is the wrong answer to the question the reader just asked.
  const settled = detail && selected && detail.id === selected.id ? detail : null;
  const current = settled || selected;
  const awaitingDetail = !!selected && !settled;
  const running = current && ["queued", "running"].includes(badgeStatus(current.status));

  // The DELIVERABLE first — the report the person asked for. The engine's
  // trace is a second tab, not a preamble: a customer wanting a meeting
  // summary should not have to scroll past node timings to reach it.
  //
  // Falls back to the log for runs that predate the split, which have their
  // report inside it and nothing in `output`. Those render exactly as they
  // always did rather than looking empty.
  const deliverable = settled?.output;
  const trace = settled?.log;
  const hasDeliverable = !!(deliverable && deliverable.trim());
  const shown = awaitingDetail
    ? "Loading this run…"
    : pane === "result"
      ? (hasDeliverable
          ? deliverable
          : current?.error
            || (running ? "Running…" : trace || "This run produced no document."))
      : (trace || "No trace recorded.");

  // The report is prose and pages as prose; the trace is machine output and
  // pages the same way, so "2 of 4" means the same thing in either pane.
  const pages = textPages(shown);
  const shownPage = clampPage(outputPage, pages.length);
  const pageText = pages[shownPage - 1] ?? shown;
  // Only the finished, structured report is rendered as a document. An error,
  // a trace or a "Running…" placeholder stays monospaced — it is output, not
  // writing, and dressing it up would imply a report that does not exist yet.
  const asReport = pane === "result" && hasDeliverable && !awaitingDetail;

  const runPages = Math.max(1, Math.ceil(runs.length / RUNS_PER_PAGE));
  const historyPage = clampPage(runPage, runPages);
  const visibleRuns = runs.slice(
    (historyPage - 1) * RUNS_PER_PAGE,
    historyPage * RUNS_PER_PAGE,
  );

  const stats = [
    // "success" is not a state a workflow can be in — it is what a RUN is.
    // Borrowing it for "enabled" put a green Success badge on a workflow whose
    // every run had failed, directly above a 0% success rate, and the card
    // people read first was the one telling them the opposite of the truth.
    // The word matches the Pause/Resume control that changes it.
    { k: "Status", v: <StatusBadge status={workflow.active ? "active" : "paused"} /> },
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
            <>
              <ul className="divide-y divide-slate-100">
                {visibleRuns.map((r) => (
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
              <PanelPager
                page={historyPage}
                totalPages={runPages}
                onPageChange={setRunPage}
                label={`${runs.length} run${runs.length === 1 ? "" : "s"} · page ${historyPage} of ${runPages}`}
              />
            </>
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
            <>
              <div className="max-h-[28rem] overflow-auto px-5 py-4">
                {asReport ? (
                  <ReportText source={pageText} />
                ) : (
                  <pre className="whitespace-pre-wrap break-words font-mono text-[12px] leading-relaxed text-slate-700">
                    {pageText}
                  </pre>
                )}
              </div>
              <PanelPager
                page={shownPage}
                totalPages={pages.length}
                onPageChange={setOutputPage}
                label={`Page ${shownPage} of ${pages.length}`}
              />
            </>
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
