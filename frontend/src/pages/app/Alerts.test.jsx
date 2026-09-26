import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

// The alerts list. Three things about this page are load-bearing and easy to
// regress: it reads the WHOLE workspace unless asked to narrow, it must sort by
// how bad the alert is rather than when it arrived, and its empty state must
// never be read as "everything is fine".

const navigate = vi.fn();
vi.mock("react-router-dom", async () => {
  const actual = await vi.importActual("react-router-dom");
  return { ...actual, useNavigate: () => navigate };
});

const listAlerts = vi.fn();
vi.mock("../../lib/alerts", () => ({ listAlerts: (...a) => listAlerts(...a) }));

// The project picker loads the workspace's projects. Irrelevant to every
// assertion here, and left unmocked it reaches the real client.
vi.mock("../../lib/api", () => ({ api: { listProjects: () => Promise.resolve([]) } }));

const { default: Alerts } = await import("./Alerts");

const renderPage = (url = "/app/alerts") =>
  render(
    <MemoryRouter initialEntries={[url]}>
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

  it("asks for the whole workspace by default", async () => {
    // The change this pins. Alerts arrive from a monitoring tool that has never
    // heard of an AutoOps project, so requiring one before anything could be
    // read meant connecting the same Datadog account in every project and then
    // guessing which one to look in.
    listAlerts.mockResolvedValue([]);
    renderPage();
    await waitFor(() =>
      expect(listAlerts).toHaveBeenCalledWith(
        expect.objectContaining({ projectId: undefined }),
      ),
    );
  });

  it("narrows to a project when the URL asks for one", async () => {
    listAlerts.mockResolvedValue([]);
    renderPage("/app/alerts?project=7");
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

    const empty = await screen.findByText(/No alerts have arrived yet/i);
    expect(empty).toBeTruthy();
    // An empty alert plane and an unconnected one look identical from here.
    // Copy that reads as reassurance is the failure being guarded against, so
    // the empty state must point at the next action instead.
    expect(empty.textContent).toMatch(/connect a monitoring source/i);
    expect(empty.textContent).not.toMatch(/all clear|healthy|no issues|you're good/i);
  });

  it("a FILTERED empty state offers to widen rather than to connect", async () => {
    // Different question, different answer. Telling someone to connect a source
    // when they have several and have simply filtered to the wrong project
    // sends them to buy something they already own.
    listAlerts.mockResolvedValue([]);
    renderPage("/app/alerts?project=7");

    const empty = await screen.findByText(/No alerts are matched to this project/i);
    expect(empty.textContent).toMatch(/clear the project filter/i);
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
