package com.intertec.autoops.alerts.web;

import com.intertec.autoops.alerts.client.KeepApiClient;
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

    public IngestController(KeepApiClient engine, AlertProperties properties) {
        this.engine = engine;
        this.properties = properties;
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

        engine.ingest(providerType, alert);
        return Map.of("accepted", true);
    }
}
