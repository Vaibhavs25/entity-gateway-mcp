package com.acme.gateway.domain.service;

import com.acme.gateway.domain.model.DeletionState;
import com.acme.gateway.domain.model.ErrorClass;
import com.acme.gateway.domain.model.FileDeletionLedger;
import com.acme.gateway.domain.port.FileStorePort;
import com.acme.gateway.domain.port.FileStorePort.DeleteOutcome;
import com.acme.gateway.domain.service.SignatureDeletionOrchestrator.Receipt;
import com.acme.gateway.ftp.FtpOperationException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Fault-injection tests for the saga driver, asserting the failure-mode
 * matrix of the architecture doc (§9) row by row. Pure Mockito — the DB and
 * FTP are stubbed at their ports, so these run in milliseconds and pin the
 * ORCHESTRATION CONTRACT: which transaction steps fire, in what order, and
 * which are forbidden, for every failure shape.
 *
 * The end-to-end proof against real MSSQL + a real (fake) FTP server lives in
 * DeletionSagaIntegrationTest.
 */
@ExtendWith(MockitoExtension.class)
class SignatureDeletionOrchestratorTest {

    private static final String PATH = "/signatures/acct-42/sig.p7s";

    @Mock private DeletionTxSteps tx;
    @Mock private FileStorePort fileStore;

    private SimpleMeterRegistry meters;
    private SignatureDeletionOrchestrator orchestrator;

