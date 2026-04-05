package auracore.key49.queue.producer;

import org.eclipse.microprofile.reactive.messaging.Channel;
import org.jboss.logging.Logger;

import auracore.key49.queue.event.DocumentEvent;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.MutinyEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Productor de eventos de documentos hacia RabbitMQ.
 * Publica mensajes a las colas del pipeline de procesamiento.
 */
@ApplicationScoped
public class DocumentEventProducer {

    @Inject
    Logger log;

    @Inject
    @Channel("doc-sign-out")
    MutinyEmitter<DocumentEvent> signEmitter;

    @Inject
    @Channel("doc-send-out")
    MutinyEmitter<DocumentEvent> sendEmitter;

    @Inject
    @Channel("doc-authorize-out")
    MutinyEmitter<DocumentEvent> authorizeEmitter;

    @Inject
    @Channel("doc-notify-out")
    MutinyEmitter<DocumentEvent> notifyEmitter;

    public Uni<Void> sendToSign(DocumentEvent event) {
        log.debugf("Publishing to sign queue: documentId=%s, tenant=%s", event.documentId(), event.tenantSchemaName());
        return signEmitter.send(event);
    }

    public Uni<Void> sendToSend(DocumentEvent event) {
        log.debugf("Publishing to send queue: documentId=%s, tenant=%s", event.documentId(), event.tenantSchemaName());
        return sendEmitter.send(event);
    }

    public Uni<Void> sendToAuthorize(DocumentEvent event) {
        log.debugf("Publishing to authorize queue: documentId=%s, tenant=%s", event.documentId(), event.tenantSchemaName());
        return authorizeEmitter.send(event);
    }

    public Uni<Void> sendToNotify(DocumentEvent event) {
        log.debugf("Publishing to notify queue: documentId=%s, tenant=%s", event.documentId(), event.tenantSchemaName());
        return notifyEmitter.send(event);
    }
}
