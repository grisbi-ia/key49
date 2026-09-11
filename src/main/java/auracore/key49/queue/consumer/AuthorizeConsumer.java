package auracore.key49.queue.consumer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.eclipse.microprofile.config.inject.ConfigProperty;
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
import auracore.key49.sri.SriNotRegisteredException;
import auracore.key49.sri.SriPendingException;
import auracore.key49.sri.client.SriAuthorizationClient;
import auracore.key49.sri.model.SriAuthorizationResponse;
import auracore.key49.sri.model.SriMessage;
import io.smallrye.common.annotation.Blocking;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

/**
 * Consumidor que consulta la autorización del SRI vía SOAP Autorización.
 * Transición: RECEIVED → AUTHORIZED (éxito), RECEIVED → REJECTED (negocio),
 * RECEIVED → RETRY (infra).
 */
@ApplicationScoped
public class AuthorizeConsumer {

    @Inject
    Logger log;

    @Inject
    ConsumerErrorHandler errorHandler;

    @Inject
    TenantCacheService tenantCacheService;

    @Inject
    TenantConnectionManager connectionManager;

    @Inject
    SriAuthorizationClient sriAuthorizationClient;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    DocumentMetrics documentMetrics;

    @Inject
    QuotaService quotaService;

    @Inject
    InFlightTracker tracker;

    @ConfigProperty(name = "key49.reconcile.interval", defaultValue = "10m")
    Duration reconcileInterval;

    /**
     * Antiguedad minima desde el envio al SRI para considerar una clave como
     * NO registrada. Antes de ese tiempo, una respuesta sin autorizacion se
     * trata como pendiente (la autorizacion del SRI es asincrona).
     */
    @ConfigProperty(name = "key49.sri.not-registered-min-age", defaultValue = "10m")
    Duration notRegisteredMinAge;

    @Incoming("doc-authorize-in")
    @Blocking
    @ActivateRequestContext
    public void process(JsonObject json) {
        tracker.increment("AuthorizeConsumer");
        try {
            var event = DocumentEvent.fromJson(json);
            MdcContext.setTenant(event.tenantSchemaName());
            MdcContext.setDocument(event.documentId());
            log.infof("AuthorizeConsumer: processing documentId=%s, tenant=%s",
                    event.documentId(), event.tenantSchemaName());

            try {
                var tenant = tenantCacheService.findBySchemaName(event.tenantSchemaName());
                if (tenant == null) {
                    log.errorf("AuthorizeConsumer: tenant not found: %s", event.tenantSchemaName());
                    return;
                }
                var sriEnv = SignConsumer.resolveEnvironment(tenant.environment);

                // Read document data (read-only, outside SOAP transaction)
                var input = connectionManager.withTenantSession(event.tenantSchemaName(), em -> {
                    var doc = em.find(Document.class, event.documentId());
                    return doc != null
                            ? new AuthInput(doc.id, doc.accessKey, doc.status)
                            : null;
                });

                if (input == null) {
                    log.warnf("AuthorizeConsumer: document not found: %s", event.documentId());
                    return;
                }
                if (!input.status.canTransitionTo(DocumentStatus.AUTHORIZED)) {
                    log.warnf("AuthorizeConsumer: skip document %s in state %s",
                            input.id, input.status);
                    return;
                }
                if (input.accessKey == null || input.accessKey.isBlank()) {
                    log.errorf("AuthorizeConsumer: no access key for document %s", input.id);
                    return;
                }

                // SOAP call (blocking, outside transaction)
                try {
                    var timer = documentMetrics.sriAuthorizationTimer(event.tenantSchemaName());
                    var sample = io.micrometer.core.instrument.Timer.start();
                    var response = sriAuthorizationClient.authorize(input.accessKey, sriEnv);
                    sample.stop(timer);
                    handleResponse(event, response);
                } catch (CircuitBreakerOpenException ex) {
                    handleInfraError(event,
                            new SriException("SRI circuit breaker open, fail-fast", ex));
                } catch (TimeoutException ex) {
                    handleInfraError(event,
                            new SriException("SRI authorization timeout exceeded", ex));
                } catch (SriPendingException ex) {
                    handlePending(event, ex.getMessage());
                } catch (SriNotRegisteredException ex) {
                    handleNotRegistered(event, ex.getMessage());
                } catch (SriException ex) {
                    handleInfraError(event, ex);
                }

            } catch (Exception ex) {
                errorHandler.persistError(event.documentId(), event.tenantSchemaName(),
                        "AuthorizeConsumer", ex);
            }
        } finally {
            MdcContext.clear();
            tracker.decrement("AuthorizeConsumer");
        }
    }

