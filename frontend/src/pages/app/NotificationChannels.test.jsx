import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

// Outbound notification channels. The states worth asserting are the honest
// ones: a channel with no rules delivers nothing, a parked channel says the
// platform gave up on it, and a secret is never rendered back to the browser.

const storeState = { can: () => true, pushToast: vi.fn() };

vi.mock("../../store/store", async () => {
  const actual = await vi.importActual("../../store/store");
  return { ...actual, useStore: () => storeState };
});

const apiMock = {
  pluginCatalog: vi.fn(),
  listInstallations: vi.fn(),
  listNotificationRules: vi.fn(),
  pluginEvents: vi.fn(),
  pluginTargetTypes: vi.fn(),
  pluginDeliveries: vi.fn(),
  installPlugin: vi.fn(),
  updateInstallation: vi.fn(),
  removeInstallation: vi.fn(),
  testInstallation: vi.fn(),
  enableInstallation: vi.fn(),
  disableInstallation: vi.fn(),
  createNotificationRule: vi.fn(),
  removeNotificationRule: vi.fn(),
  listProjects: vi.fn(),
  list: vi.fn(),
};

vi.mock("../../lib/api", () => ({ api: apiMock }));

const { default: NotificationChannels } = await import("./NotificationChannels");

const renderPage = () =>
  render(
    <MemoryRouter>
      <NotificationChannels />
    </MemoryRouter>,
  );

const SLACK = {
  key: "slack",
  displayName: "Slack",
  category: "CHAT",
  summary: "Post job and workflow events into a Slack channel.",
  setupUrl: "https://api.slack.com/messaging/webhooks",
  fields: [
    {
      name: "webhookUrl",
      label: "Incoming webhook URL",
      type: "SECRET",
      required: true,
      placeholder: "https://hooks.slack.com/services/…",
      help: "Slack → Apps → Incoming Webhooks",
    },
    {
      name: "username",
      label: "Override bot name",
      type: "TEXT",
      required: false,
      placeholder: null,
      help: null,
    },
  ],
  installedCount: 0,
};

const channel = (over = {}) => ({
  id: 1,
  pluginKey: "slack",
  pluginName: "Slack",
  displayName: "Ops alerts",
  enabled: true,
  status: "ACTIVE",
  parked: false,
  config: { webhookUrl: "••••••••" },
  lastTestOk: true,
  lastTestAt: "2026-08-06T10:00:00Z",
  lastTestDetail: null,
  consecutiveFailures: 0,
  ruleCount: 2,
  createdBy: "me@example.com",
  createdAt: "2026-08-01T10:00:00Z",
  ...over,
});

beforeEach(() => {
  storeState.pushToast.mockClear();
  apiMock.pluginCatalog.mockResolvedValue([SLACK]);
  apiMock.listInstallations.mockResolvedValue([]);
  apiMock.listNotificationRules.mockResolvedValue([]);
  apiMock.pluginEvents.mockResolvedValue([
    {
      value: "FAILED",
      label: "Failed",
      description: "A step failed.",
      severity: "CRITICAL",
      terminal: true,
      appliesTo: ["JOB", "WORKFLOW", "AGENT"],
    },
    {
      value: "MISSED",
      label: "Did not run",
      description: "A scheduled window passed and nothing ran.",
      severity: "CRITICAL",
      terminal: false,
      appliesTo: ["JOB", "WORKFLOW"],
    },
    {
      value: "AWAITING_APPROVAL",
      label: "Waiting for approval",
      description: "An agent has stopped and is waiting for someone.",
      severity: "WARNING",
      terminal: false,
      appliesTo: ["AGENT"],
    },
    {
      value: "TRIGGERED",
      label: "Firing",
      description: "An alert arrived from your monitoring and is firing.",
      severity: "CRITICAL",
      terminal: false,
      appliesTo: ["ALERT"],
    },
  ]);
  apiMock.pluginTargetTypes.mockResolvedValue([
    { value: "JOB", label: "Jobs", identifiesTargets: true },
    { value: "WORKFLOW", label: "Workflows", identifiesTargets: true },
    { value: "AGENT", label: "AI Agents", identifiesTargets: true },
    { value: "ALERT", label: "Alerts", identifiesTargets: false },
  ]);
  apiMock.pluginDeliveries.mockResolvedValue([]);
  apiMock.listProjects.mockResolvedValue([{ id: 3, name: "Platform" }]);
  apiMock.list.mockResolvedValue([]);
  apiMock.installPlugin.mockResolvedValue({});
  apiMock.testInstallation.mockResolvedValue({ ok: true, detail: "Delivered" });
});

