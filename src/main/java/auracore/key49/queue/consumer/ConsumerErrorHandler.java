package auracore.key49.queue.consumer;

import java.time.Instant;
import java.util.UUID;

import org.jboss.logging.Logger;

import auracore.key49.core.model.Document;
import auracore.key49.core.model.InvalidStateTransitionException;
import auracore.key49.core.model.enums.DocumentStatus;
import auracore.key49.core.repository.TenantRepository;
import auracore.key49.core.tenant.TenantConnectionManager;
import auracore.key49.notify.webhook.WebhookDispatcher;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

/**
 * Persiste errores inesperados de los consumers en la tabla documents. Abre una
 * transacción nueva e independiente para que el error quede registrado incluso
 * si la transacción original falló. Si el documento pasa a FAILED, despacha un
 * webhook {@code document.failed} para notificar al integrador.
 */
@ApplicationScoped
public class ConsumerErrorHandler {

    @Inject
    Logger log;

    @Inject
    TenantConnectionManager connectionManager;

    @Inject
    TenantRepository tenantRepository;

    @Inject
    WebhookDispatcher webhookDispatcher;

    /**
     * Registra el error en el documento. Si el documento no está en estado
     * terminal, lo transiciona a FAILED y despacha webhook de notificación.
     */
    public void persistError(UUID documentId, String tenantSchemaName, String stage, Throwable ex) {
        log.errorf(ex, "%s: unexpected error for documentId=%s", stage, documentId);

        try {
            connectionManager.withTenantTransaction(tenantSchemaName, em -> {
                var doc = em.find(Document.class, documentId);
                if (doc == null) {
                    log.warnf("%s: document not found for error persistence: %s",
                            stage, documentId);
                    return null;
                }

                var errorMsg = "[%s] %s".formatted(stage,
                        ex.getMessage() != null ? ex.getMessage() : ex.getClass().getName());

                boolean transitionedToFailed = false;
                if (!doc.status.isTerminal()) {
                    try {
                        doc.transitionTo(DocumentStatus.FAILED);
                        transitionedToFailed = true;
                    } catch (InvalidStateTransitionException iste) {
                        log.warnf("%s: cannot transition to FAILED from %s for document %s",
                                stage, doc.status, doc.id);
                    }
                }

                doc.lastErrorMessage = truncate(errorMsg, 2000);
                doc.updatedAt = Instant.now();

                if (transitionedToFailed) {
                    dispatchFailureWebhook(tenantSchemaName, doc, em);
                }

                return null;
            });
        } catch (Exception persistEx) {
            log.errorf(persistEx, "%s: CRITICAL — failed to persist error for documentId=%s",
                    stage, documentId);
        }
    }

    private void dispatchFailureWebhook(String tenantSchemaName, Document doc, EntityManager em) {
        try {
            var tenant = tenantRepository.findBySchemaName(tenantSchemaName);
            if (tenant == null || tenant.webhookUrl == null || tenant.webhookUrl.isBlank()) {
                return;
            }
            var delivery = webhookDispatcher.dispatch(
                    tenant.webhookUrl, tenant.webhookSecret, doc, "document.failed");
            if (delivery != null) {
                em.persist(delivery);
            }
        } catch (Exception webhookEx) {
            log.warnf(webhookEx,
                    "ConsumerErrorHandler: webhook dispatch failed for document %s (non-blocking)",
                    doc.id);
        }
    }

    private static String truncate(String value, int maxLength) {
        return value != null && value.length() > maxLength
                ? value.substring(0, maxLength)
                : value;
    }
}
