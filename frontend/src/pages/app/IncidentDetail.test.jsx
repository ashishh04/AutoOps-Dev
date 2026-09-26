import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

/**
 * An incident carries no tenant label — it is a correlation over alerts — so
 * visibility is inferred: yours if at least one of its alerts is. The server
 * now filters the evidence inside it, which means this page can show fewer
 * alerts than the incident claims to have.
 *
 * That discrepancy is the thing to get right. "Alerts: 4" above a list of one
 * is indistinguishable from a bug in AutoOps unless the page explains itself —
 * and the explanation must say HOW MANY without ever saying whose.
 */
const storeState = { pushToast: vi.fn() };
vi.mock("../../store/store", async () => {
  const actual = await vi.importActual("../../store/store");
  return { ...actual, useStore: () => storeState };
});

const incidentsMock = {
  getIncident: vi.fn(),
  getIncidentCapabilities: vi.fn(),
  setIncidentStatus: vi.fn(),
  commentOnIncident: vi.fn(),
  assignIncident: vi.fn(),
  investigateIncident: vi.fn(),
  getInvestigation: vi.fn(),
};
vi.mock("../../lib/incidents", () => incidentsMock);

const { default: IncidentDetail } = await import("./IncidentDetail");

const renderPage = () =>
  render(
    <MemoryRouter initialEntries={["/app/incidents/inc-1"]}>
      <Routes>
        <Route path="/app/incidents/:id" element={<IncidentDetail />} />
      </Routes>
    </MemoryRouter>,
  );

const detail = (over = {}) => ({
  incident: {
    id: "inc-1",
    name: "checkout-api degraded",
    summary: "Error rate above 5%",
    severity: "critical",
    status: "firing",
    alertCount: 4,
    services: ["checkout-api"],
    sources: ["prometheus"],
    correlatedBy: "same service, 10m window",
    investigated: false,
  },
  evidence: [
    {
      fingerprint: "mine",
      name: "HighErrorRate",
      description: "5xx over threshold",
      severity: "critical",
      service: "checkout-api",
      lastReceived: "2026-09-24T10:00:00Z",
    },
  ],
  withheldEvidence: 0,
  investigation: null,
  ...over,
});

beforeEach(() => {
  storeState.pushToast.mockClear();
  incidentsMock.getIncidentCapabilities.mockResolvedValue({
    investigation: true,
    clusterEngine: true,
  });
  incidentsMock.getIncident.mockResolvedValue(detail());
});

describe("IncidentDetail evidence", () => {
  it("says nothing extra when the incident is wholly this workspace's", async () => {
    // The normal case, and it must stay quiet. A warning on every incident is
    // a warning nobody reads by the third one.
    renderPage();

    await waitFor(() => expect(screen.getByText("HighErrorRate")).toBeTruthy());
    expect(screen.queryByText(/not visible in this workspace/i)).toBeNull();
  });

  it("explains a short evidence list rather than letting it read as a bug", async () => {
    incidentsMock.getIncident.mockResolvedValue(detail({ withheldEvidence: 3 }));
    renderPage();

    const note = await screen.findByText(/not visible in this workspace/i);
    expect(note.textContent).toMatch(/3 alerts are part of this incident/i);
    // And says why the investigate button will not work, so the next click is
    // not a surprise.
    expect(note.textContent).toMatch(/investigation cannot run/i);
  });

  it("does NOT name the other workspace", async () => {
    // The reader is entitled to know the page is incomplete. They are not
    // entitled to learn who they are sharing the incident with.
    incidentsMock.getIncident.mockResolvedValue(
      detail({
        withheldEvidence: 1,
        evidence: [
          {
            fingerprint: "mine",
            name: "HighErrorRate",
            description: "5xx over threshold",
            severity: "critical",
            service: "checkout-api",
            lastReceived: "2026-09-24T10:00:00Z",
          },
        ],
      }),
    );
    renderPage();

    const note = await screen.findByText(/not visible in this workspace/i);
    expect(note.textContent).not.toMatch(/tenant|workspace .*globex|belongs to/i);
    expect(note.textContent).toMatch(/^1 alert is part of this incident/i);
  });

  it("still lists the alerts that ARE this workspace's", async () => {
    // Withholding must not become hiding. The alerts they own are the reason
    // they opened the page.
    incidentsMock.getIncident.mockResolvedValue(detail({ withheldEvidence: 2 }));
    renderPage();

    await waitFor(() => expect(screen.getByText("HighErrorRate")).toBeTruthy());
    expect(screen.getByText("5xx over threshold")).toBeTruthy();
  });
});
