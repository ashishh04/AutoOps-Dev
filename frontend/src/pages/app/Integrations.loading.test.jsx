import React from "react";
import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, it, expect, vi, beforeEach } from "vitest";

/**
 * An empty list is not an answer until the request has come back.
 *
 * The bug: this page renders the ENTIRE cloud platform catalog — every
 * provider, each badged NOT CONFIGURED — whenever `clouds.length === 0`. That
 * state is also the initial one, so every visit flashed "you have configured
 * nothing" at a workspace that had three connections, until the fetch resolved
 * and replaced it with the real table.
 *
 * It is the same shape of mistake as a counter that reads zero when healthy and
 * zero when dead: one value standing for two different facts, with the
 * reassuring one shown by default.
 */

const storeState = { pushToast: vi.fn(), projects: [], can: () => true };
vi.mock("../../store/store", async () => {
  const actual = await vi.importActual("../../store/store");
  return { ...actual, useStore: () => storeState };
});

const apiMock = {
  listCloudConnections: vi.fn(),
  listConnectors: vi.fn(),
  listProjects: vi.fn(),
};
vi.mock("../../lib/api", () => ({ api: apiMock }));

const { default: Integrations } = await import("./Integrations");

const connection = (over = {}) => ({
  id: 1,
  platform: "aws",
  name: "Intertec AWS",
  status: "connected",
  hasCredentials: true,
  projectId: null,
  accountId: "123456789012",
  ...over,
});

const renderPage = () =>
  render(
    <MemoryRouter>
      <Integrations />
    </MemoryRouter>,
  );

beforeEach(() => {
  vi.clearAllMocks();
  apiMock.listConnectors?.mockResolvedValue?.([]);
  apiMock.listProjects?.mockResolvedValue?.([]);
});

describe("cloud integrations loading state", () => {
  it("does not claim nothing is configured while the request is in flight", async () => {
    // A promise that never settles IS the state under test — the moment
    // between opening the page and the connections arriving.
    apiMock.listCloudConnections.mockReturnValue(new Promise(() => {}));

    renderPage();

    expect(await screen.findByText(/Loading connections/i)).toBeInTheDocument();
    // The catalog of unconfigured platforms must not be on screen yet.
    expect(screen.queryByText("NOT CONFIGURED")).not.toBeInTheDocument();
  });

  it("shows the connections once they arrive, never the catalog", async () => {
    apiMock.listCloudConnections.mockResolvedValue([connection()]);

    renderPage();

    // The account id, not the connection's own name: the table shows the
    // PLATFORM's name and carries the connection name only as a title
    // attribute, so asserting on the name would pass without a row rendering.
    await waitFor(() =>
      expect(screen.getByText("123456789012")).toBeInTheDocument(),
    );
    expect(screen.queryByText(/Loading connections/i)).not.toBeInTheDocument();
    expect(screen.queryByText("NOT CONFIGURED")).not.toBeInTheDocument();
  });

  it("offers the catalog only once it KNOWS the workspace has none", async () => {
    // The catalog is right here and wrong a moment earlier. Same markup, and
    // the difference is entirely whether the answer is known yet.
    apiMock.listCloudConnections.mockResolvedValue([]);

    renderPage();

    await waitFor(() =>
      expect(screen.getAllByText("NOT CONFIGURED").length).toBeGreaterThan(0),
    );
  });

  it("says a failed load failed, rather than showing an empty workspace", async () => {
    // The dangerous one. Rendering "nothing is configured" because a request
    // errored tells somebody their cloud connections have disappeared.
    apiMock.listCloudConnections.mockRejectedValue(new Error("gateway down"));

    renderPage();

    await waitFor(() => expect(screen.getByRole("alert")).toBeInTheDocument());
    expect(screen.getByRole("alert")).toHaveTextContent(/not the same as having none/i);
    expect(screen.queryByText("NOT CONFIGURED")).not.toBeInTheDocument();
  });
});
