import { describe, it, expect } from "vitest";
import { toolNamesById } from "./AgentRunPanel";

/**
 * The run timeline is customer-facing. The model is offered tools as
 * `workflow_9231` because an id is the only stable way to bind a call to a
 * target, but that is how the agent is wired, not something a customer asked
 * to know — and it advertises the numbering of their automations.
 */
describe("toolNamesById", () => {
  const steps = [
    { id: 1, kind: "MODEL_CALL", response: "{}" },
    {
      id: 2,
      kind: "TOOL_CALL",
      toolType: "WORKFLOW",
      toolTargetId: 9231,
      toolName: "CloudWatch Alarm State Audit",
    },
    {
      id: 3,
      kind: "TOOL_CALL",
      toolType: "JOB",
      toolTargetId: 14,
      toolName: "Nightly Backup",
    },
  ];

  it("resolves a workflow identifier to the automation's name", () => {
    expect(toolNamesById(steps)("workflow_9231")).toBe(
      "CloudWatch Alarm State Audit",
    );
  });

  it("resolves a job identifier too", () => {
    expect(toolNamesById(steps)("job_14")).toBe("Nightly Backup");
  });

  it("falls back to the identifier when the run never ran that tool", () => {
    // A model can ask for something it was not given. Showing the raw name is
    // worse than a friendly one but far better than showing nothing, because
    // that case is exactly when someone needs to see what it reached for.
    expect(toolNamesById(steps)("workflow_999")).toBe("workflow_999");
  });

  it("tolerates a run with no steps yet", () => {
    expect(toolNamesById([])("workflow_1")).toBe("workflow_1");
    expect(toolNamesById(null)("workflow_1")).toBe("workflow_1");
  });
});
