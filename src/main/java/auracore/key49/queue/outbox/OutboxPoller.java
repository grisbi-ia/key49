package auracore.key49.queue.outbox;

import org.jboss.logging.Logger;

import auracore.key49.core.model.OutboxEvent;
import auracore.key49.core.repository.OutboxRepository;
import auracore.key49.core.repository.TenantRepository;
import auracore.key49.core.tenant.TenantConnectionManager;
import auracore.key49.queue.event.DocumentEvent;
import auracore.key49.queue.producer.DocumentEventProducer;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

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
    void poll() {
        try {
            var tenants = tenantRepository.findAllActive();
            for (var tenant : tenants) {
                pollTenant(tenant.schemaName);
            }
        } catch (Exception ex) {
            log.errorf(ex, "OutboxPoller: error during poll cycle");
        }
    }

    private void pollTenant(String schemaName) {
        try {
            connectionManager.withTenantTransaction(schemaName, em -> {
                var events = outboxRepository.findUnpublished(50);
                publishEvents(events, schemaName);
                return null;
            });
        } catch (Exception ex) {
            log.errorf(ex, "OutboxPoller: error polling tenant=%s", schemaName);
        }
    }

    private void publishEvents(List<OutboxEvent> events, String tenantSchemaName) {
        if (events.isEmpty()) {
            return;
        }

        log.debugf("OutboxPoller: publishing %d events for tenant=%s", events.size(), tenantSchemaName);

        for (var event : events) {
            publishAndMark(event, tenantSchemaName);
        }
    }

    private void publishAndMark(OutboxEvent event, String tenantSchemaName) {
        try {
            var documentEvent = DocumentEvent.fromOutbox(
                    event.aggregateId, tenantSchemaName, event.eventType, event.payload);
            router.route(documentEvent);
            outboxRepository.markAsPublished(event.id);
        } catch (Exception ex) {
            log.errorf(ex, "OutboxPoller: failed to publish event=%s, type=%s, tenant=%s",
                    event.id, event.eventType, tenantSchemaName);
        }
    }
}
