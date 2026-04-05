package auracore.key49.queue.consumer;

import java.time.Instant;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import auracore.key49.core.model.Document;
import auracore.key49.core.model.enums.DocumentStatus;
import auracore.key49.core.tenant.TenantConnectionManager;
import auracore.key49.queue.event.DocumentEvent;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Consumidor que genera RIDE (PDF), envía email y dispara webhooks.
 * Transición: AUTHORIZED → NOTIFIED.
 *
 * <p>Pendiente de implementación completa en T-015 (RIDE), T-016 (storage),
 * T-017 (email/webhook). Por ahora solo realiza la transición de estado.
 */
@ApplicationScoped
public class NotifyConsumer {

    @Inject
    Logger log;

    @Inject
    TenantConnectionManager connectionManager;

    @Incoming("doc-notify-in")
    public Uni<Void> process(DocumentEvent event) {
        log.infof("NotifyConsumer: processing documentId=%s, tenant=%s",
                event.documentId(), event.tenantSchemaName());

        return connectionManager.withTenantTransaction(event.tenantSchemaName(), session ->
                session.find(Document.class, event.documentId())
                        .chain(doc -> {
                            if (doc == null) {
                                log.warnf("NotifyConsumer: document not found: %s", event.documentId());
                                return Uni.createFrom().voidItem();
                            }
                            if (!doc.status.canTransitionTo(DocumentStatus.NOTIFIED)) {
                                log.warnf("NotifyConsumer: skip document %s in state %s",
                                        doc.id, doc.status);
                                return Uni.createFrom().voidItem();
                            }

                            // TODO: T-015 — Generate RIDE (PDF)
                            // TODO: T-016 — Store authorized XML and RIDE in MinIO
                            // TODO: T-017 — Send email to recipient
                            // TODO: T-017 — Dispatch webhook to tenant

                            doc.transitionTo(DocumentStatus.NOTIFIED);
                            doc.updatedAt = Instant.now();

                            log.infof("NotifyConsumer: document %s marked as NOTIFIED", doc.id);
                            return Uni.createFrom().voidItem();
                        })
        ).onFailure().invoke(ex ->
                log.errorf(ex, "NotifyConsumer: error processing documentId=%s", event.documentId()))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }
}
