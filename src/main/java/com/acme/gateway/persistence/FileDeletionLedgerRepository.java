package com.acme.gateway.persistence;

import com.acme.gateway.domain.model.FileDeletionLedger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FileDeletionLedgerRepository extends JpaRepository<FileDeletionLedger, UUID> {

    Optional<FileDeletionLedger> findByRequestId(UUID requestId);

    boolean existsBySignatureIdAndStateIn(
            UUID signatureId, List<com.acme.gateway.domain.model.DeletionState> states);

    /**
     * Reconciler work claim. Concurrency notes:
     *  - ShedLock guarantees only ONE reconciler instance cluster-wide, so no
     *    skip-locked semantics are needed between reconcilers.
     *  - The remaining race (reconciler vs. an inline tool call advancing the
     *    same row) is resolved by @Version optimistic locking: the loser gets
     *    ObjectOptimisticLockingFailureException and simply skips the row.
     */
    @Query("""
           SELECT l FROM FileDeletionLedger l
           WHERE (l.state IN (com.acme.gateway.domain.model.DeletionState.PENDING_DELETE,
                              com.acme.gateway.domain.model.DeletionState.FTP_FAILED_RETRYING)
                  AND l.nextAttemptAt <= :now)
              OR  l.state = com.acme.gateway.domain.model.DeletionState.FTP_DELETED
           ORDER BY l.nextAttemptAt ASC
           """)
    List<FileDeletionLedger> findDueWork(@Param("now") Instant now,
                                         org.springframework.data.domain.Pageable limit);
}
