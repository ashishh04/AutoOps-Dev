import React from "react";
import Reveal from "../components/Reveal";
import { Pill, PrimaryButton } from "../components/ui";

const NODES = [
  {
    id: "trigger",

    label: "Trigger",
    sub: "Schedule / Webhook",
    x: 18,
    y: 50,
    tone: "cyan",
  },
  {
    id: "build",
    label: "Build Image",
    sub: "docker",
    x: 40,
    y: 24,
    tone: "slate",
  },
  { id: "tf", label: "Terraform", sub: "apply", x: 40, y: 76, tone: "slate" },
  {
    id: "approve",
    label: "Approval",
    sub: "gate",
    x: 62,
    y: 50,
    tone: "amber",
  },
  {
    id: "deploy",
    label: "Deploy",
    sub: "k8s rollout",
    x: 84,
    y: 50,
    tone: "emerald",
  },
];
const EDGES = [
  ["trigger", "build"],
  ["trigger", "tf"],
  ["build", "approve"],
  ["tf", "approve"],
  ["approve", "deploy"],
];
const toneMap = {
  cyan: "border-cyan-300 bg-cyan-50 text-cyan-900 shadow-sm",
  emerald: "border-emerald-300 bg-emerald-50 text-emerald-900 shadow-sm",
  amber: "border-amber-300 bg-amber-50 text-amber-900 shadow-sm",
  slate: "border-slate-300 bg-white text-slate-800 shadow-sm",
};
const center = (id) => {
  const n = NODES.find((x) => x.id === id);
  return { x: n.x, y: n.y };
};

export default function WorkflowDesigner() {
  return (
    <section id="product" className="mx-auto max-w-7xl scroll-mt-24 px-6 py-24">
      <div className="grid items-center gap-12 lg:grid-cols-2">
        <Reveal>
          <Pill>Workflow DAG Engine</Pill>
          <h2 className="mt-5 text-4xl font-bold leading-tight tracking-tight text-slate-900 sm:text-5xl">
            Design pipelines visually, run them anywhere
          </h2>
          <p className="mt-5 max-w-md text-base leading-relaxed text-slate-500">
            Wire triggers, scripts, infrastructure steps, and approval gates
            into a single directed graph. AutoOps auto-lays out your DAG,
            validates it, and executes each node against the right targets —
            with live status on every edge.
          </p>
          <ul className="mt-6 space-y-3 text-sm text-slate-600">
            {[
              "Drag-drop nodes with automatic dagre layout",
              "Branching, fan-out / fan-in, and approval gates",
              "Autosave, undo/redo, and unsaved-change guards",
            ].map((t) => (
              <li key={t} className="flex items-start gap-2">
                <span className="mt-0.5 text-emerald-600">✓</span> {t}
              </li>
            ))}
          </ul>
          <div className="mt-8">
            <PrimaryButton to="/playground">Open the Designer</PrimaryButton>
          </div>
        </Reveal>

        <Reveal delay={120}>
          <div className="grid-bg relative h-[360px] overflow-hidden rounded-2xl border border-slate-200 bg-slate-50 p-2">
            <svg
              className="absolute inset-0 h-full w-full"
              preserveAspectRatio="none"
            >
              {EDGES.map(([a, b], i) => {
                const p1 = center(a),
                  p2 = center(b);
                return (
                  <line
                    key={i}
                    x1={`${p1.x}%`}
                    y1={`${p1.y}%`}
                    x2={`${p2.x}%`}
                    y2={`${p2.y}%`}
                    stroke="#94a3b8"
                    strokeWidth="2"
                    className="animate-dash"
                    opacity="0.8"
                  />
                );
              })}
            </svg>
            {NODES.map((n) => (
              <div
                key={n.id}
                className={`absolute -translate-x-1/2 -translate-y-1/2 rounded-lg border px-3 py-2 backdrop-blur-sm transition hover:scale-105 ${toneMap[n.tone]}`}
                style={{ left: `${n.x}%`, top: `${n.y}%` }}
              >
                <p className="text-xs font-semibold">{n.label}</p>
                <p className="font-mono text-[10px] opacity-70">{n.sub}</p>
              </div>
            ))}
          </div>
        </Reveal>
      </div>
    </section>
  );
}
