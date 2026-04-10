package auracore.key49.queue.consumer;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import auracore.key49.core.model.Document;
import auracore.key49.core.model.enums.DocumentStatus;
import auracore.key49.core.model.enums.DocumentType;
import auracore.key49.core.repository.TenantRepository;
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
    TenantRepository tenantRepository;

    @Inject
    WebhookDispatcher webhookDispatcher;

    @Inject
    RideDataMapper rideDataMapper;

    @Inject
    ObjectStorageService objectStorageService;

    @Inject
    EmailService emailService;

    @Incoming("doc-notify-in")
    @Blocking
    @ActivateRequestContext
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

            // Fase 1 (transaccional): RIDE, MinIO, webhook, transición a NOTIFIED
            var emailData = connectionManager.withTenantTransaction(event.tenantSchemaName(), em -> {
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

                // Paso 1: Generar RIDE (PDF) — independiente, no bloquea transición
                byte[] ridePdf = null;
                try {
                    ridePdf = rideDataMapper.generateRide(doc, tenant);
                    log.infof("NotifyConsumer: RIDE generated for document %s (%d bytes)",
                            doc.id, ridePdf.length);
                } catch (Exception rideEx) {
                    log.warnf(rideEx, "NotifyConsumer: RIDE generation failed for document %s (non-blocking)",
                            doc.id);
                }

                // Paso 2: Almacenar artefactos en MinIO — independiente, no bloquea transición
                byte[] authorizedXmlBytes = null;
                try {
                    if (doc.originalXml != null) {
                        authorizedXmlBytes = doc.originalXml.getBytes(StandardCharsets.UTF_8);
                        var xmlPath = objectStorageService.store(
                                tenant.schemaName, doc.issueDate, doc.documentType,
                                doc.accessKey, DocumentArtifact.AUTHORIZED_XML, authorizedXmlBytes);
                        doc.authorizedXmlPath = xmlPath;
                        log.debugf("NotifyConsumer: authorized XML stored at %s", xmlPath);
                    }
                    if (ridePdf != null) {
                        var ridePdfPath = objectStorageService.store(
                                tenant.schemaName, doc.issueDate, doc.documentType,
                                doc.accessKey, DocumentArtifact.RIDE, ridePdf);
                        doc.ridePath = ridePdfPath;
                        log.debugf("NotifyConsumer: RIDE stored at %s", ridePdfPath);
                    }
                } catch (Exception storageEx) {
                    log.warnf(storageEx, "NotifyConsumer: storage failed for document %s (non-blocking)",
                            doc.id);
                }

                // Paso 3: Despachar webhook — independiente, no bloquea transición
                dispatchWebhook(tenant.webhookUrl, tenant.webhookSecret, doc, em);

                // Transicionar a NOTIFIED — el email se envía DESPUÉS del commit
                doc.transitionTo(DocumentStatus.NOTIFIED);
                doc.updatedAt = Instant.now();
                log.infof("NotifyConsumer: document %s marked as NOTIFIED", doc.id);

                // Preparar datos de email para envío post-commit
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
                        authorizedXmlBytes
                );
            });

            // Fase 2 (post-commit): Enviar email — NO dentro de transacción JTA
            if (emailData != null) {
                sendEmailAndUpdateStatus(emailData, event);
            }

        } catch (Exception ex) {
            errorHandler.persistError(event.documentId(), event.tenantSchemaName(),
                    "NotifyConsumer", ex);
        }
    }

    /**
     * Envía email y actualiza el estado de email en una transacción corta
     * separada. Si el envío falla, registra el error pero NO afecta el estado
     * NOTIFIED.
     */
    private void sendEmailAndUpdateStatus(EmailData emailData, DocumentEvent event) {
        try {
            emailService.sendDocumentDelivery(emailData);
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
     * Despacha el webhook al tenant (operación bloqueante). Si el tenant no
     * tiene webhook configurado, se omite sin error.
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
            case AUTHORIZED ->
                "document.authorized";
            case REJECTED ->
                "document.rejected";
            default ->
                "document." + doc.status.name().toLowerCase();
        };
    }

    private static String truncate(String value, int maxLength) {
        return value != null && value.length() > maxLength
                ? value.substring(0, maxLength)
                : value;
    }
}
