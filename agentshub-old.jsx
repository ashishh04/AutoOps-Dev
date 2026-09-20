import React, { useEffect, useMemo, useState } from "react";
import { useParams } from "react-router-dom";
import {
  PageHeader,
  Card,
  Toolbar,
  Chip,
  StatusBadge,
  SmallButton,
} from "../../components/app/appui";
import Icon from "../../components/Icon";
import AgentRunPanel from "../../components/app/AgentRunPanel";
import ModelPicker from "../../components/app/ModelPicker";
import { useStore } from "../../store/store";
import { api } from "../../lib/api";
import { fmtDate } from "../../lib/format";

/**
 * The agents rolled out to this project.
 *
 * Read-and-run by design: agents are BUILT by the provider (their persona is
 * the product) and delivered here. The customer sees what each agent is and —
 * crucially — the full tool allow-list bounding what it may touch, and holds
 * the kill switch. It cannot rewrite the persona, and the server would refuse
 * if it tried.
 */
export default function AgentsHub() {
  const { pid } = useParams();
  const { can, pushToast } = useStore();
  const [agents, setAgents] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [query, setQuery] = useState("");
  // Which agent's run panel is open. Held here rather than on the card so
  // only one panel can exist at a time.
  const [running, setRunning] = useState(null);
  const [configuring, setConfiguring] = useState(null);
  const canRun = can("runWorkflow");

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      // Tool targets used to be fetched here to populate the editor's picker.
      // There is no editor now, and the resolved tool names already ride along
      // on each agent, so this is one request instead of three.
      const agentRows = await api.list("agents", pid);
      setAgents(Array.isArray(agentRows) ? agentRows : []);
    } catch (e) {
      setError(e.message || "Could not load agents");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pid]);

  const visible = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return agents;
    return agents.filter(
      (a) =>
        a.name.toLowerCase().includes(q) ||
        a.description.toLowerCase().includes(q) ||
        a.tools.some((t) => t.name.toLowerCase().includes(q)),
    );
  }, [agents, query]);



  const toggleEnabled = async (agent) => {
    try {
      const updated = await api.setAgentEnabled(agent.id, !agent.enabled);
      setAgents((rows) => rows.map((a) => (a.id === agent.id ? updated : a)));
      pushToast(
        updated.enabled ? `${updated.name} is live` : `${updated.name} is paused`,
        updated.enabled ? "emerald" : "amber",
      );
    } catch (e) {
      pushToast(e.message || "Could not change the agent state", "red");
    }
  };


  return (
    <div className="animate-fade-up">
      <PageHeader
        title="AI Agents"
        subtitle="Operators your provider has rolled out — each bounded by the automations it was granted"
      />

      <Toolbar
        placeholder="Search agents…"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        right={
          <Chip>
            {agents.length} agent{agents.length === 1 ? "" : "s"}
          </Chip>
        }
      />

      {loading ? (
        <div className="grid gap-5 sm:grid-cols-2">
          {[0, 1].map((i) => (
            <Card key={i} className="h-48 animate-pulse p-6" />
          ))}
        </div>
      ) : error ? (
        <Card className="p-10 text-center">
          <div className="flex flex-col items-center gap-2 text-sm text-red-600">
            <Icon name="shield" size={20} />
            <span>{error}</span>
            <button
              onClick={load}
              className="mt-1 rounded-lg border border-slate-200 px-3 py-1.5 text-xs text-slate-700 transition hover:border-blue-500"
            >
              Try again
            </button>
          </div>
        </Card>
      ) : visible.length === 0 ? (
        <Card className="p-10 text-center text-sm text-slate-500">
          {agents.length === 0
            ? "No agents yet. Your provider builds and rolls these out."
            : "No agent matches that search."}
        </Card>
      ) : (
        <div className="grid gap-5 sm:grid-cols-2">
          {visible.map((a) => (
            <Card
              key={a.id}
              className="group flex flex-col p-6 transition duration-300 hover:-translate-y-1 hover:border-blue-500"
            >
              <div className="flex items-start justify-between">
                <span className="flex h-12 w-12 items-center justify-center rounded-xl bg-slate-100 text-slate-900 transition group-hover:scale-110">
                  <Icon name="robot" size={24} />
                </span>
                <div className="flex items-center gap-2">
                  {a.providerManaged && (
                    <span
                      title="Built and maintained by your provider"
                      className="inline-flex items-center gap-1 rounded-full border border-violet-400/30 bg-violet-400/10 px-2 py-0.5 text-[10px] font-medium text-violet-600"
                    >
                      <Icon name="shield" size={10} /> managed
                    </span>
                  )}
                  <StatusBadge status={a.status} />
                </div>
              </div>
              <h3 className="mt-4 text-lg font-semibold text-slate-900">{a.name}</h3>
              <p className="mt-1.5 flex-1 text-sm leading-relaxed text-slate-500">
                {a.description || "No description provided."}
              </p>

              <div className="mt-4">
                <p className="text-[10px] font-semibold uppercase tracking-[0.18em] text-slate-500">
                  Tools ({a.toolCount})
                </p>
                {a.tools.length === 0 ? (
                  <p className="mt-1.5 text-xs text-slate-500">
                    No automations granted — this agent can operate nothing.
                  </p>
                ) : (
                  <div className="mt-1.5 flex flex-wrap gap-1.5">
                    {a.tools.map((t) => (
                      <span
                        key={`${t.type}-${t.id}`}
                        title={
                          t.available
                            ? `${t.type} · ${t.name}`
                            : "This automation no longer exists — ask your provider to update the agent"
                        }
                        className={`inline-flex items-center gap-1 rounded-md border px-2 py-0.5 text-xs ${
                          t.available
                            ? "border-slate-200 bg-slate-50 text-slate-600"
                            : "border-red-400/30 bg-red-400/10 text-red-600"
                        }`}
                      >
                        <Icon
                          name={t.available ? (t.type === "job" ? "list" : "blocks") : "warning"}
                          size={12}
                        />
                        {t.name}
                      </span>
                    ))}
                  </div>
                )}
              </div>

              <div className="mt-5 flex items-center justify-between gap-2 border-t border-slate-200 pt-4">
                <span className="truncate text-xs text-slate-500">
                  {a.model ? `${a.model} · ` : ""}
                  {a.updatedAt ? `updated ${fmtDate(a.updatedAt)}` : "never updated"}
                </span>
                {/* The kill switch stays with the customer whoever built the
                    agent — stopping something acting in your own workspace is
                    not the provider's call. Configuring it is. */}
                <div className="flex shrink-0 items-center gap-2">
                  {/* Open to everyone who can see the page: the run history is
                      the record of what this agent has done in the workspace,
                      and reading it is not a privileged act. Starting a run
                      inside the panel still needs canRun. */}
                  {/* Allowed on a provider-managed agent too: the persona
                      stays sealed, but which vendor processes this
                      workspace's data is the customer's decision. */}
                  <SmallButton icon="gear" onClick={() => setConfiguring(a)}>
                    {a.model ? "Change model" : "Configure model"}
                  </SmallButton>
                  <SmallButton icon="chat" onClick={() => setRunning(a)}>
                    {a.enabled ? "Run" : "History"}
                  </SmallButton>
                  {canRun && (
                    <SmallButton
                      icon={a.enabled ? "stop" : "play"}
                      onClick={() => toggleEnabled(a)}
                    >
                      {a.enabled ? "Pause" : "Resume"}
                    </SmallButton>
                  )}
                </div>
              </div>
            </Card>
          ))}
        </div>
      )}

      {configuring && (
        <ModelPicker
          agent={configuring}
          pushToast={pushToast}
          onClose={() => setConfiguring(null)}
          onSaved={() => {
            setConfiguring(null);
            load();
          }}
        />
      )}

      {running && (
        <AgentRunPanel
          agent={running}
          canRun={canRun}
          pushToast={pushToast}
          onClose={() => setRunning(null)}
        />
      )}
    </div>
  );
}