describe("notification channels", () => {
  it("shows each provider's own mark, keyed by the plugin key", async () => {
    // Slack, Teams, Gmail and the rest are recognised by their logo long
    // before anyone reads the label.
    //
    // Teams earns its own assertion: plugin-service calls it
    // "microsoft-teams", so a logo map written against the common name
    // "teams" renders the generic glyph while looking entirely correct.
    apiMock.pluginCatalog.mockResolvedValueOnce([
      SLACK,
      { ...SLACK, key: "microsoft-teams", displayName: "Microsoft Teams" },
    ]);
    renderPage();

    expect((await screen.findAllByAltText("Slack logo")).length).toBeGreaterThan(0);
    const teams = await screen.findByAltText("Microsoft Teams logo");
    expect(teams).toHaveAttribute("src", "/assets/alerts/microsoft-teams.png");
  });

  it("offers the catalog and says nothing is configured yet", async () => {
    renderPage();

    expect(await screen.findByText("No channels yet")).toBeInTheDocument();
    expect(screen.getByText("Slack")).toBeInTheDocument();
    expect(screen.getByText("Not configured")).toBeInTheDocument();
  });

  /** A channel that tests green still delivers nothing without a rule. */
  it("warns when a channel has no rules attached", async () => {
    apiMock.listInstallations.mockResolvedValue([channel({ ruleCount: 0 })]);
    renderPage();

    expect(
      await screen.findByText("No rules — this channel receives nothing yet"),
    ).toBeInTheDocument();
  });

  it("shows a verified channel as verified", async () => {
    apiMock.listInstallations.mockResolvedValue([channel()]);
    renderPage();

    expect(await screen.findByText("Verified")).toBeInTheDocument();
    expect(screen.getByText("2 rules")).toBeInTheDocument();
  });

  it("gives a verified channel a green edge, and takes it back when disabled", async () => {
    apiMock.listInstallations.mockResolvedValue([channel()]);
    const { unmount } = renderPage();

    const card = (await screen.findByText("Ops alerts")).closest(".rounded-2xl");
    expect(card.className).toContain("!border-emerald-300");
    unmount();

    // Disabled outranks the green tick: the badge says "Disabled", so an
    // emerald edge beside it would contradict the badge it sits next to.
    apiMock.listInstallations.mockResolvedValue([channel({ enabled: false })]);
    renderPage();

    const off = (await screen.findByText("Ops alerts")).closest(".rounded-2xl");
    expect(off.className).not.toContain("!border-emerald-300");
  });

  /** Never tested is not the same as working, and must not look like it. */
  it("distinguishes an untested channel from a verified one", async () => {
    apiMock.listInstallations.mockResolvedValue([channel({ lastTestOk: null })]);
    renderPage();

    expect(await screen.findByText("Not tested")).toBeInTheDocument();
    expect(screen.queryByText("Verified")).not.toBeInTheDocument();
  });

  it("explains a parked channel and how to bring it back", async () => {
    apiMock.listInstallations.mockResolvedValue([
      channel({ parked: true, status: "PARKED", consecutiveFailures: 20 }),
    ]);
    renderPage();

    expect(await screen.findByText("Paused after failures")).toBeInTheDocument();
    expect(
      screen.getByText(/Delivery stopped after 20 failures in a row/),
    ).toBeInTheDocument();
  });

  it("builds the install form from the plugin's own field spec", async () => {
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: /Add/ }));

    expect(await screen.findByText("Add Slack")).toBeInTheDocument();
    expect(screen.getByText("Incoming webhook URL")).toBeInTheDocument();
    expect(screen.getByText("Override bot name")).toBeInTheDocument();
    expect(screen.getByText("Slack → Apps → Incoming Webhooks")).toBeInTheDocument();
  });

  it("submits only the fields that were filled in", async () => {
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: /Add/ }));
    await screen.findByText("Add Slack");

    fireEvent.change(screen.getByPlaceholderText("Ops alerts"), {
      target: { value: "  Ops alerts  " },
    });
    fireEvent.change(
      screen.getByPlaceholderText("https://hooks.slack.com/services/…"),
      { target: { value: "https://hooks.slack.com/services/T/B/x" } },
    );
    fireEvent.click(screen.getByRole("button", { name: /Save/ }));

    await waitFor(() =>
      expect(apiMock.installPlugin).toHaveBeenCalledWith({
        pluginKey: "slack",
        displayName: "Ops alerts",
        // `username` was left blank, so it is omitted rather than sent empty.
        config: { webhookUrl: "https://hooks.slack.com/services/T/B/x" },
      }),
    );
  });

  /**
   * The API never returns a secret, so an edit form has nothing to resubmit.
   * The field must start blank and say the saved value is being kept.
   */
  it("starts secret fields blank when editing and offers to keep the stored value", async () => {
    apiMock.listInstallations.mockResolvedValue([channel()]);
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: /Edit/ }));

    const secret = await screen.findByPlaceholderText("Saved — type to replace");
    expect(secret).toHaveValue("");
  });

  it("reports a failed connection test with the reason the service gave", async () => {
    apiMock.listInstallations.mockResolvedValue([channel()]);
    apiMock.testInstallation.mockResolvedValue({
      ok: false,
      statusCode: 404,
      detail: "HTTP 404: no_service",
    });
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: /Test/ }));

    await waitFor(() =>
      expect(storeState.pushToast).toHaveBeenCalledWith("HTTP 404: no_service", "red"),
    );
  });

  it("renders the event checkboxes from the backend vocabulary", async () => {
    apiMock.listInstallations.mockResolvedValue([channel()]);
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: /New rule/ }));

    expect(await screen.findByText("New notification rule")).toBeInTheDocument();
    expect(screen.getByText("Did not run")).toBeInTheDocument();
    expect(
      screen.getByText("A scheduled window passed and nothing ran."),
    ).toBeInTheDocument();
  });

  /** Workspace scope sends both ids as null — that is what "everything" means. */
  it("creates a workspace-wide rule with no target or project", async () => {
    apiMock.listInstallations.mockResolvedValue([channel()]);
    apiMock.createNotificationRule.mockResolvedValue({});
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: /New rule/ }));
    await screen.findByText("New notification rule");

    fireEvent.click(screen.getByRole("button", { name: /Create rule/ }));

    await waitFor(() =>
      expect(apiMock.createNotificationRule).toHaveBeenCalledWith({
        installationId: 1,
        targetType: "JOB",
        targetId: null,
        projectId: null,
        events: ["FAILED", "MISSED"],
        // Null, not absent and not "". Every rule written before the severity
        // floor existed means "no opinion", and that has to keep being what
        // sending nothing says.
        minSeverity: null,
      }),
    );
  });

  it("cannot create a rule with no events selected", async () => {
    apiMock.listInstallations.mockResolvedValue([channel()]);
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: /New rule/ }));
    await screen.findByText("New notification rule");

    fireEvent.click(screen.getByLabelText(/Failed/));
    fireEvent.click(screen.getByLabelText(/Did not run/));

    expect(screen.getByRole("button", { name: /Create rule/ })).toBeDisabled();
  });

  it("shows the reason when the page cannot load, and invents nothing", async () => {
    apiMock.pluginCatalog.mockRejectedValue(new Error("plugin-service unreachable"));
    renderPage();

    expect(await screen.findByText("plugin-service unreachable")).toBeInTheDocument();
    expect(screen.queryByText("Slack")).not.toBeInTheDocument();
  });
});

