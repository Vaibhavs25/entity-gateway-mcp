package com.acme.gateway.mcp.tool;

// Spring AI 2.0 GA: annotations live in core at org.springframework.ai.mcp.annotation
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

import com.acme.gateway.domain.model.ProfileStatus;
import com.acme.gateway.domain.service.DeletionTxSteps;
import com.acme.gateway.domain.service.ProfileServices.ProfileQueryService;
import com.acme.gateway.mcp.dto.ToolResponses.*;
import com.acme.gateway.mcp.error.GatewayException;
import com.acme.gateway.mcp.error.ToolExecutionSupport;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * Read-only tool surface. Facade rules (enforced across all tool classes):
 *  - zero business logic — parse, delegate, map;
 *  - every method body runs inside ToolExecutionSupport.run(...) so no raw
 *    exception can cross the MCP edge;
 *  - @PreAuthorize is the second RBAC layer (the first is catalog filtering);
 *  - no document processing, no file payloads — metadata and state only.
 */
@Component
public class ProfileQueryTools {

    private final ProfileQueryService queries;
    private final DeletionTxSteps deletions;
    private final ToolExecutionSupport exec;

    public ProfileQueryTools(ProfileQueryService queries, DeletionTxSteps deletions,
                             ToolExecutionSupport exec) {
        this.queries = queries;
        this.deletions = deletions;
        this.exec = exec;
    }

    /* ── search_profiles (§7.1) ─────────────────────────────────────────── */

    @McpTool(
        name = "search_profiles",
        description = "Search customer profiles with typed filters. Returns a bounded page. "
                    + "No free-form query syntax is accepted; unknown filter values are rejected.",
        annotations = @McpTool.McpAnnotations(
                title = "Search customer profiles",
                readOnlyHint = true, destructiveHint = false,
                idempotentHint = true, openWorldHint = false))
    @PreAuthorize("hasAnyRole('AGENT_READER','AGENT_WRITER','AGENT_OPERATOR')")
    public ProfilePage searchProfiles(
            @McpToolParam(description = "Exact status filter: ACTIVE | SUSPENDED | ARCHIVED | PENDING_REVIEW",
                          required = false) String status,
            @McpToolParam(description = "Case-insensitive substring match on display name (2-64 chars, "
                          + "letters/digits/space/-._@ only)", required = false) String nameContains,
            @McpToolParam(description = "ISO-8601 lower bound on creation time", required = false) String createdAfter,
            @McpToolParam(description = "ISO-8601 upper bound on creation time", required = false) String createdBefore,
            @McpToolParam(description = "Zero-based page index (default 0)", required = false) Integer page,
            @McpToolParam(description = "Page size 1-100 (default 25)", required = false) Integer pageSize) {

        return exec.run("search_profiles", () -> {
            var result = queries.search(
                    parseEnum(status), nameContains,
                    parseInstant(createdAfter, "createdAfter"),
                    parseInstant(createdBefore, "createdBefore"),
                    page, pageSize);
            var items = result.getContent().stream()
                    .map(p -> new ProfileSummary(p.getId(), p.getDisplayName(), p.getStatus(),
                            queries.activeSignatureCount(p.getId()),
                            p.getCreatedAt(), p.getUpdatedAt()))
                    .toList();
            return new ProfilePage(items, result.getNumber(), result.getSize(),
                    result.getTotalElements(), result.getTotalPages());
        });
    }

    /* ── get_profile (§7.2) ─────────────────────────────────────────────── */

