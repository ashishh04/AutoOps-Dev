import { useCallback, useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import {
  PageHeader,
  Card,
  CloudHealthBadge,
  SmallButton,
} from "../../components/app/appui";
import CloudLogo from "../../components/app/CloudLogo";
import { platformById } from "../../data/saasData";
import { useStore } from "../../store/store";
import { api } from "../../lib/api";

export default function ProjectIntegrations() {
  const { pid } = useParams();
  const { projects, pushToast } = useStore();
  const project = projects.find((p) => String(p.id) === String(pid));
  const [clouds, setClouds] = useState([]);
  // "Not loaded yet" and "none available" are different answers and this page
  // stated the second before it knew. Less alarming than the workspace page —
  // a sentence rather than a wall of platform cards — but the same untruth.
  const [loaded, setLoaded] = useState(false);
  const [saving, setSaving] = useState(null); // connection id being toggled

  // Only this project's connections and global ones are shown — a connection
  // assigned to a different project belongs to that project alone.
  const load = useCallback(async () => {
    try {
      const rows = await api.listCloudConnections();
      setClouds(
        rows.filter(
          (c) => c.projectId == null || String(c.projectId) === String(pid),
        ),
      );
    } catch {
      /* keep current rows: a failed refresh must not empty the list on screen */
    } finally {
      setLoaded(true);
    }
  }, [pid]);
  useEffect(() => {
    load();
  }, [load]);

  // ON = dedicated to this project; OFF = global (available to all projects).
  const toggle = async (c) => {
    const dedicated = String(c.projectId) === String(pid);
    setSaving(c.id);
    try {
      await api.assignCloudConnection(c.id, dedicated ? null : Number(pid));
      pushToast(
        dedicated
          ? `${platformById(c.platform).name} is now available to all projects`
          : `${platformById(c.platform).name} assigned to ${project.name}`,
        "emerald",
      );
      await load();
    } catch (e) {
      pushToast(e.message || "Could not change the assignment", "red");
    }
    setSaving(null);
  };

  if (!project)
    return (
      <div className="animate-fade-up">
        <PageHeader
          title="Project not found"
          subtitle="This project isn't available yet."
        />
        <Card className="p-10 text-center text-sm text-slate-500">
          Nothing here yet.{" "}
          <Link to="/app/projects" className="text-slate-900 hover:underline">
            Back to projects
          </Link>
        </Card>
      </div>
    );

  return (
    <div className="animate-fade-up">
      <Link
        to={`/app/projects/${project.id}`}
        className="text-sm text-slate-500 transition hover:text-slate-900"
      >
        ← {project.name}
      </Link>
      <PageHeader
        title="Cloud Integrations"
        subtitle="Connections available to this project — dedicate one to keep it out of other projects"
        actions={
          <Link to="/app/integrations">
            <SmallButton icon="plus" variant="primary">
              Connect a cloud
            </SmallButton>
          </Link>
        }
      />

      {!loaded ? (
        <Card className="p-10 text-center text-sm text-slate-500">
          Loading connections…
        </Card>
      ) : clouds.length === 0 ? (
        <Card className="p-10 text-center text-sm text-slate-500">
          No cloud connections available to this project yet.{" "}
          <Link to="/app/integrations" className="text-slate-900 hover:underline">
            Connect a cloud →
          </Link>
        </Card>
      ) : (
        <div className="grid gap-4 sm:grid-cols-2">
          {clouds.map((c) => {
            const pf = platformById(c.platform);
            const dedicated = String(c.projectId) === String(pid);
            return (
              <Card
                key={c.id}
                className={`p-5 transition ${dedicated ? "border-slate-300 bg-slate-100" : ""}`}
              >
                <div className="flex items-start justify-between">
                  <div className="flex items-center gap-3">
                    <span className="flex h-11 w-11 items-center justify-center rounded-xl border border-slate-200 bg-white">
                      <CloudLogo platform={pf} size={24} />
                    </span>
                    <div>
                      <p className="font-semibold text-slate-900">
                        {c.accountName || pf.name}
                      </p>
                      <p className="font-mono text-xs text-slate-500">
                        {c.accountId || c.name}
                      </p>
                    </div>
                  </div>
                  <CloudHealthBadge connection={c} />
                </div>
                <div className="mt-4 flex items-center justify-between border-t border-slate-200 pt-3">
                  <span className="text-xs text-slate-500">
                    {c.region
                      ? `Region · ${c.region}`
                      : c.hasCredentials
                        ? "Credentials configured"
                        : "No credentials"}
                  </span>
                  <button
                    onClick={() => toggle(c)}
                    disabled={saving === c.id}
                    title={
                      dedicated
                        ? "Make available to all projects"
                        : "Dedicate to this project"
                    }
                    className={`relative inline-flex h-6 w-11 items-center rounded-full transition disabled:opacity-40 ${dedicated ? "bg-gradient-to-r from-slate-900 to-slate-900" : "bg-slate-200"}`}
                  >
                    <span
                      className={`inline-block h-4 w-4 transform rounded-full bg-white transition ${dedicated ? "translate-x-6" : "translate-x-1"}`}
                    />
                  </button>
                </div>
                <p
                  className={`mt-2 text-xs ${dedicated ? "text-emerald-600" : "text-slate-600"}`}
                >
                  {dedicated
                    ? "Dedicated to this project"
                    : "Global — available to all projects"}
                </p>
              </Card>
            );
          })}
        </div>
      )}

      <Card className="mt-6 flex items-center justify-between p-4">
        <p className="text-sm text-slate-500">
          Need another provider? Connect it once at the workspace level, then
          assign it here.
        </p>
        <Link
          to="/app/integrations"
          className="text-sm font-medium text-slate-900 hover:underline"
        >
          Workspace integrations →
        </Link>
      </Card>
    </div>
  );
}
