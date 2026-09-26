import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

/**
 * Monitoring sources moved from the project to the workspace.
 *
 * The asymmetry is what this file exists to pin. READING is workspace-wide, so
 * a customer sees every source they have connected in one place instead of
 * hunting through projects. CONNECTING is not, and cannot be: a connection
 * mints an ingest token that stamps a project onto every alert arriving through
 * it, so there is no such thing as a workspace-wide source. Where the project
 * is not already implied, the page has to ASK rather than pick one — filing
 * somebody's whole Datadog feed under whichever project sorted first is a
 * mistake they would only discover much later.
 */
const storeState = { pushToast: vi.fn() };
vi.mock("../../store/store", async () => {
  const actual = await vi.importActual("../../store/store");
  return { ...actual, useStore: () => storeState };
});

const alertsMock = {
  listProviderTypes: vi.fn(),
  listConnectedProviders: vi.fn(),
  connectProvider: vi.fn(),
  disconnectProvider: vi.fn(),
  getProviderSetup: vi.fn(),
};
vi.mock("../../lib/alerts", () => alertsMock);

const apiMock = { listProjects: vi.fn() };
vi.mock("../../lib/api", () => ({ api: apiMock }));

const { default: AlertProviders } = await import("./AlertProviders");

const renderPage = (url = "/app/alerts/sources") =>
  render(
    <MemoryRouter initialEntries={[url]}>
      <Routes>
        <Route path="/app/alerts/sources" element={<AlertProviders />} />
      </Routes>
    </MemoryRouter>,
  );

const DATADOG = {
  type: "datadog",
  displayName: "Datadog",
  categories: ["Monitoring"],
  tags: ["alert"],
  supportsWebhook: true,
  comingSoon: false,
  fields: [],
  scopes: [],
};

beforeEach(() => {
  storeState.pushToast.mockClear();
  alertsMock.listProviderTypes.mockResolvedValue([DATADOG]);
  alertsMock.listConnectedProviders.mockResolvedValue([]);
  alertsMock.connectProvider.mockResolvedValue({});
  alertsMock.getProviderSetup.mockResolvedValue({
    url: "https://autoops.example/api/alerts/ingest/datadog",
    token: "tok",
    instructions: "Paste this.",
    pushOnly: true,
  });
  apiMock.listProjects.mockResolvedValue([
    { id: 7, name: "Platform" },
    { id: 9004, name: "Retail" },
  ]);
});

describe("AlertProviders", () => {
  it("lists every source the WORKSPACE connected, not one project's", async () => {
    renderPage();
    await waitFor(() =>
      expect(alertsMock.listConnectedProviders).toHaveBeenCalledWith(undefined),
    );
  });

  it("narrows to a project when the URL asks for one", async () => {
    renderPage("/app/alerts/sources?project=7");
    await waitFor(() =>
      expect(alertsMock.listConnectedProviders).toHaveBeenCalledWith("7"),
    );
  });

  it("says which project owns each source, but only workspace-wide", async () => {
    // Rows genuinely come from different projects here. One that did not say
    // which would send someone to the wrong place to change it.
    alertsMock.listConnectedProviders.mockResolvedValue([
      { id: "p1", type: "datadog", label: "prod-datadog", projectId: "9004", lastAlertAt: null },
    ]);
    renderPage();

    await waitFor(() => expect(screen.getByText("prod-datadog")).toBeTruthy());
    expect(screen.getByText("Retail")).toBeTruthy();
  });

  it("asks which project a new connection belongs to", async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText("Datadog")).toBeTruthy());

    fireEvent.click(screen.getByText("Datadog"));

    await waitFor(() => expect(screen.getByText("Connect Datadog")).toBeTruthy());
    expect(screen.getByLabelText("Project")).toBeTruthy();
    // And has NOT started the connect flow, which would have needed a project
    // it does not have.
    expect(alertsMock.getProviderSetup).not.toHaveBeenCalled();
  });

  it("connects into the project that was chosen", async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText("Datadog")).toBeTruthy());
    fireEvent.click(screen.getByText("Datadog"));
    await waitFor(() => expect(screen.getByLabelText("Project")).toBeTruthy());

    fireEvent.change(screen.getByLabelText("Project"), { target: { value: "9004" } });

    await waitFor(() =>
      expect(alertsMock.getProviderSetup).toHaveBeenCalledWith("datadog", "9004"),
    );
  });

  it("does not ask when the page is already filtered to a project", async () => {
    renderPage("/app/alerts/sources?project=7");
    await waitFor(() => expect(screen.getByText("Datadog")).toBeTruthy());

    fireEvent.click(screen.getByText("Datadog"));

    await waitFor(() =>
      expect(alertsMock.getProviderSetup).toHaveBeenCalledWith("datadog", "7"),
    );
    // Asserted on the project control rather than the heading: the connect
    // panel has its own "Connect Datadog" title, so the text alone proves
    // nothing about which of the two dialogs opened.
    expect(screen.queryByLabelText("Project")).toBeNull();
  });

  it("does not ask when the workspace has only one project", async () => {
    // There is no decision to make, and a dialog whose only option is already
    // selected is a click that changes nothing.
    apiMock.listProjects.mockResolvedValue([{ id: 7, name: "Platform" }]);
    renderPage();
    await waitFor(() => expect(screen.getByText("Datadog")).toBeTruthy());

    fireEvent.click(screen.getByText("Datadog"));

    await waitFor(() =>
      expect(alertsMock.getProviderSetup).toHaveBeenCalledWith("datadog", "7"),
    );
  });

  it("says so plainly when there is no project to connect into", async () => {
    apiMock.listProjects.mockResolvedValue([]);
    renderPage();
    await waitFor(() => expect(screen.getByText("Datadog")).toBeTruthy());

    fireEvent.click(screen.getByText("Datadog"));

    await waitFor(() =>
      expect(screen.getByText(/This workspace has no projects yet/)).toBeTruthy(),
    );
  });
});
