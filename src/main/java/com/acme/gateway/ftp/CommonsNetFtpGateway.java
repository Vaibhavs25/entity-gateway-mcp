package com.acme.gateway.ftp;

import com.acme.gateway.config.FtpProperties;
import com.acme.gateway.domain.model.ErrorClass;
import com.acme.gateway.domain.port.FileStorePort;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;

/**
 * FTP adapter over Apache Commons Net.
 *
 * Design decisions:
 *  - Connection-per-operation. FTP control connections are fragile to pool
 *    (server-side idle kills, NAT timeouts) and deletion volume is low; a
 *    fresh, strictly-timeboxed connection is the more reliable production
 *    choice. All timeouts come from configuration — there is no unbounded
 *    network wait anywhere on this path, which is what makes Scenario B
 *    deterministic (a hang becomes a classified RETRYABLE_NETWORK failure
 *    within connect+data timeout).
 *  - Reply-code classification is the single source of truth for the
 *    orchestrator's retry/abort decision:
 *        250            -> DELETED
 *        550            -> ALREADY_ABSENT (idempotent success; the desired
 *                          postcondition "file absent" already holds — this is
 *                          also what heals a crash between FTP 250 and TX2)
 *        450            -> RETRYABLE_FILE_LOCKED  (file busy/locked)
 *        421, 425, 426  -> RETRYABLE_NETWORK      (service closing, data-conn failures)
 *        IOException /
 *        SocketTimeout  -> RETRYABLE_NETWORK
 *        530, 532, 553,
 *        50x            -> PERMANENT (auth/config drift — retrying cannot help)
 */
@Component
public class CommonsNetFtpGateway implements FileStorePort {

    private static final Logger log = LoggerFactory.getLogger(CommonsNetFtpGateway.class);

    private final FtpProperties props;
    private final MeterRegistry meters;

    public CommonsNetFtpGateway(FtpProperties props, MeterRegistry meters) {
        this.props = props;
        this.meters = meters;
    }

    @Override
    public DeleteOutcome delete(String path) {
        Timer.Sample sample = Timer.start(meters);
        String outcomeTag = "error";
        int replyForTag = -1;
        FTPClient ftp = new FTPClient();
        try {
            configure(ftp);
            connectAndLogin(ftp);

            boolean deleted = ftp.deleteFile(path);
            int reply = ftp.getReplyCode();
            replyForTag = reply;

            if (deleted) {
                outcomeTag = "deleted";
                log.info("FTP delete OK path={} reply={}", path, reply);
                return DeleteOutcome.DELETED;
            }
            if (reply == 550) {
                // Not found: idempotent success by design (see class javadoc).
                outcomeTag = "already_absent";
                log.info("FTP delete idempotent-success (550 not found) path={}", path);
                return DeleteOutcome.ALREADY_ABSENT;
            }
            throw classify(reply, ftp.getReplyString(), null);

        } catch (SocketTimeoutException e) {
            throw new FtpOperationException(ErrorClass.RETRYABLE_NETWORK, replyForTag,
                    "FTP timeout during delete of " + path, e);
        } catch (IOException e) {
            throw new FtpOperationException(ErrorClass.RETRYABLE_NETWORK, replyForTag,
                    "FTP I/O failure during delete of " + path + ": " + e.getMessage(), e);
        } finally {
            sample.stop(meters.timer("gateway.ftp.operations",
                    "op", "delete", "outcome", outcomeTag, "reply_code", String.valueOf(replyForTag)));
            disconnectQuietly(ftp);
        }
    }

    private void configure(FTPClient ftp) {
        int connectMs = (int) props.connectTimeout().toMillis();
        ftp.setConnectTimeout(connectMs);
        ftp.setDefaultTimeout(connectMs);
        ftp.setDataTimeout(Duration.ofMillis(props.dataTimeout().toMillis()));
        ftp.setControlKeepAliveTimeout(props.controlKeepAlive());
    }

    private void connectAndLogin(FTPClient ftp) throws IOException {
        ftp.connect(props.host(), props.port());
        // setSoTimeout only takes effect on a connected socket.
        ftp.setSoTimeout((int) props.dataTimeout().toMillis());

        int reply = ftp.getReplyCode();
        if (!FTPReply.isPositiveCompletion(reply)) {
            throw classify(reply, "FTP server refused connection", null);
        }
        if (!ftp.login(props.username(), props.password())) {
            throw classify(ftp.getReplyCode(), "FTP login failed", null);
        }
        ftp.enterLocalPassiveMode();  // firewall/NAT-safe in almost all enterprise topologies
    }

    private FtpOperationException classify(int reply, String detail, Throwable cause) {
        ErrorClass klass = switch (reply) {
            case 450 -> ErrorClass.RETRYABLE_FILE_LOCKED;          // file busy / locked
            case 421, 425, 426 -> ErrorClass.RETRYABLE_NETWORK;    // service closing / data-conn failure
            case 530, 532, 553, 500, 501, 502, 503, 504 -> ErrorClass.PERMANENT;
            default -> reply >= 400 && reply < 500
                    ? ErrorClass.RETRYABLE_NETWORK                 // 4xx transient by RFC 959 convention
                    : ErrorClass.PERMANENT;                        // unknown 5xx: retrying cannot help
        };
        return new FtpOperationException(klass, reply, "FTP reply " + reply + ": " + detail, cause);
    }

    private void disconnectQuietly(FTPClient ftp) {
        try {
            if (ftp.isConnected()) {
                ftp.logout();
                ftp.disconnect();
            }
        } catch (IOException e) {
            log.debug("Ignoring FTP disconnect failure", e);
        }
    }
}