    private void handleResponse(DocumentEvent event, SriAuthorizationResponse response) {
        connectionManager.withTenantTransaction(event.tenantSchemaName(), em -> {
            var doc = em.find(Document.class, event.documentId());
            if (doc == null) {
                return null;
            }

            doc.sriMessages = serializeMessages(response.messages());
            doc.updatedAt = Instant.now();

            if (response.isAuthorized()) {
                doc.transitionTo(DocumentStatus.AUTHORIZED);
                doc.authorizationNumber = response.authorizationNumber();
                if (response.authorizationDate() != null) {
                    doc.authorizationDate = Instant.now();
                }
                // Persistir el XML autorizado devuelto por el SRI. NotifyConsumer lo
                // almacena en MinIO (AUTHORIZED_XML) y lo adjunta al email.
                if (response.authorizedXml() != null && !response.authorizedXml().isBlank()) {
                    doc.originalXml = response.authorizedXml();
                }
                // Limpiar errores previos: el documento ahora está autorizado.
                doc.lastErrorCode = null;
                doc.lastErrorMessage = null;
                doc.nextRetryAt = null;
                documentMetrics.recordAuthorized(event.tenantSchemaName());
                log.infof("AuthorizeConsumer: document %s authorized, authNum=%s",
                        doc.id, doc.authorizationNumber);
                var outbox = OutboxEvent.create(doc.id, "doc.notify", "{}");
                em.persist(outbox);

            } else if (response.hasBusinessErrors() || !hasInProcessing(response.messages())) {
                // NO AUTORIZADO sin mensaje de "en procesamiento" (código 70): permanente.
                var code = response.hasBusinessErrors()
                        ? SendConsumer.extractFirstErrorCode(response.messages())
                        : "NO_AUTORIZADO";
                var message = response.hasBusinessErrors()
                        ? SendConsumer.extractErrorSummary(response.messages())
                        : summarizeMessages(response.messages());
                markRejected(doc, em, event.tenantSchemaName(), code, message);

            } else {
                var sriSummary = summarizeMessages(response.messages());
                log.infof("AuthorizeConsumer: document %s pending at SRI (in processing), messages=%s",
                        doc.id, sriSummary);
                handlePendingTransition(doc, em, event.tenantSchemaName(), sriSummary);
            }
            return null;
        });
    }

    private static boolean hasInProcessing(List<SriMessage> messages) {
        return messages != null && messages.stream().anyMatch(SriMessage::isInProcessing);
    }

    /**
     * Marca el documento como rechazado (o fallido si no admite REJECTED) con el
     * código/motivo indicado. Estado terminal: no se reintenta.
     */
    private void markRejected(Document doc, EntityManager em, String schemaName,
            String code, String message) {
        var targetStatus = doc.status.canTransitionTo(DocumentStatus.REJECTED)
                ? DocumentStatus.REJECTED : DocumentStatus.FAILED;
        doc.transitionTo(targetStatus);
        quotaService.releaseQuota(em, schemaName);
        doc.lastErrorCode = code;
        doc.lastErrorMessage = message;
        doc.nextRetryAt = null;
        doc.updatedAt = Instant.now();
        documentMetrics.recordRejected(schemaName, code != null ? code : "SRI_REJECTED");
        log.warnf("AuthorizeConsumer: document %s %s (code=%s): %s",
                doc.id, targetStatus, code, message);
    }

