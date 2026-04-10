package auracore.key49.queue.consumer;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Observa el evento de shutdown de Quarkus para registrar el estado de los
 * consumers RabbitMQ antes de que la aplicación se detenga. Esto permite
 * verificar que no se pierdan mensajes durante un redeploy.
 *
 * <p>
 * Comportamiento de RabbitMQ al shutdown:</p>
 * <ul>
 * <li>Quarkus deja de aceptar nuevos mensajes (cierra el consumer)</li>
 * <li>Los mensajes en vuelo continúan procesándose hasta que el timeout
 * expira</li>
 * <li>Si algún mensaje no se completa antes del timeout, RabbitMQ lo re-encola
 * automáticamente (basic.nack con requeue)</li>
 * </ul>
 */
@ApplicationScoped
public class GracefulShutdownObserver {

    @Inject
    Logger log;

    @Inject
    InFlightTracker tracker;

    @ConfigProperty(name = "quarkus.shutdown.timeout", defaultValue = "30s")
    String shutdownTimeout;

    void onShutdown(@Observes ShutdownEvent event) {
        var snapshot = tracker.snapshot();
        var total = tracker.totalInFlight();

        log.infof("Graceful shutdown initiated — timeout=%s, in-flight messages=%d",
                shutdownTimeout, total);

        snapshot.forEach((consumer, count) -> {
            if (count > 0) {
                log.infof("  Consumer '%s': %d message(s) in flight", consumer, count);
            }
        });

        if (total == 0) {
            log.info("No in-flight messages — clean shutdown");
        } else {
            log.warnf("%d message(s) still in flight — Quarkus will wait up to %s "
                    + "before forcing shutdown. Non-acked messages will be requeued by RabbitMQ.",
                    total, shutdownTimeout);
        }
    }
}
