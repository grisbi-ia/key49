package auracore.key49.queue.outbox;

import auracore.key49.queue.event.DocumentEvent;
import auracore.key49.queue.producer.DocumentEventProducer;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Enruta eventos del outbox al canal correcto del {@link DocumentEventProducer}
 * según el {@code eventType} del evento.
 */
@ApplicationScoped
public class OutboxEventRouter {

    @Inject
    DocumentEventProducer producer;

    /**
     * Enruta el evento al canal de RabbitMQ correspondiente.
     *
     * @param event evento a enrutar
     * @return Uni completado cuando el evento se publica
     * @throws IllegalArgumentException si el eventType no es reconocido
     */
    public Uni<Void> route(DocumentEvent event) {
        return switch (event.eventType()) {
            case "doc.sign" -> producer.sendToSign(event);
            case "doc.send" -> producer.sendToSend(event);
            case "doc.authorize" -> producer.sendToAuthorize(event);
            case "doc.notify" -> producer.sendToNotify(event);
            default -> Uni.createFrom().failure(
                    new IllegalArgumentException("Unknown event type: " + event.eventType()));
        };
    }
}
