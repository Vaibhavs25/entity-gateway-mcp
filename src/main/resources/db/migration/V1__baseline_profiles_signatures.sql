-- Baseline: customer profiles and signature file registry (system of record).
CREATE TABLE customer_profile (
    id              UNIQUEIDENTIFIER NOT NULL PRIMARY KEY,
    display_name    NVARCHAR(128)    NOT NULL,
    email           NVARCHAR(256)    NOT NULL,
    status          VARCHAR(32)      NOT NULL,  -- ACTIVE | SUSPENDED | ARCHIVED | PENDING_REVIEW
    version         BIGINT           NOT NULL DEFAULT 0,
    created_at      DATETIME2(3)     NOT NULL,
    updated_at      DATETIME2(3)     NOT NULL
);
CREATE INDEX ix_profile_status     ON customer_profile (status);
CREATE INDEX ix_profile_created_at ON customer_profile (created_at);

CREATE TABLE signature_file (
    id               UNIQUEIDENTIFIER NOT NULL PRIMARY KEY,
    profile_id       UNIQUEIDENTIFIER NOT NULL
        CONSTRAINT fk_signature_profile REFERENCES customer_profile (id),
    file_name        NVARCHAR(256)    NOT NULL,
    content_type     VARCHAR(128)     NULL,
    size_bytes       BIGINT           NOT NULL,
    checksum_sha256  CHAR(64)         NULL,
    ftp_path         NVARCHAR(512)    NOT NULL,  -- server-owned; NEVER accepted from callers
    status           VARCHAR(32)      NOT NULL,  -- ACTIVE | DELETE_IN_PROGRESS | DELETED
    version          BIGINT           NOT NULL DEFAULT 0,
    uploaded_at      DATETIME2(3)     NOT NULL,
    updated_at       DATETIME2(3)     NOT NULL
);
CREATE INDEX ix_signature_profile ON signature_file (profile_id, status);
