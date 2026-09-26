package com.intertec.autoops.plugin.web;

import com.intertec.autoops.plugin.domain.LifecycleEvent;
import com.intertec.autoops.plugin.domain.TargetType;
import com.intertec.autoops.plugin.exception.PluginException;
import com.intertec.autoops.plugin.repo.PluginInstallationRepository;
import com.intertec.autoops.plugin.service.PluginRegistry;
import com.intertec.autoops.plugin.spi.ConfigField;
import com.intertec.autoops.plugin.spi.PluginDescriptor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * What can be installed, and what can be subscribed to.
 *
 * <p>The install form is rendered from {@link ConfigField}, and the event
 * checkboxes from {@link LifecycleEvent}, so adding a provider or an event
 * needs no frontend change. The old connectors UI hard-coded its three kinds
 * and their fields in JSX; that is precisely what this replaces.
 */
@RestController
public class PluginCatalogController {

    private final PluginRegistry registry;
    private final PluginInstallationRepository installations;

    public PluginCatalogController(PluginRegistry registry,
                                   PluginInstallationRepository installations) {
        this.registry = registry;
        this.installations = installations;
    }

    /** The plugin catalog, with how many copies this tenant already has. */
    @GetMapping("/api/plugins/catalog")
    public List<CatalogEntry> catalog(@AuthenticationPrincipal Jwt jwt) {
        String tenantId = tenant(jwt);
        return registry.catalog().stream()
                .map(descriptor -> CatalogEntry.from(descriptor,
                        installations.findByTenantIdAndPluginKey(tenantId, descriptor.key()).size()))
                .toList();
    }

    /**
     * The event vocabulary a rule can subscribe to, with its severity and the
     * target types it can happen to.
     *
     * <p>{@code appliesTo} is the part the console cannot infer. An alert never
     * stalls and a job never parks on an approval; without this the form offers
     * every event for every target, a customer picks one that cannot fire, and
     * the rule they end up with is indistinguishable from a broken channel.
     */
    @GetMapping("/api/plugins/events")
    public List<EventOption> events() {
        return Arrays.stream(LifecycleEvent.values())
                .map(event -> new EventOption(
                        event.name(),
                        label(event),
                        describe(event),
                        event.severity().name(),
                        event.isTerminal(),
                        Arrays.stream(TargetType.values())
                                .filter(event::appliesTo)
                                .map(Enum::name)
                                .toList()))
                .toList();
    }

    /**
     * The target kinds a rule can watch.
     *
     * <p>{@code identifiesTargets} is false for ALERT alone: an alert has no
     * numeric id, so a rule for one is always project-wide or workspace-wide.
     * The console needs that to know whether to render a target picker at all.
     */
    @GetMapping("/api/plugins/target-types")
    public List<TargetOption> targetTypes() {
        return Arrays.stream(TargetType.values())
                .map(type -> new TargetOption(type.name(), targetLabel(type),
                        type.identifiesTargets()))
                .toList();
    }

    private static String targetLabel(TargetType type) {
        return switch (type) {
            case JOB -> "Jobs";
            case WORKFLOW -> "Workflows";
            case AGENT -> "AI Agents";
            case ALERT -> "Alerts";
        };
    }

    private static String label(LifecycleEvent event) {
        return switch (event) {
            case QUEUED -> "Queued";
            case STARTED -> "Started";
            case SUCCEEDED -> "Succeeded";
            case FAILED -> "Failed";
            case CANCELED -> "Canceled";
            case MISSED -> "Did not run";
            case STALLED -> "Running too long";
            case RECOVERED -> "Recovered";
            case AWAITING_APPROVAL -> "Waiting for approval";
            case TRIGGERED -> "Firing";
            case RESOLVED -> "Resolved";
            case ACKNOWLEDGED -> "Acknowledged";
        };
    }

    private static String describe(LifecycleEvent event) {
        return switch (event) {
            case QUEUED -> "Accepted by the scheduler or an API call, before any step runs.";
            case STARTED -> "The run engine picked it up and the first step began.";
            case SUCCEEDED -> "Every step finished cleanly.";
            case FAILED -> "A step failed, or the run engine crashed part-way through.";
            case CANCELED -> "Stopped by a user or by a shutdown.";
            case MISSED -> "A scheduled window passed and nothing ran at all.";
            case STALLED -> "Still running well past how long it normally takes.";
            case RECOVERED -> "Succeeded for the first time after one or more failures.";
            case AWAITING_APPROVAL ->
                    "An agent has stopped and is waiting for someone to approve an action. "
                            + "It stays stopped until they do.";
            case TRIGGERED -> "An alert arrived from your monitoring and is firing.";
            case RESOLVED -> "An alert that was firing has cleared.";
            case ACKNOWLEDGED ->
                    "Someone took ownership of an alert in the tool that raised it.";
        };
    }

    private String tenant(Jwt jwt) {
        String tenantId = jwt.getClaimAsString("tenantId");
        if (tenantId == null || tenantId.isBlank()) {
            throw PluginException.badRequest("missing_tenant", "Token has no tenantId claim");
        }
        return tenantId;
    }

    /** A plugin plus this tenant's install count. */
    public record CatalogEntry(
            String key,
            String displayName,
            String category,
            String summary,
            String setupUrl,
            List<ConfigField> fields,
            int installedCount) {

        static CatalogEntry from(PluginDescriptor descriptor, int installedCount) {
            return new CatalogEntry(
                    descriptor.key(),
                    descriptor.displayName(),
                    descriptor.category().name(),
                    descriptor.summary(),
                    descriptor.setupUrl(),
                    descriptor.fields(),
                    installedCount);
        }
    }

    /**
     * @param appliesTo the target types this event can happen to. Never empty —
     *                  an event nothing can raise would be dead vocabulary.
     */
    public record EventOption(String value, String label, String description, String severity,
                              boolean terminal, List<String> appliesTo) {
    }

    /**
     * @param identifiesTargets whether a rule of this kind can name one specific
     *                          target, or only a project and the workspace
     */
    public record TargetOption(String value, String label, boolean identifiesTargets) {
    }
}