    private UUID signatureId;
    private UUID requestId;
    private FileDeletionLedger ledger;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        orchestrator = new SignatureDeletionOrchestrator(tx, fileStore, meters);
        signatureId = UUID.randomUUID();
        requestId = UUID.randomUUID();
        ledger = new FileDeletionLedger(requestId, signatureId, PATH,
                "GDPR erasure #123", "agent-svc@idp", "trace-abc");
    }

    /* ═══ Matrix row: happy path (and row 4: FTP 550 = idempotent success) ═══ */

    @Test
    @DisplayName("happy path: TX1 -> FTP DELETED -> TX2 -> TX3, receipt COMPLETED")
    void happyPath_completesInline() {
        stubIntent();
        when(fileStore.delete(PATH)).thenReturn(DeleteOutcome.DELETED);
        stubMarkFtpDeleted();
        stubFinalize();

        Receipt receipt = orchestrator.initiate(signatureId, requestId, "GDPR erasure #123",
                "agent-svc@idp", "trace-abc");

        assertThat(receipt.status()).isEqualTo(Receipt.Status.COMPLETED);
        assertThat(receipt.state()).isEqualTo(DeletionState.COMPLETED);
        assertThat(receipt.retryable()).isFalse();
        assertThat(receipt.completedAt()).isNotNull();

        var inOrder = inOrder(tx, fileStore);
        inOrder.verify(tx).recordIntent(signatureId, requestId, "GDPR erasure #123",
                "agent-svc@idp", "trace-abc");
        inOrder.verify(fileStore).delete(PATH);          // FTP strictly AFTER TX1
        inOrder.verify(tx).markFtpDeleted(ledger.getId());
        inOrder.verify(tx).finalizeCompletion(ledger.getId());
    }

    @Test
    @DisplayName("row 4: FTP 550 not-found is treated as success, saga completes")
    void ftpAlreadyAbsent_isIdempotentSuccess() {
        stubIntent();
        when(fileStore.delete(PATH)).thenReturn(DeleteOutcome.ALREADY_ABSENT);
        stubMarkFtpDeleted();
        stubFinalize();

        Receipt receipt = orchestrator.initiate(signatureId, requestId, "GDPR erasure #123",
                "agent-svc@idp", "trace-abc");

        assertThat(receipt.state()).isEqualTo(DeletionState.COMPLETED);
        verify(tx).markFtpDeleted(ledger.getId());        // 550 still advances to TX2
    }

    /* ═══ Matrix row 3 — SCENARIO B: FTP fails (timeout / locked) ═══ */

    @Test
    @DisplayName("Scenario B: retryable FTP failure -> ACCEPTED/FTP_FAILED_RETRYING; TX2/TX3 NEVER fire")
    void scenarioB_retryableFtpFailure_failsClosed() {
        stubIntent();
        when(fileStore.delete(PATH)).thenThrow(new FtpOperationException(
                ErrorClass.RETRYABLE_NETWORK, -1, "connect timed out", null));
        when(tx.recordFtpFailure(eq(ledger.getId()), eq(ErrorClass.RETRYABLE_NETWORK), anyString()))
                .thenAnswer(inv -> {
                    ledger.markRetry(ErrorClass.RETRYABLE_NETWORK, "connect timed out",
                            Instant.now().plusSeconds(30));
                    return ledger;
                });

        Receipt receipt = orchestrator.initiate(signatureId, requestId, "GDPR erasure #123",
                "agent-svc@idp", "trace-abc");

        assertThat(receipt.status()).isEqualTo(Receipt.Status.ACCEPTED);
        assertThat(receipt.state()).isEqualTo(DeletionState.FTP_FAILED_RETRYING);
        assertThat(receipt.retryable()).isTrue();
        assertThat(receipt.attempts()).isEqualTo(1);
        assertThat(receipt.nextAttemptAt()).isNotNull();

        // FAIL CLOSED: while the file may still exist, the DB is never advanced.
        verify(tx, never()).markFtpDeleted(any());
        verify(tx, never()).finalizeCompletion(any());
    }

    @Test
    @DisplayName("Scenario B terminal: PERMANENT FTP error -> FAILED_MANUAL + alert metric; still no DB finalization")
    void scenarioB_permanentFailure_escalatesToManual() {
        stubIntent();
        when(fileStore.delete(PATH)).thenThrow(new FtpOperationException(
                ErrorClass.PERMANENT, 530, "auth failed", null));
        when(tx.recordFtpFailure(eq(ledger.getId()), eq(ErrorClass.PERMANENT), anyString()))
                .thenAnswer(inv -> {
                    ledger.markManual(ErrorClass.PERMANENT, "auth failed");
                    return ledger;
                });

        Receipt receipt = orchestrator.initiate(signatureId, requestId, "GDPR erasure #123",
                "agent-svc@idp", "trace-abc");

        assertThat(receipt.state()).isEqualTo(DeletionState.FAILED_MANUAL);
        assertThat(receipt.retryable()).isFalse();
        assertThat(meters.counter("gateway.deletion.manual", "error_class", "PERMANENT").count())
                .isEqualTo(1.0);
        verify(tx, never()).markFtpDeleted(any());
        verify(tx, never()).finalizeCompletion(any());
    }

    /* ═══ Matrix row 6 — SCENARIO A: FTP succeeded, DB finalization fails ═══ */

    @Test
    @DisplayName("Scenario A: TX3 failure is swallowed -> ACCEPTED/FTP_DELETED; NO exception to the caller")
    void scenarioA_finalizationFailure_leavesLedgerHealable() {
        stubIntent();
        when(fileStore.delete(PATH)).thenReturn(DeleteOutcome.DELETED);
        stubMarkFtpDeleted();
        when(tx.finalizeCompletion(ledger.getId()))
                .thenThrow(new DataAccessResourceFailureException("connection lost"));

        Receipt receipt = orchestrator.initiate(signatureId, requestId, "GDPR erasure #123",
                "agent-svc@idp", "trace-abc");

        // The RPC does NOT fail: the operation's truth lives in the ledger.
        assertThat(receipt.status()).isEqualTo(Receipt.Status.ACCEPTED);
        assertThat(receipt.state()).isEqualTo(DeletionState.FTP_DELETED);
        assertThat(receipt.retryable()).isTrue();
        assertThat(meters.counter("gateway.deletion.finalize_failures").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Scenario A healing invariant: advance() from FTP_DELETED runs TX3 ONLY — FTP is never re-contacted")
    void scenarioA_healing_neverRecontactsFtp() {
        ledger.markFtpDeleted();                          // row persisted at FTP_DELETED
        stubFinalize();

        Receipt receipt = orchestrator.advance(ledger);

        assertThat(receipt.state()).isEqualTo(DeletionState.COMPLETED);
        verifyNoInteractions(fileStore);                  // THE invariant of §6.3
        verify(tx).finalizeCompletion(ledger.getId());
        verify(tx, never()).recordFtpFailure(any(), any(), any());
    }

    /* ═══ Matrix row 7 — duplicate requestId ═══ */

    @Test
    @DisplayName("row 7: duplicate requestId -> DUPLICATE receipt of the original; FTP never touched")
    void duplicateRequestId_returnsOriginalReceipt_neverDeletesTwice() {
        ledger.markFtpDeleted();
        ledger.markCompleted();                           // the original op already converged
        when(tx.recordIntent(any(), any(), any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("uq_ledger_request"));
        when(tx.findByRequestId(requestId)).thenReturn(ledger);

        Receipt receipt = orchestrator.initiate(signatureId, requestId, "retry of same op",
                "agent-svc@idp", "trace-xyz");

        assertThat(receipt.status()).isEqualTo(Receipt.Status.DUPLICATE);
        assertThat(receipt.deletionId()).isEqualTo(ledger.getId());
        assertThat(receipt.state()).isEqualTo(DeletionState.COMPLETED);
        verifyNoInteractions(fileStore);                  // exactly-once observable effect
    }

    /* ═══ Terminal states are inert ═══ */

    @Test
    @DisplayName("advance() on FAILED_MANUAL touches nothing — operator owns the row")
    void failedManual_isInert() {
        ledger.markManual(ErrorClass.PERMANENT, "exhausted");

        Receipt receipt = orchestrator.advance(ledger);

        assertThat(receipt.status()).isEqualTo(Receipt.Status.ACCEPTED);
        assertThat(receipt.state()).isEqualTo(DeletionState.FAILED_MANUAL);
        verifyNoInteractions(fileStore);
        verifyNoInteractions(tx);
    }

    /* ── stubbing helpers ───────────────────────────────────────────────── */

    private void stubIntent() {
        when(tx.recordIntent(signatureId, requestId, "GDPR erasure #123",
                "agent-svc@idp", "trace-abc")).thenReturn(ledger);
    }

    private void stubMarkFtpDeleted() {
        when(tx.markFtpDeleted(ledger.getId())).thenAnswer(inv -> {
            ledger.markFtpDeleted();
            return ledger;
        });
    }

    private void stubFinalize() {
        when(tx.finalizeCompletion(ledger.getId())).thenAnswer(inv -> {
            ledger.markCompleted();
            return ledger;
        });
    }
}
