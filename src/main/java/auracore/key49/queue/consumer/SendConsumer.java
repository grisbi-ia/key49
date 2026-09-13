package auracore.key49.queue.consumer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;
import org.eclipse.microprofile.faulttolerance.exceptions.TimeoutException;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import auracore.key49.admin.metrics.DocumentMetrics;
import auracore.key49.core.model.Document;
import auracore.key49.core.model.InvalidStateTransitionException;
import auracore.key49.core.model.OutboxEvent;
import auracore.key49.core.model.enums.DocumentStatus;
import auracore.key49.core.service.QuotaService;
import auracore.key49.core.service.TenantCacheService;
import auracore.key49.core.tenant.MdcContext;
import auracore.key49.core.tenant.TenantConnectionManager;
import auracore.key49.queue.event.DocumentEvent;
import auracore.key49.queue.retry.RetryDelayCalculator;
import auracore.key49.sri.SriException;
import auracore.key49.sri.client.SriReceptionClient;
import auracore.key49.sri.model.SriMessage;
import auracore.key49.sri.model.SriReceptionResponse;
import io.smallrye.common.annotation.Blocking;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

/**
 * Consumidor que envía documentos firmados al SRI vía SOAP Recepción.
 * Transición: SIGNED → SENT → RECEIVED (éxito), SIGNED → RETRY (infra), SIGNED
 * → REJECTED (negocio).
 */
@ApplicationScoped
public class SendConsumer {

    @Inject
    Logger log;

    @Inject
    ConsumerErrorHandler errorHandler;

    @Inject
    TenantCacheService tenantCacheService;

    @Inject
    TenantConnectionManager connectionManager;

    @Inject
    SriReceptionClient sriReceptionClient;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    DocumentMetrics documentMetrics;

    @Inject
    QuotaService quotaService;

    @Inject
    InFlightTracker tracker;

    @Incoming("doc-send-in")
    @Blocking
    @ActivateRequestContext
    public void process(JsonObject json) {
        tracker.increment("SendConsumer");
        try {
            var event = DocumentEvent.fromJson(json);
            MdcContext.setTenant(event.tenantSchemaName());
            MdcContext.setDocument(event.documentId());
            log.infof("SendConsumer: processing documentId=%s, tenant=%s",
                    event.documentId(), event.tenantSchemaName());

            try {
                var tenant = tenantCacheService.findBySchemaName(event.tenantSchemaName());
                if (tenant == null) {
                    log.errorf("SendConsumer: tenant not found: %s", event.tenantSchemaName());
                    return;
                }

                // Read document data (read-only, outside SOAP transaction)
                var input = connectionManager.withTenantSession(event.tenantSchemaName(), em -> {
                    var doc = em.find(Document.class, event.documentId());
                    return doc != null
                            ? new SendInput(doc.id, doc.originalXml, doc.accessKey, doc.status)
                            : null;
                });

                if (input == null) {
                    log.warnf("SendConsumer: document not found: %s", event.documentId());
                    return;
                }

                // El endpoint SOAP se resuelve con el ambiente embebido en la clave
                // de acceso firmada, no con el del tenant actual: así el <ambiente>
                // del XML y el servicio destino nunca se desalinean si el tenant
                // cambia de ambiente entre la firma y el envío.
                var sriEnv = SignConsumer.resolveEnvironmentFromAccessKey(
                        input.accessKey, tenant.environment);
                if (!sriEnv.equals(SignConsumer.resolveEnvironment(tenant.environment))) {
                    log.warnf("SendConsumer: document %s was signed for %s but tenant is now %s — using the document's environment",
                            input.id, sriEnv, tenant.environment);
                }
                if (!input.status.canTransitionTo(DocumentStatus.SENT)) {
                    log.warnf("SendConsumer: skip document %s in state %s",
                            input.id, input.status);
                    return;
                }
                if (input.signedXml == null || input.signedXml.isBlank()) {
                    log.errorf("SendConsumer: no signed XML for document %s", input.id);
                    markFailed(event, "No signed XML available");
                    return;
                }

                // Guarda de consistencia: el <ambiente> del comprobante firmado debe
                // coincidir con el ambiente del servicio SRI destino. Sin esto, un
                // XML de un ambiente enviado al otro produce rechazos silenciosos
                // (SRI 35 "el ambiente de la solicitud no coincide").
                var xmlAmbiente = extractXmlAmbiente(input.signedXml);
                if (xmlAmbiente != null && !xmlAmbiente.equals(sriEnv.sriCode())) {
                    log.errorf("SendConsumer: document %s declares ambiente=%s in XML but target endpoint is %s — refusing to send",
                            input.id, xmlAmbiente, sriEnv.sriCode());
                    markFailed(event, "Ambiente del XML (%s) no coincide con el ambiente SRI del endpoint (%s)"
                            .formatted(xmlAmbiente, sriEnv.sriCode()));
                    return;
                }

                // SOAP call (blocking, outside transaction)
                try {
                    var timer = documentMetrics.sriReceptionTimer(event.tenantSchemaName());
                    var sample = io.micrometer.core.instrument.Timer.start();
                    var response = sriReceptionClient.send(input.signedXml, sriEnv);
                    sample.stop(timer);
                    handleResponse(event, response);
                } catch (CircuitBreakerOpenException ex) {
                    handleInfraError(event,
                            new SriException("SRI circuit breaker open, fail-fast", ex));
                } catch (TimeoutException ex) {
                    handleInfraError(event,
                            new SriException("SRI reception timeout exceeded", ex));
                } catch (SriException ex) {
                    handleInfraError(event, ex);
                }

            } catch (Exception ex) {
                errorHandler.persistError(event.documentId(), event.tenantSchemaName(),
                        "SendConsumer", ex);
            }
        } finally {
            MdcContext.clear();
            tracker.decrement("SendConsumer");
        }
    }

