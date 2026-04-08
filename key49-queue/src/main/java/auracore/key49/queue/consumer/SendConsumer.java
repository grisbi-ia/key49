package auracore.key49.queue.consumer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import auracore.key49.core.model.Document;
import auracore.key49.core.model.InvalidStateTransitionException;
import auracore.key49.core.model.OutboxEvent;
import auracore.key49.core.model.enums.DocumentStatus;
import auracore.key49.core.repository.TenantRepository;
import auracore.key49.core.tenant.TenantConnectionManager;
import auracore.key49.queue.event.DocumentEvent;
import auracore.key49.queue.retry.RetryDelayCalculator;
import auracore.key49.sri.SriException;
import auracore.key49.sri.client.SriReceptionClient;
import auracore.key49.sri.model.SriMessage;
import auracore.key49.sri.model.SriReceptionResponse;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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
    TenantRepository tenantRepository;

    @Inject
    TenantConnectionManager connectionManager;

    @Inject
    SriReceptionClient sriReceptionClient;

    @Inject
    ObjectMapper objectMapper;

    @Incoming("doc-send-in")
    @WithSession
    public Uni<Void> process(JsonObject json) {
        var event = DocumentEvent.fromJson(json);
        log.infof("SendConsumer: processing documentId=%s, tenant=%s",
                event.documentId(), event.tenantSchemaName());

        return tenantRepository.findBySchemaName(event.tenantSchemaName())
                .chain(tenant -> {
                    if (tenant == null) {
                        log.errorf("SendConsumer: tenant not found: %s", event.tenantSchemaName());
                        return Uni.createFrom().voidItem();
                    }
                    var sriEnv = SignConsumer.resolveEnvironment(tenant.environment);

                    return connectionManager.withTenantSession(event.tenantSchemaName(), session
                            -> session.find(Document.class, event.documentId())
                                    .map(doc -> doc != null
                                    ? new SendInput(doc.id, doc.originalXml, doc.status)
                                    : null)
                    ).chain(input -> {
                        if (input == null) {
                            log.warnf("SendConsumer: document not found: %s", event.documentId());
                            return Uni.createFrom().voidItem();
                        }
                        if (!input.status.canTransitionTo(DocumentStatus.SENT)) {
                            log.warnf("SendConsumer: skip document %s in state %s",
                                    input.id, input.status);
                            return Uni.createFrom().voidItem();
                        }
                        if (input.signedXml == null || input.signedXml.isBlank()) {
                            log.errorf("SendConsumer: no signed XML for document %s", input.id);
                            return markFailed(event, "No signed XML available");
                        }

                        return Uni.createFrom()
                                .item(() -> sriReceptionClient.send(input.signedXml, sriEnv))
                                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                                .chain(response -> handleResponse(event, response))
                                .onFailure(SriException.class)
                                .recoverWithUni(ex -> handleInfraError(event, ex));
                    });
                })
                .onFailure().recoverWithUni(ex
                        -> errorHandler.persistError(event.documentId(), event.tenantSchemaName(),
                        "SendConsumer", ex))
                .replaceWithVoid();
    }

    private Uni<Void> handleResponse(DocumentEvent event, SriReceptionResponse response) {
        return connectionManager.withTenantTransaction(event.tenantSchemaName(), session
                -> session.find(Document.class, event.documentId())
                        .chain(doc -> {
                            if (doc == null) {
                                return Uni.createFrom().voidItem();
                            }

                            doc.sriMessages = serializeMessages(response.messages());
                            doc.sriSubmissionDate = Instant.now();
                            doc.updatedAt = Instant.now();

                            if (response.isReceived()) {
                                doc.transitionTo(DocumentStatus.SENT);
                                doc.transitionTo(DocumentStatus.RECEIVED);
                                log.infof("SendConsumer: document %s received by SRI", doc.id);
                                var outbox = OutboxEvent.create(doc.id, "doc.authorize", "{}");
                                return session.persist(outbox).replaceWithVoid();

                            } else if (response.hasBusinessErrors()) {
                                var targetStatus = doc.status.canTransitionTo(DocumentStatus.REJECTED)
                                        ? DocumentStatus.REJECTED : DocumentStatus.FAILED;
                                doc.transitionTo(targetStatus);
                                doc.lastErrorCode = extractFirstErrorCode(response.messages());
                                doc.lastErrorMessage = extractErrorSummary(response.messages());
                                log.warnf("SendConsumer: document %s %s by SRI: %s",
                                        doc.id, targetStatus, doc.lastErrorMessage);
                                return Uni.createFrom().voidItem();

                            } else {
                                return handleRetryTransition(doc,
                                        extractErrorSummary(response.messages()),
                                        "SendConsumer");
                            }
                        })
        );
    }

    private Uni<Void> handleInfraError(DocumentEvent event, Throwable ex) {
        log.warnf(ex, "SendConsumer: SRI infrastructure error for document %s", event.documentId());

        return connectionManager.withTenantTransaction(event.tenantSchemaName(), session
                -> session.find(Document.class, event.documentId())
                        .chain(doc -> {
                            if (doc == null) {
                                return Uni.createFrom().voidItem();
                            }
                            return handleRetryTransition(doc, ex.getMessage(), "SendConsumer");
                        })
        );
    }

    private Uni<Void> handleRetryTransition(Document doc, String errorMessage, String consumer) {
        doc.retryCount++;
        doc.lastErrorMessage = errorMessage;
        doc.updatedAt = Instant.now();

        if (RetryDelayCalculator.isExhausted(doc.retryCount, doc.maxRetries)) {
            var targetStatus = doc.status.canTransitionTo(DocumentStatus.FAILED)
                    ? DocumentStatus.FAILED : DocumentStatus.FAILED;
            try {
                doc.transitionTo(targetStatus);
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
        return Uni.createFrom().voidItem();
    }

    private Uni<Void> markFailed(DocumentEvent event, String reason) {
        return connectionManager.withTenantTransaction(event.tenantSchemaName(), session
                -> session.find(Document.class, event.documentId())
                        .chain(doc -> {
                            if (doc == null) {
                                return Uni.createFrom().voidItem();
                            }
                            try {
                                doc.transitionTo(DocumentStatus.FAILED);
                            } catch (InvalidStateTransitionException e) {
                                log.warnf("SendConsumer: cannot transition to FAILED from %s", doc.status);
                            }
                            doc.lastErrorMessage = reason;
                            doc.updatedAt = Instant.now();
                            return Uni.createFrom().voidItem();
                        })
        );
    }

    private String serializeMessages(List<SriMessage> messages) {
        try {
            return objectMapper.writeValueAsString(messages);
        } catch (JsonProcessingException e) {
            return messages.toString();
        }
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

    record SendInput(UUID id, String signedXml, DocumentStatus status) {

    }
}
