package com.intertec.autoops.alerts.web;

import com.intertec.autoops.alerts.client.KeepApiClient;
import com.intertec.autoops.alerts.client.PluginClient;
import com.intertec.autoops.alerts.service.ProviderCatalogService;
import com.intertec.autoops.alerts.config.AlertProperties;
import com.intertec.autoops.alerts.exception.AlertException;
import com.intertec.autoops.alerts.service.IngestToken;
import com.intertec.autoops.alerts.service.TenantScope;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The public door a customer's monitoring tool posts alerts through.
 *
 * <p>Unauthenticated in the JWT sense — Datadog has no AutoOps login — so the
 * signed token IS the credential, exactly as {@code /api/hooks/{token}} works
 * for webhooks. It travels in a header rather than the path so it stays out of
 * proxy logs and browser history.
 *
 * <p><b>This is where an alert gets its owner.</b> The token names the tenant
 * and project, so the labels are stamped HERE, by us, from a signed value — a
 * caller cannot set them, and a caller cannot omit them. Everything downstream
 * can then trust {@code autoops_tenant} because nothing else can write it.
 */
@RestController
public class IngestController {

    private final KeepApiClient engine;
    private final AlertProperties properties;
    private final PluginClient notifications;
    private final ProviderCatalogService providers;

    public IngestController(KeepApiClient engine, AlertProperties properties,
                            PluginClient notifications, ProviderCatalogService providers) {
        this.engine = engine;
        this.properties = properties;
        this.notifications = notifications;
        this.providers = providers;
    }

    /**
     * Which connected source this token's scope owns for that type.
     *
     * <p>Never fails the ingest. This is a best-effort improvement to how
     * recognisable the alert is; a monitoring tool that gets an error back will
     * retry, and duplicate alerts are a worse outcome than one alert that is
     * harder to attribute.
     */
    private String connectionFor(IngestToken.Scope scope, String providerType) {
        try {
            return providers.connectedIdFor(scope.tenantId(), scope.projectId(), providerType);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    @PostMapping("/api/alerts/ingest/{providerType}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> ingest(@PathVariable String providerType,
                                      @RequestHeader(value = "X-API-KEY", required = false) String headerKey,
                                      @RequestBody(required = false) Map<String, Object> body) {
        IngestToken.Scope scope = IngestToken
                .verify(properties.getIngest().getSecret(), headerKey)
                // One answer for every failure. Saying "unknown project" or
                // "bad signature" would let someone probe which half was wrong.
                .orElseThrow(() -> AlertException.unauthorized("invalid_ingest_key",
                        "That key is not valid for this source."));

        // The type in the token wins over the one in the path. They are minted
        // together, so a mismatch means someone is reusing a token for a source
        // it was not issued for.
        if (!scope.providerType().equals(providerType)) {
            throw AlertException.unauthorized("invalid_ingest_key",
                    "That key is not valid for this source.");
        }

        Map<String, Object> alert = body == null ? new LinkedHashMap<>() : new LinkedHashMap<>(body);

        // Stamped LAST and unconditionally, so a payload that already carries
        // these keys cannot claim another tenant's alerts.
        Map<String, Object> labels = new HashMap<>();
        Object existing = alert.get("labels");
        if (existing instanceof Map<?, ?> m) {
            m.forEach((k, v) -> labels.put(String.valueOf(k), v));
        }
        labels.put(TenantScope.TENANT_LABEL, scope.tenantId());
        labels.put(TenantScope.PROJECT_LABEL, scope.projectId());
        alert.put("labels", labels);

        // TWO ways to recognise this alert later, because one of them is not
        // reliable. The labels above are stamped at the top level of the
        // payload; the engine then runs this source type's own parser, and a
        // parser that rebuilds the alert from the vendor's shape — Alertmanager
        // nests its labels under alerts[].labels — throws the stamp away. The
        // alert then belongs to nobody, and the customer who just sent it does
        // not see it in their own feed.
        //
        // Naming the connection it arrived through is the second route, and the
        // one that survives any parser. See TenantScope.arrivedThroughOwnedSource.
        engine.ingest(providerType, alert, connectionFor(scope, providerType));

        // AFTER the engine has it, and only then. A notification for an alert
        // that failed to land would send someone to a feed it is not in — and
        // the ingest call throwing is how this door tells the monitoring tool
        // to retry, which must not be pre-empted by a Slack message.
        //
        // The SIGNED scope, not the payload, decides whose channels this
        // reaches. The body is a third party's.
        notifications.publish(scope, alert);
        return Map.of("accepted", true);
    }
}
