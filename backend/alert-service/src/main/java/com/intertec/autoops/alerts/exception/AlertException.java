package com.intertec.autoops.alerts.exception;

import org.springframework.http.HttpStatus;

/**
 * Domain error carrying a snake_case code + HTTP status, matching the
 * convention every other AutoOps service uses so the console can key off the
 * code rather than parse prose.
 *
 * <p>The codes that matter to the UI: {@code alert_not_found},
 * {@code provider_only}, {@code alert_engine_unreachable},
 * {@code alert_engine_unauthorized}, {@code alert_engine_error}.
 *
 * <p>None of them name the engine, and neither does any message built here.
 * "Alert engine" is the product's own word for it; a customer has never heard
 * of the thing behind it and must not learn of it from an error body.
 */
public class AlertException extends RuntimeException {

    private final String error;
    private final HttpStatus status;

    private AlertException(String error, String message, HttpStatus status) {
        super(message);
        this.error = error;
        this.status = status;
    }

    public static AlertException badRequest(String error, String message) {
        return new AlertException(error, message, HttpStatus.BAD_REQUEST);
    }

    /**
     * The ingest token did not verify. Deliberately 401 and not 500: a
     * monitoring tool treats 5xx as "retry later" and will hammer a
     * misconfigured webhook forever, while 4xx says "fix your config" and stops.
     */
    public static AlertException unauthorized(String error, String message) {
        return new AlertException(error, message, HttpStatus.UNAUTHORIZED);
    }

    public static AlertException forbidden(String error, String message) {
        return new AlertException(error, message, HttpStatus.FORBIDDEN);
    }

    public static AlertException notFound(String error, String message) {
        return new AlertException(error, message, HttpStatus.NOT_FOUND);
    }

    /**
     * The alert engine failed us. Deliberately 502, not 500: this service is
     * healthy, the thing it depends on is not, and an operator reading the
     * status code should be pointed at the right box.
     */
    public static AlertException upstream(String error, String message) {
        return new AlertException(error, message, HttpStatus.BAD_GATEWAY);
    }

    public String getError() {
        return error;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
