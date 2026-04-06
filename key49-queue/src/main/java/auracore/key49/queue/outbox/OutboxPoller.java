package auracore.key49.queue.outbox;

import java.util.List;

import org.jboss.logging.Logger;

import auracore.key49.core.model.OutboxEvent;
import auracore.key49.core.model.Tenant;
import auracore.key49.core.repository.OutboxRepository;
import auracore.key49.core.repository.TenantRepository;
import auracore.key49.core.tenant.TenantConnectionManager;
import auracore.key49.queue.event.DocumentEvent;
import auracore.key49.queue.producer.DocumentEventProducer;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Poller que lee eventos no publicados de la tabla outbox de cada tenant
 * y los publica a RabbitMQ a través del {@link DocumentEventProducer}.
 *
 * <p>Ejecuta cada 500ms (configurable), procesando hasta 50 eventos por tenant
 * en orden FIFO. Garantiza entrega al menos una vez (at-least-once).
 */
@ApplicationScoped
public class OutboxPoller {

    @Inject
    Logger log;

    @Inject
    TenantRepository tenantRepository;

    @Inject
    TenantConnectionManager connectionManager;

    @Inject
    OutboxRepository outboxRepository;

    @Inject
    DocumentEventProducer producer;

    @Inject
    OutboxEventRouter router;

    @Scheduled(every = "${key49.outbox.poll-interval:500ms}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    Uni<Void> poll() {
        return tenantRepository.findAllActive()
                .onItem().transformToMulti(tenants -> Multi.createFrom().iterable(tenants))
                .onItem().transformToUniAndConcatenate(this::pollTenant)
                .toUni().replaceWithVoid()
                .onFailure().invoke(ex -> log.errorf(ex, "OutboxPoller: error during poll cycle"));
    }

    private Uni<Void> pollTenant(Tenant tenant) {
        return connectionManager.withTenantTransaction(tenant.schemaName, session ->
                outboxRepository.findUnpublished(50)
                        .chain(events -> publishEvents(events, tenant.schemaName))
        ).onFailure().invoke(ex ->
                log.errorf(ex, "OutboxPoller: error polling tenant=%s", tenant.schemaName)
        ).onFailure().recoverWithNull().replaceWithVoid();
    }

    private Uni<Void> publishEvents(List<OutboxEvent> events, String tenantSchemaName) {
        if (events.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        log.debugf("OutboxPoller: publishing %d events for tenant=%s", events.size(), tenantSchemaName);

        return Multi.createFrom().iterable(events)
                .onItem().transformToUniAndConcatenate(event -> publishAndMark(event, tenantSchemaName))
                .toUni().replaceWithVoid();
    }

    private Uni<Void> publishAndMark(OutboxEvent event, String tenantSchemaName) {
        var documentEvent = DocumentEvent.fromOutbox(
                event.aggregateId, tenantSchemaName, event.eventType, event.payload);

        return router.route(documentEvent)
                .chain(() -> outboxRepository.markAsPublished(event.id))
                .replaceWithVoid()
                .onFailure().invoke(ex ->
                        log.errorf(ex, "OutboxPoller: failed to publish event=%s, type=%s, tenant=%s",
                                event.id, event.eventType, tenantSchemaName));
    }
}
