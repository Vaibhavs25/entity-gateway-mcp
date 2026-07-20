package com.acme.gateway.domain.service;

import com.acme.gateway.domain.model.DeletionState;
import com.acme.gateway.domain.model.FileDeletionLedger;
import com.acme.gateway.domain.port.FileStorePort;
import com.acme.gateway.ftp.FtpOperationException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * The deletion saga driver.
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ INVARIANT: this class holds NO database transaction. Ever.              │
 * │ Each DB step is its own short REQUIRES_NEW transaction (DeletionTxSteps)│
 * │ and the FTP call happens BETWEEN transactions, never inside one.        │
 * │ Holding a JDBC connection open across a 15-second FTP timeout would     │
 * │ starve the pool and — worse — make the "commit point" analysis of the   │
 * │ failure modes invalid.                                                  │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * Ordering (architecture doc §6.1): intent-first, FTP second, finalize third.
 *
 *   initiate():  TX1 recordIntent  ──►  advance()
 *   advance():   state-driven:
 *     PENDING_DELETE / FTP_FAILED_RETRYING ──► FTP delete
 *          ├─ success or 550 ──► TX2 markFtpDeleted ──► TX3 finalizeCompletion
 *          └─ FtpOperationException ──► recordFtpFailure (Scenario B)
 *     FTP_DELETED ──► TX3 finalizeCompletion only (Scenario A healing;
 *                     FTP is NEVER re-contacted from this state)
 *
 * The reconciler calls the very same advance() — the inline tool call is just
 * a low-latency first attempt. That equivalence is what makes the design
 * crash-safe at every instruction boundary (matrix §9).
 */
