package com.acme.gateway.mcp.dto;

import com.acme.gateway.domain.model.*;
import com.acme.gateway.domain.service.SignatureDeletionOrchestrator.Receipt;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Tool response records. Concrete records only (no interfaces/generics in
 * return position) — the schema generator produces vague schemas for
 * polymorphic types. Serialized by Spring AI via Jackson 3 (tools.jackson):
 * do NOT add com.fasterxml.jackson.* annotations here.
 *
 * NOTE: ftpPath is deliberately absent from every record — server-internal.
 */
public final class ToolResponses {

    private ToolResponses() { }

    public record ProfileSummary(UUID profileId, String displayName, ProfileStatus status,
                                 long signatureCount, Instant createdAt, Instant updatedAt) { }

    public record ProfilePage(List<ProfileSummary> items, int page, int pageSize,
                              long totalElements, int totalPages) { }

    public record SignatureSummary(UUID signatureId, String fileName, long sizeBytes,
                                   Instant uploadedAt, SignatureFileStatus fileStatus) {
        public static SignatureSummary from(SignatureFile f) {
            return new SignatureSummary(f.getId(), f.getFileName(), f.getSizeBytes(),
                    f.getUploadedAt(), f.getStatus());
        }
    }

    public record ProfileView(UUID profileId, String displayName, String email,
                              ProfileStatus status, long version,
                              List<SignatureSummary> signatures,
                              Instant createdAt, Instant updatedAt) { }

    public record SignatureFileView(UUID signatureId, String fileName, String contentType,
                                    long sizeBytes, String checksumSha256,
                                    SignatureFileStatus fileStatus, Instant uploadedAt) {
        public static SignatureFileView from(SignatureFile f) {
            return new SignatureFileView(f.getId(), f.getFileName(), f.getContentType(),
                    f.getSizeBytes(), f.getChecksumSha256(), f.getStatus(), f.getUploadedAt());
        }
    }

    public record ProfileFiles(UUID profileId, List<SignatureFileView> files) { }

    public record StatusChangeResult(UUID profileId, ProfileStatus previousStatus,
                                     ProfileStatus newStatus, long version, Instant updatedAt) { }

    public record DeletionReceipt(UUID deletionId, UUID requestId, UUID signatureId,
                                  String status, DeletionState state, boolean retryable,
                                  int attempts, Instant nextAttemptAt, Instant completedAt,
                                  String traceId) {
        public static DeletionReceipt from(Receipt r) {
            return new DeletionReceipt(r.deletionId(), r.requestId(), r.signatureId(),
                    r.status().name(), r.state(), r.retryable(), r.attempts(),
                    r.nextAttemptAt(), r.completedAt(), r.traceId());
        }
    }

    public record DeletionStatus(UUID deletionId, UUID requestId, UUID signatureId,
                                 DeletionState state, int attempts, Instant nextAttemptAt,
                                 ErrorClass lastErrorClass, Instant initiatedAt, Instant completedAt) {
        public static DeletionStatus from(FileDeletionLedger l) {
            return new DeletionStatus(l.getId(), l.getRequestId(), l.getSignatureId(),
                    l.getState(), l.getAttempts(),
                    l.getState() == DeletionState.FTP_FAILED_RETRYING ? l.getNextAttemptAt() : null,
                    l.getLastErrorClass(), l.getCreatedAt(), l.getCompletedAt());
        }
    }
}
