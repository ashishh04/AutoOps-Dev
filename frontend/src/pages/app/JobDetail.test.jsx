import { render, screen, fireEvent, waitFor, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

// A job's log pane answers "what did THIS execution print". Both tests here
// are about that word: the pane must follow the selection, and the history
// must be navigable when a nightly job has run four hundred times.

const storeState = { can: () => true, pushToast: vi.fn() };
vi.mock("../../store/store", async () => {
  const actual = await vi.importActual("../../store/store");
  return { ...actual, useStore: () => storeState };
});

vi.mock("react-router-dom", async () => {
  const actual = await vi.importActual("react-router-dom");
  return { ...actual, useParams: () => ({ pid: "2", id: "9114" }) };
});

const apiMock = { get: vi.fn(), list: vi.fn(), runJob: vi.fn() };
vi.mock("../../lib/api", () => ({ api: apiMock }));

const { default: JobDetail } = await import("./JobDetail");

const job = {
  id: "9114",
  name: "TEST",
  status: "ACTIVE",
  avgDurationMs: 5800,
  successRate: 100,
  runsTotal: 10,
  lastRunAt: "2026-08-26T16:12:00Z",
};

const runs = Array.from({ length: 10 }, (_, i) => ({
  id: 10190 + i,
  jobId: "9114",
  status: "SUCCESS",
  startedAt: "2026-08-26T16:12:00Z",
  trigger: "manual",
}));

const detailFor = (id) => ({
  id,
  status: "SUCCESS",
  log: `[1/1] step — ok (5.8s)\n    | output of ${id}`,
});

const renderPage = () =>
  render(
    <MemoryRouter>
      <JobDetail />
    </MemoryRouter>,
  );

beforeEach(() => {
  vi.clearAllMocks();
  apiMock.list.mockResolvedValue(runs);
  apiMock.get.mockImplementation(async (kind, id) =>
    kind === "jobs" ? job : detailFor(Number(id)),
  );
});

describe("JobDetail", () => {
  it("pages the execution history", async () => {
    renderPage();
    await screen.findByText(/10 executions · page 1 of 2/);
    // The label IS the header div, so the card itself is one step up.
    const history = within(screen.getByText("Execution History").parentElement);

    expect(history.getByText("10197")).toBeInTheDocument();
    expect(history.queryByText("10198")).not.toBeInTheDocument();

    fireEvent.click(history.getByLabelText("Next page"));
    expect(await history.findByText("10198")).toBeInTheDocument();
    expect(history.queryByText("10190")).not.toBeInTheDocument();
  });

  it("drops the previous execution's output the moment another run is picked", async () => {
    let release;
    apiMock.get.mockImplementation(async (kind, id) => {
      if (kind === "jobs") return job;
      if (Number(id) === 10191) {
        return new Promise((resolve) => {
          release = () => resolve(detailFor(10191));
        });
      }
      return detailFor(Number(id));
    });

    renderPage();
    await screen.findByText(/output of 10190/);

    fireEvent.click(screen.getByText("10191"));

    await waitFor(() =>
      expect(screen.queryByText(/output of 10190/)).not.toBeInTheDocument(),
    );
    expect(screen.getByText("Loading this run…")).toBeInTheDocument();

    release();
    expect(await screen.findByText(/output of 10191/)).toBeInTheDocument();
  });
});
