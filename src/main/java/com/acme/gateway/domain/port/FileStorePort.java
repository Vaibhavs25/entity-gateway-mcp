package com.acme.gateway.domain.port;

/**
 * Hexagonal port for the remote file store. The domain never sees Commons Net.
 * Swap FTPS/SFTP adapters behind this interface with zero domain changes.
 */
public interface FileStorePort {

    enum DeleteOutcome {
        /** Server acknowledged deletion (FTP 250). */
        DELETED,
        /** File was already absent (FTP 550) — idempotent success: the desired
         *  postcondition ("file does not exist") already holds. */
        ALREADY_ABSENT
    }

    /**
     * Deletes {@code path}. MUST be invoked with NO database transaction open
     * (enforced by the orchestrator's structure, see SignatureDeletionOrchestrator).
     *
     * @throws com.acme.gateway.ftp.FtpOperationException classified RETRYABLE_* or PERMANENT
     */
    DeleteOutcome delete(String path);
}