    /**
     * El SRI no tiene registrada la clave de acceso ({@code numeroComprobantes=0}):
     * estado permanente, se marca rechazado para que el contribuyente investigue.
     */
    private void handleNotRegistered(DocumentEvent event, String reason) {
        connectionManager.withTenantTransaction(event.tenantSchemaName(), em -> {
            var doc = em.find(Document.class, event.documentId());
            if (doc == null) {
                return null;
            }
            boolean recentlySubmitted = doc.sriSubmissionDate != null
                    && doc.sriSubmissionDate.isAfter(Instant.now().minus(notRegisteredMinAge));
            if (recentlySubmitted) {
                // El SRI genera la autorizacion de forma asincrona: un
                // numeroComprobantes=0 inmediato al envio NO significa NO registrada.
                if (doc.status != DocumentStatus.RECEIVED) {
                    doc.transitionTo(DocumentStatus.RECEIVED);
                }
                doc.lastErrorMessage = reason;
                doc.nextRetryAt = Instant.now().plus(Duration.ofMinutes(1));
                doc.updatedAt = Instant.now();
                log.infof("AuthorizeConsumer: document %s submitted recently (%s) — not registered yet, reconcile soon",
                        doc.id, doc.sriSubmissionDate);
                return null;
            }
            markRejected(doc, em, event.tenantSchemaName(), "NO_REG", reason);
            return null;
        });
    }

    /**
     * Reintenta la autorización más tarde sin consumir el presupuesto de reintentos
     * de error: mantiene el documento en {@code RECEIVED} y programa una
     * reconciliación. El {@code ReconciliationPoller} volverá a consultar el SRI.
     */
    private void handlePendingTransition(Document doc, EntityManager em,
            String schemaName, String reason) {
        if (doc.status == DocumentStatus.RECEIVED
                || doc.status == DocumentStatus.RETRY
                || doc.status == DocumentStatus.FAILED) {
            if (doc.status != DocumentStatus.RECEIVED) {
                doc.transitionTo(DocumentStatus.RECEIVED);
            }
            doc.lastErrorMessage = reason;
            doc.nextRetryAt = Instant.now().plus(reconcileInterval);
            doc.updatedAt = Instant.now();
            log.infof("AuthorizeConsumer: document %s pending authorization, next reconcile at %s",
                    doc.id, doc.nextRetryAt);
            return;
        }
        handleRetryTransition(doc, em, schemaName, reason, "AuthorizeConsumer");
    }

    private void handlePending(DocumentEvent event, String reason) {
        log.infof("AuthorizeConsumer: document %s authorization pending at SRI: %s",
                event.documentId(), reason);
        connectionManager.withTenantTransaction(event.tenantSchemaName(), em -> {
            var doc = em.find(Document.class, event.documentId());
            if (doc == null) {
                return null;
            }
            handlePendingTransition(doc, em, event.tenantSchemaName(), reason);
            return null;
        });
    }

    private void handleInfraError(DocumentEvent event, Throwable ex) {
        // En la etapa de autorización, un error de infraestructura (SRI caído,
        // circuit breaker abierto, timeout) NO debe marcar el documento como
        // fallido: el comprobante puede estar autorizado en el SRI. Se mantiene
        // en RECEIVED y se reconcilia hasta confirmar su estado.
        log.warnf(ex, "AuthorizeConsumer: SRI infrastructure error for document %s — scheduling reconciliation",
                event.documentId());
        handlePending(event, ex.getMessage());
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
            log.infof("%s: scheduling retry %d/%d for document %s, nextRetryAt=%s, error=%s",
                    consumer, doc.retryCount, doc.maxRetries, doc.id, doc.nextRetryAt, errorMessage);
        }
    }

    private String serializeMessages(List<SriMessage> messages) {
        try {
            return objectMapper.writeValueAsString(messages);
        } catch (JsonProcessingException e) {
            return messages.toString();
        }
    }

    /**
     * Resume todos los mensajes del SRI (ERROR, ADVERTENCIA, INFORMATIVO) para diagnóstico.
     * Útil en reintentos donde el SRI no autoriza pero tampoco devuelve errores de negocio
     * (ej: comprobante aún en procesamiento).
     */
    static String summarizeMessages(List<SriMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "No SRI messages returned";
        }
        return messages.stream()
                .map(m -> "[%s|%s] %s".formatted(
                        m.type() != null ? m.type() : "?",
                        m.identifier() != null ? m.identifier() : "?",
                        m.message() != null ? m.message() : ""))
                .reduce((a, b) -> a + "; " + b)
                .orElse("No SRI messages");
    }

    record AuthInput(UUID id, String accessKey, DocumentStatus status) {

    }
}
