import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

// The job designer's link to the script library.
//
// A job step used to be able to hold a PASTED COPY of a script body and nothing
// else, so the library row and the thing actually running were two unrelated
// strings. A "Library Script" step names the row instead, and core-service
// resolves it to the current body when the run is queued.
//
// What these cases pin is the half that lives in the browser: that the step
// exists, that it offers only scripts this workspace OWNS, and that it stores a
// reference rather than a body.

const storeState = {
  workspace: { plan: "BUSINESS" },
  pushToast: vi.fn(),
  can: () => true,
};

vi.mock("../../store/store", async () => {
  const actual = await vi.importActual("../../store/store");
  return { ...actual, useStore: () => storeState };
});

const navigate = vi.fn();
vi.mock("react-router-dom", async () => {
  const actual = await vi.importActual("react-router-dom");
  return {
    ...actual,
    useNavigate: () => navigate,
    useParams: () => ({ pid: "7" }),
  };
});

const apiMock = {
  listLibrary: vi.fn(),
  listInstallations: vi.fn(),
  get: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
};

vi.mock("../../lib/api", () => ({ api: apiMock }));

const { default: CreateJob } = await import("./CreateJob");

const script = (over = {}) => ({
  id: 233,
  title: "AWS Instance Scheduler",
  description: "Starts and stops tagged instances on a schedule.",
  type: "script",
  category: "AWS",
  premium: false,
  managed: false,
  owned: true,
  locked: false,
  installs: 0,
  ...over,
});

/** Opens the designer on the tab that holds the step palette. */
const openSteps = async () => {
  render(
    <MemoryRouter>
      <CreateJob />
    </MemoryRouter>,
  );
  fireEvent.click(await screen.findByRole("button", { name: /^Workflow$/ }));
};

beforeEach(() => {
  vi.clearAllMocks();
  apiMock.listInstallations.mockResolvedValue([]);
  apiMock.listLibrary.mockResolvedValue([script()]);
});

describe("job designer — library scripts", () => {
  it("offers a Library Script step in the palette", async () => {
    await openSteps();

    expect(
      await screen.findByRole("button", { name: /Library Script/ }),
    ).toBeInTheDocument();
  });

  it("lists only the scripts this workspace owns", async () => {
    // Catalog rows are deliberately absent. A reference resolves against owned
    // rows only, so offering a catalog item here would build a step that fails
    // at run time with "not in this workspace" — import it first.
    apiMock.listLibrary.mockResolvedValue([
      script(),
      script({ id: 20, title: "Catalog-only script", owned: false, managed: true }),
      script({ id: 99, title: "A delivered workflow", type: "workflow" }),
    ]);

    await openSteps();
    fireEvent.click(await screen.findByRole("button", { name: /Library Script/ }));

    const picker = await screen.findByRole("combobox", { name: "Library script" });
    const options = [...picker.options].map((o) => o.textContent);
    expect(options).toContain("AWS — AWS Instance Scheduler");
    expect(options).not.toContain("AWS — Catalog-only script");
    expect(options).not.toContain("AWS — A delivered workflow");
  });

  it("stores a reference and names the step after the script", async () => {
    await openSteps();

    // save() refuses an unnamed job, so fill Details in first.
    fireEvent.click(screen.getByRole("button", { name: /^Details$/ }));
    fireEvent.change(screen.getByPlaceholderText("System Diagnostics Check"), {
      target: { value: "Nightly instance schedule" },
    });
    fireEvent.click(screen.getByRole("button", { name: /^Workflow$/ }));

    fireEvent.click(await screen.findByRole("button", { name: /Library Script/ }));

    const picker = await screen.findByRole("combobox", { name: "Library script" });
    fireEvent.change(picker, { target: { value: "233" } });

    fireEvent.click(screen.getByRole("button", { name: /Create Job/i }));

    await waitFor(() => expect(apiMock.create).toHaveBeenCalled());
    const [, payload] = apiMock.create.mock.calls[0];
    const step = payload.steps[0];
    // A reference, not a body: the job never carries a copy of the script.
    expect(step.id).toBe("library");
    expect(step.libraryItemId).toBe(233);
    expect(step.value).toBeUndefined();
    expect(step.label).toBe("AWS Instance Scheduler");
  });

  it("points an empty workspace at the library instead of an empty dropdown", async () => {
    apiMock.listLibrary.mockResolvedValue([]);

    await openSteps();
    fireEvent.click(await screen.findByRole("button", { name: /Library Script/ }));

    expect(await screen.findByText(/no scripts yet/i)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /library/i })).toHaveAttribute(
      "href",
      "/app/library",
    );
  });

  it("matches the type the API actually sends, not the enum name", async () => {
    // LibraryController serialises with `getType().name().toLowerCase()`, so
    // this arrives as "script". The first version of this filter compared
    // against "SCRIPT" and matched nothing, so every workspace reported having
    // no scripts however many it owned. The original fixture used "SCRIPT" too,
    // which is why the test agreed with the bug.
    apiMock.listLibrary.mockResolvedValue([script({ type: "script" })]);

    await openSteps();
    fireEvent.click(await screen.findByRole("button", { name: /Library Script/ }));

    const picker = await screen.findByRole("combobox", { name: "Library script" });
    expect([...picker.options].map((o) => o.textContent)).toContain(
      "AWS — AWS Instance Scheduler",
    );
  });

  it("distinguishes a failed load from an empty workspace", async () => {
    // "Import one from the library" is a lie when the list simply failed to
    // load, and it sends the author off to fix a problem they do not have.
    apiMock.listLibrary.mockRejectedValue(new Error("network down"));

    await openSteps();
    fireEvent.click(await screen.findByRole("button", { name: /Library Script/ }));

    expect(await screen.findByText(/could not load your scripts/i)).toBeInTheDocument();
    expect(screen.queryByText(/no scripts yet/i)).not.toBeInTheDocument();
  });

  it("offers PowerShell as a step type of its own", async () => {
    // job-service has had a PowerShell runner and a pwsh install for a while;
    // the palette simply never offered it, so the only way to reach it was a
    // hand-edited definition.
    await openSteps();

    expect(
      await screen.findByRole("button", { name: /PowerShell/ }),
    ).toBeInTheDocument();
  });
});
