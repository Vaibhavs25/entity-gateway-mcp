package com.acme.gateway.it;

import com.acme.gateway.domain.model.*;
import com.acme.gateway.domain.service.DeletionTxSteps;
import com.acme.gateway.domain.service.SignatureDeletionOrchestrator;
import com.acme.gateway.domain.service.SignatureDeletionOrchestrator.Receipt;
import com.acme.gateway.persistence.FileDeletionLedgerRepository;
import com.acme.gateway.persistence.ProfileRepository;
import com.acme.gateway.persistence.SignatureFileRepository;
import com.acme.gateway.reconcile.DeletionReconciler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.mockftpserver.fake.FakeFtpServer;
import org.mockftpserver.fake.UserAccount;
import org.mockftpserver.fake.filesystem.DirectoryEntry;
import org.mockftpserver.fake.filesystem.FileEntry;
import org.mockftpserver.fake.filesystem.UnixFakeFileSystem;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * End-to-end proof of the failure-mode matrix (§9) against the REAL stack:
 *
 *   - real MS SQL Server (Testcontainers) — which also validates the Flyway
 *     DDL, the UNIQUE(request_id) idempotency barrier, and @Version behavior
 *     against actual MSSQL semantics, not H2 approximations;
 *   - real FTP protocol conversation (MockFtpServer's FakeFtpServer);
 *   - the real DeletionTxSteps transactions, with faults injected via
 *     @MockitoSpyBean exactly at the TX2/TX3 boundaries the matrix names.
 *
 * The reconciler is invoked manually (interval set to 1h) so each test drives
 * time deterministically.
 *
 * Requires a local Docker daemon (Testcontainers).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DeletionSagaIntegrationTest {

    /* ── infrastructure ─────────────────────────────────────────────────── */

    @Container
    static final MSSQLServerContainer<?> MSSQL =
            new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest")
                    .acceptLicense();

    private static final String FTP_USER = "gw";
    private static final String FTP_PASS = "secret";
    private static FakeFtpServer ftp;
    private static int ftpPort;

    static {
        ftp = newFtpServer(0);
        ftp.start();
        ftpPort = ftp.getServerControlPort();
    }

    private static FakeFtpServer newFtpServer(int port) {
        var server = new FakeFtpServer();
        server.setServerControlPort(port);
        var fs = new UnixFakeFileSystem();
        fs.add(new DirectoryEntry("/signatures"));
        server.setFileSystem(fs);
        server.addUserAccount(new UserAccount(FTP_USER, FTP_PASS, "/"));
        return server;
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MSSQL::getJdbcUrl);
        r.add("spring.datasource.username", MSSQL::getUsername);
        r.add("spring.datasource.password", MSSQL::getPassword);
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "https://test-idp.invalid");
        r.add("gateway.ftp.host", () -> "localhost");
        r.add("gateway.ftp.port", () -> ftpPort);
        r.add("gateway.ftp.username", () -> FTP_USER);
        r.add("gateway.ftp.password", () -> FTP_PASS);
        r.add("gateway.ftp.connect-timeout", () -> "2s");
        r.add("gateway.ftp.data-timeout", () -> "3s");
        // Deterministic tests: tiny retry budget, reconciler never self-fires.
        r.add("gateway.deletion.max-retries", () -> 2);
        r.add("gateway.deletion.backoff-initial", () -> "30s");
        r.add("gateway.deletion.reconcile-interval", () -> "1h");
        r.add("gateway.deletion.lock-at-least", () -> "0s");   // manual reconcile() never skipped
        // Keep telemetry local.
        r.add("management.otlp.metrics.export.enabled", () -> false);
        r.add("management.tracing.enabled", () -> false);
    }

    @TestConfiguration
    static class TestSecurity {
        /** No IdP in tests; nothing in these tests goes through HTTP auth. */
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> { throw new UnsupportedOperationException("not used in IT"); };
        }
    }

    /* ── system under test ──────────────────────────────────────────────── */

    @Autowired SignatureDeletionOrchestrator orchestrator;
    @Autowired DeletionReconciler reconciler;
    @Autowired ProfileRepository profiles;
    @Autowired SignatureFileRepository signatures;
    @Autowired FileDeletionLedgerRepository ledgers;
    @Autowired JdbcTemplate jdbc;

    /** Real bean, real transactions — faults injected per test, then reset. */
    @MockitoSpyBean DeletionTxSteps txSteps;

    private UUID signatureId;
    private String ftpPath;

    @BeforeEach
    void seed() {
        Mockito.reset(txSteps);
        ensureFtpRunning();

        var profile = profiles.save(new CustomerProfile(
                "Test User " + UUID.randomUUID(), "user@test.acme", ProfileStatus.ACTIVE));
        ftpPath = "/signatures/" + UUID.randomUUID() + ".p7s";
        var sig = signatures.save(new SignatureFile(profile.getId(), "sig.p7s",
                "application/pkcs7-signature", 2048, "a".repeat(64), ftpPath));
        signatureId = sig.getId();
        ftp.getFileSystem().add(new FileEntry(ftpPath, "pkcs7-bytes"));
    }

    /* ═══ Happy path — also validates DDL, TX1 tombstone, TX2, TX3 on real MSSQL ═══ */

    @Test
    @DisplayName("happy path: file removed from FTP; signature DELETED; ledger COMPLETED")
    void happyPath() {
        Receipt receipt = initiate(UUID.randomUUID());

        assertThat(receipt.status()).isEqualTo(Receipt.Status.COMPLETED);
        assertThat(ftp.getFileSystem().exists(ftpPath)).isFalse();
        assertThat(signature().getStatus()).isEqualTo(SignatureFileStatus.DELETED);
        assertThat(ledger(receipt).getState()).isEqualTo(DeletionState.COMPLETED);
        assertThat(ledger(receipt).getCompletedAt()).isNotNull();
    }

    /* ═══ Matrix row 3 — SCENARIO B: FTP down, then recovery via reconciler ═══ */

    @Test
    @DisplayName("Scenario B: FTP down -> FTP_FAILED_RETRYING, tombstone holds; reconciler converges after recovery")
    void scenarioB_ftpDown_thenReconcilerHeals() {
        ftp.stop();                                             // network failure

        Receipt receipt = initiate(UUID.randomUUID());

        assertThat(receipt.status()).isEqualTo(Receipt.Status.ACCEPTED);
        assertThat(receipt.state()).isEqualTo(DeletionState.FTP_FAILED_RETRYING);
        assertThat(receipt.attempts()).isEqualTo(1);
        // FAIL CLOSED: never falsely deleted while the file may still exist.
        assertThat(signature().getStatus()).isEqualTo(SignatureFileStatus.DELETE_IN_PROGRESS);

        // FTP comes back; the file is still there.
        ensureFtpRunning();
        ftp.getFileSystem().add(new FileEntry(ftpPath, "pkcs7-bytes"));

        makeDueNow(receipt.deletionId());
        reconciler.reconcile();

        assertThat(ledger(receipt).getState()).isEqualTo(DeletionState.COMPLETED);
        assertThat(ftp.getFileSystem().exists(ftpPath)).isFalse();
        assertThat(signature().getStatus()).isEqualTo(SignatureFileStatus.DELETED);
    }

    /* ═══ Scenario B terminal: retry budget exhausted -> FAILED_MANUAL ═══ */

    @Test
    @DisplayName("Scenario B exhaustion: max-retries=2 -> FAILED_MANUAL; tombstone REMAINS (fail-closed)")
    void scenarioB_exhaustion_escalatesToManual() {
        ftp.stop();

        Receipt receipt = initiate(UUID.randomUUID());          // attempt 1 fails
        makeDueNow(receipt.deletionId());
        reconciler.reconcile();                                 // attempt 2 fails -> exhausted

        var row = ledger(receipt);
        assertThat(row.getState()).isEqualTo(DeletionState.FAILED_MANUAL);
        assertThat(row.getAttempts()).isEqualTo(2);
        assertThat(row.getLastErrorClass()).isEqualTo(ErrorClass.RETRYABLE_NETWORK);
        // Operator runbook owns it now; the record is still not falsely deleted.
        assertThat(signature().getStatus()).isEqualTo(SignatureFileStatus.DELETE_IN_PROGRESS);
    }

    /* ═══ Matrix row 6 — SCENARIO A: FTP succeeded, TX3 fails, reconciler heals DB-only ═══ */

    @Test
    @DisplayName("Scenario A: TX3 injected failure -> ACCEPTED/FTP_DELETED; reconciler finalizes WITHOUT re-contacting FTP")
    void scenarioA_finalizationFails_thenReconcilerHeals() {
        doThrow(new DataAccessResourceFailureException("injected: db connection lost"))
                .when(txSteps).finalizeCompletion(any());

        Receipt receipt = initiate(UUID.randomUUID());

        assertThat(receipt.status()).isEqualTo(Receipt.Status.ACCEPTED);
        assertThat(receipt.state()).isEqualTo(DeletionState.FTP_DELETED);
        assertThat(ftp.getFileSystem().exists(ftpPath)).isFalse();      // side effect happened
        // Consistency during the gap: reads still see only the tombstone.
        assertThat(signature().getStatus()).isEqualTo(SignatureFileStatus.DELETE_IN_PROGRESS);

        Mockito.reset(txSteps);                                 // "DB recovered"
        ftp.stop();                                             // PROVE FTP is not needed to heal
        try {
            reconciler.reconcile();
        } finally {
            ensureFtpRunning();
        }

        assertThat(ledger(receipt).getState()).isEqualTo(DeletionState.COMPLETED);
        assertThat(signature().getStatus()).isEqualTo(SignatureFileStatus.DELETED);
    }

    /* ═══ Matrix row 5 — crash between FTP "250" and TX2: heals via idempotent 550 ═══ */

    @Test
    @DisplayName("row 5: crash after FTP delete but before TX2 -> reconciler retries FTP, 550 counts as success")
    void crashBetweenFtpAndTx2_healsViaIdempotent550() {
        doThrow(new DataAccessResourceFailureException("injected: crash before TX2 commit"))
                .when(txSteps).markFtpDeleted(any());

        // Inline call blows up AFTER the FTP delete — the worst-timed crash.
        assertThatThrownBy(() -> initiate(UUID.randomUUID()))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(ftp.getFileSystem().exists(ftpPath)).isFalse();      // file already gone
        var row = ledgers.findAll().stream()
                .filter(l -> l.getSignatureId().equals(signatureId)).findFirst().orElseThrow();
        assertThat(row.getState()).isEqualTo(DeletionState.PENDING_DELETE);  // intent survived

        Mockito.reset(txSteps);
        reconciler.reconcile();   // re-runs FTP delete -> 550 -> ALREADY_ABSENT -> TX2 -> TX3

        assertThat(ledgers.findById(row.getId()).orElseThrow().getState())
                .isEqualTo(DeletionState.COMPLETED);
        assertThat(signature().getStatus()).isEqualTo(SignatureFileStatus.DELETED);
    }

    /* ═══ Matrix row 7 — duplicate requestId against the REAL unique constraint ═══ */

    @Test
    @DisplayName("row 7: same requestId twice -> DUPLICATE receipt, exactly one ledger row")
    void duplicateRequestId_exactlyOnce() {
        UUID requestId = UUID.randomUUID();

        Receipt first = initiate(requestId);
        Receipt second = initiate(requestId);

        assertThat(first.status()).isEqualTo(Receipt.Status.COMPLETED);
        assertThat(second.status()).isEqualTo(Receipt.Status.DUPLICATE);
        assertThat(second.deletionId()).isEqualTo(first.deletionId());
        assertThat(ledgers.findAll().stream()
                .filter(l -> l.getRequestId().equals(requestId)).count()).isEqualTo(1);
    }

    /* ── helpers ────────────────────────────────────────────────────────── */

    private Receipt initiate(UUID requestId) {
        return orchestrator.initiate(signatureId, requestId,
                "GDPR erasure request #4711", "it-agent@test", "trace-it");
    }

    private SignatureFile signature() {
        return signatures.findById(signatureId).orElseThrow();
    }

    private FileDeletionLedger ledger(Receipt receipt) {
        return ledgers.findById(receipt.deletionId()).orElseThrow();
    }

    /**
     * Deterministic time travel instead of sleeping through the 30s backoff.
     *
     * Hibernate is configured with {@code hibernate.jdbc.time_zone: UTC}, so
     * it reads/writes this column's wall-clock value as UTC. Writing via raw
     * JDBC without pinning the same UTC calendar would let the driver apply
     * the JVM's default zone instead, silently shifting the stored value and
     * making the row look due hours later than intended.
     */
    private static final Calendar UTC = Calendar.getInstance(TimeZone.getTimeZone("UTC"));

    private void makeDueNow(UUID ledgerId) {
        Timestamp due = Timestamp.from(Instant.now().minusSeconds(5));
        jdbc.update("UPDATE file_deletion_ledger SET next_attempt_at = ? WHERE id = ?",
                ps -> {
                    ps.setTimestamp(1, due, UTC);
                    ps.setObject(2, ledgerId);
                });
    }

    private static void ensureFtpRunning() {
        if (ftp == null || ftp.isShutdown()) {
            ftp = newFtpServer(ftpPort);                 // same port: config stays valid
            ftp.start();
        }
    }

    @AfterAll
    static void tearDown() {
        if (ftp != null && !ftp.isShutdown()) ftp.stop();
    }
}
