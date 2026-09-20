import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";

vi.mock("../../lib/api", () => ({
  agentRuns: {
    listForAgent: vi.fn(),
    start: vi.fn(),
    get: vi.fn(),
    cancel: vi.fn(),
  },
}));

import { agentRuns } from "../../lib/api";
import AgentRunPanel from "./AgentRunPanel";

/** Long enough for several poll ticks at 2.5s each. */
const POLL_ROUNDS = 12000;

const AGENT = {
  id: 9316,
  name: "AWS Incident RCA Analyst",
  model: "deepseek.v3.2",
  toolCount: 2,
  enabled: true,
};

/** What the POST returns: accepted, queued, and carrying no steps yet. */
const QUEUED = {
  id: 29,
  agentId: 9316,
  status: "PENDING",
  running: true,
  waiting: false,
  finished: false,
  input: "Correlate CloudWatch alarms.",
  output: "",
  error: "",
  stepCount: 0,
  maxSteps: 12,
  promptTokens: 0,
  completionTokens: 0,
  createdAt: new Date().toISOString(),
  steps: null,
};

/** What a poll returns a moment later. */
const RUNNING = {
  ...QUEUED,
  status: "RUNNING",
  stepCount: 1,
  steps: [
    {
      id: 1,
      seq: 1,
      kind: "TOOL_CALL",
      toolType: "workflow",
      toolTargetId: 9231,
      toolName: "CloudWatch Alarm State Audit",
      request: "{}",
      response: "",
      isError: false,
      durationMs: null,
    },
  ],
};

describe("AgentRunPanel liveness", () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    agentRuns.listForAgent.mockResolvedValue([]);
    agentRuns.start.mockResolvedValue(QUEUED);
    agentRuns.get.mockResolvedValue(RUNNING);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.clearAllMocks();
  });

  it("shows steps as they arrive, without the customer reloading the page", async () => {
    // The whole point of the panel. A run takes minutes; if the only way to
    // see progress is F5 then the panel is a screenshot, not a view.
    render(<AgentRunPanel agent={AGENT} canRun onClose={() => {}} />);

    await waitFor(() => expect(agentRuns.listForAgent).toHaveBeenCalled());

    fireEvent.change(screen.getByRole("textbox"), {
      target: { value: "Correlate CloudWatch alarms." },
    });
    fireEvent.click(screen.getByRole("button", { name: /run/i }));

    await waitFor(() => expect(agentRuns.start).toHaveBeenCalled());

    await vi.advanceTimersByTimeAsync(3000);

    await waitFor(() =>
      expect(screen.getByText("CloudWatch Alarm State Audit")).toBeTruthy(),
    );
    expect(agentRuns.get).toHaveBeenCalledWith(29);
  });

  it("says so when it has stopped receiving updates, instead of looking frozen", async () => {
    // The bug this closes: every poll failure was swallowed, so a panel that
    // had silently stopped updating was indistinguishable from an agent that
    // was simply thinking. The only way a customer could tell was to reload —
    // which is not something they should ever need to do to see a live run.
    agentRuns.get.mockRejectedValue(new Error("network"));
    render(<AgentRunPanel agent={AGENT} canRun onClose={() => {}} />);
    await waitFor(() => expect(agentRuns.listForAgent).toHaveBeenCalled());

    fireEvent.change(screen.getByRole("textbox"), { target: { value: "go" } });
    fireEvent.click(screen.getByRole("button", { name: /run/i }));
    await waitFor(() => expect(agentRuns.start).toHaveBeenCalled());

    await vi.advanceTimersByTimeAsync(POLL_ROUNDS);

    await waitFor(() =>
      expect(screen.getByText(/not updating right now/i)).toBeTruthy(),
    );
  });

  it("clears the warning as soon as a poll succeeds again", async () => {
    agentRuns.get.mockRejectedValue(new Error("network"));
    render(<AgentRunPanel agent={AGENT} canRun onClose={() => {}} />);
    await waitFor(() => expect(agentRuns.listForAgent).toHaveBeenCalled());
    fireEvent.change(screen.getByRole("textbox"), { target: { value: "go" } });
    fireEvent.click(screen.getByRole("button", { name: /run/i }));
    await waitFor(() => expect(agentRuns.start).toHaveBeenCalled());
    await vi.advanceTimersByTimeAsync(POLL_ROUNDS);
    await waitFor(() => expect(screen.getByText(/not updating right now/i)).toBeTruthy());

    agentRuns.get.mockResolvedValue(RUNNING);
    await vi.advanceTimersByTimeAsync(POLL_ROUNDS);

    await waitFor(() => expect(screen.queryByText(/not updating right now/i)).toBeNull());
    expect(screen.getByText("CloudWatch Alarm State Audit")).toBeTruthy();
  });

  it("stops polling once the run reaches a terminal state", async () => {
    // A panel left open on a finished run must not keep asking.
    agentRuns.get.mockResolvedValue({
      ...RUNNING,
      status: "SUCCEEDED",
      running: false,
      finished: true,
    });
    render(<AgentRunPanel agent={AGENT} canRun onClose={() => {}} />);
    await waitFor(() => expect(agentRuns.listForAgent).toHaveBeenCalled());

    fireEvent.change(screen.getByRole("textbox"), { target: { value: "go" } });
    fireEvent.click(screen.getByRole("button", { name: /run/i }));
    await waitFor(() => expect(agentRuns.start).toHaveBeenCalled());

    await vi.advanceTimersByTimeAsync(3000);
    const afterFirst = agentRuns.get.mock.calls.length;
    await vi.advanceTimersByTimeAsync(10000);
    expect(agentRuns.get.mock.calls.length).toBe(afterFirst);
  });
});
