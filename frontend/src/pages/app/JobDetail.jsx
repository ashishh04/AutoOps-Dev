import React, { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import {
  PageHeader,
  Card,
  StatusBadge,
  SmallButton,
  Skeleton,
  PanelPager,
} from "../../components/app/appui";
import { api } from "../../lib/api";
import { useStore } from "../../store/store";
import { fmtDate, fmtDuration, badgeStatus } from "../../lib/format";
import { textPages, clampPage } from "../../lib/textPages";

const LOG_TABS = ["Output", "Grouped", "Raw"];

/** Executions per page in the history column. */
const RUNS_PER_PAGE = 8;

/** Step groups per page in the Grouped view. */
const GROUPS_PER_PAGE = 12;

// Engine log format: step status lines ("[1/3] label — ok (812ms)") with the
// step's captured output indented under them as "    | …" lines.
function parseLog(text) {
  const groups = [];
  let cur = null;
  for (const line of String(text || "").split("\n")) {
    if (line.startsWith("    | ") && cur) {
      cur.lines.push(line.slice(6));
    } else if (line.trim() !== "") {
      cur = { header: line, lines: [] };
      groups.push(cur);
    }
  }
  return groups;
}

const headerTone = (h) =>
  h.includes("FAILED")
    ? "text-red-600"
    : /—\s*ok\s*\(/.test(h)
      ? "text-emerald-600"
      : "text-slate-500";

export default function JobDetail() {
  const { pid, id } = useParams();
  const navigate = useNavigate();
  const { can, pushToast } = useStore();
  const [job, setJob] = useState(null);
  const [runs, setRuns] = useState([]);
  const [loading, setLoading] = useState(true);
  const [notFound, setNotFound] = useState(false);
  const [selected, setSelected] = useState(null);
  const [detail, setDetail] = useState(null);
  const [tab, setTab] = useState("Output");
  const [runPage, setRunPage] = useState(1);
  const [logPage, setLogPage] = useState(1);
  const canRun = can("runWorkflow");
  const b = `/app/projects/${pid}`;

  const load = async () => {
    setLoading(true);
    setNotFound(false);
    try {
      const j = await api.get("jobs", id);
      if (!j) {
        setNotFound(true);
        return;
      }
      setJob(j);
      const all = await api.list("executions", pid);
      const mine = (all || []).filter((e) => e.jobId === id);
      setRuns(mine);
      setSelected(mine[0] || null);
      // The newest run is selected, and it lives on the first page.
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

  // The runs LIST endpoint omits the log (summaries stay light) — the full
  // log only comes from the run detail endpoint. Fetch it whenever a run is
  // selected, and keep polling while that run is still queued/running.
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
        // Keep the history list's status in sync without resetting selection.
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

  // A different run, or a different view of it, starts at its own first page.
  useEffect(() => {
    setLogPage(1);
  }, [selectedId, tab]);

  const run = async () => {
    try {
      const res = await api.runJob(id);
      if (res?.approvalRequired) {
        pushToast("Approval requested — an admin must approve this run", "amber");
      } else {
        pushToast("Job run started", "cyan");
      }
      load();
    } catch (e) {
      pushToast(e.message || "Could not run job", "red");
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

  if (notFound || !job)
    return (
      <div className="animate-fade-up">
        <PageHeader
          title="Job not found"
          subtitle="This job isn’t available."
        />
        <Card className="p-10 text-center text-sm text-slate-500">
          Nothing here.{" "}
          <Link to={`${b}/jobs`} className="text-slate-900 hover:underline">
            Back to jobs
          </Link>
        </Card>
      </div>
    );

  const stats = [
    { k: "Status", v: <StatusBadge status={badgeStatus(job.status)} /> },
    { k: "Avg duration", v: fmtDuration(job.avgDurationMs) },
    // Null means never run. Showing 0% would claim it fails every time,
    // which is the same lie as showing 100% — just in the other direction.
    { k: "Success rate", v: job.successRate == null ? "—" : job.successRate + "%" },
    { k: "Total runs", v: job.runsTotal ?? runs.length },
    { k: "Last run", v: fmtDate(job.lastRunAt) },
  ];

  // A detail belongs to the selected run only if its id says so. `detail ||
  // selected` kept the PREVIOUS run's log on screen from the click until the
  // new run's detail came back — and after pressing Run Job, the old run's
  // output sat under the new execution's id. The log pane must never show a
  // run other than the one highlighted in the history.
  const settled = detail && selected && detail.id === selected.id ? detail : null;
  const current = settled || selected;
  const awaitingDetail = !!selected && !settled;
  const running =
    current && ["queued", "running"].includes(badgeStatus(current.status));
  const rawLog = awaitingDetail
    ? "Loading this run…"
    : current
      ? current.log ||
        current.error ||
        (running
          ? "Run in progress — output appears as steps complete…"
          : "No log output captured for this run.")
      : "Select a run from the execution history to view its logs.";
  const groups = parseLog(settled?.log);
  const outputOnly = groups.flatMap((g) => g.lines).join("\n");

  const runPages = Math.max(1, Math.ceil(runs.length / RUNS_PER_PAGE));
  const historyPage = clampPage(runPage, runPages);
  const visibleRuns = runs.slice(
    (historyPage - 1) * RUNS_PER_PAGE,
    historyPage * RUNS_PER_PAGE,
  );

  // Whichever view is up, the pane shows one page of it. A job that loops over
  // 400 nodes produces a log nobody can navigate by scroll bar alone.
  const showRaw = tab === "Raw" || !settled?.log;
  const logText = showRaw
    ? rawLog
    : outputOnly ||
      "No command output captured — steps ran but printed nothing. See Grouped or Raw for step statuses.";
  const logSlices = tab === "Grouped" && !showRaw ? [] : textPages(logText);
  const groupPages =
    tab === "Grouped" && !showRaw
      ? Math.max(1, Math.ceil(groups.length / GROUPS_PER_PAGE))
      : logSlices.length;
  const shownPage = clampPage(logPage, groupPages);
  const visibleGroups = groups.slice(
    (shownPage - 1) * GROUPS_PER_PAGE,
    shownPage * GROUPS_PER_PAGE,
  );

  return (
    <div className="animate-fade-up">
      <Link
        to={`${b}/jobs`}
        className="text-sm text-slate-500 transition hover:text-slate-900"
      >
        ← Jobs
      </Link>
      <PageHeader
        title={job.name}
        subtitle={`${job.group || "Ungrouped"} · ${job.description || ""}`}
        actions={
          <div className="flex items-center gap-2">
            {canRun && (
              <SmallButton
                icon="gear"
                onClick={() => navigate(`${b}/jobs/${id}/edit`)}
              >
                Edit
              </SmallButton>
            )}
            <SmallButton
              icon="trail"
              onClick={() => navigate(`${b}/executions?jobId=${id}`)}
            >
              History
            </SmallButton>
            {canRun && (
              <SmallButton icon="play" variant="primary" onClick={run}>
                Run Job
              </SmallButton>
            )}
          </div>
        }
      />

      <div className="mb-6 flex flex-wrap gap-3">
        {stats.map((m) => (
          <Card key={m.k} className="px-4 py-3">
            <p className="text-[11px] text-slate-500">{m.k}</p>
            <div className="mt-1 text-sm font-medium text-slate-900">{m.v}</div>
          </Card>
        ))}
      </div>

      <div className="grid gap-6 lg:grid-cols-3">
        <Card className="overflow-hidden lg:col-span-1">
          <div className="border-b border-slate-200 px-5 py-3 text-sm font-semibold text-slate-900">
            Execution History
          </div>
          {runs.length === 0 ? (
            <p className="px-5 py-6 text-sm text-slate-500">No runs yet.</p>
          ) : (
            <>
              <div className="divide-y divide-slate-200">
                {visibleRuns.map((r) => {
                  const active = selected && selected.id === r.id;
                  return (
                    <button
                      key={r.id}
                      onClick={() => setSelected(r)}
                      className={`flex w-full items-center justify-between px-5 py-3 text-left transition hover:bg-slate-100 ${active ? "bg-slate-50" : ""}`}
                    >
                      <div>
                        <p className="font-mono text-xs text-slate-700">
                          {String(r.id)}
                        </p>
                        <p className="text-[11px] text-slate-500">
                          {fmtDate(r.startedAt || r.createdAt)} ·{" "}
                          {r.trigger || "manual"}
                        </p>
                      </div>
                      <StatusBadge status={badgeStatus(r.status)} />
                    </button>
                  );
                })}
              </div>
              <PanelPager
                page={historyPage}
                totalPages={runPages}
                onPageChange={setRunPage}
                label={`${runs.length} execution${runs.length === 1 ? "" : "s"} · page ${historyPage} of ${runPages}`}
              />
            </>
          )}
        </Card>

        <Card className="overflow-hidden lg:col-span-2">
          <div className="flex items-center justify-between border-b border-slate-200 px-5 py-2.5">
            <span className="text-sm font-semibold text-slate-900">
              Execution Logs
            </span>
            <div className="flex gap-1 rounded-lg bg-slate-100 p-0.5">
              {LOG_TABS.map((t) => (
                <button
                  key={t}
                  onClick={() => setTab(t)}
                  className={`rounded-md px-3 py-1 text-xs font-medium transition ${tab === t ? "bg-white text-slate-900 shadow-sm" : "text-slate-500 hover:text-slate-900"}`}
                >
                  {t}
                </button>
              ))}
            </div>
          </div>
          <div className="max-h-[420px] overflow-auto bg-slate-900/[0.03] p-4">
            {showRaw || tab === "Output" ? (
              <pre className="whitespace-pre-wrap font-mono text-xs leading-relaxed text-slate-700">
                {logSlices[shownPage - 1] ?? logText}
              </pre>
            ) : (
              <div className="space-y-2">
                {visibleGroups.map((g, i) => (
                  <div
                    key={i}
                    className="rounded-lg border border-slate-200 bg-white/60"
                  >
                    <p
                      className={`px-3 py-1.5 font-mono text-xs font-semibold ${headerTone(g.header)}`}
                    >
                      {g.header}
                    </p>
                    {g.lines.length > 0 && (
                      <pre className="whitespace-pre-wrap border-t border-slate-100 px-3 py-2 font-mono text-xs leading-relaxed text-slate-700">
                        {g.lines.join("\n")}
                      </pre>
                    )}
                  </div>
                ))}
              </div>
            )}
            {running && settled?.log && (
              <p className="mt-3 flex items-center gap-1.5 font-mono text-[11px] text-slate-500">
                <span className="h-1.5 w-1.5 animate-pulse-dot rounded-full bg-cyan-500" />
                running — refreshing every 3s
              </p>
            )}
          </div>
          <PanelPager
            page={shownPage}
            totalPages={groupPages}
            onPageChange={setLogPage}
            label={
              tab === "Grouped" && !showRaw
                ? `${groups.length} step${groups.length === 1 ? "" : "s"} · page ${shownPage} of ${groupPages}`
                : `Page ${shownPage} of ${groupPages}`
            }
          />
        </Card>
      </div>
    </div>
  );
}
