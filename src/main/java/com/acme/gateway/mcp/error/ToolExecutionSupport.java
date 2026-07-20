package com.acme.gateway.mcp.error;

import com.acme.gateway.ftp.FtpOperationException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Tracer;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.lang.Nullable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * The single choke point every @McpTool method runs through.
 *
 * WHY THIS EXISTS: Spring AI's default behavior returns a thrown exception's
 * message verbatim to the MCP client. Against a SQL Server + Hibernate
 * backend that means SQLState codes, constraint names, table names, and FTP
 * hostnames leaking to an untrusted agent. This boundary guarantees that the
 * ONLY strings crossing the MCP edge are (a) DTOs we built, or (b) the stable
 * JSON error envelope of contract §7.8.
 *
 * It also owns per-tool telemetry: one timer per call tagged
 * tool/outcome/error_code/role, and MDC enrichment so structured logs carry
 * the tool name and caller identity.
 */
@Component
public class ToolExecutionSupport {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutionSupport.class);

    private final MeterRegistry meters;
    @Nullable
    private final Tracer tracer;   // present when micrometer-tracing is on the classpath

    public ToolExecutionSupport(MeterRegistry meters, @Nullable Tracer tracer) {
        this.meters = meters;
        this.tracer = tracer;
    }

    public <T> T run(String toolName, Supplier<T> body) {
        Timer.Sample sample = Timer.start(meters);
        String errorCode = "NONE";
        MDC.put("mcp.tool", toolName);
        MDC.put("agent.sub", currentSubject());
        try {
            return body.get();

        } catch (GatewayException e) {                    // domain: message is already agent-safe
            errorCode = e.code().name();
            log.warn("tool.domain_error tool={} code={} msg={}", toolName, e.code(), e.getMessage());
            throw envelope(e.code(), e.getMessage());

        } catch (AccessDeniedException e) {               // method-security denial
            errorCode = ToolErrorCode.FORBIDDEN.name();
            meters.counter("gateway.authz.denied", "tool", toolName).increment();
            log.warn("tool.forbidden tool={} sub={}", toolName, currentSubject());
            throw envelope(ToolErrorCode.FORBIDDEN, "Caller is not authorized for this tool.");

        } catch (ConstraintViolationException e) {        // bean validation
            errorCode = ToolErrorCode.VALIDATION_FAILED.name();
            throw envelope(ToolErrorCode.VALIDATION_FAILED, violationsOf(e));

        } catch (ObjectOptimisticLockingFailureException e) {
            errorCode = ToolErrorCode.CONFLICT.name();
            throw envelope(ToolErrorCode.CONFLICT,
                    "The record was modified concurrently. Refetch and retry.");

        } catch (FtpOperationException e) {               // full detail stays server-side
            errorCode = ToolErrorCode.FTP_UNAVAILABLE.name();
            log.error("tool.ftp_error tool={} class={} reply={}",
                    toolName, e.errorClass(), e.replyCode(), e);
            throw envelope(ToolErrorCode.FTP_UNAVAILABLE,
                    "The file store is temporarily unavailable. The operation was not confirmed.");

        } catch (DataAccessException e) {                 // NEVER forward SQL detail
            errorCode = ToolErrorCode.INTERNAL.name();
            log.error("tool.db_error tool={}", toolName, e);
            throw envelope(ToolErrorCode.INTERNAL, "A storage error occurred.");

        } catch (RuntimeException e) {                    // catch-all: log full, say nothing
            errorCode = ToolErrorCode.INTERNAL.name();
            log.error("tool.unexpected_error tool={}", toolName, e);
            throw envelope(ToolErrorCode.INTERNAL, "An internal error occurred.");

        } finally {
            sample.stop(meters.timer("mcp.tool.calls",
                    "tool", toolName,
                    "outcome", "NONE".equals(errorCode) ? "success" : "error",
                    "error_code", errorCode));
            MDC.remove("mcp.tool");
            MDC.remove("agent.sub");
        }
    }

    /* ── envelope construction (contract §7.8) ──────────────────────────── */

    private GatewayToolException envelope(ToolErrorCode code, String safeMessage) {
        String json = """
                {"errorCode":"%s","message":"%s","retryable":%s,"traceId":"%s"}"""
                .formatted(code.name(), jsonEscape(safeMessage), code.retryable(), currentTraceId());
        return new GatewayToolException(json);
    }

    public String currentTraceId() {
        if (tracer != null && tracer.currentSpan() != null) {
            return tracer.currentSpan().context().traceId();
        }
        return "unavailable";
    }

    public String currentSubject() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "anonymous" : auth.getName();
    }

    private static String violationsOf(ConstraintViolationException e) {
        StringBuilder sb = new StringBuilder("Validation failed: ");
        e.getConstraintViolations().forEach(v ->
                sb.append(v.getPropertyPath()).append(' ').append(v.getMessage()).append("; "));
        return sb.toString();
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ").replace("\t", " ");
    }

    /**
     * The exception whose message IS the sanitized §7.8 envelope. Spring AI
     * forwards it as the MCP tool error (isError: true) — by construction the
     * only failure text an agent can ever receive from this server.
     */
    public static final class GatewayToolException extends RuntimeException {
        GatewayToolException(String envelopeJson) {
            super(envelopeJson);
        }
    }
}
