import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

// The workflow engine is not a tenant-facing surface: a shared workspace
// token it would administer can read and delete every app in the shared
// workspace. So the assertion that matters here is a negative one — no engine
// tab, for anybody, including a provider session that lands on /app/models.

const storeState = { session: { role: "admin" }, can: () => true, pushToast: vi.fn() };

vi.mock("../../store/store", async () => {
  const actual = await vi.importActual("../../store/store");
  return { ...actual, useStore: () => storeState };
});

vi.mock("./AiProviders", () => ({
  default: ({ embedded }) => <div>tenant-keys-body embedded={String(embedded)}</div>,
}));

const { default: Models } = await import("./Models");

const renderPage = () =>
  render(
    <MemoryRouter>
      <Models />
    </MemoryRouter>,
  );

beforeEach(() => {
  storeState.session = { role: "admin" };
});

describe("models screen", () => {
  it("shows the workspace's own vendor keys", () => {
    renderPage();

    expect(screen.getByText(/tenant-keys-body/)).toBeInTheDocument();
  });

  it("mounts the body embedded so it drops its own page header", () => {
    renderPage();

    // One header for the screen, not two stacked.
    expect(screen.getByText(/tenant-keys-body embedded=true/)).toBeInTheDocument();
  });

  it("offers no shared-workspace surface to a tenant admin", () => {
    renderPage();

    expect(screen.queryByText(/Dify/i)).not.toBeInTheDocument();
    expect(screen.queryByText("Platform (Dify)")).not.toBeInTheDocument();
  });

  /**
   * A provider CAN reach /app/models — nothing redirects them away from the
   * tenant console. The tab used to appear for exactly this session, so this
   * is the case the removal has to hold for.
   */
  it("offers no shared-workspace surface to a provider session either", () => {
    storeState.session = { role: "provider" };
    renderPage();

    expect(screen.queryByText(/Dify/i)).not.toBeInTheDocument();
    expect(screen.getByText(/tenant-keys-body/)).toBeInTheDocument();
  });

  it("renders no tab bar at all", () => {
    storeState.session = { role: "provider" };
    renderPage();

    expect(screen.queryByRole("button", { name: "Your keys" })).not.toBeInTheDocument();
    expect(screen.queryByText("Your keys")).not.toBeInTheDocument();
  });
});
