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
import auracore.key49.core.service.TenantCacheService;
import auracore.key49.core.tenant.TenantConnectionManager;
import auracore.key49.queue.event.DocumentEvent;
import auracore.key49.queue.retry.RetryDelayCalculator;
import auracore.key49.sri.SriException;
import auracore.key49.sri.client.SriAuthorizationClient;
import auracore.key49.sri.model.SriAuthorizationResponse;
import auracore.key49.sri.model.SriMessage;
import io.smallrye.common.annotation.Blocking;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;

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
    InFlightTracker tracker;

    @Incoming("doc-authorize-in")
    @Blocking
    @ActivateRequestContext
    public void process(JsonObject json) {
        tracker.increment("AuthorizeConsumer");
        try {
            var event = DocumentEvent.fromJson(json);
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
                } catch (SriException ex) {
                    handleInfraError(event, ex);
                }

            } catch (Exception ex) {
                errorHandler.persistError(event.documentId(), event.tenantSchemaName(),
                        "AuthorizeConsumer", ex);
            }
        } finally {
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
                documentMetrics.recordAuthorized(event.tenantSchemaName());
                // TODO: T-016 — Store authorized XML in MinIO
                log.infof("AuthorizeConsumer: document %s authorized, authNum=%s",
                        doc.id, doc.authorizationNumber);
                var outbox = OutboxEvent.create(doc.id, "doc.notify", "{}");
                em.persist(outbox);

            } else if (response.hasBusinessErrors()) {
                var targetStatus = doc.status.canTransitionTo(DocumentStatus.REJECTED)
                        ? DocumentStatus.REJECTED : DocumentStatus.FAILED;
                doc.transitionTo(targetStatus);
                doc.lastErrorCode = SendConsumer.extractFirstErrorCode(response.messages());
                doc.lastErrorMessage = SendConsumer.extractErrorSummary(response.messages());
                documentMetrics.recordRejected(event.tenantSchemaName(),
                        doc.lastErrorCode != null ? doc.lastErrorCode : "SRI_REJECTED");
                log.warnf("AuthorizeConsumer: document %s %s: %s",
                        doc.id, targetStatus, doc.lastErrorMessage);

            } else {
                handleRetryTransition(doc,
                        SendConsumer.extractErrorSummary(response.messages()),
                        "AuthorizeConsumer");
            }
            return null;
        });
    }

    private void handleInfraError(DocumentEvent event, Throwable ex) {
        log.warnf(ex, "AuthorizeConsumer: SRI infrastructure error for document %s", event.documentId());

        connectionManager.withTenantTransaction(event.tenantSchemaName(), em -> {
            var doc = em.find(Document.class, event.documentId());
            if (doc == null) {
                return null;
            }
            handleRetryTransition(doc, ex.getMessage(), "AuthorizeConsumer");
            return null;
        });
    }

    private void handleRetryTransition(Document doc, String errorMessage, String consumer) {
        doc.retryCount++;
        doc.lastErrorMessage = errorMessage;
        doc.updatedAt = Instant.now();

        if (RetryDelayCalculator.isExhausted(doc.retryCount, doc.maxRetries)) {
            try {
                doc.transitionTo(DocumentStatus.FAILED);
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

    private String serializeMessages(List<SriMessage> messages) {
        try {
            return objectMapper.writeValueAsString(messages);
        } catch (JsonProcessingException e) {
            return messages.toString();
        }
    }

    record AuthInput(UUID id, String accessKey, DocumentStatus status) {

    }
}