    private void handleResponse(DocumentEvent event, SriReceptionResponse response) {
        connectionManager.withTenantTransaction(event.tenantSchemaName(), em -> {
            var doc = em.find(Document.class, event.documentId());
            if (doc == null) {
                return null;
            }

            doc.sriMessages = serializeMessages(response.messages());
            doc.sriSubmissionDate = Instant.now();
            doc.updatedAt = Instant.now();

            if (response.isReceived()) {
                doc.transitionTo(DocumentStatus.SENT);
                doc.transitionTo(DocumentStatus.RECEIVED);
                doc.nextRetryAt = null;
                log.infof("SendConsumer: document %s received by SRI", doc.id);
                var outbox = OutboxEvent.create(doc.id, "doc.authorize", "{}");
                em.persist(outbox);

            } else if (hasAlreadyRegistered(response.messages())) {
                // Código 43 "CLAVE ACCESO REGISTRADA": el SRI ya tiene el comprobante con
                // esta clave de acceso. NO es un error: no se reenvía, se consulta su
                // autorización (reconciliación). Evita marcarlo como FAILED.
                doc.transitionTo(DocumentStatus.SENT);
                doc.transitionTo(DocumentStatus.RECEIVED);
                doc.nextRetryAt = null;
                doc.lastErrorCode = null;
                doc.lastErrorMessage = null;
                log.infof("SendConsumer: document %s already registered at SRI (code 43) — reconciling authorization",
                        doc.id);
                var outbox = OutboxEvent.create(doc.id, "doc.authorize", "{}");
                em.persist(outbox);

            } else if (response.hasBusinessErrors()) {
                var targetStatus = doc.status.canTransitionTo(DocumentStatus.REJECTED)
                        ? DocumentStatus.REJECTED : DocumentStatus.FAILED;
                doc.transitionTo(targetStatus);
                quotaService.releaseQuota(em, event.tenantSchemaName());
                doc.lastErrorCode = extractFirstErrorCode(response.messages());
                doc.lastErrorMessage = extractErrorSummary(response.messages());
                documentMetrics.recordRejected(event.tenantSchemaName(),
                        doc.lastErrorCode != null ? doc.lastErrorCode : "SRI_REJECTED");
                log.warnf("SendConsumer: document %s %s by SRI: %s",
                        doc.id, targetStatus, doc.lastErrorMessage);

            } else {
                handleRetryTransition(doc, em, event.tenantSchemaName(),
                        extractErrorSummary(response.messages()), "SendConsumer");
            }
            return null;
        });
    }

