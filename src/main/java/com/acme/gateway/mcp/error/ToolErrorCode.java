package com.acme.gateway.mcp.error;

/** Stable machine-readable codes — agents branch on these, not on prose. */
public enum ToolErrorCode {
    VALIDATION_FAILED(false),
    NOT_FOUND(false),
    FORBIDDEN(false),
    INVALID_TRANSITION(false),
    CONFLICT(true),               // refetch-and-retry is meaningful
    FTP_UNAVAILABLE(true),
    DELETION_IN_PROGRESS(false),
    RATE_LIMITED(true),
    INTERNAL(true);

    private final boolean retryable;
    ToolErrorCode(boolean retryable) { this.retryable = retryable; }
    public boolean retryable() { return retryable; }
}
