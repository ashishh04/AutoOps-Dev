package com.intertec.autoops.alerts.web.dto;

/**
 * An investigation, which may not have finished.
 *
 * <p>The two engines behave differently and this type refuses to hide that.
 * The investigation engine answers in one call; the AWS agent accepts the work
 * and runs for minutes, so the console is told {@code running} and polls. A
 * shape that pretended both were synchronous would mean holding a request open
 * for minutes through the gateway, which is the wrong thing to do to a
 * blocking proxy and a worse thing to do to someone on a bridge call.
 *
 * @param engine        which engine ran it, for the console to name honestly
 * @param status        {@code running}, {@code complete} or {@code failed}
 * @param investigation present once complete
 * @param runId         the agent run to poll, while running
 */
public record InvestigationStatusView(String engine,
                                      String status,
                                      InvestigationView investigation,
                                      Long runId,
                                      String message) {

    public static InvestigationStatusView complete(String engine, InvestigationView v) {
        return new InvestigationStatusView(engine, "complete", v, null, null);
    }

    public static InvestigationStatusView running(String engine, Long runId) {
        return new InvestigationStatusView(engine, "running", null, runId, null);
    }

    public static InvestigationStatusView failed(String engine, String message) {
        return new InvestigationStatusView(engine, "failed", null, null, message);
    }

    public static InvestigationStatusView none() {
        return new InvestigationStatusView(null, "none", null, null, null);
    }
}
