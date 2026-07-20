package com.acme.gateway.mcp.error;

/**
 * Domain-level exception carrying a stable error code and an agent-safe
 * message. The message MUST already be safe (no SQL, paths, hostnames):
 * only these messages ever cross the MCP boundary.
 */
public class GatewayException extends RuntimeException {

    private final ToolErrorCode code;

    public GatewayException(ToolErrorCode code, String safeMessage) {
        super(safeMessage);
        this.code = code;
    }

    public ToolErrorCode code() { return code; }

    public static GatewayException notFound(String what)   { return new GatewayException(ToolErrorCode.NOT_FOUND, what + " not found."); }
    public static GatewayException conflict(String msg)    { return new GatewayException(ToolErrorCode.CONFLICT, msg); }
    public static GatewayException invalidTransition(String msg) { return new GatewayException(ToolErrorCode.INVALID_TRANSITION, msg); }
    public static GatewayException validation(String msg)  { return new GatewayException(ToolErrorCode.VALIDATION_FAILED, msg); }
    public static GatewayException deletionInProgress()    { return new GatewayException(ToolErrorCode.DELETION_IN_PROGRESS, "A deletion for this signature is already in progress; poll get_deletion_status."); }
}