@Service
public class SignatureDeletionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SignatureDeletionOrchestrator.class);

    private final DeletionTxSteps tx;
    private final FileStorePort fileStore;
    private final MeterRegistry meters;

    public SignatureDeletionOrchestrator(DeletionTxSteps tx, FileStorePort fileStore,
                                         MeterRegistry meters) {
        this.tx = tx;
        this.fileStore = fileStore;
        this.meters = meters;
    }

    /** Receipt mirrors tool contract §7.5. */
    public record Receipt(UUID deletionId, UUID requestId, UUID signatureId,
                          Status status, DeletionState state, boolean retryable,
                          int attempts, Instant nextAttemptAt, Instant completedAt,
                          String traceId) {

        public enum Status { COMPLETED, ACCEPTED, DUPLICATE }

        static Receipt of(FileDeletionLedger l, Status status) {
            boolean retryable = switch (l.getState()) {
                case FTP_FAILED_RETRYING, PENDING_DELETE, FTP_DELETED -> true;
                case COMPLETED, FAILED_MANUAL -> false;
            };
            return new Receipt(l.getId(), l.getRequestId(), l.getSignatureId(),
                    status, l.getState(), retryable, l.getAttempts(),
                    l.getState() == DeletionState.FTP_FAILED_RETRYING ? l.getNextAttemptAt() : null,
                    l.getCompletedAt(), l.getTraceId());
        }
    }

    /**
     * Entry point for the delete_signature_file tool.
     *
     * Idempotency: the ledger's UNIQUE(request_id) is the barrier. A repeated
     * requestId fails TX1 with DataIntegrityViolationException; we then load
     * the original row and return it as DUPLICATE — the deletion is never
     * performed twice, and retried calls always see the same operation.
     */
    public Receipt initiate(UUID signatureId, UUID requestId, String reason,
                            String initiatedBy, String traceId) {
        FileDeletionLedger ledger;
        try {
            ledger = tx.recordIntent(signatureId, requestId, reason, initiatedBy, traceId);
        } catch (DataIntegrityViolationException duplicate) {
            FileDeletionLedger original = tx.findByRequestId(requestId);
            log.info("deletion.duplicate requestId={} originalLedgerId={}", requestId, original.getId());
            return Receipt.of(original, Receipt.Status.DUPLICATE);
        }
        // Intent is durable from this point: even a crash on the next line
        // leaves a PENDING_DELETE row the reconciler will drive to completion.
        return advance(ledger);
    }

    /**
     * Advances a ledger row as far as it can go right now. Shared verbatim by
     * the inline tool path and the reconciler.
     */
    public Receipt advance(FileDeletionLedger ledger) {
        return switch (ledger.getState()) {
            case PENDING_DELETE, FTP_FAILED_RETRYING -> attemptFtpThenFinalize(ledger);
            case FTP_DELETED -> finalizeOnly(ledger);          // Scenario A healing path
            case COMPLETED   -> Receipt.of(ledger, Receipt.Status.COMPLETED);
            case FAILED_MANUAL -> Receipt.of(ledger, Receipt.Status.ACCEPTED);
        };
    }

    /* ── the FTP attempt: no transaction open here ──────────────────────── */

    private Receipt attemptFtpThenFinalize(FileDeletionLedger ledger) {
        final UUID id = ledger.getId();
        try {
            FileStorePort.DeleteOutcome outcome = fileStore.delete(ledger.getResolvedFtpPath());
            // ALREADY_ABSENT (FTP 550) is idempotent success: it is exactly how
            // a crash between FTP's "250" and TX2 heals on retry (matrix row 5).
            log.info("deletion.ftp_ok ledgerId={} outcome={}", id, outcome);
            countTransition(ledger.getState(), DeletionState.FTP_DELETED);
            ledger = tx.markFtpDeleted(id);                                    // TX2

        } catch (FtpOperationException ftpFailure) {
            /* ═══ SCENARIO B: FTP deletion failed (timeout / locked / net) ═══
             * Nothing irreversible happened. The DB update is NOT performed:
             * the signature stays tombstoned (DELETE_IN_PROGRESS), never
             * falsely DELETED — fail closed. recordFtpFailure either schedules
             * a backoff retry or escalates to FAILED_MANUAL + alert metric.  */
            DeletionState from = ledger.getState();
            ledger = tx.recordFtpFailure(id, ftpFailure.errorClass(), ftpFailure.getMessage());
            countTransition(from, ledger.getState());
            if (ledger.getState() == DeletionState.FAILED_MANUAL) {
                meters.counter("gateway.deletion.manual",
                        "error_class", ftpFailure.errorClass().name()).increment();
            }
            return Receipt.of(ledger, Receipt.Status.ACCEPTED);
        }
        return finalizeOnly(ledger);
    }

    /* ── DB-local finalization: the Scenario A commit point ─────────────── */

    private Receipt finalizeOnly(FileDeletionLedger ledger) {
        final UUID id = ledger.getId();
        try {
            DeletionState from = ledger.getState();
            ledger = tx.finalizeCompletion(id);                                // TX3
            countTransition(from, DeletionState.COMPLETED);
            return Receipt.of(ledger, Receipt.Status.COMPLETED);

        } catch (RuntimeException dbFailure) {
            /* ═══ SCENARIO A: FTP succeeded, DB finalization failed ═══
             * The irreversible side effect already happened, but TX2 has
             * durably recorded it: the ledger sits at FTP_DELETED. We do NOT
             * fail the tool call — the operation's truth lives in the ledger,
             * not in this RPC. The reconciler re-runs finalizeCompletion(),
             * which is pure DB-local work and converges under retries. Reads
             * stay consistent meanwhile because the TX1 tombstone already
             * hides the signature from every query tool.                    */
            log.error("deletion.finalize_failed ledgerId={} — ledger remains FTP_DELETED; "
                    + "reconciler will heal (Scenario A)", id, dbFailure);
            meters.counter("gateway.deletion.finalize_failures").increment();
            return Receipt.of(ledger, Receipt.Status.ACCEPTED);
        }
    }

    private void countTransition(DeletionState from, DeletionState to) {
        meters.counter("gateway.deletion.transitions",
                "from", from.name(), "to", to.name()).increment();
    }
}
