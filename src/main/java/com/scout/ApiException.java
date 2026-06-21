package com.scout;

/**
 * A non-2xx HTTP response. Carries the status, parsed body, request id, and a
 * machine-readable code. Use {@link #getStatusCode()} or the {@code isX}
 * predicates to branch on the failure.
 */
public class ApiException extends ScoutException {

    private final int statusCode;
    private final String requestId;
    private final String code;
    private final Object body;
    /** Seconds from a Retry-After header, or -1. Package-private. */
    double retryAfterSeconds = -1;

    public ApiException(String message, int statusCode, String requestId, String code, Object body) {
        super(message);
        this.statusCode = statusCode;
        this.requestId = requestId;
        this.code = code;
        this.body = body;
    }

    /** HTTP status code. */
    public int getStatusCode() {
        return statusCode;
    }

    /** Server-assigned request id (from {@code x-request-id}), for support. */
    public String getRequestId() {
        return requestId;
    }

    /** Machine-readable code from the body, if any. */
    public String getCode() {
        return code;
    }

    /** Parsed JSON error body, if any. */
    public Object getBody() {
        return body;
    }

    public boolean isBadRequest() {
        return statusCode == 400;
    }

    public boolean isAuthentication() {
        return statusCode == 401;
    }

    public boolean isInsufficientCredits() {
        return statusCode == 402;
    }

    public boolean isPermissionDenied() {
        return statusCode == 403;
    }

    public boolean isNotFound() {
        return statusCode == 404;
    }

    public boolean isRateLimited() {
        return statusCode == 429;
    }

    public boolean isServerError() {
        return statusCode >= 500;
    }
}
