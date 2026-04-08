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
import io.smallrye.common.annotation.Blocking;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

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
    @Blocking
    public void process(JsonObject json) {
        var event = DocumentEvent.fromJson(json);
        log.infof("NotifyConsumer: processing documentId=%s, tenant=%s",
                event.documentId(), event.tenantSchemaName());

        try {
            var tenant = tenantRepository.findBySchemaName(event.tenantSchemaName());
            if (tenant == null) {
                log.warnf("NotifyConsumer: tenant not found: %s", event.tenantSchemaName());
                return;
            }

            connectionManager.withTenantTransaction(event.tenantSchemaName(), em -> {
                var doc = em.find(Document.class, event.documentId());
                if (doc == null) {
                    log.warnf("NotifyConsumer: document not found: %s", event.documentId());
                    return null;
                }
                if (!doc.status.canTransitionTo(DocumentStatus.NOTIFIED)) {
                    log.warnf("NotifyConsumer: skip document %s in state %s",
                            doc.id, doc.status);
                    return null;
                }

                // TODO: T-015 — Generate RIDE (PDF)
                // TODO: T-016 — Store authorized XML and RIDE in MinIO
                // TODO: T-017 — Send email to recipient

                dispatchWebhook(tenant.webhookUrl, tenant.webhookSecret, doc, em);

                doc.transitionTo(DocumentStatus.NOTIFIED);
                doc.updatedAt = Instant.now();
                log.infof("NotifyConsumer: document %s marked as NOTIFIED", doc.id);
                return null;
            });

        } catch (Exception ex) {
            errorHandler.persistError(event.documentId(), event.tenantSchemaName(),
                    "NotifyConsumer", ex);
        }
    }

    /**
     * Despacha el webhook al tenant (operación bloqueante).
     * Si el tenant no tiene webhook configurado, se omite sin error.
     */
    private void dispatchWebhook(String webhookUrl, String webhookSecret,
            Document doc, EntityManager em) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.debugf("NotifyConsumer: no webhook configured for document %s", doc.id);
            return;
        }

        String eventType = resolveEventType(doc);

        try {
            var delivery = webhookDispatcher.dispatch(webhookUrl, webhookSecret, doc, eventType);
            if (delivery != null) {
                em.persist(delivery);
            }
        } catch (Exception ex) {
            log.warnf(ex, "NotifyConsumer: webhook dispatch failed for document %s (non-blocking)", doc.id);
        }
    }

    private String resolveEventType(Document doc) {
        return switch (doc.status) {
            case AUTHORIZED -> "document.authorized";
            case REJECTED -> "document.rejected";
            default -> "document." + doc.status.name().toLowerCase();
        };
    }
}
