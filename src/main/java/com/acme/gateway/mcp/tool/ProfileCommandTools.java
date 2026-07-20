package com.acme.gateway.mcp.tool;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

import com.acme.gateway.domain.service.ProfileServices.ProfileCommandService;
import com.acme.gateway.domain.service.SignatureDeletionOrchestrator;
import com.acme.gateway.mcp.dto.ToolResponses.DeletionReceipt;
import com.acme.gateway.mcp.dto.ToolResponses.StatusChangeResult;
import com.acme.gateway.mcp.error.GatewayException;
import com.acme.gateway.mcp.error.ToolExecutionSupport;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

import java.util.UUID;

import static com.acme.gateway.mcp.tool.ProfileQueryTools.parseUuid;

/**
 * Mutating tool surface. Same facade rules as ProfileQueryTools: no business
 * logic here, everything through ToolExecutionSupport, RBAC on every method.
 */
@Component
public class ProfileCommandTools {

    private static final int REASON_MIN = 5, REASON_MAX = 256;

    private final ProfileCommandService commands;
    private final SignatureDeletionOrchestrator deletions;
    private final ToolExecutionSupport exec;

    public ProfileCommandTools(ProfileCommandService commands,
                               SignatureDeletionOrchestrator deletions,
                               ToolExecutionSupport exec) {
        this.commands = commands;
        this.deletions = deletions;
        this.exec = exec;
    }

    /* ── update_profile_status (§7.3) — AGENT_WRITER and above ──────────── */

    @McpTool(
        name = "update_profile_status",
        description = "Transition a profile to a new status. Only whitelisted transitions are "
                    + "permitted (ACTIVE<->SUSPENDED, PENDING_REVIEW->ACTIVE|SUSPENDED, "
                    + "ACTIVE|SUSPENDED->ARCHIVED; ARCHIVED is terminal). Illegal transitions "
                    + "fail with INVALID_TRANSITION. Optionally compare-and-set on expectedVersion.",
        annotations = @McpTool.McpAnnotations(
                title = "Update profile status",
                readOnlyHint = false, destructiveHint = false,
                idempotentHint = true, openWorldHint = false))
    @PreAuthorize("hasAnyRole('AGENT_WRITER','AGENT_OPERATOR')")
    public StatusChangeResult updateProfileStatus(
            @McpToolParam(description = "Profile UUID", required = true) String profileId,
            @McpToolParam(description = "Target status: ACTIVE | SUSPENDED | ARCHIVED | PENDING_REVIEW",
                          required = true) String newStatus,
            @McpToolParam(description = "Mandatory audit justification (5-256 chars); persisted with "
                          + "the caller identity", required = true) String reason,
            @McpToolParam(description = "Optional optimistic-concurrency guard from get_profile.version; "
                          + "if stale the tool fails with CONFLICT instead of overwriting",
                          required = false) Long expectedVersion) {

        return exec.run("update_profile_status", () -> {
            var change = commands.updateStatus(
                    parseUuid(profileId, "profileId"),
                    requireStatus(newStatus),
                    requireReason(reason),
                    expectedVersion,
                    exec.currentSubject());
            return new StatusChangeResult(change.profileId(), change.previous(),
                    change.current(), change.version(), change.updatedAt());
        });
    }

    /* ── delete_signature_file (§7.5) — AGENT_OPERATOR only ─────────────── */

    @McpTool(
        name = "delete_signature_file",
        description = "Initiates coordinated deletion of a signature file from the FTP store and the "
                    + "database record. Asynchronous-by-contract: returns a deletion receipt whose "
                    + "'state' reflects how far the saga progressed inline; poll get_deletion_status "
                    + "for convergence. Supply a client-generated requestId (UUID) for idempotency — "
                    + "repeating the same requestId always returns the original receipt and never "
                    + "performs the deletion twice. File paths are resolved server-side from "
                    + "signatureId and are never accepted from the caller.",
        annotations = @McpTool.McpAnnotations(
                title = "Securely delete a signature file (FTP + database)",
                readOnlyHint = false, destructiveHint = true,
                idempotentHint = true, openWorldHint = true))
    @PreAuthorize("hasRole('AGENT_OPERATOR')")
    public DeletionReceipt deleteSignatureFile(
            @McpToolParam(description = "The signature file to delete", required = true) String signatureId,
            @McpToolParam(description = "Client-generated idempotency key (UUID). REQUIRED. Reuse on "
                          + "retry of the SAME operation; never reuse across distinct operations",
                          required = true) String requestId,
            @McpToolParam(description = "Mandatory audit justification (5-256 chars), e.g. GDPR "
                          + "erasure request #", required = true) String reason) {

        return exec.run("delete_signature_file", () -> {
            var receipt = deletions.initiate(
                    parseUuid(signatureId, "signatureId"),
                    parseUuid(requestId, "requestId"),
                    requireReason(reason),
                    exec.currentSubject(),
                    exec.currentTraceId());
            return DeletionReceipt.from(receipt);
        });
    }

    /* ── shared validation ──────────────────────────────────────────────── */

    private static String requireReason(String reason) {
        String r = reason == null ? "" : reason.trim();
        if (r.length() < REASON_MIN || r.length() > REASON_MAX) {
            throw GatewayException.validation("reason must be between 5 and 256 characters.");
        }
        return r.replaceAll("[\\r\\n\\t]", " ");   // log/audit-injection hygiene
    }

    private static com.acme.gateway.domain.model.ProfileStatus requireStatus(String raw) {
        var parsed = ProfileQueryTools.parseEnum(raw);
        if (parsed == null) {
            throw GatewayException.validation("newStatus is required.");
        }
        return parsed;
    }
}
