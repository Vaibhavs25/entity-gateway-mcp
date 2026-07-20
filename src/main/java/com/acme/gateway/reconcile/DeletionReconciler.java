package com.acme.gateway.reconcile;

import com.acme.gateway.config.DeletionProperties;
import com.acme.gateway.domain.model.FileDeletionLedger;
import com.acme.gateway.domain.service.SignatureDeletionOrchestrator;
import com.acme.gateway.persistence.FileDeletionLedgerRepository;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Background healer for the deletion saga. Picks up:
 *   - PENDING_DELETE / FTP_FAILED_RETRYING rows whose next_attempt_at is due
 *     (Scenario B retries, and crash-between-TX1-and-FTP recovery), and
 *   - FTP_DELETED rows (Scenario A: finalize-only healing).
 *
 * Concurrency model:
 *   - @SchedulerLock (ShedLock, JDBC-backed) → exactly one reconciler runs
 *     across all replicas.
 *   - The only remaining race — an inline tool call advancing a row this
 *     sweep also claimed — is settled by @Version optimistic locking; the
 *     loser logs and skips. Both drivers run identical orchestrator code, so
 *     whoever wins produces the same state.
 */
@Component
public class DeletionReconciler {

    private static final Logger log = LoggerFactory.getLogger(DeletionReconciler.class);

    private final FileDeletionLedgerRepository ledgers;
    private final SignatureDeletionOrchestrator orchestrator;
    private final DeletionProperties props;
    private final MeterRegistry meters;

    public DeletionReconciler(FileDeletionLedgerRepository ledgers,
                              SignatureDeletionOrchestrator orchestrator,
                              DeletionProperties props,
                              MeterRegistry meters) {
        this.ledgers = ledgers;
        this.orchestrator = orchestrator;
        this.props = props;
        this.meters = meters;
        // Backlog gauge: FTP_DELETED age / backlog growth is the Scenario-A alarm.
        meters.gauge("gateway.deletion.pending", this,
                r -> r.ledgers.count() == 0 ? 0 : dueCount(r));
    }

    private static double dueCount(DeletionReconciler r) {
        return r.ledgers.findDueWork(Instant.now(), PageRequest.of(0, 1_000)).size();
    }

    @Scheduled(fixedDelayString = "${gateway.deletion.reconcile-interval}")
    @SchedulerLock(name = "deletion-reconciler",
                   // > worst-case batch (25 × 20s FTP ceiling); property-driven so
                   // tests can zero the windows and drive reconcile() manually.
                   lockAtMostFor = "${gateway.deletion.lock-at-most:10m}",
                   lockAtLeastFor = "${gateway.deletion.lock-at-least:10s}")
    public void reconcile() {
        List<FileDeletionLedger> due =
                ledgers.findDueWork(Instant.now(), PageRequest.of(0, props.reconcileBatchSize()));
        if (due.isEmpty()) {
            return;
        }
        log.info("reconciler.sweep due={}", due.size());
        for (FileDeletionLedger ledger : due) {
            try {
                var receipt = orchestrator.advance(ledger);
                log.debug("reconciler.advanced ledgerId={} state={}", ledger.getId(), receipt.state());
            } catch (ObjectOptimisticLockingFailureException raceLost) {
                log.debug("reconciler.skip ledgerId={} — advanced concurrently by inline call",
                        ledger.getId());
            } catch (Exception unexpected) {
                // Never let one poisoned row stall the sweep.
                log.error("reconciler.row_failed ledgerId={}", ledger.getId(), unexpected);
                meters.counter("gateway.reconciler.row_failures").increment();
            }
        }
    }
}
