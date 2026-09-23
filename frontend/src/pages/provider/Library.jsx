import React, { useEffect, useMemo, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import {
  PageHeader,
  Card,
  StatCard,
  Toolbar,
  Table,
  StatusBadge,
  SmallButton,
  Chip,
  Pagination,
} from "../../components/app/appui";
import Icon from "../../components/Icon";
import { api } from "../../lib/api";
import { useStore } from "../../store/store";
import RolloutDialog from "../../components/provider/RolloutDialog";
import ModalPortal from "../../components/app/ModalPortal";

// The sidebar's Scripts / Workflows / Agents entries are this same page with
// the type filter pinned by the URL, so there is one catalog and one loader.
const TYPE_FOR_ROUTE = { scripts: "script", workflows: "workflow", agents: "agent" };
const ROUTE_FOR_TYPE = { script: "scripts", workflow: "workflows", agent: "agents" };

/** One reading of an item's category, so the filter and the counts agree. */
const categoryOf = (item) => (item.category || "").trim() || "General";

const HEADINGS = {
  all: ["Library", "Your managed catalog of scripts, workflows and agents"],
  script: ["Scripts", "Catalog scripts tenants import into their own workspace"],
  workflow: ["Workflows", "Workflows you publish and roll out to tenants"],
  agent: ["Agents", "Agents you publish and roll out to tenants"],
};

/** Catalog definitions are stored as JSON text; show them readably. */
function prettyDefinition(definition) {
  if (!definition) return "(empty)";
  try {
    return JSON.stringify(JSON.parse(definition), null, 2);
  } catch {
    return definition;
  }
}

/**
 * The Agents view of the catalog: one agent, one card.
 *
 * A table row reduces an agent to a title and two chips, which is the wrong
 * shape for the thing being sold — what distinguishes one agent from another
 * is what it DOES and what it runs on. Those get room here; scripts and
 * workflows keep the table, where a dense list genuinely reads better.
 *
 * <p>The operating brief is deliberately absent: it is the product, it is
 * withheld from customers, and it is long. Open the card to read it.
 */
function AgentCards({ agents, loading, onOpen, onEdit, onRollOut, page, pageSize, total, onPageChange }) {
  if (loading) {
    return (
      <div className="mt-4 grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
        {[0, 1, 2].map((i) => (
          <Card key={i} className="h-56 animate-pulse p-6" />
        ))}
      </div>
    );
  }
  if (agents.length === 0) {
    return (
      <Card className="mt-4 p-10 text-center text-sm text-slate-500">
        No agents in the catalog yet. Build one and roll it out to your customers.
      </Card>
    );
  }
  return (
    <>
      <div className="mt-4 grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
        {agents.map((a) => {
          return (
            <Card
              key={a.id}
              onClick={() => onOpen(a)}
              className="group flex cursor-pointer flex-col p-6 transition duration-300 hover:-translate-y-1 hover:border-blue-500"
            >
              <div className="flex items-start justify-between">
                <span className="flex h-12 w-12 items-center justify-center rounded-xl bg-slate-100 text-slate-900 transition group-hover:scale-110">
                  <Icon name="robot" size={24} />
                </span>
                <Chip>{a.premium ? "Premium" : "Standard"}</Chip>
              </div>

              <h3 className="mt-4 text-lg font-semibold text-slate-900">{a.title}</h3>
              <p className="mt-1.5 flex-1 text-sm leading-relaxed text-slate-500">
                {a.description || "No description provided."}
              </p>

              <div className="mt-4 flex flex-wrap items-center gap-1.5 text-xs">
                {a.model && (
                  <span className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-slate-50 px-2 py-0.5 font-mono text-[11px] text-slate-600">
                    <Icon name="sparkles" size={11} />
                    {a.model}
                  </span>
                )}
                <Chip>{a.category}</Chip>
              </div>

              <div className="mt-5 flex items-center justify-between gap-2 border-t border-slate-200 pt-4">
                {/* `rollouts`, not `installs`. installs counts SCRIPT imports
                    and is never touched by a rollout, so an agent delivered to
                    ten customers still read "0 rollouts". This is counted live
                    from the delivered copies, so revoking one takes it down. */}
                <span className="text-xs text-slate-500">
                  {a.rollouts ?? 0} rollout{(a.rollouts ?? 0) === 1 ? "" : "s"}
                </span>
                <div className="flex gap-2" onClick={(e) => e.stopPropagation()}>
                  {/* An agent is EDITABLE in the catalog even though the
                      delivered copies are sealed: this is the original, and
                      the alternative was re-authoring the JSON file and
                      re-running publish.py to fix a typo in a persona. */}
                  <SmallButton icon="pencil" onClick={() => onEdit(a)}>
                    Edit
                  </SmallButton>
                  <SmallButton
                    icon="bolt"
                    variant="primary"
                    onClick={() => onRollOut(a)}
                  >
                    Roll out
                  </SmallButton>
                </div>
              </div>
            </Card>
          );
        })}
      </div>
      <Pagination
        page={page}
        pageSize={pageSize}
        totalItems={total}
        onPageChange={onPageChange}
      />
    </>
  );
}

/** An agent's catalog spec lives in `definition` as JSON; read it defensively. */

export default function ProviderLibrary() {
  const { pushToast } = useStore();
  const navigate = useNavigate();
  const { type: typeParam } = useParams();
  // An unknown segment falls back to the full catalog rather than 404-ing.
  const typeFilter = TYPE_FOR_ROUTE[typeParam] || "all";
  const [title, subtitle] = HEADINGS[typeFilter];
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(true);
  const [query, setQuery] = useState("");
  const [category, setCategory] = useState("all");
  const [selectedItem, setSelectedItem] = useState(null);
  // Who holds the item currently open in the drawer. Null while loading, so
  // "still fetching" and "nobody has it" do not look the same.
  const [deliveries, setDeliveries] = useState(null);
  const [definition, setDefinition] = useState(null);
  const [revoking, setRevoking] = useState(null);
  const [rolloutItem, setRolloutItem] = useState(null);

  const load = async () => {
    setLoading(true);
    try {
      const list = await api.providerLibrary();
      setItems(Array.isArray(list) ? list : []);
    } catch (err) {
      pushToast(err.message || "Could not load library", "red");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);



  // Categories present in the CURRENT type view, with counts. The count is the
  // point rather than decoration: the filter exists to check a category came
  // across whole, and the number answers that without opening it.
  const categoryCounts = useMemo(() => {
    const counts = new Map();
    for (const i of items) {
      if (typeFilter !== "all" && (i.type || "script") !== typeFilter) continue;
      const c = categoryOf(i);
      counts.set(c, (counts.get(c) || 0) + 1);
    }
    return [...counts.entries()].sort((a, b) => a[0].localeCompare(b[0]));
  }, [items, typeFilter]);

  // A category selected under one type may not exist under the next. Falling
  // back here rather than in an effect means the list is never briefly empty
  // with a selection the dropdown cannot even show.
  const activeCategory = categoryCounts.some(([c]) => c === category)
    ? category
    : "all";

  const filtered = items.filter((i) => {
    const matchQuery = `${i.title} ${i.category}`
      .toLowerCase()
      .includes(query.toLowerCase());
    const matchType =
      typeFilter === "all" || (i.type || "script") === typeFilter;
    const matchCategory =
      activeCategory === "all" || categoryOf(i) === activeCategory;
    return matchQuery && matchType && matchCategory;
  });

  const [page, setPage] = useState(1);
  // Six, not five: the grid is three columns, so five leaves a gap in the
  // second row on every page. A page size that is not a multiple of the column
  // count always looks like something failed to load.
  const pageSize = 6;
  const totalPages = Math.ceil(filtered.length / pageSize) || 1;
  const currentPage = Math.min(page, totalPages);
  const visibleFiltered = filtered.slice(
    (currentPage - 1) * pageSize,
    currentPage * pageSize,
  );

  // The definition for the item the drawer is showing. The list stopped
  // carrying bodies — ~240 items at 8.6KB each made it a two-megabyte response
  // for a panel that displays one at a time.
  useEffect(() => {
    if (!selectedItem) {
      setDefinition(null);
      return;
    }
    let dropped = false;
    setDefinition(null);
    api
      .libraryItem(selectedItem.id)
      .then((item) => !dropped && setDefinition(item?.definition ?? ""))
      .catch(() => !dropped && setDefinition("error"));
    return () => {
      dropped = true;
    };
  }, [selectedItem]);

  useEffect(() => {
    if (!selectedItem || selectedItem.type === "script") {
      setDeliveries(null);
      return;
    }
    let cancelled = false;
    setDeliveries(null);
    api
      .providerDeliveries(selectedItem.id)
      .then((rows) => !cancelled && setDeliveries(rows || []))
      // An empty list on failure would read as "nobody holds this", which is
      // the answer that makes a provider stop looking.
      .catch(() => !cancelled && setDeliveries("error"));
    return () => {
      cancelled = true;
    };
  }, [selectedItem]);

  const revoke = async (delivery) => {
    setRevoking(delivery.id);
    try {
      await api.providerRevoke(selectedItem.id, delivery.id, delivery.tenantId);
      setDeliveries((rows) => rows.filter((d) => d.id !== delivery.id));
      pushToast(`Revoked from ${delivery.tenantId}`, "green");
      // The rollout count on the card behind the drawer is now stale.
      load();
    } catch (err) {
      pushToast(err.message || "Could not revoke.", "red");
    } finally {
      setRevoking(null);
    }
  };

  const handleQueryChange = (e) => {
    setQuery(e.target.value);
    setPage(1);
  };

  // The dropdown and the sidebar are the same control now — changing it moves
  // the URL, which is what re-filters the list and lights up the nav entry.
  const handleTypeChange = (e) => {
    const next = e.target.value;
    navigate(
      next === "all"
        ? "/provider/library"
        : `/provider/library/${ROUTE_FOR_TYPE[next]}`,
    );
  };

  const handleCategoryChange = (e) => {
    setCategory(e.target.value);
    setPage(1);
  };

  useEffect(() => {
    setPage(1);
    // Switching type re-scopes the category list, so a stale pick would filter
    // against options that are no longer on offer.
    setCategory("all");
  }, [typeParam]);

  const totalScripts = items.filter(
    (i) => (i.type || "script") === "script",
  ).length;
  const totalAgents = items.filter((i) => i.type === "agent").length;
  const totalWorkflows = items.filter((i) => i.type === "workflow").length;


  return (
    <div className="animate-fade-up">
      <PageHeader
        title={title}
        subtitle={subtitle}
        actions={
          <>
            <SmallButton
              icon="terminal"
              onClick={() => navigate("/provider/library/script/new")}
            >
              New script
            </SmallButton>
            <SmallButton
              icon="robot"
              onClick={() => navigate("/provider/library/agent/new")}
            >
              New agent
            </SmallButton>
            {/* There is no "Import" button beside this one any more, and there
                should not be: it navigated to the same designer with the
                import panel pre-opened, so two buttons that read as two
                different acts did the same thing. Importing a definition is
                something you do INSIDE the designer — it already has its own
                Import control — not a separate way to start.

                (It replaced a "Publish" button pointing at
                /library/workflow/publish, a route that has never existed. That
                act — putting an already-authored workflow in the catalog — is
                what the designer's Import does now.) */}
            <SmallButton
              icon="blocks"
              variant="primary"
              onClick={() => navigate("/provider/library/workflow/new")}
            >
              New workflow
            </SmallButton>
          </>
        }
      />

      {/* Totals belong to the catalog overview. On the Scripts / Workflows /
          Agents views they would restate the one number you just filtered to
          and repeat the two you filtered out. */}
      {typeFilter === "all" && (
        <div className="mb-6 grid gap-4 sm:grid-cols-3">
          <StatCard
            label="Total scripts"
            value={totalScripts}
            icon="terminal"
            tone="violet"
          />
          <StatCard
            label="Total agents"
            value={totalAgents}
            icon="robot"
            tone="cyan"
          />
          <StatCard
            label="Total workflows"
            value={totalWorkflows}
            icon="blocks"
            tone="emerald"
          />
        </div>
      )}

      <Toolbar
        placeholder="Search library…"
        value={query}
        onChange={handleQueryChange}
        right={
          <>
            {/* Built from what is actually in the catalog, so a category added
                by a future import appears here without a code change. */}
            <select
              aria-label="Filter by category"
              value={activeCategory}
              onChange={handleCategoryChange}
              disabled={categoryCounts.length === 0}
              className="rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-700 outline-none focus:border-slate-300 disabled:opacity-50"
            >
              <option value="all">
                All Categories ({categoryCounts.reduce((n, [, c]) => n + c, 0)})
              </option>
              {categoryCounts.map(([name, count]) => (
                <option key={name} value={name}>
                  {name} ({count})
                </option>
              ))}
            </select>
            <select
              aria-label="Filter by type"
              value={typeFilter}
              onChange={handleTypeChange}
              className="rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-700 outline-none focus:border-slate-300"
            >
              <option value="all">All Types</option>
              <option value="script">Scripts</option>
              <option value="agent">Agents</option>
              <option value="workflow">Workflows</option>
            </select>
          </>
        }
      />

      {typeFilter === "agent" ? (
        <AgentCards
          agents={visibleFiltered}
          loading={loading}
          onOpen={setSelectedItem}
          onEdit={(a) => navigate(`/provider/library/agent/${a.id}`)}
          onRollOut={setRolloutItem}
          page={currentPage}
          pageSize={pageSize}
          total={filtered.length}
          onPageChange={setPage}
        />
      ) : (
        <div className="mt-4">
          <Table
            loading={loading}
            onRowClick={(r) => setSelectedItem(r)}
            empty="No catalog items yet. Publish your first item to get started."
            columns={[
              {
                key: "title",
                label: "Item",
                render: (r) => (
                  <div>
                    <p className="font-medium text-slate-900">{r.title}</p>
                    {r.description && (
                      <p className="max-w-md truncate text-xs text-slate-500">
                        {r.description}
                      </p>
                    )}
                  </div>
                ),
              },
              {
                key: "type",
                label: "Type",
                render: (r) => <Chip>{r.type || "script"}</Chip>,
              },
              {
                key: "category",
                label: "Category",
                render: (r) => <Chip>{r.category}</Chip>,
              },
              {
                key: "premium",
                label: "Tier",
                render: (r) => (
                  <span className="text-sm text-slate-600">
                    {r.premium ? "Premium" : "Standard"}
                  </span>
                ),
              },
              {
                // Two different numbers, and calling both "Installs" hid that.
                // A script is IMPORTED by a customer (installs); a workflow or
                // agent is DELIVERED by the provider (rollouts). Neither
                // counter is ever populated by the other's action, so showing
                // one column for both meant whichever type you were looking at,
                // the number was zero.
                key: "installs",
                label: "Delivered",
                render: (r) =>
                  r.type === "script" ? (
                    <span className="text-sm text-slate-500">
                      {r.installs ?? 0} import{(r.installs ?? 0) === 1 ? "" : "s"}
                    </span>
                  ) : (
                    <span className="text-sm text-slate-500">
                      {r.rollouts ?? 0} rollout{(r.rollouts ?? 0) === 1 ? "" : "s"}
                    </span>
                  ),
              },
              {
                key: "managed",
                label: "Status",
                render: () => <StatusBadge status="active" />,
              },
              {
                key: "act",
                label: "",
                // Scripts are IMPORTED by customers from the library; workflows
                // and agents are DELIVERED by you. Only the latter get a rollout
                // button, because only they are sealed on arrival.
                render: (r) =>
                  r.type === "script" ? (
                    // Scripts are not rolled out, but they ARE editable — the
                    // catalog copy is the original every customer imports.
                    <div onClick={(e) => e.stopPropagation()}>
                      <SmallButton
                        icon="pencil"
                        onClick={() => navigate(`/provider/library/script/${r.id}`)}
                      >
                        Edit
                      </SmallButton>
                    </div>
                  ) : (
                    <div className="flex gap-2" onClick={(e) => e.stopPropagation()}>
                      {/* Workflows are editable now. This used to be agents
                          only, on the reasoning that a workflow's definition is
                          a node graph with no editor behind it — true until the
                          designer existed, and it left every published workflow
                          in the catalog permanently unopenable. */}
                      <SmallButton
                        icon="pencil"
                        onClick={() =>
                          navigate(`/provider/library/${r.type}/${r.id}`)
                        }
                      >
                        Edit
                      </SmallButton>
                      <SmallButton
                        icon="bolt"
                        variant="primary"
                        onClick={() => setRolloutItem(r)}
                      >
                        Roll out
                      </SmallButton>
                    </div>
                  ),
              },
            ]}
            rows={visibleFiltered}
          />
          <Pagination
            page={currentPage}
            pageSize={pageSize}
            totalItems={filtered.length}
            onPageChange={setPage}
          />
        </div>
      )}

      {selectedItem && (
        <ModalPortal
          layerClass="z-[100] items-start overflow-y-auto px-6 pb-6 pt-10"
          onClose={() => setSelectedItem(null)}
        >
          <div className="animate-fade-up relative w-full max-w-2xl overflow-hidden rounded-2xl bg-white shadow-2xl ring-1 ring-slate-200">
            <div className="flex items-start justify-between border-b border-slate-200 p-5">
              <div className="min-w-0">
                <h2 className="truncate text-lg font-bold text-slate-900">
                  {selectedItem.title}
                </h2>
                <p className="mt-1 text-xs text-slate-500">
                  {selectedItem.description || "No description."}
                </p>
              </div>
              <button
                onClick={() => setSelectedItem(null)}
                aria-label="Close"
                className="shrink-0 text-slate-400 transition hover:text-slate-600"
              >
                <Icon name="x" size={20} />
              </button>
            </div>

            <div className="space-y-4 p-5">
              <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
                {[
                  ["Type", selectedItem.type || "script"],
                  ["Category", selectedItem.category],
                  ["Tier", selectedItem.premium ? "Premium" : "Standard"],
                  // Scripts are imported by customers; workflows and agents are
                  // delivered by rollout. Different counters, so name the one
                  // that actually applies rather than always saying "Installs".
                  selectedItem.type === "script"
                    ? ["Imports", String(selectedItem.installs ?? 0)]
                    : ["Rollouts", String(selectedItem.rollouts ?? 0)],
                ].map(([label, value]) => (
                  <div key={label}>
                    <p className="text-[10px] font-semibold uppercase tracking-[0.18em] text-slate-500">
                      {label}
                    </p>
                    <p className="mt-1 text-sm text-slate-900">{value}</p>
                  </div>
                ))}
              </div>

              <div>
                <p className="mb-1.5 text-[10px] font-semibold uppercase tracking-[0.18em] text-slate-500">
                  Definition
                </p>
                <pre className="max-h-72 overflow-auto rounded-xl border border-slate-800 bg-slate-950 p-4 font-mono text-[11px] leading-relaxed text-slate-200">
                  {definition === null
                    ? "Loading…"
                    : definition === "error"
                      // Not an empty body. A definition that failed to load must
                      // not read as one that is empty.
                      ? "Could not load this definition."
                      : prettyDefinition(definition)}
                </pre>
              </div>

              {selectedItem.type !== "script" && (
                <div>
                  <p className="mb-1.5 text-[10px] font-semibold uppercase tracking-[0.18em] text-slate-500">
                    Delivered to
                  </p>
                  {deliveries === null ? (
                    <p className="text-sm text-slate-500">Loading…</p>
                  ) : deliveries === "error" ? (
                    // Not an empty list. "Nobody holds this" is the answer that
                    // makes a provider stop looking, and it must not be what a
                    // failed request looks like.
                    <p className="text-sm text-amber-700" role="alert">
                      Could not read who holds this. The list below is not empty —
                      it is unknown.
                    </p>
                  ) : deliveries.length === 0 ? (
                    <p className="text-sm text-slate-500">
                      No customer holds a copy of this yet.
                    </p>
                  ) : (
                    <div className="space-y-1.5">
                      {deliveries.map((d) => (
                        <div
                          key={`${d.tenantId}-${d.id}`}
                          className="flex items-center justify-between gap-3 rounded-xl border border-slate-200 px-3 py-2"
                        >
                          <div className="min-w-0">
                            <p className="truncate text-sm text-slate-900">{d.tenantId}</p>
                            <p className="truncate font-mono text-[10px] text-slate-500">
                              {d.name} · project {d.projectId}
                              {!d.enabled && " · disabled"}
                            </p>
                          </div>
                          <SmallButton
                            onClick={() => revoke(d)}
                            disabled={revoking === d.id}
                          >
                            {revoking === d.id ? "Revoking…" : "Revoke"}
                          </SmallButton>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              )}

              {selectedItem.type !== "script" && (
                <div className="flex justify-end">
                  <SmallButton
                    icon="bolt"
                    variant="primary"
                    onClick={() => {
                      setRolloutItem(selectedItem);
                      setSelectedItem(null);
                    }}
                  >
                    Roll out
                  </SmallButton>
                </div>
              )}
            </div>
          </div>
        </ModalPortal>
      )}

      {rolloutItem && (
        <RolloutDialog
          item={rolloutItem}
          onClose={() => setRolloutItem(null)}
          onDone={load}
        />
      )}
    </div>
  );
}
