package com.acme.gateway.domain.service;

import com.acme.gateway.config.DeletionProperties;
import com.acme.gateway.domain.model.*;
import com.acme.gateway.mcp.error.GatewayException;
import com.acme.gateway.persistence.FileDeletionLedgerRepository;
import com.acme.gateway.persistence.SignatureFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The three database transactions of the deletion saga, each deliberately
 * SMALL and SEPARATE. This class exists so that the orchestrator — which also
 * performs the FTP call — can hold NO transaction of its own:
 *
 *   TX1 recordIntent        — durable intent + tombstone   (commit point of "start")
 *   TX2 markFtpDeleted      — durable fact "file is gone"  (commit point of the side effect)
 *   TX3 finalizeCompletion  — entity finalization           (commit point of "done")
 *
 * Every method is REQUIRES_NEW: even if a caller accidentally has a
 * transaction open, these steps commit independently, preserving the
 * crash-safety analysis of architecture doc §6/§9. Each step re-fetches its
 * entities by ID — no detached state crosses transaction boundaries.
 */
@Service
public class DeletionTxSteps {

    private static final Logger log = LoggerFactory.getLogger(DeletionTxSteps.class);
    private static final Marker AUDIT = MarkerFactory.getMarker("AUDIT");

    private final FileDeletionLedgerRepository ledgers;
    private final SignatureFileRepository signatures;
    private final DeletionProperties props;

    public DeletionTxSteps(FileDeletionLedgerRepository ledgers,
                           SignatureFileRepository signatures,
                           DeletionProperties props) {
        this.ledgers = ledgers;
        this.signatures = signatures;
        this.props = props;
    }

    /* ── TX1 ────────────────────────────────────────────────────────────── */

    /**
     * Records durable intent BEFORE any FTP interaction:
     *  1. resolves the FTP path server-side (never accepted from the caller),
     *  2. tombstones the signature row (DELETE_IN_PROGRESS) so reads are
     *     consistent-by-construction for the whole life of the saga,
     *  3. inserts the ledger row (request_id UNIQUE = idempotency barrier).
     *
     * A duplicate requestId violates the unique constraint on commit; the
     * orchestrator catches DataIntegrityViolationException and returns the
     * original receipt (exactly-once observable effect).
     *
     * The requestId is checked FIRST, before any file-state lookup: once the
     * original request has run to completion the signature row becomes
     * terminal (DELETED) or otherwise invisible, and a file-status check
     * ahead of the requestId check would reject the duplicate instead of
     * recognizing it — breaking idempotency exactly when it matters most.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FileDeletionLedger recordIntent(UUID signatureId, UUID requestId,
                                           String reason, String initiatedBy, String traceId) {
        if (ledgers.findByRequestId(requestId).isPresent()) {
            throw new DataIntegrityViolationException("Duplicate requestId=" + requestId);
        }

        SignatureFile file = signatures.findById(signatureId)
                .orElseThrow(() -> GatewayException.notFound("Signature file"));

        if (file.getStatus() == SignatureFileStatus.DELETED) {
            throw GatewayException.notFound("Signature file");  // terminal rows are invisible
        }
        if (file.getStatus() == SignatureFileStatus.DELETE_IN_PROGRESS
                && ledgers.existsBySignatureIdAndStateIn(signatureId, List.of(
                        DeletionState.PENDING_DELETE, DeletionState.FTP_DELETED,
                        DeletionState.FTP_FAILED_RETRYING))) {
            // A DIFFERENT request already owns this deletion. (The SAME
            // requestId never reaches here — it dies on the unique constraint.)
            throw GatewayException.deletionInProgress();
        }

        file.markDeleteInProgress();

        FileDeletionLedger ledger = new FileDeletionLedger(
                requestId, signatureId, file.getFtpPath(), reason, initiatedBy, traceId);
        ledgers.save(ledger);

        log.info(AUDIT, "deletion.initiated ledgerId={} signatureId={} requestId={} by={}",
                ledger.getId(), signatureId, requestId, initiatedBy);
        return ledger;
    }

    /* ── TX2 ────────────────────────────────────────────────────────────── */

    /** Persists the fact that FTP acknowledged deletion (250 or idempotent 550). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FileDeletionLedger markFtpDeleted(UUID ledgerId) {
        FileDeletionLedger ledger = required(ledgerId);
        ledger.markFtpDeleted();
        log.info("deletion.ftp_deleted ledgerId={}", ledgerId);
        return ledger;
    }

    /* ── TX3 ────────────────────────────────────────────────────────────── */

    /**
     * Pure DB-local finalization. If THIS transaction fails (Scenario A), the
     * ledger stays at FTP_DELETED and the reconciler re-runs exactly this
     * method — no external dependency, so it converges under retries.
     * FTP is never re-contacted from the FTP_DELETED state.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FileDeletionLedger finalizeCompletion(UUID ledgerId) {
        FileDeletionLedger ledger = required(ledgerId);
        if (ledger.getState() == DeletionState.COMPLETED) {
            return ledger;  // reconciler/tool double-drive: idempotent
        }
        SignatureFile file = signatures.findById(ledger.getSignatureId())
                .orElseThrow(() -> new IllegalStateException(
                        "Ledger " + ledgerId + " references missing signature row"));
        file.markDeleted();
        ledger.markCompleted();
        log.info(AUDIT, "deletion.completed ledgerId={} signatureId={}",
                ledgerId, ledger.getSignatureId());
        return ledger;
    }

    /* ── Failure bookkeeping (Scenario B) ───────────────────────────────── */

    /**
     * Records a retryable FTP failure OR escalates to FAILED_MANUAL when the
     * error is PERMANENT / the retry budget is exhausted. In BOTH cases the
     * signature row remains tombstoned and is NEVER marked DELETED — we fail
     * closed: a file that may still exist is never reported deleted.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FileDeletionLedger recordFtpFailure(UUID ledgerId, ErrorClass errorClass, String diagnostic) {
        FileDeletionLedger ledger = required(ledgerId);
        boolean permanent = errorClass == ErrorClass.PERMANENT;
        boolean exhausted = ledger.getAttempts() + 1 >= props.maxRetries();

        if (permanent || exhausted) {
            ledger.markManual(errorClass, diagnostic);
            log.error(AUDIT, "deletion.failed_manual ledgerId={} signatureId={} class={} attempts={}",
                    ledgerId, ledger.getSignatureId(), errorClass, ledger.getAttempts());
        } else {
            Instant next = Instant.now().plus(props.backoffFor(ledger.getAttempts() + 1));
            ledger.markRetry(errorClass, diagnostic, next);
            log.warn("deletion.retry_scheduled ledgerId={} class={} attempt={} nextAttemptAt={}",
                    ledgerId, errorClass, ledger.getAttempts(), next);
        }
        return ledger;
    }

    @Transactional(readOnly = true)
    public FileDeletionLedger findByRequestId(UUID requestId) {
        return ledgers.findByRequestId(requestId)
                .orElseThrow(() -> GatewayException.notFound("Deletion operation"));
    }

    @Transactional(readOnly = true)
    public FileDeletionLedger findById(UUID ledgerId) {
        return required(ledgerId);
    }

    private FileDeletionLedger required(UUID ledgerId) {
        return ledgers.findById(ledgerId)
                .orElseThrow(() -> GatewayException.notFound("Deletion operation"));
    }
}
