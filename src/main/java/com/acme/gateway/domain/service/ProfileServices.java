package com.acme.gateway.domain.service;

import com.acme.gateway.domain.model.*;
import com.acme.gateway.mcp.error.GatewayException;
import com.acme.gateway.persistence.ProfileRepository;
import com.acme.gateway.persistence.SignatureFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Database utility services over JPA/Hibernate. Two classes in one file for
 * review convenience; split freely.
 *
 * Hard rules enforced here (not merely assumed):
 *  - No dynamic query construction — repository methods with bind params only.
 *  - Free-text input is pattern-validated BEFORE reaching the persistence layer.
 *  - All writes run inside explicit, short @Transactional boundaries.
 *  - Status transitions are whitelist-checked; illegal ones fail loudly.
 */
public final class ProfileServices {

    private ProfileServices() { }

    /* ═══════════════════════════ READ SIDE ═══════════════════════════ */

    @Service
    public static class ProfileQueryService {

        /** Mirrors the JSON Schema pattern in tool contract §7.1. */
        private static final Pattern NAME_FILTER = Pattern.compile("^[\\p{L}\\p{N}\\s\\-._@]{2,64}$");
        private static final int MAX_PAGE_SIZE = 100;

        private final ProfileRepository profiles;
        private final SignatureFileRepository signatures;

        public ProfileQueryService(ProfileRepository profiles, SignatureFileRepository signatures) {
            this.profiles = profiles;
            this.signatures = signatures;
        }

        @Transactional(readOnly = true)
        public Page<CustomerProfile> search(ProfileStatus status, String nameContains,
                                            Instant createdAfter, Instant createdBefore,
                                            Integer page, Integer pageSize) {
            if (nameContains != null && !NAME_FILTER.matcher(nameContains).matches()) {
                throw GatewayException.validation(
                        "nameContains must match ^[letters digits space -._@]{2,64}$");
            }
            int p  = page == null ? 0 : Math.max(0, page);
            int ps = pageSize == null ? 25 : Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);
            return profiles.search(status, nameContains, createdAfter, createdBefore,
                    PageRequest.of(p, ps));
        }

        @Transactional(readOnly = true)
        public CustomerProfile getRequired(UUID profileId) {
            return profiles.findById(profileId)
                    .orElseThrow(() -> GatewayException.notFound("Profile"));
        }

        @Transactional(readOnly = true)
        public List<SignatureFile> signaturesOf(UUID profileId, boolean includeInProgress) {
            getRequired(profileId); // 404 semantics for unknown profile
            var visible = includeInProgress
                    ? EnumSet.of(SignatureFileStatus.ACTIVE, SignatureFileStatus.DELETE_IN_PROGRESS)
                    : EnumSet.of(SignatureFileStatus.ACTIVE);
            // DELETED rows are terminal and never visible through any tool.
            return signatures.findByProfileIdAndStatusInOrderByUploadedAtDesc(profileId, visible);
        }

        @Transactional(readOnly = true)
        public long activeSignatureCount(UUID profileId) {
            return signatures.countByProfileIdAndStatus(profileId, SignatureFileStatus.ACTIVE);
        }
    }

    /* ═══════════════════════════ WRITE SIDE ═══════════════════════════ */

    @Service
    public static class ProfileCommandService {

        private static final Logger log = LoggerFactory.getLogger(ProfileCommandService.class);
        private static final Marker AUDIT = MarkerFactory.getMarker("AUDIT");

        /** The complete set of legal transitions. Everything else is rejected. */
        private static final Map<ProfileStatus, Set<ProfileStatus>> ALLOWED = Map.of(
                ProfileStatus.ACTIVE,         EnumSet.of(ProfileStatus.SUSPENDED, ProfileStatus.ARCHIVED),
                ProfileStatus.SUSPENDED,      EnumSet.of(ProfileStatus.ACTIVE, ProfileStatus.ARCHIVED),
                ProfileStatus.PENDING_REVIEW, EnumSet.of(ProfileStatus.ACTIVE, ProfileStatus.SUSPENDED),
                ProfileStatus.ARCHIVED,       EnumSet.noneOf(ProfileStatus.class)  // terminal
        );

        private final ProfileRepository profiles;

        public ProfileCommandService(ProfileRepository profiles) {
            this.profiles = profiles;
        }

        public record StatusChange(UUID profileId, ProfileStatus previous, ProfileStatus current,
                                   long version, Instant updatedAt) { }

        /**
         * Compare-and-set status transition.
         *
         * Concurrency: two layers. If the caller supplies expectedVersion we
         * fail fast pre-write; regardless, Hibernate @Version turns any
         * write-write race into ObjectOptimisticLockingFailureException,
         * mapped to CONFLICT so the agent refetches via get_profile.
         */
        @Transactional
        public StatusChange updateStatus(UUID profileId, ProfileStatus newStatus,
                                         String reason, Long expectedVersion, String actor) {
            CustomerProfile profile = profiles.findById(profileId)
                    .orElseThrow(() -> GatewayException.notFound("Profile"));

            if (expectedVersion != null && profile.getVersion() != expectedVersion) {
                throw GatewayException.conflict(
                        "Profile was modified concurrently (expected version " + expectedVersion
                        + ", actual " + profile.getVersion() + "). Refetch and retry.");
            }

            ProfileStatus previous = profile.getStatus();
            if (previous == newStatus) {
                // Idempotent no-op: repeating the same transition is not an error.
                return new StatusChange(profileId, previous, newStatus,
                        profile.getVersion(), profile.getUpdatedAt());
            }
            if (!ALLOWED.getOrDefault(previous, Set.of()).contains(newStatus)) {
                throw GatewayException.invalidTransition(
                        "Transition " + previous + " -> " + newStatus + " is not permitted.");
            }

            profile.transitionTo(newStatus);
            try {
                profiles.flush();  // surface the optimistic-lock failure inside this method
            } catch (ObjectOptimisticLockingFailureException e) {
                throw GatewayException.conflict("Profile was modified concurrently. Refetch and retry.");
            }

            log.info(AUDIT, "profile.status.changed profileId={} from={} to={} actor={} reason={}",
                    profileId, previous, newStatus, actor, sanitizeForLog(reason));

            return new StatusChange(profileId, previous, newStatus,
                    profile.getVersion(), profile.getUpdatedAt());
        }

        /** Log-injection hygiene: agent-supplied text never breaks log structure. */
        private static String sanitizeForLog(String s) {
            return s == null ? null : s.replaceAll("[\\r\\n\\t]", " ");
        }
    }
}
