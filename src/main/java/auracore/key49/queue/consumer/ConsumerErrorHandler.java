package auracore.key49.queue.consumer;

import java.time.Instant;
import java.util.UUID;

import org.jboss.logging.Logger;

import auracore.key49.core.model.Document;
import auracore.key49.core.model.InvalidStateTransitionException;
import auracore.key49.core.model.enums.DocumentStatus;
import auracore.key49.core.service.AuditService;
import auracore.key49.core.service.QuotaService;
import auracore.key49.core.service.TenantCacheService;
import auracore.key49.core.tenant.TenantConnectionManager;
import auracore.key49.notify.webhook.WebhookDispatcher;
import io.vertx.core.json.JsonObject;
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
    TenantCacheService tenantCacheService;

    @Inject
    QuotaService quotaService;

    @Inject
    WebhookDispatcher webhookDispatcher;

    @Inject
    AuditService auditService;

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
                        quotaService.releaseQuota(em, tenantSchemaName);
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

    /**
     * Notifica de forma explícita el fallo definitivo de un documento: despacha el
     * webhook {@code document.failed} al tenant y registra el evento en
     * {@code audit_log}. Abre su propia transacción, por lo que puede invocarse
     * fuera de otra (p. ej. tras agotar reintentos o al procesar la DLQ).
     */
    public void notifyFailure(String tenantSchemaName, UUID documentId, String reason) {
        try {
            connectionManager.withTenantTransaction(tenantSchemaName, em -> {
                var doc = em.find(Document.class, documentId);
                if (doc == null) {
                    log.warnf("ConsumerErrorHandler: document not found for failure notification: %s",
                            documentId);
                    return null;
                }
                dispatchFailureWebhook(tenantSchemaName, doc, em);
                recordFailureAudit(tenantSchemaName, doc, reason);
                return null;
            });
        } catch (Exception ex) {
            log.warnf(ex, "ConsumerErrorHandler: failure notification error for document %s", documentId);
        }
    }

    private void recordFailureAudit(String tenantSchemaName, Document doc, String reason) {
        try {
            var tenant = tenantCacheService.findBySchemaName(tenantSchemaName);
            if (tenant == null) {
                return;
            }
            auditService.record(tenant.id, "system", "document.failed", "document", doc.id, null,
                    new JsonObject().put("reason", truncate(reason, 500)).encode());
        } catch (Exception auditEx) {
            log.warnf(auditEx, "ConsumerErrorHandler: audit record failed for document %s", doc.id);
        }
    }

    private void dispatchFailureWebhook(String tenantSchemaName, Document doc, EntityManager em) {
        try {
            var tenant = tenantCacheService.findBySchemaName(tenantSchemaName);
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
