package com.acme.gateway.ftp;

import com.acme.gateway.config.FtpProperties;
import com.acme.gateway.domain.model.ErrorClass;
import com.acme.gateway.domain.port.FileStorePort.DeleteOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockftpserver.fake.FakeFtpServer;
import org.mockftpserver.fake.UserAccount;
import org.mockftpserver.fake.filesystem.FileEntry;
import org.mockftpserver.fake.filesystem.UnixFakeFileSystem;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The reply-code -> ErrorClass classification IS the contract between the FTP
 * adapter and the saga: get it wrong and Scenario B either retries forever on
 * a permanent error or gives up on a transient one. These tests pin it
 * against a real protocol conversation (MockFtpServer's FakeFtpServer).
 */
class CommonsNetFtpGatewayTest {

    private static final String USER = "gw";
    private static final String PASS = "secret";
    private static final String EXISTING = "/signatures/acct-42/sig.p7s";

    private FakeFtpServer ftp;
    private CommonsNetFtpGateway gateway;

    @BeforeEach
    void startServer() {
        ftp = new FakeFtpServer();
        ftp.setServerControlPort(0);                       // ephemeral port
        var fs = new UnixFakeFileSystem();
        fs.add(new FileEntry(EXISTING, "pkcs7-bytes"));
        ftp.setFileSystem(fs);
        ftp.addUserAccount(new UserAccount(USER, PASS, "/"));
        ftp.start();
        gateway = gateway(PASS);
    }

    @AfterEach
    void stopServer() {
        if (ftp != null && !ftp.isShutdown()) ftp.stop();
    }

    @Test
    @DisplayName("existing file -> DELETED, and the file is actually gone")
    void delete_existingFile() {
        assertThat(gateway.delete(EXISTING)).isEqualTo(DeleteOutcome.DELETED);
        assertThat(ftp.getFileSystem().exists(EXISTING)).isFalse();
    }

    @Test
    @DisplayName("missing file (550) -> ALREADY_ABSENT: idempotent success, no exception")
    void delete_missingFile_isIdempotentSuccess() {
        assertThat(gateway.delete("/signatures/ghost.p7s"))
                .isEqualTo(DeleteOutcome.ALREADY_ABSENT);
    }

    @Test
    @DisplayName("bad credentials (530) -> PERMANENT: the reconciler must NOT retry this")
    void delete_badCredentials_isPermanent() {
        var ex = catchThrowableOfType(FtpOperationException.class,
                () -> gateway("wrong-password").delete(EXISTING));
        assertThat(ex.errorClass()).isEqualTo(ErrorClass.PERMANENT);
        assertThat(ex.retryable()).isFalse();
    }

    @Test
    @DisplayName("server down (connection refused) -> RETRYABLE_NETWORK: Scenario B backoff path")
    void delete_serverDown_isRetryableNetwork() {
        ftp.stop();
        var ex = catchThrowableOfType(FtpOperationException.class,
                () -> gateway.delete(EXISTING));
        assertThat(ex.errorClass()).isEqualTo(ErrorClass.RETRYABLE_NETWORK);
        assertThat(ex.retryable()).isTrue();
    }

    private CommonsNetFtpGateway gateway(String password) {
        var props = new FtpProperties("localhost", ftp.getServerControlPort(), USER, password,
                "/signatures", Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofSeconds(5));
        return new CommonsNetFtpGateway(props, new SimpleMeterRegistry());
    }
}
