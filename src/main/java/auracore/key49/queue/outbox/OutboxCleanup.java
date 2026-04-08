package auracore.key49.queue.outbox;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.jboss.logging.Logger;

import auracore.key49.core.repository.OutboxRepository;
import auracore.key49.core.repository.TenantRepository;
import auracore.key49.core.tenant.TenantConnectionManager;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Job nocturno que elimina eventos publicados con más de 7 días de antigüedad.
 *
 * <p>Ejecuta a las 02:00 AM (zona Ecuador). Itera sobre todos los tenants activos
 * y limpia su tabla outbox.
 */
@ApplicationScoped
public class OutboxCleanup {

    static final int RETENTION_DAYS = 7;

    @Inject
    Logger log;

    @Inject
    TenantRepository tenantRepository;

    @Inject
    TenantConnectionManager connectionManager;

    @Inject
    OutboxRepository outboxRepository;

    @Scheduled(cron = "0 0 2 * * ?", timeZone = "America/Guayaquil",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void cleanup() {
        var cutoff = Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS);
        log.infof("OutboxCleanup: starting cleanup of events published before %s", cutoff);

        try {
            var tenants = tenantRepository.findAllActive();
            for (var tenant : tenants) {
                cleanupTenant(tenant.schemaName, cutoff);
            }
        } catch (Exception ex) {
            log.errorf(ex, "OutboxCleanup: error during cleanup cycle");
        }
    }

    private void cleanupTenant(String schemaName, Instant cutoff) {
        try {
            connectionManager.withTenantTransaction(schemaName, em -> {
                long count = outboxRepository.deleteOldPublished(cutoff);
                if (count > 0) {
                    log.infof("OutboxCleanup: deleted %d old events for tenant=%s",
                            count, schemaName);
                }
                return null;
            });
        } catch (Exception ex) {
            log.errorf(ex, "OutboxCleanup: error cleaning tenant=%s", schemaName);
        }
    }
}
