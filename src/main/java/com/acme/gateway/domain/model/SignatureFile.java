package com.acme.gateway.domain.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "signature_file")
public class SignatureFile {

    @Id
    @Column(columnDefinition = "uniqueidentifier")
    private UUID id;

    @Column(name = "profile_id", nullable = false, columnDefinition = "uniqueidentifier")
    private UUID profileId;

    @Column(name = "file_name", nullable = false, length = 256)
    private String fileName;

    @Column(name = "content_type", length = 128)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "checksum_sha256", columnDefinition = "char(64)")
    private String checksumSha256;

    /**
     * Server-owned FTP location. NEVER exposed through any tool output and
     * NEVER accepted from a caller — path traversal is closed by construction.
     */
    @Column(name = "ftp_path", nullable = false, length = 512)
    private String ftpPath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SignatureFileStatus status;

    @Version
    @Column(nullable = false)
    private long version;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SignatureFile() { }

    public SignatureFile(UUID profileId, String fileName, String contentType,
                         long sizeBytes, String checksumSha256, String ftpPath) {
        this.id = UUID.randomUUID();
        this.profileId = profileId;
        this.fileName = fileName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.checksumSha256 = checksumSha256;
        this.ftpPath = ftpPath;
        this.status = SignatureFileStatus.ACTIVE;
        this.uploadedAt = Instant.now();
        this.updatedAt = this.uploadedAt;
    }

    /** TX1 of the deletion saga: soft tombstone hides the row from reads. */
    public void markDeleteInProgress() {
        this.status = SignatureFileStatus.DELETE_IN_PROGRESS;
        this.updatedAt = Instant.now();
    }

    /** TX3 of the deletion saga: terminal state. */
    public void markDeleted() {
        this.status = SignatureFileStatus.DELETED;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getProfileId() { return profileId; }
    public String getFileName() { return fileName; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getChecksumSha256() { return checksumSha256; }
    public String getFtpPath() { return ftpPath; }
    public SignatureFileStatus getStatus() { return status; }
    public Instant getUploadedAt() { return uploadedAt; }
}
