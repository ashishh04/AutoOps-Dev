import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

/**
 * Correlation rules, as a customer's own screen.
 *
 * <p>Two things here are load-bearing. The empty state must not read as a
 * finished screen — with no rule, the Incidents page can never show anything,
 * and a customer watching an empty Incidents page concludes the product is
 * broken rather than unconfigured. And the form must send VALUES, never an
 * expression: the server writes the matcher with the caller's own workspace
 * ANDed in, and a console that invented its own would be authoring a boundary.
 */
const storeState = { pushToast: vi.fn() };
vi.mock("../../store/store", async () => {
  const actual = await vi.importActual("../../store/store");
  return { ...actual, useStore: () => storeState };
});

const incidentsMock = {
  listCorrelationRules: vi.fn(),
  getCorrelationVocabulary: vi.fn(),
  createCorrelationRule: vi.fn(),
  deleteCorrelationRule: vi.fn(),
};
vi.mock("../../lib/incidents", () => incidentsMock);

const apiMock = { listProjects: vi.fn() };
vi.mock("../../lib/api", () => ({ api: apiMock }));

const { default: CorrelationRules } = await import("./CorrelationRules");

const renderPage = (url = "/app/incidents/rules") =>
  render(
    <MemoryRouter initialEntries={[url]}>
      <Routes>
        <Route path="/app/incidents/rules" element={<CorrelationRules />} />
      </Routes>
    </MemoryRouter>,
  );

const rule = (over = {}) => ({
  id: "r1",
  label: "payments-outage",
  projectId: "7",
  groupBy: ["service"],
  windowSeconds: 600,
  expression: '(labels.autoops_tenant == "acme") && (severity == "critical")',
  createdAt: "2026-09-24T10:00:00",
  ...over,
});

beforeEach(() => {
  storeState.pushToast.mockClear();
  incidentsMock.listCorrelationRules.mockResolvedValue([]);
  incidentsMock.getCorrelationVocabulary.mockResolvedValue({
    fields: [
      { value: "severity", label: "Severity" },
      { value: "service", label: "Service" },
      { value: "source", label: "Source" },
    ],
    severities: ["critical", "high", "warning", "info", "low"],
    defaultWindowMinutes: 10,
  });
  incidentsMock.createCorrelationRule.mockResolvedValue({});
  apiMock.listProjects.mockResolvedValue([{ id: 7, name: "Platform" }]);
});

describe("CorrelationRules", () => {
  it("does not let an empty list read as a finished screen", async () => {
    // The whole point. "No rules yet" alone looks tidy and done; it is not,
    // because with no rule the Incidents page can never show anything.
    renderPage();

    const empty = await screen.findByText(/No correlation rules yet/i);
    expect(empty).toBeTruthy();
    expect(screen.getByText(/Incidents page will stay empty/i)).toBeTruthy();
  });

  it("reads the whole workspace by default and narrows on request", async () => {
    renderPage();
    await waitFor(() =>
      expect(incidentsMock.listCorrelationRules).toHaveBeenCalledWith(undefined),
    );

    renderPage("/app/incidents/rules?project=7");
    await waitFor(() =>
      expect(incidentsMock.listCorrelationRules).toHaveBeenCalledWith("7"),
    );
  });

  it("shows the expression a rule matches on", async () => {
    // "Why are these grouped?" is the first question anyone asks of a
    // correlated view. A rule nobody can inspect is a grouping nobody trusts.
    incidentsMock.listCorrelationRules.mockResolvedValue([rule()]);
    renderPage();

    await waitFor(() => expect(screen.getByText("payments-outage")).toBeTruthy());
    expect(
      screen.getByText(/labels\.autoops_tenant == "acme"/),
    ).toBeTruthy();
  });

  it("sends VALUES, never an expression", async () => {
    renderPage();
    await screen.findByText(/No correlation rules yet/i);
    fireEvent.click(screen.getByText("New rule"));
    await screen.findByText("New correlation rule");

    fireEvent.change(screen.getByLabelText("Name"), {
      target: { value: "Payments outage" },
    });
    fireEvent.click(screen.getByText("Create rule"));

    await waitFor(() =>
      expect(incidentsMock.createCorrelationRule).toHaveBeenCalledWith(
        "7",
        expect.objectContaining({
          label: "Payments outage",
          conditions: [{ field: "severity", values: ["critical"] }],
          groupBy: ["service"],
          windowMinutes: 10,
        }),
      ),
    );
    // Nothing resembling a matcher is composed client-side.
    const body = incidentsMock.createCorrelationRule.mock.calls[0][1];
    expect(JSON.stringify(body)).not.toMatch(/autoops_tenant|&&|==/);
  });

  it("splits a multi-value condition rather than sending one string", async () => {
    // The server takes a list. A comma-separated string arriving there would
    // make it guess at a delimiter for values that may contain one.
    renderPage();
    await screen.findByText(/No correlation rules yet/i);
    fireEvent.click(screen.getByText("New rule"));
    await screen.findByText("New correlation rule");

    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "r" } });
    fireEvent.change(screen.getByLabelText("Condition 1 field"), {
      target: { value: "service" },
    });
    fireEvent.change(screen.getByLabelText("Condition 1 value"), {
      target: { value: "checkout-api, payments-api ," },
    });
    fireEvent.click(screen.getByText("Create rule"));

    await waitFor(() =>
      expect(incidentsMock.createCorrelationRule).toHaveBeenCalledWith(
        "7",
        expect.objectContaining({
          conditions: [
            { field: "service", values: ["checkout-api", "payments-api"] },
          ],
        }),
      ),
    );
  });

  it("cannot create a rule with nothing to group by", async () => {
    // Without it every matching alert becomes its own incident — a feed with
    // extra steps.
    renderPage();
    await screen.findByText(/No correlation rules yet/i);
    fireEvent.click(screen.getByText("New rule"));
    await screen.findByText("New correlation rule");

    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "r" } });
    fireEvent.click(screen.getByLabelText("Service"));

    expect(screen.getByText("Create rule").closest("button")).toBeDisabled();
  });

  it("does not claim 'no rules' when the request failed", async () => {
    // An empty list on error is indistinguishable from genuinely having none,
    // and the customer goes and creates a duplicate.
    incidentsMock.listCorrelationRules.mockRejectedValue(new Error("engine down"));
    renderPage();

    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("engine down"));
    expect(screen.queryByText(/No correlation rules yet/i)).toBeNull();
  });
});
