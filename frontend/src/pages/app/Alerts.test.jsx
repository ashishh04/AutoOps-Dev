import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

// The alerts list. Two things about this page are load-bearing and easy to
// regress: it must sort by how bad the alert is rather than when it arrived,
// and its empty state must never be read as "everything is fine".

const navigate = vi.fn();
vi.mock("react-router-dom", async () => {
  const actual = await vi.importActual("react-router-dom");
  return { ...actual, useNavigate: () => navigate, useParams: () => ({ pid: "7" }) };
});

const listAlerts = vi.fn();
vi.mock("../../lib/alerts", () => ({ listAlerts: (...a) => listAlerts(...a) }));

const { default: Alerts } = await import("./Alerts");

const renderPage = () =>
  render(
    <MemoryRouter>
      <Alerts />
    </MemoryRouter>,
  );

const alert = (name, severity) => ({
  fingerprint: `fp-${name}`,
  name,
  severity,
  status: "firing",
  source: ["prometheus"],
  service: "payments",
  receivedAt: "2026-09-18T10:00:00Z",
});

describe("Alerts", () => {
  beforeEach(() => {
    listAlerts.mockReset();
    navigate.mockReset();
  });

  it("scopes the request to the open project", async () => {
    listAlerts.mockResolvedValue([]);
    renderPage();
    await waitFor(() =>
      expect(listAlerts).toHaveBeenCalledWith(
        expect.objectContaining({ projectId: "7" }),
      ),
    );
  });

  it("orders by severity, worst first — not by arrival", async () => {
    listAlerts.mockResolvedValue([
      alert("info-one", "info"),
      alert("critical-one", "critical"),
      alert("warning-one", "warning"),
    ]);
    renderPage();

    await waitFor(() => expect(screen.getByText("critical-one")).toBeTruthy());
    const rendered = screen.getAllByText(/-one$/).map((n) => n.textContent);
    expect(rendered).toEqual(["critical-one", "warning-one", "info-one"]);
  });

  it("an unknown severity sorts last rather than first", async () => {
    // A monitoring tool nobody anticipated sends severity: "sev1". It must not
    // outrank a real critical just because the string is unrecognised.
    listAlerts.mockResolvedValue([
      alert("weird-one", "sev1"),
      alert("critical-one", "critical"),
    ]);
    renderPage();

    await waitFor(() => expect(screen.getByText("critical-one")).toBeTruthy());
    const rendered = screen.getAllByText(/-one$/).map((n) => n.textContent);
    expect(rendered).toEqual(["critical-one", "weird-one"]);
  });

  it("the empty state does NOT claim the estate is healthy", async () => {
    listAlerts.mockResolvedValue([]);
    renderPage();

    const empty = await screen.findByText(/No alerts are matched to this project/i);
    expect(empty).toBeTruthy();
    // An empty alert plane and an unconnected one look identical from here.
    // Copy that reads as reassurance is the failure being guarded against, so
    // the empty state must point at the next action instead.
    expect(empty.textContent).toMatch(/connect a monitoring source/i);
    expect(empty.textContent).not.toMatch(/all clear|healthy|no issues|you're good/i);
  });

  it("surfaces a load failure instead of rendering an empty list", async () => {
    listAlerts.mockRejectedValue(new Error("The alert engine is not responding"));
    renderPage();
    await waitFor(() =>
      expect(screen.getByText(/alert engine is not responding/i)).toBeTruthy(),
    );
  });
});
