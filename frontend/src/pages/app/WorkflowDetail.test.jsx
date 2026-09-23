import { render, screen, fireEvent, waitFor, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

// What a workflow run page has to get right: the output on screen belongs to
// the run highlighted in the history, and a long history or a long report is
// navigable without a scroll bar being the only instrument.

const storeState = { can: () => true, pushToast: vi.fn() };
vi.mock("../../store/store", async () => {
  const actual = await vi.importActual("../../store/store");
  return { ...actual, useStore: () => storeState };
});

vi.mock("react-router-dom", async () => {
  const actual = await vi.importActual("react-router-dom");
  return { ...actual, useParams: () => ({ pid: "1", id: "7" }) };
});

const apiMock = {
  get: vi.fn(),
  list: vi.fn(),
  runWorkflow: vi.fn(),
  workflowInputs: vi.fn(),
  workflowReadiness: vi.fn(),
};
vi.mock("../../lib/api", () => ({ api: apiMock }));

const { default: WorkflowDetail } = await import("./WorkflowDetail");

const workflow = {
  id: 7,
  name: "AWS Unused EBS Volume Cleanup",
  description: "Automation workflow",
  active: true,
  nodeCount: 5,
  successRate: 80,
  runsTotal: 12,
  lastRunAt: "2026-09-18T12:30:00Z",
};

const run = (id) => ({
  id,
  status: "SUCCESS",
  startedAt: "2026-09-18T12:30:00Z",
  durationMs: 2200,
  by: "ashish@intertecsys.com",
});

const runs = Array.from({ length: 12 }, (_, i) => run(9800 + i));

const detailFor = (id) => ({
  id,
  status: "SUCCESS",
  durationMs: 2200,
  output: `**Volumes reviewed**\n\nRun ${id} released four unattached volumes.`,
  log: `[1/1] cleanup — ok (2.2s) for ${id}`,
});

const renderPage = () =>
  render(
    <MemoryRouter>
      <WorkflowDetail />
    </MemoryRouter>,
  );

beforeEach(() => {
  vi.clearAllMocks();
  apiMock.list.mockResolvedValue(runs);
  apiMock.get.mockImplementation(async (kind, id) =>
    kind === "workflows" ? workflow : detailFor(Number(id)),
  );
});

describe("WorkflowDetail", () => {
  it("pages the run history rather than stacking every run in one column", async () => {
    renderPage();
    await screen.findByText(/12 runs · page 1 of 2/);
    // Scoped to the history column: the result card names the selected run
    // too, and an unscoped query cannot tell the two mentions apart.
    const history = within(screen.getByText("Run history").closest("div"));

    // Six on the first page, and the seventh is not merely scrolled out of
    // view — it is not rendered at all.
    expect(history.getByText("#9805")).toBeInTheDocument();
    expect(history.queryByText("#9806")).not.toBeInTheDocument();

    fireEvent.click(history.getByLabelText("Next page"));
    expect(await history.findByText("#9806")).toBeInTheDocument();
    expect(history.queryByText("#9800")).not.toBeInTheDocument();
  });

  it("never shows the previous run's report under the run you just picked", async () => {
    // The second run's detail is left in flight, which is exactly the window
    // in which the page used to keep displaying the first run's output.
    let release;
    apiMock.get.mockImplementation(async (kind, id) => {
      if (kind === "workflows") return workflow;
      if (Number(id) === 9801) {
        return new Promise((resolve) => {
          release = () => resolve(detailFor(9801));
        });
      }
      return detailFor(Number(id));
    });

    renderPage();
    await screen.findByText(/Run 9800 released four unattached volumes/);

    fireEvent.click(screen.getByText("#9801"));

    await waitFor(() =>
      expect(
        screen.queryByText(/Run 9800 released four unattached volumes/),
      ).not.toBeInTheDocument(),
    );
    expect(screen.getByText("Loading this run…")).toBeInTheDocument();

    release();
    expect(
      await screen.findByText(/Run 9801 released four unattached volumes/),
    ).toBeInTheDocument();
  });

  it("says a workflow is active rather than calling it a success", async () => {
    // Every run in this fixture failed. A green "Success" over a 0% success
    // rate is the card contradicting the two next to it.
    apiMock.list.mockResolvedValue(runs.map((r) => ({ ...r, status: "FAILED" })));
    apiMock.get.mockImplementation(async (kind, id) =>
      kind === "workflows"
        ? { ...workflow, successRate: 0 }
        : { ...detailFor(Number(id)), status: "FAILED" },
    );

    renderPage();
    expect(await screen.findByText("active")).toBeInTheDocument();
    expect(screen.queryByText("success")).not.toBeInTheDocument();
  });

  it("renders the report as prose instead of printing the model's markdown", async () => {
    renderPage();
    const heading = await screen.findByText("Volumes reviewed");

    expect(heading.tagName).toBe("H4");
    expect(screen.queryByText(/\*\*/)).not.toBeInTheDocument();
  });
});
