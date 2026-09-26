package com.intertec.autoops.alerts.web;

import com.intertec.autoops.alerts.exception.AlertException;
import com.intertec.autoops.alerts.service.ProviderCatalogService;
import com.intertec.autoops.alerts.web.dto.ConnectedProviderView;
import com.intertec.autoops.alerts.web.dto.ProviderSetupView;
import com.intertec.autoops.alerts.web.dto.ProviderTypeView;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Connecting a project's monitoring sources.
 *
 * <p>Unlike the rest of this service this surface WRITES, so the VIEWER rule
 * that {@code SecurityConfig} enforces on POST/DELETE applies for the first
 * time here: connecting Datadog hands a third party's credentials to the
 * platform, which is not something a read-only role does.
 *
 * <p>Credentials go one way. They are posted in, forwarded to the engine, and
 * there is deliberately no endpoint that reads one back — the engine itself
 * only ever returns them masked, and adding a GET would make this service the
 * weakest point in that chain.
 */
@RestController
public class ProviderController {

    private final ProviderCatalogService providers;

    public ProviderController(ProviderCatalogService providers) {
        this.providers = providers;
    }

    public record ConnectRequest(String type, String name, Map<String, Object> config) {
    }

    /** What can be connected. Identical for every caller — it is a catalog, not data. */
    @GetMapping("/api/alert-providers")
    public List<ProviderTypeView> catalog() {
        return providers.catalog();
    }

    /**
     * What has already been connected.
     *
     * <p>{@code projectId} is optional and NARROWS. Omitted, this answers for
     * the whole workspace, which is how the alert plane is read at tenant level
     * — a customer connects Datadog once rather than repeating it in every
     * project. Each row still reports the project that owns it.
     */
    @GetMapping("/api/alert-providers/connected")
    public List<ConnectedProviderView> connected(@RequestParam(required = false) Long projectId,
                                                 @AuthenticationPrincipal Jwt jwt) {
        return providers.connected(tenant(jwt), scope(projectId));
    }

    /** How to make this source send alerts here. Scoped to the caller's project. */
    @GetMapping("/api/alert-providers/{type}/setup")
    public ProviderSetupView setup(@PathVariable String type,
                                   @RequestParam Long projectId,
                                   @AuthenticationPrincipal Jwt jwt) {
        return providers.setup(tenant(jwt), String.valueOf(projectId), type);
    }

    @PostMapping("/api/alert-providers")
    @ResponseStatus(HttpStatus.CREATED)
    public ConnectedProviderView connect(@RequestParam Long projectId,
                                         @RequestBody ConnectRequest request,
                                         @AuthenticationPrincipal Jwt jwt) {
        if (request == null || request.type() == null || request.type().isBlank()) {
            throw AlertException.badRequest("missing_type", "Choose a monitoring source.");
        }
        return providers.connect(tenant(jwt), String.valueOf(projectId),
                request.type(),
                request.name() == null || request.name().isBlank()
                        ? request.type() : request.name(),
                request.config() == null ? Map.of() : request.config());
    }

    /**
     * {@code projectId} optional for the same reason as {@link #connected}: a
     * source removed from the workspace screen is one the customer found there,
     * and they should not have to name the project it happens to live in.
     *
     * <p>This does not weaken the check. Ownership is still proved by finding
     * the id among the sources this TENANT connected; an id belonging to
     * another workspace is still a 404.
     */
    @DeleteMapping("/api/alert-providers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(@PathVariable String id,
                           @RequestParam(required = false) Long projectId,
                           @AuthenticationPrincipal Jwt jwt) {
        providers.disconnect(tenant(jwt), scope(projectId), id);
    }

    /**
     * Null rather than {@code "null"}. {@code String.valueOf} on an absent Long
     * produces the four-character string, which would build a prefix matching
     * nothing and read as "this workspace has connected nothing".
     */
    private static String scope(Long projectId) {
        return projectId == null ? null : String.valueOf(projectId);
    }

    private static String tenant(Jwt jwt) {
        String tenantId = jwt.getClaimAsString("tenantId");
        if (tenantId == null || tenantId.isBlank()) {
            throw AlertException.badRequest("missing_tenant", "Token has no tenantId claim");
        }
        return tenantId;
    }
}
