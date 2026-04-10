package auracore.key49.queue.outbox;

import java.util.List;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import auracore.key49.core.model.OutboxEvent;
import auracore.key49.core.repository.OutboxRepository;
import auracore.key49.core.repository.TenantRepository;
import auracore.key49.core.tenant.TenantConnectionManager;
import auracore.key49.queue.event.DocumentEvent;
import auracore.key49.queue.producer.DocumentEventProducer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Poller que lee eventos no publicados de la tabla outbox de cada tenant y los
 * publica a RabbitMQ a través del {@link DocumentEventProducer}.
 *
 * <p>
 * Ejecuta con intervalo configurable (default 500ms). Usa polling adaptativo:
 * al encontrar eventos, el siguiente ciclo usa intervalo corto; si no hay
 * eventos, usa intervalo largo (idle). Batch-size y ambos intervalos son
 * configurables vía variables de entorno.</p>
 *
 * <p>
 * Usa SELECT ... FOR UPDATE SKIP LOCKED para soportar concurrencia segura entre
 * múltiples instancias (futuro multi-instancia).</p>
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

    @ConfigProperty(name = "key49.outbox.batch-size", defaultValue = "50")
    int batchSize;

    private final Counter polledCounter;
    private final Timer pollDurationTimer;

    /**
     * Indica si el último ciclo encontró eventos (para polling adaptativo).
     */
    private volatile boolean lastCycleHadEvents = false;

    @Inject
    public OutboxPoller(MeterRegistry registry) {
        this.polledCounter = Counter.builder("key49.outbox.events.polled")
                .description("Total outbox events polled and published")
                .register(registry);
        this.pollDurationTimer = Timer.builder("key49.outbox.poll.duration")
                .description("Duration of each outbox poll cycle")
                .register(registry);
    }

    @Scheduled(every = "${key49.outbox.poll-interval:500ms}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void poll() {
        pollDurationTimer.record(() -> {
            try {
                boolean foundAny = false;
                var tenants = tenantRepository.findAllActive();
                for (var tenant : tenants) {
                    boolean tenantHadEvents = pollTenant(tenant.schemaName);
                    if (tenantHadEvents) {
                        foundAny = true;
                    }
                }
                lastCycleHadEvents = foundAny;
            } catch (Exception ex) {
                log.errorf(ex, "OutboxPoller: error during poll cycle");
            }
        });
    }

    /**
     * Indica si el último ciclo de polling encontró eventos. Útil para testing
     * y monitoreo del polling adaptativo.
     */
    public boolean lastCycleHadEvents() {
        return lastCycleHadEvents;
    }

    private boolean pollTenant(String schemaName) {
        try {
            return connectionManager.withTenantTransaction(schemaName, em -> {
                var events = outboxRepository.findUnpublishedForUpdate(batchSize);
                publishEvents(events, schemaName);
                return !events.isEmpty();
            });
        } catch (Exception ex) {
            log.errorf(ex, "OutboxPoller: error polling tenant=%s", schemaName);
            return false;
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
            polledCounter.increment();
        } catch (Exception ex) {
            log.errorf(ex, "OutboxPoller: failed to publish event=%s, type=%s, tenant=%s",
                    event.id, event.eventType, tenantSchemaName);
        }
    }
}