/**
 * Jobs and workflows were the only things a rule could watch. Agents and
 * alerts are now two more, and they are NOT interchangeable with them — an
 * alert never stalls, a job never parks on an approval. A form that offered
 * every event for every target would let someone save a rule that can never
 * fire, which reads as a broken channel rather than as a rule they mis-wrote.
 */
describe("watching agents and alerts", () => {
  const openRuleForm = async () => {
    apiMock.listInstallations.mockResolvedValue([channel()]);
    renderPage();
    await waitFor(() => expect(screen.getByText("Ops alerts")).toBeTruthy());
    fireEvent.click(screen.getByText("New rule"));
    await waitFor(() => expect(screen.getByText("New notification rule")).toBeTruthy());
  };

  const watchSelect = () => screen.getByLabelText("Watch", { selector: "select" });

  it("offers every target type the server publishes", async () => {
    await openRuleForm();
    const options = [...screen.getAllByRole("option")].map((o) => o.textContent);
    expect(options).toContain("AI Agents");
    expect(options).toContain("Alerts");
  });

  it("only offers events that can happen to the chosen target", async () => {
    await openRuleForm();
    // A job can be missed; an alert cannot — nothing was scheduled.
    expect(screen.getByText("Did not run")).toBeTruthy();

    fireEvent.change(watchSelect(), { target: { value: "ALERT" } });

    await waitFor(() => expect(screen.queryByText("Did not run")).toBeNull());
    expect(screen.getByText("Firing")).toBeTruthy();
  });

  it("offers the approval event for agents and nothing else", async () => {
    await openRuleForm();
    expect(screen.queryByText("Waiting for approval")).toBeNull();

    fireEvent.change(watchSelect(), { target: { value: "AGENT" } });

    await waitFor(() => expect(screen.getByText("Waiting for approval")).toBeTruthy());
    // An agent is not scheduled, so there is no window for it to miss.
    expect(screen.queryByText("Did not run")).toBeNull();
  });

  it("does not offer to watch ONE alert, because there is no id to watch", async () => {
    await openRuleForm();
    expect(screen.getByText("One job")).toBeTruthy();

    fireEvent.change(watchSelect(), { target: { value: "ALERT" } });

    await waitFor(() => expect(screen.queryByText("One alert")).toBeNull());
    expect(screen.getByText("Whole workspace")).toBeTruthy();
  });

  /**
   * A workspace takes hundreds of alerts a day, most of them informational. A
   * rule that cannot say "critical only" is a rule nobody can afford to leave
   * on, and a channel people mute is worse than one they never had.
   */
  it("offers a severity floor for alerts and not for runs", async () => {
    await openRuleForm();
    expect(screen.queryByText("Only if at least")).toBeNull();

    fireEvent.change(watchSelect(), { target: { value: "ALERT" } });

    await waitFor(() => expect(screen.getByText("Only if at least")).toBeTruthy());
  });

  it("sends the target type, the events and the floor that were chosen", async () => {
    apiMock.createNotificationRule.mockResolvedValue({});
    await openRuleForm();

    fireEvent.change(watchSelect(), { target: { value: "ALERT" } });
    await waitFor(() => expect(screen.getByText("Firing")).toBeTruthy());
    fireEvent.change(screen.getByLabelText("Only if at least", { selector: "select" }), {
      target: { value: "CRITICAL" },
    });
    fireEvent.click(screen.getByText("Create rule"));

    await waitFor(() =>
      expect(apiMock.createNotificationRule).toHaveBeenCalledWith(
        expect.objectContaining({
          targetType: "ALERT",
          events: ["TRIGGERED"],
          minSeverity: "CRITICAL",
          targetId: null,
        }),
      ),
    );
  });

  it("sends no floor at all when none was chosen", async () => {
    // Null, not "". Every rule written before this control existed means "no
    // opinion", and an empty string would be a severity nobody picked.
    apiMock.createNotificationRule.mockResolvedValue({});
    await openRuleForm();
    fireEvent.click(screen.getByText("Create rule"));

    await waitFor(() =>
      expect(apiMock.createNotificationRule).toHaveBeenCalledWith(
        expect.objectContaining({ minSeverity: null }),
      ),
    );
  });
});
