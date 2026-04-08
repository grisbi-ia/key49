package auracore.key49.queue.consumer;

import java.time.Instant;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import auracore.key49.core.model.Document;
import auracore.key49.core.model.enums.DocumentStatus;
import auracore.key49.core.repository.TenantRepository;
import auracore.key49.core.tenant.TenantConnectionManager;
import auracore.key49.notify.webhook.WebhookDispatcher;
import auracore.key49.queue.event.DocumentEvent;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Consumidor que genera RIDE (PDF), envía email y dispara webhooks. Transición:
 * AUTHORIZED → NOTIFIED.
 */
@ApplicationScoped
public class NotifyConsumer {

    @Inject
    Logger log;

    @Inject
    ConsumerErrorHandler errorHandler;

    @Inject
    TenantConnectionManager connectionManager;

    @Inject
    TenantRepository tenantRepository;

    @Inject
    WebhookDispatcher webhookDispatcher;

    @Incoming("doc-notify-in")
    @WithSession
    public Uni<Void> process(JsonObject json) {
        var event = DocumentEvent.fromJson(json);
        log.infof("NotifyConsumer: processing documentId=%s, tenant=%s",
                event.documentId(), event.tenantSchemaName());

        return tenantRepository.findBySchemaName(event.tenantSchemaName())
                .chain(tenant -> {
                    if (tenant == null) {
                        log.warnf("NotifyConsumer: tenant not found: %s", event.tenantSchemaName());
                        return Uni.createFrom().voidItem();
                    }

                    return connectionManager.withTenantTransaction(event.tenantSchemaName(), session
                            -> session.find(Document.class, event.documentId())
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
                                        return dispatchWebhook(tenant.webhookUrl, tenant.webhookSecret, doc, session)
                                                .chain(() -> {
                                                    doc.transitionTo(DocumentStatus.NOTIFIED);
                                                    doc.updatedAt = Instant.now();
                                                    log.infof("NotifyConsumer: document %s marked as NOTIFIED", doc.id);
                                                    return Uni.createFrom().voidItem();
                                                });
                                    })
                    );
                })
                .onFailure().recoverWithUni(ex
                        -> errorHandler.persistError(event.documentId(), event.tenantSchemaName(),
                        "NotifyConsumer", ex))
                .replaceWithVoid();
    }

    /**
     * Despacha el webhook al tenant (operación bloqueante ejecutada en worker
     * pool). Si el tenant no tiene webhook configurado, se omite sin error.
     */
    private Uni<Void> dispatchWebhook(String webhookUrl, String webhookSecret,
            Document doc, org.hibernate.reactive.mutiny.Mutiny.Session session) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.debugf("NotifyConsumer: no webhook configured for document %s", doc.id);
            return Uni.createFrom().voidItem();
        }

        String eventType = resolveEventType(doc);

        return Uni.createFrom().item(() -> webhookDispatcher.dispatch(webhookUrl, webhookSecret, doc, eventType))
                .emitOn(Infrastructure.getDefaultWorkerPool())
                .chain(delivery -> {
                    if (delivery != null) {
                        return session.persist(delivery).replaceWithVoid();
                    }
                    return Uni.createFrom().voidItem();
                })
                .onFailure().invoke(ex
                        -> log.warnf(ex, "NotifyConsumer: webhook dispatch failed for document %s (non-blocking)", doc.id))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    private String resolveEventType(Document doc) {
        return switch (doc.status) {
            case AUTHORIZED ->
                "document.authorized";
            case REJECTED ->
                "document.rejected";
            default ->
                "document." + doc.status.name().toLowerCase();
        };
    }
}
