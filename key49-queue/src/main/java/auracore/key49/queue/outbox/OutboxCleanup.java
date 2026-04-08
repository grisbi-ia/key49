package auracore.key49.queue.outbox;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.jboss.logging.Logger;

import auracore.key49.core.model.Tenant;
import auracore.key49.core.repository.OutboxRepository;
import auracore.key49.core.repository.TenantRepository;
import auracore.key49.core.tenant.TenantConnectionManager;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
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

    @WithSession
    @Scheduled(cron = "0 0 2 * * ?", timeZone = "America/Guayaquil",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    Uni<Void> cleanup() {
        var cutoff = Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS);
        log.infof("OutboxCleanup: starting cleanup of events published before %s", cutoff);

        return tenantRepository.findAllActive()
                .onItem().transformToMulti(tenants -> Multi.createFrom().iterable(tenants))
                .onItem().transformToUniAndConcatenate(tenant -> cleanupTenant(tenant, cutoff))
                .toUni().replaceWithVoid()
                .onFailure().invoke(ex -> log.errorf(ex, "OutboxCleanup: error during cleanup cycle"));
    }

    private Uni<Void> cleanupTenant(Tenant tenant, Instant cutoff) {
        return connectionManager.withTenantTransaction(tenant.schemaName, session ->
                outboxRepository.deleteOldPublished(cutoff)
                        .invoke(count -> {
                            if (count > 0) {
                                log.infof("OutboxCleanup: deleted %d old events for tenant=%s",
                                        count, tenant.schemaName);
                            }
                        })
        ).onFailure().invoke(ex ->
                log.errorf(ex, "OutboxCleanup: error cleaning tenant=%s", tenant.schemaName)
        ).onFailure().recoverWithNull().replaceWithVoid();
    }
}
