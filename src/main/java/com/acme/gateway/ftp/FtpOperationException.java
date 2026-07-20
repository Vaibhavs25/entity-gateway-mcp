package com.acme.gateway.ftp;

import com.acme.gateway.domain.model.ErrorClass;

/**
 * Carries the RETRYABLE / PERMANENT classification that drives the ledger
 * state machine (Scenario B handling). The message is diagnostic-grade and is
 * NEVER forwarded to MCP clients (see GatewayMcpExceptionMapper).
 */
public class FtpOperationException extends RuntimeException {

    private final ErrorClass errorClass;
    private final int replyCode;

    public FtpOperationException(ErrorClass errorClass, int replyCode, String message, Throwable cause) {
        super(message, cause);
        this.errorClass = errorClass;
        this.replyCode = replyCode;
    }

    public ErrorClass errorClass() { return errorClass; }
    public int replyCode() { return replyCode; }
    public boolean retryable() { return errorClass != ErrorClass.PERMANENT; }
}
