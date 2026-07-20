package com.acme.gateway.domain.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per deletion operation — the durable saga state machine.
 * Every crash point in the FTP+DB deletion leaves a row here that the
 * reconciler can drive to convergence (architecture doc §6).
 */
@Entity
@Table(name = "file_deletion_ledger")
public class FileDeletionLedger {

    @Id
    @Column(columnDefinition = "uniqueidentifier")
    private UUID id;

    /** Client-supplied idempotency key; UNIQUE at the DB level. */
    @Column(name = "request_id", nullable = false, unique = true, columnDefinition = "uniqueidentifier")
    private UUID requestId;

    @Column(name = "signature_id", nullable = false, columnDefinition = "uniqueidentifier")
    private UUID signatureId;

    @Column(name = "resolved_ftp_path", nullable = false, length = 512)
    private String resolvedFtpPath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DeletionState state;

    @Column(nullable = false)
    private int attempts;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_error_class", nullable = false, length = 32)
    private ErrorClass lastErrorClass = ErrorClass.NONE;

    @Column(name = "last_error_msg", length = 512)
    private String lastErrorMsg;

    @Column(nullable = false, length = 256)
    private String reason;

    @Column(name = "initiated_by", nullable = false, length = 256)
    private String initiatedBy;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    /** Optimistic lock resolves the inline-tool vs. reconciler race. */
    @Version
    @Column(nullable = false)
    private long version;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "completed_at")
    private Instant completedAt;

    protected FileDeletionLedger() { }

    public FileDeletionLedger(UUID requestId, UUID signatureId, String resolvedFtpPath,
                              String reason, String initiatedBy, String traceId) {
        this.id = UUID.randomUUID();
        this.requestId = requestId;
        this.signatureId = signatureId;
        this.resolvedFtpPath = resolvedFtpPath;
        this.state = DeletionState.PENDING_DELETE;
        this.attempts = 0;
        this.nextAttemptAt = Instant.now();
        this.reason = reason;
        this.initiatedBy = initiatedBy;
        this.traceId = traceId;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** TX2: FTP acknowledged (250, or idempotent 550-not-found). */
    public void markFtpDeleted() {
        this.state = DeletionState.FTP_DELETED;
        this.lastErrorClass = ErrorClass.NONE;
        this.lastErrorMsg = null;
        this.updatedAt = Instant.now();
    }

    /** TX3: DB finalization done — terminal success. */
    public void markCompleted() {
        this.state = DeletionState.COMPLETED;
        this.completedAt = Instant.now();
        this.updatedAt = this.completedAt;
    }

    /** Scenario B: schedule a retry with exponential backoff. */
    public void markRetry(ErrorClass errorClass, String message, Instant nextAttemptAt) {
        this.state = DeletionState.FTP_FAILED_RETRYING;
        this.attempts++;
        this.lastErrorClass = errorClass;
        this.lastErrorMsg = truncate(message);
        this.nextAttemptAt = nextAttemptAt;
        this.updatedAt = Instant.now();
    }

    /** Retries exhausted or permanent failure — operator runbook takes over. */
    public void markManual(ErrorClass errorClass, String message) {
        this.state = DeletionState.FAILED_MANUAL;
        this.attempts++;
        this.lastErrorClass = errorClass;
        this.lastErrorMsg = truncate(message);
        this.updatedAt = Instant.now();
    }

    private static String truncate(String s) {
        return (s == null || s.length() <= 512) ? s : s.substring(0, 512);
    }

    public UUID getId() { return id; }
    public UUID getRequestId() { return requestId; }
    public UUID getSignatureId() { return signatureId; }
    public String getResolvedFtpPath() { return resolvedFtpPath; }
    public DeletionState getState() { return state; }
    public int getAttempts() { return attempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public ErrorClass getLastErrorClass() { return lastErrorClass; }
    public String getInitiatedBy() { return initiatedBy; }
    public String getTraceId() { return traceId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
}
