-- Deletion saga ledger: the durable state machine driving FTP+DB deletion.
-- The DB transition is the commit point; FTP is an idempotent, retryable effect.
CREATE TABLE file_deletion_ledger (
    id                UNIQUEIDENTIFIER NOT NULL PRIMARY KEY,
    request_id        UNIQUEIDENTIFIER NOT NULL,          -- client idempotency key
    signature_id      UNIQUEIDENTIFIER NOT NULL
        CONSTRAINT fk_ledger_signature REFERENCES signature_file (id),
    resolved_ftp_path NVARCHAR(512)    NOT NULL,
    state             VARCHAR(32)      NOT NULL,          -- PENDING_DELETE | FTP_DELETED | COMPLETED | FTP_FAILED_RETRYING | FAILED_MANUAL
    attempts          INT              NOT NULL DEFAULT 0,
    next_attempt_at   DATETIME2(3)     NOT NULL,
    last_error_class  VARCHAR(32)      NOT NULL DEFAULT 'NONE',
    last_error_msg    NVARCHAR(512)    NULL,              -- truncated; diagnostic only
    reason            NVARCHAR(256)    NOT NULL,          -- mandatory audit justification
    initiated_by      NVARCHAR(256)    NOT NULL,          -- JWT subject of the calling agent
    trace_id          VARCHAR(64)      NULL,              -- W3C trace id of the initiating call
    version           BIGINT           NOT NULL DEFAULT 0,
    created_at        DATETIME2(3)     NOT NULL,
    updated_at        DATETIME2(3)     NOT NULL,
    completed_at      DATETIME2(3)     NULL,
    CONSTRAINT uq_ledger_request UNIQUE (request_id)      -- idempotency: exactly-once intent
);
-- Reconciler scan: due, non-terminal work.
CREATE INDEX ix_ledger_due ON file_deletion_ledger (state, next_attempt_at)
    INCLUDE (signature_id, attempts);