    @McpTool(
        name = "get_profile",
        description = "Fetch one customer profile by UUID, including its signature-file summaries. "
                    + "Soft-deleted signatures are never returned. The 'version' field can be echoed "
                    + "into update_profile_status.expectedVersion for compare-and-set semantics.",
        annotations = @McpTool.McpAnnotations(
                title = "Get profile by ID",
                readOnlyHint = true, destructiveHint = false,
                idempotentHint = true, openWorldHint = false))
    @PreAuthorize("hasAnyRole('AGENT_READER','AGENT_WRITER','AGENT_OPERATOR')")
    public ProfileView getProfile(
            @McpToolParam(description = "Profile UUID", required = true) String profileId) {

        return exec.run("get_profile", () -> {
            var profile = queries.getRequired(parseUuid(profileId, "profileId"));
            var signatures = queries.signaturesOf(profile.getId(), false).stream()
                    .map(SignatureSummary::from)
                    .toList();
            return new ProfileView(profile.getId(), profile.getDisplayName(), profile.getEmail(),
                    profile.getStatus(), profile.getVersion(), signatures,
                    profile.getCreatedAt(), profile.getUpdatedAt());
        });
    }

    /* ── list_signature_files (§7.4) ────────────────────────────────────── */

    @McpTool(
        name = "list_signature_files",
        description = "List the DB-registered signature files belonging to a profile. Reflects the "
                    + "database system of record, including in-flight deletion tombstones when "
                    + "requested; does NOT scan the FTP directory.",
        annotations = @McpTool.McpAnnotations(
                title = "List signature files for a profile",
                readOnlyHint = true, destructiveHint = false,
                idempotentHint = true, openWorldHint = false))
    @PreAuthorize("hasAnyRole('AGENT_READER','AGENT_WRITER','AGENT_OPERATOR')")
    public ProfileFiles listSignatureFiles(
            @McpToolParam(description = "Profile UUID", required = true) String profileId,
            @McpToolParam(description = "Include files with an in-flight deletion (default false)",
                          required = false) Boolean includeInProgressDeletions) {

        return exec.run("list_signature_files", () -> {
            UUID pid = parseUuid(profileId, "profileId");
            var files = queries.signaturesOf(pid, Boolean.TRUE.equals(includeInProgressDeletions))
                    .stream().map(SignatureFileView::from).toList();
            return new ProfileFiles(pid, files);
        });
    }

    /* ── get_deletion_status (§7.6) ─────────────────────────────────────── */

    @McpTool(
        name = "get_deletion_status",
        description = "Fetch the current state of a deletion previously initiated via "
                    + "delete_signature_file, by deletionId or by the original requestId "
                    + "(supply exactly one).",
        annotations = @McpTool.McpAnnotations(
                title = "Get deletion operation status",
                readOnlyHint = true, destructiveHint = false,
                idempotentHint = true, openWorldHint = false))
    @PreAuthorize("hasAnyRole('AGENT_READER','AGENT_WRITER','AGENT_OPERATOR')")
    public DeletionStatus getDeletionStatus(
            @McpToolParam(description = "Ledger ID returned by delete_signature_file", required = false) String deletionId,
            @McpToolParam(description = "The original client-supplied requestId", required = false) String requestId) {

        return exec.run("get_deletion_status", () -> {
            boolean byId = deletionId != null && !deletionId.isBlank();
            boolean byReq = requestId != null && !requestId.isBlank();
            if (byId == byReq) {
                throw GatewayException.validation("Supply exactly one of deletionId or requestId.");
            }
            var ledger = byId
                    ? deletions.findById(parseUuid(deletionId, "deletionId"))
                    : deletions.findByRequestId(parseUuid(requestId, "requestId"));
            return DeletionStatus.from(ledger);
        });
    }

    /* ── strict parsing helpers (validation errors, never 500s) ─────────── */

    static UUID parseUuid(String raw, String field) {
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw GatewayException.validation(field + " must be a valid UUID.");
        }
    }

    static Instant parseInstant(String raw, String field) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw GatewayException.validation(field + " must be an ISO-8601 instant, e.g. 2026-07-01T00:00:00Z.");
        }
    }

    static ProfileStatus parseEnum(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return ProfileStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw GatewayException.validation(
                    "status must be one of ACTIVE, SUSPENDED, ARCHIVED, PENDING_REVIEW.");
        }
    }
}
