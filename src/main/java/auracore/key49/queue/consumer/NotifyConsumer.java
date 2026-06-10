package auracore.key49.queue.consumer;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import auracore.key49.admin.metrics.DocumentMetrics;
import auracore.key49.core.model.Document;
import auracore.key49.core.model.Tenant;
import auracore.key49.core.model.enums.DocumentStatus;
import auracore.key49.core.model.enums.DocumentType;
import auracore.key49.core.service.TenantCacheService;
import auracore.key49.core.tenant.MdcContext;
import auracore.key49.core.tenant.TenantConnectionManager;
import auracore.key49.notify.email.EmailData;
import auracore.key49.notify.email.EmailService;
import auracore.key49.notify.webhook.WebhookDispatcher;
import auracore.key49.queue.event.DocumentEvent;
import auracore.key49.queue.mapper.RideDataMapper;
import auracore.key49.storage.DocumentArtifact;
import auracore.key49.storage.ObjectStorageService;
import io.smallrye.common.annotation.Blocking;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
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
    TenantCacheService tenantCacheService;

    @Inject
    WebhookDispatcher webhookDispatcher;

    @Inject
    RideDataMapper rideDataMapper;

    @Inject
    ObjectStorageService objectStorageService;

    @Inject
    EmailService emailService;

    @Inject
    DocumentMetrics documentMetrics;

    @Inject
    InFlightTracker tracker;

    @Incoming("doc-notify-in")
    @Blocking
    @ActivateRequestContext
    public void process(JsonObject json) {
        tracker.increment("NotifyConsumer");
        try {
            var event = DocumentEvent.fromJson(json);
            MdcContext.setTenant(event.tenantSchemaName());
            MdcContext.setDocument(event.documentId());
            log.infof("NotifyConsumer: processing documentId=%s, tenant=%s",
                    event.documentId(), event.tenantSchemaName());

            try {
                var tenant = tenantCacheService.findBySchemaName(event.tenantSchemaName());
                if (tenant == null) {
                    log.warnf("NotifyConsumer: tenant not found: %s", event.tenantSchemaName());
                    return;
                }

                // Fase 1 (transacción de lectura): obtener documento desconectado para I/O
                var doc = connectionManager.withTenantTransaction(event.tenantSchemaName(), em -> {
                    var d = em.find(Document.class, event.documentId());
                    if (d == null) {
                        log.warnf("NotifyConsumer: document not found: %s", event.documentId());
                        return null;
                    }
                    if (!d.status.canTransitionTo(DocumentStatus.NOTIFIED) && d.status != DocumentStatus.NOTIFIED) {
                        log.warnf("NotifyConsumer: skip document %s in state %s", d.id, d.status);
                        return null;
                    }
                    em.detach(d);
                    return d;
                });

                if (doc == null) {
                    return;
                }

                // Fase 2 (sin transacción): operaciones I/O pesadas
                byte[] ridePdf = null;
                try {
                    ridePdf = rideDataMapper.generateRide(doc, tenant);
                    log.infof("NotifyConsumer: RIDE generated for document %s (%d bytes)",
                            doc.id, ridePdf.length);
                } catch (Exception rideEx) {
                    log.warnf(rideEx, "NotifyConsumer: RIDE generation failed for document %s (non-blocking)",
                            doc.id);
                }

                String xmlPath = null;
                String ridePdfPath = null;
                byte[] authorizedXmlBytes = null;
                try {
                    if (doc.originalXml != null) {
                        authorizedXmlBytes = doc.originalXml.getBytes(StandardCharsets.UTF_8);
                        xmlPath = objectStorageService.store(
                                tenant.schemaName, doc.issueDate, doc.documentType,
                                doc.accessKey, DocumentArtifact.AUTHORIZED_XML, authorizedXmlBytes);
                        log.debugf("NotifyConsumer: authorized XML stored at %s", xmlPath);
                    }
                    if (ridePdf != null) {
                        ridePdfPath = objectStorageService.store(
                                tenant.schemaName, doc.issueDate, doc.documentType,
                                doc.accessKey, DocumentArtifact.RIDE, ridePdf);
                        log.debugf("NotifyConsumer: RIDE stored at %s", ridePdfPath);
                    }
                } catch (Exception storageEx) {
                    log.warnf(storageEx, "NotifyConsumer: storage failed for document %s (non-blocking)",
                            doc.id);
                }

                // Fase 3 (transacción corta): actualizar rutas, webhook, transicionar
                final var fXmlPath = xmlPath;
                final var fRidePath = ridePdfPath;
                connectionManager.withTenantTransaction(event.tenantSchemaName(), em -> {
                    var d = em.find(Document.class, event.documentId());
                    if (d == null || (!d.status.canTransitionTo(DocumentStatus.NOTIFIED) && d.status != DocumentStatus.NOTIFIED)) {
                        log.warnf("NotifyConsumer: document %s no longer eligible for NOTIFIED", event.documentId());
                        return null;
                    }

                    if (fXmlPath != null) {
                        d.authorizedXmlPath = fXmlPath;
                    }
                    if (fRidePath != null) {
                        d.ridePath = fRidePath;
                    }

                    dispatchWebhook(tenant.webhookUrl, tenant.webhookSecret, d, em,
                            event.tenantSchemaName());

                    if (d.status != DocumentStatus.NOTIFIED) {
                        d.transitionTo(DocumentStatus.NOTIFIED);
                    }
                    d.updatedAt = Instant.now();
                    log.infof("NotifyConsumer: document %s marked as NOTIFIED", d.id);
                    return null;
                });

                // Fase 4 (post-commit): enviar email sin afectar NOTIFIED
                if (!tenant.emailNotificationsEnabled) {
                    log.infof("NotifyConsumer: email notifications disabled for tenant=%s, skipping document=%s",
                            event.tenantSchemaName(), event.documentId());
                    markEmailSkipped(event);
                } else if (!tenant.notifyFinalConsumer && isFinalConsumer(doc.recipientId)) {
                    log.infof("NotifyConsumer: recipient is Consumidor Final, skipping email | tenant=%s document=%s recipientId=%s",
                            event.tenantSchemaName(), event.documentId(), doc.recipientId);
                    markEmailSkipped(event);
                } else {
                    sendEmailAndUpdateStatus(
                            buildEmailData(doc, tenant, ridePdf, authorizedXmlBytes), tenant, event);
                }

            } catch (Exception ex) {
                errorHandler.persistError(event.documentId(), event.tenantSchemaName(),
                        "NotifyConsumer", ex);
            }
        } finally {
            MdcContext.clear();
            tracker.decrement("NotifyConsumer");
        }
    }

    /**
     * Construye el objeto EmailData a partir del documento desconectado.
     */
    private EmailData buildEmailData(Document doc, Tenant tenant,
            byte[] ridePdf, byte[] authorizedXmlBytes) {
        var docType = DocumentType.fromSriCode(doc.documentType);
        var documentNumber = doc.establishment + "-" + doc.issuePoint + "-" + doc.sequenceNumber;
        return new EmailData(
                tenant.legalName,
                tenant.ruc,
                doc.recipientName,
                EmailData.parseEmails(doc.recipientEmail),
                docType.description(),
                documentNumber,
                doc.accessKey,
                doc.issueDate,
                doc.totalAmount,
                doc.currency,
                ridePdf,
                authorizedXmlBytes);
    }

    /**
     * Envía email y actualiza el estado de email en una transacción corta
     * separada. Si el envío falla, registra el error pero NO afecta el estado
     * NOTIFIED.
     */
    private void sendEmailAndUpdateStatus(EmailData emailData, Tenant tenant, DocumentEvent event) {
        try {
            emailService.sendDocumentDelivery(emailData, tenant);
            documentMetrics.recordEmailSent(event.tenantSchemaName());
            connectionManager.withTenantTransaction(event.tenantSchemaName(), em -> {
                var doc = em.find(Document.class, event.documentId());
                if (doc != null) {
                    doc.emailSentAt = Instant.now();
                    doc.emailStatus = "SENT";
                    doc.updatedAt = Instant.now();
                }
                return null;
            });
        } catch (Exception emailEx) {
            log.warnf(emailEx, "NotifyConsumer: email failed for document %s (non-blocking)",
                    event.documentId());
            documentMetrics.recordEmailFailed(event.tenantSchemaName());
            try {
                connectionManager.withTenantTransaction(event.tenantSchemaName(), em -> {
                    var doc = em.find(Document.class, event.documentId());
                    if (doc != null) {
                        doc.emailStatus = "FAILED";
                        doc.emailError = truncate(emailEx.getMessage(), 500);
                        doc.updatedAt = Instant.now();
                    }
                    return null;
                });
            } catch (Exception updateEx) {
                log.errorf(updateEx, "NotifyConsumer: failed to update email error status for document %s",
                        event.documentId());
            }
        }
    }

    /**
     * Marca el documento con emailStatus = "SKIPPED" cuando el tenant tiene
     * deshabilitadas las notificaciones por email.
     */
    private void markEmailSkipped(DocumentEvent event) {
        try {
            connectionManager.withTenantTransaction(event.tenantSchemaName(), em -> {
                var doc = em.find(Document.class, event.documentId());
                if (doc != null) {
                    doc.emailStatus = "SKIPPED";
                    doc.updatedAt = Instant.now();
                }
                return null;
            });
        } catch (Exception ex) {
            log.errorf(ex, "NotifyConsumer: failed to set emailStatus=SKIPPED for document %s",
                    event.documentId());
        }
    }

    /**
     * Despacha el webhook al tenant (operación bloqueante). Si el tenant no
     * tiene webhook configurado, se omite sin error.
     */
    private void dispatchWebhook(String webhookUrl, String webhookSecret,
            Document doc, EntityManager em, String tenantSchemaName) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.debugf("NotifyConsumer: no webhook configured for document %s", doc.id);
            return;
        }

        String eventType = resolveEventType(doc);

        try {
            var delivery = webhookDispatcher.dispatch(webhookUrl, webhookSecret, doc, eventType);
            if (delivery != null) {
                em.persist(delivery);
                documentMetrics.recordWebhookDispatched(tenantSchemaName);
            }
        } catch (Exception ex) {
            log.warnf(ex, "NotifyConsumer: webhook dispatch failed for document %s (non-blocking)", doc.id);
        }
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

    /**
     * Detecta si el receptor es Consumidor Final según la normativa SRI Ecuador.
     * El identificador de Consumidor Final es todo nines: {@code 9999999999} (cédula,
     * 10 dígitos) o {@code 9999999999999} (RUC, 13 dígitos).
     */
    static boolean isFinalConsumer(String recipientId) {
        if (recipientId == null || recipientId.isBlank()) return false;
        return (recipientId.length() == 10 || recipientId.length() == 13)
                && recipientId.chars().allMatch(c -> c == '9');
    }

    private static String truncate(String value, int maxLength) {
        return value != null && value.length() > maxLength
                ? value.substring(0, maxLength)
                : value;
    }
}
