import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import ReportText from "./ReportText";

// The rule this component exists to enforce: whatever notation the model used
// to write the report, the reader sees the report and not the notation.

const REPORT = `**No alarms fired; the only CloudWatch alarm is in INSUFFICIENT_DATA for 46 hours [e:123].**

---

**Status in us-east-1 (last 24 hours)**
- **Breached / Failed:** None.
- **Could not check:** The monitoring for AWS/EC2/CPUUtilization stopped
  reporting and has been in INSUFFICIENT_DATA since 2026-09-19 [e:123].

## Correlation
No alarm firings occurred to correlate with the change timeline. This
suggests the instance was stopped outside the lookback period.`;

describe("ReportText", () => {
  it("leaves no markdown punctuation on screen", () => {
    const { container } = render(<ReportText source={REPORT} />);
    const text = container.textContent;

    expect(text).not.toMatch(/\*\*/);
    expect(text).not.toMatch(/^#/m);
    expect(text).not.toMatch(/^---$/m);
  });

  it("promotes a bold-only line to a heading, which is how models label sections", () => {
    render(<ReportText source={REPORT} />);
    expect(screen.getByText("Status in us-east-1 (last 24 hours)").tagName).toBe("H4");
    expect(screen.getByText("Correlation").tagName).toBe("H4");
  });

  it("keeps bold inside a sentence as bold rather than as a heading", () => {
    render(<ReportText source={REPORT} />);
    const label = screen.getByText("Breached / Failed:");
    expect(label.tagName).toBe("STRONG");
    expect(label.closest("li")).not.toBeNull();
  });

  it("joins a hard-wrapped item back into one line", () => {
    render(<ReportText source={REPORT} />);
    expect(
      screen.getByText(/stopped reporting and has been in INSUFFICIENT_DATA/),
    ).toBeInTheDocument();
  });

  it("renders a table as a table instead of a row of pipes", () => {
    const { container } = render(
      <ReportText
        source={"| Volume | Size |\n| --- | --- |\n| vol-1 | 100 GiB |"}
      />,
    );
    expect(container.querySelectorAll("th")).toHaveLength(2);
    expect(screen.getByText("vol-1").tagName).toBe("TD");
    expect(container.textContent).not.toMatch(/\|/);
  });

  it("does not interpret the text as HTML", () => {
    const { container } = render(<ReportText source={"<img src=x> is not markup here"} />);
    expect(container.querySelector("img")).toBeNull();
    expect(container.textContent).toContain("<img src=x>");
  });

  it("renders nothing for an empty report", () => {
    const { container } = render(<ReportText source="   " />);
    expect(container.firstChild).toBeNull();
  });
});