    private void handleInfraError(DocumentEvent event, Throwable ex) {
        log.warnf(ex, "SendConsumer: SRI infrastructure error for document %s", event.documentId());

        connectionManager.withTenantTransaction(event.tenantSchemaName(), em -> {
            var doc = em.find(Document.class, event.documentId());
            if (doc == null) {
                return null;
            }
            handleRetryTransition(doc, em, event.tenantSchemaName(),
                    ex.getMessage(), "SendConsumer");
            return null;
        });
    }

    private void handleRetryTransition(Document doc, EntityManager em,
            String schemaName, String errorMessage, String consumer) {
        doc.retryCount++;
        doc.lastErrorMessage = errorMessage;
        doc.updatedAt = Instant.now();

        if (RetryDelayCalculator.isExhausted(doc.retryCount, doc.maxRetries)) {
            try {
                doc.transitionTo(DocumentStatus.FAILED);
                quotaService.releaseQuota(em, schemaName);
            } catch (InvalidStateTransitionException e) {
                log.warnf("%s: cannot transition to FAILED from %s for document %s",
                        consumer, doc.status, doc.id);
            }
            log.warnf("%s: retries exhausted for document %s (%d/%d): %s",
                    consumer, doc.id, doc.retryCount, doc.maxRetries, errorMessage);
        } else {
            if (doc.status != DocumentStatus.RETRY) {
                try {
                    doc.transitionTo(DocumentStatus.RETRY);
                } catch (InvalidStateTransitionException e) {
                    log.warnf("%s: cannot transition to RETRY from %s for document %s",
                            consumer, doc.status, doc.id);
                }
            }
            doc.nextRetryAt = RetryDelayCalculator.calculateNextRetryAt(doc.retryCount);
            log.infof("%s: scheduling retry %d/%d for document %s, nextRetryAt=%s",
                    consumer, doc.retryCount, doc.maxRetries, doc.id, doc.nextRetryAt);
        }
    }

    private void markFailed(DocumentEvent event, String reason) {
        connectionManager.withTenantTransaction(event.tenantSchemaName(), em -> {
            var doc = em.find(Document.class, event.documentId());
            if (doc == null) {
                return null;
            }
            try {
                doc.transitionTo(DocumentStatus.FAILED);
                quotaService.releaseQuota(em, event.tenantSchemaName());
            } catch (InvalidStateTransitionException e) {
                log.warnf("SendConsumer: cannot transition to FAILED from %s", doc.status);
            }
            doc.lastErrorMessage = reason;
            doc.updatedAt = Instant.now();
            return null;
        });
    }

    private String serializeMessages(List<SriMessage> messages) {
        try {
            return objectMapper.writeValueAsString(messages);
        } catch (JsonProcessingException e) {
            return messages.toString();
        }
    }

    static boolean hasAlreadyRegistered(List<SriMessage> messages) {
        return messages.stream().anyMatch(SriMessage::isAlreadyRegistered);
    }

    static String extractFirstErrorCode(List<SriMessage> messages) {
        return messages.stream()
                .filter(m -> "ERROR".equalsIgnoreCase(m.type()))
                .map(SriMessage::identifier)
                .findFirst()
                .orElse(null);
    }

    static String extractErrorSummary(List<SriMessage> messages) {
        return messages.stream()
                .filter(m -> "ERROR".equalsIgnoreCase(m.type()))
                .map(m -> "[%s] %s".formatted(m.identifier(), m.message()))
                .reduce((a, b) -> a + "; " + b)
                .orElse("Unknown error");
    }

    /**
     * Extrae el valor de {@code <ambiente>} del XML firmado, o {@code null} si no
     * está presente. Permite validar que el comprobante y el endpoint destino
     * apunten al mismo ambiente SRI antes de enviarlo.
     */
    static String extractXmlAmbiente(String signedXml) {
        if (signedXml == null) {
            return null;
        }
        var start = signedXml.indexOf("<ambiente>");
        if (start < 0) {
            return null;
        }
        var end = signedXml.indexOf("</ambiente>", start);
        if (end < 0) {
            return null;
        }
        return signedXml.substring(start + "<ambiente>".length(), end).trim();
    }

    record SendInput(UUID id, String signedXml, String accessKey, DocumentStatus status) {

    }
}
