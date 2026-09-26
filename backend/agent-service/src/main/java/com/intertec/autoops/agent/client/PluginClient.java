package com.intertec.autoops.agent.client;

import com.intertec.autoops.agent.config.AgentProperties;
import com.intertec.autoops.agent.domain.AgentRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Reports agent run lifecycle events to plugin-service, which decides whose
 * Slack, Teams, Outlook, Gmail or GitHub they reach.
 *
 * <h2>Why agent-service had to grow its own</h2>
 * core-service already reports job and workflow runs this way, and an agent run
 * is the third kind. It is not a copy for its own sake — agent runs live in
 * this service's tables and never pass through core's run engine, so there is
 * no point at which core could report one.
 *
 * <h2>The event that justifies the whole thing</h2>
 * {@code AWAITING_APPROVAL}. Every other lifecycle event describes something
 * that already finished and can be read whenever someone next looks. That one
 * describes work that is STOPPED and will stay stopped until a human approves
 * it, and nobody sits watching a console for it. An agent that parked at 2am
 * and was noticed at 9 is the failure this closes.
 *
 * <h2>Failure policy: never throw, never retry</h2>
 * Identical to core's, and for the same reason. This sits on the agent loop's
 * own thread between turns. A notification that cannot be delivered must not
 * fail the run that caused it, and a retry loop here would hold a run open
 * waiting on a service that is already unhealthy — plugin-service owns delivery
 * retries; this owns handing the event over once.
 */
@Component
public class PluginClient {

    private static final Logger log = LoggerFactory.getLogger(PluginClient.class);

    private final RestClient pluginRestClient;
    private final String internalToken;
    private final boolean enabled;

    public PluginClient(@Qualifier("pluginRestClient") RestClient pluginRestClient,
                        AgentProperties properties) {
        this.pluginRestClient = pluginRestClient;
        this.internalToken = properties.getPlugin().getInternalToken();
        this.enabled = properties.getPlugin().isEnabled();
    }

    /**
     * The name plugin-service knows a run status by.
     *
     * <p><b>The spellings differ, and that is not a mistake to correct here.</b>
     * This service spells it {@code CANCELLED}; plugin-service's
     * {@code LifecycleEvent} spells it {@code CANCELED}, because it has to line
     * up with core-service's {@code RunStatus} — which is what every job and
     * workflow in the platform reports. Two of the three agree, so the odd one
     * out translates, here, where the wire format is written.
     *
     * <p>Passing {@code status.name()} straight through is the bug this
     * replaces, and it is invisible: plugin-service cannot bind the unknown
     * name, answers 400, and {@link #publish} swallows that at DEBUG on purpose.
     * Every cancellation notice would vanish with nothing logged anywhere a
     * person would look.
     */
    public static String eventFor(AgentRun.Status status) {
        return status == AgentRun.Status.CANCELLED ? "CANCELED" : status.name();
    }

    /**
     * Reports one moment in an agent run.
     *
     * <p>The agent NAME travels, not its id alone. A Slack message reading
     * "Agent 41 is waiting for approval" is a message the reader has to go and
     * decode before they can act on it.
     *
     * @param agentName the name a customer gave the agent
     * @param event     a {@code LifecycleEvent} name, passed as a string so
     *                  this service does not take a dependency on
     *                  plugin-service's enum. Use {@link #eventFor} for the
     *                  ones that come from a status. plugin-service's
     *                  {@code EventNameContractTest} pins the other end.
     * @param detail    the error for FAILED, what is being approved for
     *                  AWAITING_APPROVAL, else null
     */
    public void publish(AgentRun run, String agentName, String event, String detail) {
        if (!enabled || run == null) {
            return;
        }
        Map<String, Object> body = new HashMap<>();
        body.put("tenantId", run.getTenantId());
        body.put("targetType", "AGENT");
        body.put("targetId", run.getAgentId());
        body.put("targetName", agentName == null || agentName.isBlank()
                ? "Agent " + run.getAgentId() : agentName);
        body.put("event", event);
        body.put("runId", run.getId());
        body.put("projectId", run.getProjectId());
        body.put("projectName", null);
        body.put("triggeredBy", run.getCreatedBy());
        body.put("detail", detail);
        body.put("occurredAt", Instant.now().toString());
        body.put("durationSeconds", elapsed(run));

        try {
            pluginRestClient.post()
                    .uri("/internal/events")
                    .header("X-Internal-Token", internalToken)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception ex) {
            // Debug, not warn. With plugin-service intentionally not started
            // this would otherwise log on every status change of every run.
            log.debug("Could not report {} for agent {} (tenant {}): {}",
                    event, run.getAgentId(), run.getTenantId(), ex.getMessage());
        }
    }

    /**
     * How long the run has been going, or null while there is nothing to
     * measure.
     *
     * <p>Read from the run's own timestamps rather than held in memory: a run
     * outlives the request that started it, and the loop may resume it on a
     * different thread after an approval that took two days.
     */
    private static Long elapsed(AgentRun run) {
        Instant started = run.getStartedAt() != null ? run.getStartedAt() : run.getCreatedAt();
        if (started == null) {
            return null;
        }
        Instant ended = run.getFinishedAt() != null ? run.getFinishedAt() : Instant.now();
        long seconds = Duration.between(started, ended).toSeconds();
        // Negative is impossible unless clocks moved; report nothing rather
        // than a duration that reads as a bug in the agent.
        return seconds < 0 ? null : seconds;
    }
}
