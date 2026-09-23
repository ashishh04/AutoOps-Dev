import { useCallback, useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { api } from "../../lib/api";
import {
  Card,
  PageHeader,
  StatusBadge,
  SmallButton,
  Table,
  Chip,
  ConfirmModal,
} from "../../components/app/appui";
import Icon from "../../components/Icon";
import CloudLogo from "../../components/app/CloudLogo";
import {
  cloudPlatforms,
  platformById,
} from "../../data/saasData";
import { useStore } from "../../store/store";

// Custom select that always opens its list DOWNWARD (a native <select> near
// the bottom of the viewport flips its popup upward and can't be controlled).
const DropdownSelect = ({ value, options, onChange, disabled, error }) => {
  const [open, setOpen] = useState(false);
  const ref = useRef(null);
  useEffect(() => {
    if (!open) return;
    const onAway = (e) => {
      if (ref.current && !ref.current.contains(e.target)) setOpen(false);
    };
    const onKey = (e) => {
      if (e.key === "Escape") setOpen(false);
    };
    document.addEventListener("mousedown", onAway);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onAway);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);
  const opts = options.map((o) =>
    typeof o === "string" ? { value: o, label: o } : o,
  );
  const current = opts.find((o) => o.value === value);
  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        disabled={disabled}
        aria-haspopup="listbox"
        aria-expanded={open}
        className={`flex w-full items-center justify-between rounded-lg border bg-slate-50 px-4 py-2.5 text-left text-sm text-slate-900 outline-none transition disabled:opacity-60 ${
          error
            ? "border-red-500/50"
            : open
              ? "border-slate-300 ring-2 ring-slate-300"
              : "border-slate-200 focus:border-slate-300 focus:ring-2 focus:ring-slate-300"
        }`}
      >
        <span className="truncate">{current ? current.label : value}</span>
        <Icon
          name="chevron"
          size={14}
          className={`ml-2 shrink-0 text-slate-400 transition ${open ? "-rotate-90" : "rotate-90"}`}
        />
      </button>
      {open && (
        <div
          role="listbox"
          className="absolute left-0 right-0 top-full z-20 mt-1 max-h-52 overflow-y-auto rounded-lg border border-slate-200 bg-white py-1 shadow-xl"
        >
          {opts.map((o) => (
            <button
              key={o.value}
              type="button"
              role="option"
              aria-selected={o.value === value}
              onClick={() => {
                onChange(o.value);
                setOpen(false);
              }}
              className={`block w-full px-4 py-2 text-left text-sm transition hover:bg-slate-100 ${
                o.value === value
                  ? "bg-slate-50 font-semibold text-slate-900"
                  : "text-slate-700"
              }`}
            >
              {o.label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
};

// All commercial AWS regions (GovCloud and China partitions excluded —
// they need separate credentials and endpoints).
const AWS_REGIONS = [
  // United States
  "us-east-1",
  "us-east-2",
  "us-west-1",
  "us-west-2",
  // Canada
  "ca-central-1",
  "ca-west-1",
  // Mexico
  "mx-central-1",
  // South America
  "sa-east-1",
  // Europe
  "eu-central-1",
  "eu-central-2",
  "eu-west-1",
  "eu-west-2",
  "eu-west-3",
  "eu-north-1",
  "eu-south-1",
  "eu-south-2",
  // Middle East
  "me-central-1",
  "me-south-1",
  // Israel
  "il-central-1",
  // Africa
  "af-south-1",
  // Asia Pacific
  "ap-east-1",
  "ap-east-2",
  "ap-south-1",
  "ap-south-2",
  "ap-southeast-1",
  "ap-southeast-2",
  "ap-southeast-3",
  "ap-southeast-4",
  "ap-southeast-5",
  "ap-southeast-6",
  "ap-southeast-7",
  "ap-northeast-1",
  "ap-northeast-2",
  "ap-northeast-3",
];

// Azure public-cloud locations (ARM `location` codes).
const AZURE_REGIONS = [
  "eastus",
  "eastus2",
  "centralus",
  "northcentralus",
  "southcentralus",
  "westcentralus",
  "westus",
  "westus2",
  "westus3",
  "canadacentral",
  "canadaeast",
  "mexicocentral",
  "brazilsouth",
  "brazilsoutheast",
  "northeurope",
  "westeurope",
  "uksouth",
  "ukwest",
  "francecentral",
  "francesouth",
  "germanywestcentral",
  "germanynorth",
  "switzerlandnorth",
  "switzerlandwest",
  "norwayeast",
  "norwaywest",
  "swedencentral",
  "polandcentral",
  "italynorth",
  "spaincentral",
  "austriaeast",
  "uaenorth",
  "uaecentral",
  "qatarcentral",
  "israelcentral",
  "southafricanorth",
  "southafricawest",
  "centralindia",
  "southindia",
  "westindia",
  "eastasia",
  "southeastasia",
  "japaneast",
  "japanwest",
  "koreacentral",
  "koreasouth",
  "indonesiacentral",
  "malaysiawest",
  "taiwannorth",
  "australiaeast",
  "australiasoutheast",
  "australiacentral",
  "newzealandnorth",
];

// Google Cloud regions.
const GCP_REGIONS = [
  "us-central1",
  "us-east1",
  "us-east4",
  "us-east5",
  "us-south1",
  "us-west1",
  "us-west2",
  "us-west3",
  "us-west4",
  "northamerica-northeast1",
  "northamerica-northeast2",
  "northamerica-south1",
  "southamerica-east1",
  "southamerica-west1",
  "europe-central2",
  "europe-north1",
  "europe-southwest1",
  "europe-west1",
  "europe-west2",
  "europe-west3",
  "europe-west4",
  "europe-west6",
  "europe-west8",
  "europe-west9",
  "europe-west10",
  "europe-west12",
  "me-central1",
  "me-central2",
  "me-west1",
  "africa-south1",
  "asia-east1",
  "asia-east2",
  "asia-northeast1",
  "asia-northeast2",
  "asia-northeast3",
  "asia-south1",
  "asia-south2",
  "asia-southeast1",
  "asia-southeast2",
  "australia-southeast1",
  "australia-southeast2",
];

// Oracle Cloud Infrastructure commercial regions.
const OCI_REGIONS = [
  "us-ashburn-1",
  "us-phoenix-1",
  "us-sanjose-1",
  "us-chicago-1",
  "ca-toronto-1",
  "ca-montreal-1",
  "sa-saopaulo-1",
  "sa-vinhedo-1",
  "sa-santiago-1",
  "sa-bogota-1",
  "sa-valparaiso-1",
  "uk-london-1",
  "uk-cardiff-1",
  "eu-frankfurt-1",
  "eu-amsterdam-1",
  "eu-zurich-1",
  "eu-madrid-1",
  "eu-marseille-1",
  "eu-milan-1",
  "eu-paris-1",
  "eu-stockholm-1",
  "il-jerusalem-1",
  "me-jeddah-1",
  "me-dubai-1",
  "me-abudhabi-1",
  "me-riyadh-1",
  "af-johannesburg-1",
  "ap-tokyo-1",
  "ap-osaka-1",
  "ap-seoul-1",
  "ap-chuncheon-1",
  "ap-mumbai-1",
  "ap-hyderabad-1",
  "ap-singapore-1",
  "ap-singapore-2",
  "ap-sydney-1",
  "ap-melbourne-1",
];

const getFormFields = (platformId) => {
  switch (platformId) {
    case "aws":
      return [
        { key: "accessId", label: "Access ID", required: true },
        {
          key: "secret",
          label: "Secret Access",
          required: true,
          type: "password",
        },
        {
          key: "region",
          label: "Region",
          type: "select",
          options: AWS_REGIONS,
        },
      ];
    case "azure":
      return [
        { key: "clientId", label: "Client ID", required: true },
        { key: "tenantId", label: "Tenant ID", required: true },
        {
          key: "clientSecret",
          label: "Client Secret",
          required: true,
          type: "password",
        },
        { key: "subscriptionId", label: "Subscription ID", required: true },
        {
          key: "region",
          label: "Location",
          type: "select",
          options: AZURE_REGIONS,
        },
      ];
    case "oracle":
      return [
        { key: "userOcid", label: "User OCID", required: true },
        { key: "tenancyOcid", label: "Tenancy OCID", required: true },
        { key: "fingerprint", label: "Fingerprint", required: true },
        {
          key: "privateKey",
          label: "Private Key",
          required: true,
          type: "textarea",
        },
        { key: "compartmentId", label: "Compartment ID", required: false },
        {
          key: "region",
          label: "Region",
          type: "select",
          options: OCI_REGIONS,
        },
      ];
    case "gcp":
      return [
        { key: "projectId", label: "Project ID", required: true },
        {
          key: "serviceAccount",
          label: "Service Account JSON",
          required: true,
          type: "textarea",
        },
        {
          key: "region",
          label: "Region",
          type: "select",
          options: GCP_REGIONS,
        },
      ];
    case "huawei":
      return [
        { key: "accessKey", label: "Access Key", required: true },
        {
          key: "secretKey",
          label: "Secret Access Key",
          required: true,
          type: "password",
        },
        { key: "projectId", label: "Project ID", required: true },
        { key: "region", label: "Region", required: true },
      ];
    case "kubernetes":
      return [
        {
          key: "kubeconfig",
          label: "Kubeconfig (YAML)",
          required: true,
          type: "textarea",
        },
      ];
    case "m365":
      return [
        { key: "tenantId", label: "Tenant ID", required: true },
        {
          key: "clientId",
          label: "Client ID (Application ID)",
          required: true,
        },
        {
          key: "clientSecret",
          label: "Client Secret / Certificate",
          required: true,
          type: "password",
        },
        {
          key: "userCreds",
          label: "User Credentials (Optional)",
          required: false,
        },
        {
          key: "accessToken",
          label: "Access Token",
          required: true,
          type: "password",
        },
      ];
    default:
      return [];
  }
};

export default function Integrations() {
  const { pushToast, projects } = useStore();
  const [modal, setModal] = useState(false);
  const [selected, setSelected] = useState(null);
  const [clouds, setClouds] = useState([]); // real rows only — no seed
  // Three states, not two. `clouds.length === 0` cannot distinguish "the
  // request has not come back yet" from "this workspace has no cloud
  // connections", and the page renders the ENTIRE platform catalog for the
  // second — so on every open, every platform flashed up as NOT CONFIGURED
  // before the real rows replaced it.
  const [loaded, setLoaded] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);
  const [confirm, setConfirm] = useState(null);

  // Live connections from core-service (credentials themselves never leave it).
  const load = useCallback(async () => {
    try {
      const rows = await api.listCloudConnections();
      setClouds(
        rows.map((c) => ({
          id: c.id,
          platform: String(c.platform || "").toLowerCase(),
          name: c.name,
          // Real identity from the stored credentials (see core-service
          // CloudAccountDescriptor); null when the platform has no such
          // concept or no credentials are stored yet.
          account: c.accountId,
          accountName: c.accountName,
          region: c.region,
          hasCredentials: c.hasCredentials,
          status: "active",
          verifiedOk: c.lastVerifiedOk, // true | false | null (never checked)
          verifiedAt: c.lastVerifiedAt,
          verifiedMessage: c.lastVerifiedMessage,
          projectId: c.projectId, // null = global (all projects)
        })),
      );
      setLoadFailed(false);
    } catch {
      // Kept, not cleared: a failed refresh must not empty a list that is
      // already on screen. But it IS recorded — showing the "nothing
      // configured" catalog because a request failed would tell somebody
      // their connections are gone.
      setLoadFailed(true);
    } finally {
      setLoaded(true);
    }
  }, []);
  useEffect(() => {
    load();
  }, [load]);

  const [form, setForm] = useState({});
  const [errors, setErrors] = useState({});
  // idle → verifying → verified (nothing stored yet) → saving → closed
  const [verifyState, setVerifyState] = useState("idle");
  const [verifiedDetails, setVerifiedDetails] = useState(null);
  const [assignedProject, setAssignedProject] = useState("global");

  const [updateItem, setUpdateItem] = useState(null);

  // Row-level project assignment (small dedicated modal — the table clips
  // an inline dropdown).
  const [assignItem, setAssignItem] = useState(null);
  const [assignValue, setAssignValue] = useState("global");
  const [assignSaving, setAssignSaving] = useState(false);

  const projectName = (id) =>
    (projects.find((p) => String(p.id) === String(id)) || {}).name ||
    `Project ${id}`;

  const saveAssignment = async () => {
    setAssignSaving(true);
    try {
      await api.assignCloudConnection(
        assignItem.id,
        assignValue === "global" ? null : Number(assignValue),
      );
      pushToast(
        assignValue === "global"
          ? `${platformById(assignItem.platform).name} is now available to all projects`
          : `${platformById(assignItem.platform).name} assigned to ${projectName(assignValue)}`,
        "emerald",
      );
      await load();
      setAssignItem(null);
    } catch (e) {
      pushToast(e.message || "Could not change the assignment", "red");
    }
    setAssignSaving(false);
  };

  const close = () => {
    setModal(false);
    setSelected(null);
    setUpdateItem(null);
    setForm({});
    setErrors({});
    setVerifyState("idle");
    setVerifiedDetails(null);
    setAssignedProject("global");
  };

  const handleSelect = (p) => {
    setSelected(p);
    setUpdateItem(null);
    setForm({});
    setErrors({});
    setVerifyState("idle");
    setVerifiedDetails(null);
    setAssignedProject("global");
  };

  const handleUpdate = (r) => {
    setUpdateItem(r);
    setSelected(platformById(r.platform));
    // Keep the connection's current region rather than silently resetting it
    // to the first option — the rest of the credentials must be re-entered
    // (the server never returns them), but the region is known.
    setForm(r.region ? { region: r.region } : {});
    setErrors({});
    setVerifyState("idle");
    setVerifiedDetails(null);
    setAssignedProject(r.projectId != null ? String(r.projectId) : "global");
    setModal(true);
  };

  const disconnect = async () => {
    try {
      await api.removeCloudConnection(confirm.id);
      pushToast(`${platformById(confirm.platform).name} disconnected`, "red");
      await load();
    } catch (e) {
      pushToast(e.message || "Could not disconnect", "red");
    }
    setConfirm(null);
  };

  // Select fields display their first option as the default without writing
  // it into form state — fold those defaults in so they are actually saved.
  const buildPayload = (fields) => ({
    ...Object.fromEntries(
      fields
        .filter((f) => f.type === "select" && f.options?.length)
        .map((f) => [f.key, f.options[0]]),
    ),
    ...form,
  });

  // PHASE 1 — check the credentials against the real provider (AWS STS,
  // Microsoft Entra ID, Google OAuth, the cluster's /version) WITHOUT storing
  // anything. Nothing is created until the user confirms the result, so
  // closing the dialog here leaves no connection behind.
  const handleVerify = async () => {
    const fields = getFormFields(selected.id);
    const er = {};
    fields
      .filter((f) => f.required)
      .forEach((f) => {
        if (!form[f.key] || !form[f.key].trim()) er[f.key] = "Required";
      });
    if (Object.keys(er).length > 0) {
      setErrors(er);
      return;
    }
    setErrors({});
    setVerifyState("verifying");
    let outcome;
    try {
      outcome = await api.verifyCloudCredentials(
        selected.id,
        JSON.stringify(buildPayload(fields)),
      );
    } catch (e) {
      // An unreachable runtime must not block saving — the credentials may
      // be perfectly good and can be re-checked from the table later.
      outcome = {
        supported: true,
        verified: false,
        unreachable: true,
        message: e.message || "Verification runtime unreachable — not checked",
      };
    }
    setVerifiedDetails(outcome);
    setVerifyState("verified");
  };

  // PHASE 2 — the user accepted the result: now store it. The connection is
  // re-verified server-side afterwards so the durable badge reflects a real
  // check rather than the client's word for it.
  const handleSave = async () => {
    const fields = getFormFields(selected.id);
    const payload = buildPayload(fields);
    const assignedId =
      assignedProject === "global" ? null : Number(assignedProject);
    setVerifyState("saving");
    try {
      let id;
      if (updateItem) {
        id = updateItem.id;
        await api.updateCloudCredentials(id, JSON.stringify(payload));
        const prev =
          updateItem.projectId != null ? Number(updateItem.projectId) : null;
        if (prev !== assignedId) {
          await api.assignCloudConnection(id, assignedId);
        }
      } else {
        const hint =
          verifiedDetails?.accountName || verifiedDetails?.accountId ||
          payload.region || payload.subscriptionId || payload.projectId ||
          payload.tenantId || "account";
        const created = await api.createCloudConnection({
          platform: selected.id,
          name: `${selected.name} · ${hint}`,
          credentials: JSON.stringify(payload),
          projectId: assignedId,
        });
        id = created.id;
      }
      // Records the outcome on the row; a failure here is not fatal — the
      // credentials are stored and the row-level Verify button can retry.
      await api.verifyCloudConnection(id).catch(() => {});
      pushToast(
        `${selected.name} ${updateItem ? "updated" : "connected"}`,
        "emerald",
      );
      await load();
      close();
    } catch (e) {
      setVerifyState("verified");
      pushToast(e.message || "Could not save the connection", "red");
    }
  };

  // Row-level re-check from the table (e.g. after rotating keys provider-side).
  const handleRowVerify = async (r) => {
    try {
      const outcome = await api.verifyCloudConnection(r.id);
      pushToast(
        outcome.verified
          ? `Verified: ${outcome.message}`
          : outcome.supported
            ? `Verification failed: ${outcome.message}`
            : outcome.message,
        outcome.verified ? "emerald" : outcome.supported ? "red" : "amber",
      );
      await load();
    } catch (e) {
      pushToast(e.message || "Could not verify", "red");
    }
  };

  return (
    <div className="animate-fade-up">
      <PageHeader
        title="Cloud Integrations"
        subtitle="Connect your cloud accounts once, then assign them to projects"
        actions={
          <SmallButton
            icon="plus"
            variant="primary"
            onClick={() => setModal(true)}
          >
            Add New Platform
          </SmallButton>
        }
      />

      {!loaded ? (
        <Card>
          <div className="p-6 text-sm text-slate-500">Loading connections…</div>
        </Card>
      ) : loadFailed && clouds.length === 0 ? (
        <Card>
          <div className="p-6 text-sm text-amber-700" role="alert">
            Could not load this workspace&rsquo;s cloud connections. This is not the
            same as having none — try again rather than reconnecting anything.
          </div>
        </Card>
      ) : clouds.length === 0 ? (
        <div className="grid gap-6 sm:grid-cols-2 lg:grid-cols-3">
          {cloudPlatforms.map((p) => (
            <div
              key={p.id}
              className="flex flex-col rounded-2xl border border-slate-200 bg-slate-50 p-6 transition hover:border-blue-500"
            >
              <div className="mb-4 flex items-start justify-between">
                <div className="flex h-12 items-center">
                  <CloudLogo platform={p} size={40} />
                </div>
                <span className="rounded bg-slate-50 px-2 py-1 text-[10px] font-bold tracking-wider text-slate-500">
                  NOT CONFIGURED
                </span>
              </div>
              <h3 className="text-lg font-semibold text-slate-900">{p.name}</h3>
              <p className="mt-1 flex-1 text-sm text-slate-500">{p.desc}</p>
              <button
                onClick={() => {
                  setModal(true);
                  handleSelect(p);
                }}
                className="mt-6 w-full rounded-lg bg-slate-50 py-2.5 text-sm font-semibold text-slate-900 transition hover:bg-blue-600 hover:text-white"
              >
                Configure Credentials
              </button>
            </div>
          ))}
        </div>
      ) : (
        <Table
          columns={[
            {
              key: "platform",
              label: "Platform",
              render: (r) => {
                const pf = platformById(r.platform);
                return (
                  <div className="flex items-center gap-3">
                    <span className="flex h-10 w-10 items-center justify-center rounded-lg border border-slate-200 bg-white">
                      <CloudLogo platform={pf} size={24} />
                    </span>
                    <span className="font-medium text-slate-900">
                      {pf.name}
                    </span>
                  </div>
                );
              },
            },
            {
              key: "account",
              label: "Account",
              render: (r) =>
                r.account || r.accountName ? (
                  <div className="leading-tight" title={r.name}>
                    {r.accountName && (
                      <p className="font-medium text-slate-900">
                        {r.accountName}
                      </p>
                    )}
                    {r.account && (
                      <p className="font-mono text-[11px] text-slate-500">
                        {r.account}
                      </p>
                    )}
                    {!r.accountName && (
                      <p className="text-[11px] text-slate-500">
                        Verify to fetch the account name
                      </p>
                    )}
                  </div>
                ) : (
                  <span className="text-xs text-slate-500">
                    {r.hasCredentials ? "—" : "No credentials"}
                  </span>
                ),
            },
            {
              key: "region",
              label: "Region",
              render: (r) => (
                <span
                  className={
                    r.region ? "text-slate-700" : "text-xs text-slate-500"
                  }
                  title={r.region ? undefined : "This platform is not region-scoped"}
                >
                  {r.region || "—"}
                </span>
              ),
            },
            {
              key: "project",
              label: "Project",
              render: (r) => (
                <button
                  onClick={() => {
                    setAssignItem(r);
                    setAssignValue(
                      r.projectId != null ? String(r.projectId) : "global",
                    );
                  }}
                  title="Change project assignment"
                  className="group inline-flex items-center gap-1.5"
                >
                  {r.projectId != null ? (
                    <Chip>{projectName(r.projectId)}</Chip>
                  ) : (
                    <span className="text-xs text-slate-600">All projects</span>
                  )}
                  <Icon
                    name="pencil"
                    size={13}
                    className="text-slate-400 transition group-hover:text-slate-700"
                  />
                </button>
              ),
            },
            {
              key: "status",
              label: "Status",
              render: (r) => <StatusBadge status={r.status} />,
            },
            {
              key: "verified",
              label: "Verified",
              render: (r) => (
                <span
                  title={
                    r.verifiedMessage ||
                    "Not checked against the provider yet"
                  }
                  className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-[11px] font-semibold ${
                    r.verifiedOk === true
                      ? "bg-emerald-500/10 text-emerald-600"
                      : r.verifiedOk === false
                        ? "bg-red-500/10 text-red-600"
                        : "bg-slate-100 text-slate-500"
                  }`}
                >
                  <span
                    className={`h-1.5 w-1.5 rounded-full ${
                      r.verifiedOk === true
                        ? "bg-emerald-500"
                        : r.verifiedOk === false
                          ? "bg-red-500"
                          : "bg-slate-400"
                    }`}
                  />
                  {r.verifiedOk === true
                    ? "Verified"
                    : r.verifiedOk === false
                      ? "Failed"
                      : "Not checked"}
                </span>
              ),
            },
            {
              key: "act",
              label: "",
              render: (r) => (
                <div className="flex items-center justify-end gap-3">
                  <button
                    onClick={() => handleRowVerify(r)}
                    className="rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-xs font-semibold text-slate-700 transition hover:border-blue-500 hover:bg-slate-50"
                  >
                    Verify
                  </button>
                  <button
                    onClick={() => handleUpdate(r)}
                    className="rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-xs font-semibold text-slate-700 transition hover:border-blue-600 hover:bg-blue-600 hover:text-white"
                  >
                    Update Credentials
                  </button>
                  <button
                    onClick={() => setConfirm(r)}
                    className="text-slate-500 transition hover:text-red-600"
                    aria-label={`Disconnect ${platformById(r.platform).name}`}
                    title="Disconnect"
                  >
                    <Icon name="trash" size={16} />
                  </button>
                </div>
              ),
            },
          ]}
          rows={clouds}
        />
      )}

      {modal &&
        createPortal(
          <div className="fixed inset-0 z-[100] flex items-center justify-center p-4">
            <div
              className="absolute inset-0 bg-slate-900/25 backdrop-blur-md"
              onClick={() => {
                if (verifyState !== "verifying" && verifyState !== "saving") {
                  close();
                }
              }}
            />
            {/* Capped height with only the middle scrolling, so the action
                buttons stay reachable no matter how many credential fields a
                platform needs. */}
            <div
              className={`relative flex max-h-[90vh] w-full flex-col animate-fade-up rounded-2xl border border-slate-200 bg-white shadow-2xl ${
                selected ? "max-w-4xl" : "max-w-2xl"
              }`}
            >
              <div className="flex shrink-0 items-start justify-between border-b border-slate-200 p-6 pb-4">
                <div>
                  <h2 className="text-lg font-semibold text-slate-900">
                    {selected ? `${updateItem ? 'Update' : 'Connect'} ${selected.name}` : "Add New Platform"}
                  </h2>
                  <p className="mt-0.5 text-sm text-slate-500">
                    {selected
                      ? "Enter credentials to configure this platform"
                      : "Select a platform type to configure"}
                  </p>
                </div>
                <button
                  onClick={close}
                  className="text-slate-500 transition hover:text-slate-900"
                >
                  <Icon name="logout" size={18} className="rotate-180" />
                </button>
              </div>

              <div className="flex-1 overflow-y-auto p-6">
              {!selected ? (
                <div className="grid gap-3 sm:grid-cols-3">
                  {cloudPlatforms.map((p) => (
                    <button
                      key={p.id}
                      onClick={() => handleSelect(p)}
                      className="group rounded-xl border border-slate-200 bg-slate-50 p-4 text-left transition hover:-translate-y-0.5 hover:border-blue-500 hover:bg-slate-100"
                    >
                      <div className="flex h-12 items-center justify-start transition group-hover:scale-105">
                        <CloudLogo platform={p} size={36} />
                      </div>
                      <p className="mt-3 text-sm font-semibold text-slate-900">
                        {p.name}
                      </p>
                      <p className="mt-0.5 text-xs text-slate-500">{p.desc}</p>
                    </button>
                  ))}
                </div>
              ) : (
                <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_20rem]">
                <div className="grid gap-4 sm:grid-cols-2">
                  {getFormFields(selected.id).map((f) => (
                    <div
                      key={f.key}
                      className={f.type === "textarea" ? "sm:col-span-2" : ""}
                    >
                      <label className="mb-1.5 flex items-center gap-1 text-xs font-medium text-slate-500">
                        {f.label}{" "}
                        {f.required && <span className="text-red-500">*</span>}
                      </label>
                      {f.type === "select" ? (
                        <DropdownSelect
                          value={form[f.key] || (f.options ? f.options[0] : "")}
                          options={f.options || []}
                          disabled={verifyState !== "idle"}
                          error={!!errors[f.key]}
                          onChange={(v) => {
                            setForm({ ...form, [f.key]: v });
                            if (errors[f.key])
                              setErrors({ ...errors, [f.key]: undefined });
                          }}
                        />
                      ) : f.type === "textarea" ? (
                        <textarea
                          rows={3}
                          value={form[f.key] || ""}
                          onChange={(e) => {
                            setForm({ ...form, [f.key]: e.target.value });
                            if (errors[f.key])
                              setErrors({ ...errors, [f.key]: undefined });
                          }}
                          className={`w-full rounded-lg border bg-slate-50 px-4 py-2.5 text-sm text-slate-900 outline-none transition ${errors[f.key] ? "border-red-500/50" : "border-slate-200 focus:border-slate-300 focus:ring-2 focus:ring-slate-300"}`}
                          disabled={verifyState !== "idle"}
                        />
                      ) : (
                        <input
                          type={f.type || "text"}
                          value={form[f.key] || ""}
                          onChange={(e) => {
                            setForm({ ...form, [f.key]: e.target.value });
                            if (errors[f.key])
                              setErrors({ ...errors, [f.key]: undefined });
                          }}
                          className={`w-full rounded-lg border bg-slate-50 px-4 py-2.5 text-sm text-slate-900 outline-none transition ${errors[f.key] ? "border-red-500/50" : "border-slate-200 focus:border-slate-300 focus:ring-2 focus:ring-slate-300"}`}
                          disabled={verifyState !== "idle"}
                        />
                      )}
                      {errors[f.key] && (
                        <p className="mt-1 text-xs text-red-600">
                          {errors[f.key]}
                        </p>
                      )}
                    </div>
                  ))}

                  <div className="sm:col-span-2">
                    <label className="mb-1.5 flex items-center gap-1 text-xs font-medium text-slate-500">
                      Assign to
                    </label>
                    <DropdownSelect
                      value={assignedProject}
                      options={[
                        { value: "global", label: "Globally (All projects)" },
                        ...projects.map((p) => ({
                          value: String(p.id),
                          label: p.name,
                        })),
                      ]}
                      onChange={setAssignedProject}
                    />
                  </div>
                </div>

                {/* Right rail: the provider's answer, beside the form rather
                    than stacked under it so it never pushes the buttons away. */}
                <div className="lg:sticky lg:top-0 lg:self-start">
                  {verifyState !== "verified" || !verifiedDetails ? (
                    <div className="rounded-lg border border-dashed border-slate-200 bg-slate-50 p-4">
                      <p className="text-xs font-medium uppercase tracking-wider text-slate-500">
                        Verification
                      </p>
                      <p className="mt-2 text-sm text-slate-600">
                        {verifyState === "verifying"
                          ? "Asking the provider to confirm these credentials..."
                          : "The account details reported by the provider appear here once you verify. Nothing is saved until you confirm."}
                      </p>
                    </div>
                  ) : (
                    <div
                      className={`rounded-lg border p-4 animate-fade-up ${
                        verifiedDetails.verified
                          ? "border-emerald-500/30 bg-emerald-500/10"
                          : verifiedDetails.supported
                            ? "border-red-500/30 bg-red-500/10"
                            : "border-amber-500/30 bg-amber-500/10"
                      }`}
                    >
                      <div className="flex items-center gap-2 mb-1.5">
                        <Icon
                          name={verifiedDetails.verified ? "check" : "warning"}
                          size={16}
                          className={
                            verifiedDetails.verified
                              ? "text-emerald-600"
                              : verifiedDetails.supported
                                ? "text-red-600"
                                : "text-amber-600"
                          }
                        />
                        <p
                          className={`text-sm font-semibold ${
                            verifiedDetails.verified
                              ? "text-emerald-600"
                              : verifiedDetails.supported
                                ? "text-red-600"
                                : "text-amber-600"
                          }`}
                        >
                          {verifiedDetails.verified
                            ? "Credentials verified with the provider"
                            : verifiedDetails.supported
                              ? "The provider rejected these credentials"
                              : "No live check for this platform yet"}
                        </p>
                      </div>
                      <p className="text-xs text-slate-600">
                        {verifiedDetails.message}
                      </p>

                      {/* Everything the provider told us about the account —
                          not just its id. */}
                      {Object.keys(verifiedDetails.details || {}).length > 0 && (
                        <dl className="mt-3 space-y-2 border-t border-slate-900/10 pt-3">
                          {Object.entries(verifiedDetails.details).map(
                            ([label, value]) => (
                              <div key={label}>
                                <dt className="text-[10px] font-medium uppercase tracking-wider text-slate-500">
                                  {label}
                                </dt>
                                <dd
                                  className="break-words text-xs text-slate-900"
                                  title={value}
                                >
                                  {value}
                                </dd>
                              </div>
                            ),
                          )}
                        </dl>
                      )}

                      <p className="mt-3 border-t border-slate-900/10 pt-3 text-[11px] text-slate-500">
                        {verifiedDetails.verified
                          ? "Nothing has been saved yet — confirm to add this connection."
                          : "Nothing has been saved. Edit the credentials, or save them anyway and fix them later."}
                      </p>
                    </div>
                  )}
                </div>
                </div>
              )}
              </div>

              <div className="flex shrink-0 justify-end gap-3 border-t border-slate-200 p-6 pt-5">
                <button
                  onClick={
                    verifyState === "verified"
                      ? () => {
                          // Back to editing: the result no longer describes
                          // whatever is about to be typed.
                          setVerifyState("idle");
                          setVerifiedDetails(null);
                        }
                      : selected
                        ? () => handleSelect(null)
                        : close
                  }
                  disabled={verifyState === "verifying" || verifyState === "saving"}
                  className="rounded-lg border border-slate-200 px-4 py-2 text-sm font-semibold text-slate-900 transition hover:bg-slate-100 disabled:opacity-50"
                >
                  {verifyState === "verified"
                    ? "Edit credentials"
                    : selected
                      ? "Back"
                      : "Cancel"}
                </button>

                {selected && (
                  <SmallButton
                    icon={
                      verifyState === "verified"
                        ? "check"
                        : verifyState === "verifying" || verifyState === "saving"
                          ? "pulse"
                          : "bolt"
                    }
                    variant="primary"
                    onClick={
                      verifyState === "verified" ? handleSave : handleVerify
                    }
                    disabled={
                      verifyState === "verifying" || verifyState === "saving"
                    }
                  >
                    {verifyState === "verifying"
                      ? "Checking with provider..."
                      : verifyState === "saving"
                        ? "Saving..."
                        : verifyState === "verified"
                          ? verifiedDetails?.verified
                            ? updateItem
                              ? "Save changes"
                              : "Add connection"
                            : "Save anyway"
                          : "Verify credentials"}
                  </SmallButton>
                )}
              </div>
            </div>
          </div>,
          document.body,
        )}

      {assignItem &&
        createPortal(
          <div className="fixed inset-0 z-[100] flex items-center justify-center p-4">
            <div
              className="absolute inset-0 bg-slate-900/25 backdrop-blur-md"
              onClick={() => !assignSaving && setAssignItem(null)}
            />
            <div className="relative w-full max-w-md animate-fade-up rounded-2xl border border-slate-200 bg-white p-6 shadow-2xl">
              <h2 className="text-lg font-semibold text-slate-900">
                Assign {platformById(assignItem.platform).name}
              </h2>
              <p className="mt-0.5 text-sm text-slate-500">
                A connection assigned to a project appears only on that
                project. Global connections are available everywhere.
              </p>
              <div className="mt-5">
                <label className="mb-1.5 flex items-center gap-1 text-xs font-medium text-slate-500">
                  Assign to
                </label>
                <DropdownSelect
                  value={assignValue}
                  options={[
                    { value: "global", label: "Globally (All projects)" },
                    ...projects.map((p) => ({
                      value: String(p.id),
                      label: p.name,
                    })),
                  ]}
                  onChange={setAssignValue}
                  disabled={assignSaving}
                />
              </div>
              <div className="mt-8 flex justify-end gap-3 border-t border-slate-200 pt-5">
                <button
                  onClick={() => setAssignItem(null)}
                  disabled={assignSaving}
                  className="rounded-lg border border-slate-200 px-4 py-2 text-sm font-semibold text-slate-900 transition hover:bg-slate-100 disabled:opacity-50"
                >
                  Cancel
                </button>
                <SmallButton
                  icon={assignSaving ? "pulse" : "check"}
                  variant="primary"
                  onClick={saveAssignment}
                  disabled={assignSaving}
                >
                  {assignSaving ? "Saving..." : "Save assignment"}
                </SmallButton>
              </div>
            </div>
          </div>,
          document.body,
        )}

      <ConfirmModal
        open={!!confirm}
        title="Disconnect cloud account?"
        message={
          confirm
            ? `${platformById(confirm.platform).name} (${confirm.account}) will be removed and unassigned from all projects. You can reconnect it later.`
            : ""
        }
        confirmLabel="Disconnect"
        onConfirm={disconnect}
        onClose={() => setConfirm(null)}
      />
    </div>
  );
}
