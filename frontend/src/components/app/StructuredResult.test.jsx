import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import StructuredResult, { parseStructured } from "./StructuredResult";

/**
 * Machine output, rendered for a person.
 *
 * <p>The two risks this pins are opposite. If the detector is too eager it
 * shreds a written report into meaningless tiles — the workflows that produce
 * a postmortem or a set of meeting actions must keep rendering as prose. If it
 * is too shy the customer keeps seeing `events_total=7 runs_failed=0` on the
 * Result pane, which is what prompted this.
 *
 * <p>And it must not editorialise. Everything rendered has to be traceable to
 * the input string.
 */
describe("parseStructured", () => {
  const timeline =
    "window_hours=168 since=2026-09-17T16:43:38.293786050Z events_total=7 " +
    "runs_total=7 runs_failed=0 runs_succeeded=6 runs_in_flight=1 " +
    "automations_that_failed_more_than_once=none";

  it("recognises the agent-facing key=value shape", () => {
    const d = parseStructured(timeline);
    expect(d).not.toBeNull();
    expect(Object.fromEntries(d.pairs)).toMatchObject({
      window_hours: "168",
      events_total: "7",
      runs_failed: "0",
    });
  });

  it("recognises a banner with a count and trailing JSON", () => {
    const d = parseStructured(
      'OPEN INCIDENTS (0) tenant=acme project=9005\n\nJSON {"incident_count": 0, "incidents": []}',
    );
    expect(d.banner).toEqual({ label: "Open Incidents", count: 0 });
    expect(d.json.incident_count).toBe(0);
  });

  it("leaves a written report alone", () => {
    // The critical negative. Shredding a postmortem into tiles would be a far
    // worse regression than the plain output this was built to fix.
    const report =
      "## Incident Postmortem\n\nThe checkout service degraded at 14:02 when " +
      "the connection pool was exhausted. Impact was roughly 2800 failed " +
      "checkouts across 24 minutes, and the trigger was a worker count raised " +
      "from 8 to 24 earlier that morning.";
    expect(parseStructured(report)).toBeNull();
  });

  it("leaves prose that happens to mention a pair alone", () => {
    expect(
      parseStructured(
        "We scaled the workers back down because max_connections=200 was " +
          "already saturated and the pool could not recover on its own without " +
          "a restart of every application node in the cluster.",
      ),
    ).toBeNull();
  });

  it("is null for empty or non-string input", () => {
    expect(parseStructured("")).toBeNull();
    expect(parseStructured(null)).toBeNull();
    expect(parseStructured(undefined)).toBeNull();
  });
});

describe("StructuredResult", () => {
  const render1 = (raw) =>
    render(<StructuredResult data={parseStructured(raw)} />);

  it("lays the numbers out as labelled tiles", () => {
    render1(
      "window_hours=168 events_total=7 runs_total=7 runs_failed=0 runs_succeeded=6",
    );
    expect(screen.getByText("Events Total")).toBeTruthy();
    expect(screen.getByText("Runs Succeeded")).toBeTruthy();
    expect(screen.getByText("6")).toBeTruthy();
  });

  it("does NOT colour a zero failure count red", () => {
    // A red "0 failed" tile trains people to ignore red, which costs exactly
    // when a real failure appears.
    render1("runs_total=7 runs_failed=0 runs_succeeded=6");
    const zero = screen
      .getByText("Runs Failed")
      .parentElement.querySelector(".tabular-nums");
    expect(zero.className).not.toMatch(/text-red/);
  });

  it("does colour a real failure count red", () => {
    render1("runs_total=7 runs_failed=3 runs_succeeded=4");
    const three = screen
      .getByText("Runs Failed")
      .parentElement.querySelector(".tabular-nums");
    expect(three.className).toMatch(/text-red/);
  });

  it("renders a JSON collection as a table", () => {
    render1(
      'INCIDENTS (2) scope=workspace project=9005 JSON {"incidents":[' +
        '{"id":"inc-1","service":"checkout","severity":"critical"},' +
        '{"id":"inc-2","service":"payments","severity":"warning"}]}',
    );
    expect(screen.getByText("checkout")).toBeTruthy();
    expect(screen.getByText("payments")).toBeTruthy();
    expect(screen.getByText("Severity")).toBeTruthy();
  });

  it("says so when a collection is genuinely empty", () => {
    // Rather than leaving a blank space that reads as a rendering failure.
    render1(
      'OPEN INCIDENTS (0) tenant=acme project=9005 JSON {"incident_count":0,"incidents":[]}',
    );
    expect(screen.getByText("0")).toBeTruthy();
    expect(screen.getByText(/Nothing matched in this window/i)).toBeTruthy();
  });

  it("invents nothing that was not in the input", () => {
    // No derived percentage, no "healthy" verdict, no trend. A summary that
    // editorialises can be wrong in a way the data was not.
    const { container } = render1(
      "runs_total=7 runs_failed=0 runs_succeeded=6",
    );
    const text = container.textContent;
    expect(text).not.toMatch(/%|healthy|degraded|improv|trend/i);
  });

  it("makes an ISO timestamp readable without changing it into a claim", () => {
    render1("window_hours=168 since=2026-09-17T16:43:38Z events_total=7");
    expect(screen.getByText("Since")).toBeTruthy();
    expect(screen.queryByText(/2026-09-17T16:43:38Z/)).toBeNull();
  });

  it("hides the tenant and project ids the reader already has", () => {
    // These outputs are written for an agent, which has no address bar and
    // genuinely needs telling which tenant it is reasoning about. A customer
    // navigated here. Echoing an internal tenant slug back at the person whose
    // tenant it is reads like leaked plumbing.
    render1(
      "OPEN INCIDENTS (0) tenant=intertec-systems-1542f8a3 project=9005 status_filter=open",
    );

    expect(screen.queryByText("Tenant")).toBeNull();
    expect(screen.queryByText(/intertec-systems-1542f8a3/)).toBeNull();
    expect(screen.queryByText("Project")).toBeNull();
    // But a genuinely informative one stays.
    expect(screen.getByText("Status Filter")).toBeTruthy();
  });

  it("does not render the JSON marker as a stray paragraph", () => {
    // `JSON` separates the prose head from the payload. Rendered, it looked
    // exactly like a rendering bug, because it was one.
    const { container } = render1(
      'OPEN INCIDENTS (0) tenant=acme project=9005 JSON {"incidents":[]}',
    );
    expect(container.textContent).not.toMatch(/JSON/);
  });

  it("still keeps a word that merely contains json", () => {
    // The word-boundary check. Stripping it blindly would mangle real text.
    const d = parseStructured(
      'REPORT (1) a=1 b=2 c=3 parsed from jsonschema output JSON {"rows":[]}',
    );
    expect(d.leftover).toMatch(/jsonschema/);
  });
});
