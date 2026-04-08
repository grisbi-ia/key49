package auracore.key49.queue.producer;

import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.logging.Logger;

import auracore.key49.queue.event.DocumentEvent;
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
    Emitter<DocumentEvent> signEmitter;

    @Inject
    @Channel("doc-send-out")
    Emitter<DocumentEvent> sendEmitter;

    @Inject
    @Channel("doc-authorize-out")
    Emitter<DocumentEvent> authorizeEmitter;

    @Inject
    @Channel("doc-notify-out")
    Emitter<DocumentEvent> notifyEmitter;

    public void sendToSign(DocumentEvent event) {
        log.debugf("Publishing to sign queue: documentId=%s, tenant=%s", event.documentId(), event.tenantSchemaName());
        signEmitter.send(event);
    }

    public void sendToSend(DocumentEvent event) {
        log.debugf("Publishing to send queue: documentId=%s, tenant=%s", event.documentId(), event.tenantSchemaName());
        sendEmitter.send(event);
    }

    public void sendToAuthorize(DocumentEvent event) {
        log.debugf("Publishing to authorize queue: documentId=%s, tenant=%s", event.documentId(), event.tenantSchemaName());
        authorizeEmitter.send(event);
    }

    public void sendToNotify(DocumentEvent event) {
        log.debugf("Publishing to notify queue: documentId=%s, tenant=%s", event.documentId(), event.tenantSchemaName());
        notifyEmitter.send(event);
    }
}
