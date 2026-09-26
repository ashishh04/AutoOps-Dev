import React from "react";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import { describe, it, expect } from "vitest";
import { useAlertScope } from "./alertScope";

/**
 * The alert plane reads at workspace level and narrows by project. That makes
 * the project a query parameter, and the cases worth pinning are the ones where
 * a wrong answer still looks like an answer: an edited parameter being
 * forwarded verbatim, and a filter quietly dropping as you move between
 * screens.
 */
function Probe() {
  const scope = useAlertScope();
  return (
    <div>
      <span data-testid="project">{scope.projectId ?? "(none)"}</span>
      <span data-testid="link">{scope.link("/alerts/sources")}</span>
      <button onClick={() => scope.setProject("9004")}>narrow</button>
      <button onClick={() => scope.setProject(undefined)}>clear</button>
    </div>
  );
}

const at = (url) =>
  render(
    <MemoryRouter initialEntries={[url]}>
      <Routes>
        <Route path="/app/alerts" element={<Probe />} />
      </Routes>
    </MemoryRouter>,
  );

describe("useAlertScope", () => {
  it("defaults to the whole workspace", () => {
    at("/app/alerts");
    expect(screen.getByTestId("project")).toHaveTextContent("(none)");
    expect(screen.getByTestId("link")).toHaveTextContent("/app/alerts/sources");
  });

  it("reads a project filter off the URL", () => {
    at("/app/alerts?project=7");
    expect(screen.getByTestId("project")).toHaveTextContent("7");
  });

  it("carries the filter into every link between these screens", () => {
    // Doing this by hand at each call site is how the filter silently resets
    // on the third click.
    at("/app/alerts?project=7");
    expect(screen.getByTestId("link")).toHaveTextContent(
      "/app/alerts/sources?project=7",
    );
  });

  it("ignores a project that is not an id", () => {
    // A query parameter is user-editable. `?project=all` forwarded verbatim
    // comes back as a rejected request on a screen where the customer only
    // pressed a dropdown.
    at("/app/alerts?project=all");
    expect(screen.getByTestId("project")).toHaveTextContent("(none)");
    expect(screen.getByTestId("link")).toHaveTextContent("/app/alerts/sources");
  });

  it("narrows and clears without leaving the screen", async () => {
    at("/app/alerts");
    await userEvent.click(screen.getByText("narrow"));
    expect(screen.getByTestId("project")).toHaveTextContent("9004");

    await userEvent.click(screen.getByText("clear"));
    expect(screen.getByTestId("project")).toHaveTextContent("(none)");
  });
});
