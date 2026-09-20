package com.intertec.autoops.core.exception;

import org.springframework.http.HttpStatus;

/** Domain error carrying a snake_case code + HTTP status (same convention as auth-service). */
public class CoreException extends RuntimeException {

    private final String error;
    private final HttpStatus status;

    private CoreException(String error, String message, HttpStatus status) {
        super(message);
        this.error = error;
        this.status = status;
    }

    public static CoreException badRequest(String error, String message) {
        return new CoreException(error, message, HttpStatus.BAD_REQUEST);
    }

    public static CoreException forbidden(String error, String message) {
        return new CoreException(error, message, HttpStatus.FORBIDDEN);
    }

    public static CoreException notFound(String error, String message) {
        return new CoreException(error, message, HttpStatus.NOT_FOUND);
    }

    public static CoreException conflict(String error, String message) {
        return new CoreException(error, message, HttpStatus.CONFLICT);
    }

    public static CoreException serviceUnavailable(String error, String message) {
        return new CoreException(error, message, HttpStatus.SERVICE_UNAVAILABLE);
    }

    /**
     * An upstream we depend on answered, but wrongly — a rejection of OUR
     * credentials, say. Distinct from 503 (couldn't reach it) and from 401
     * (which the console would misread as the USER's session expiring).
     */
    public static CoreException badGateway(String error, String message) {
        return new CoreException(error, message, HttpStatus.BAD_GATEWAY);
    }

    public String getError() {
        return error;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
