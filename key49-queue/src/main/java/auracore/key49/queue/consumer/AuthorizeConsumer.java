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
import auracore.key49.sri.client.SriAuthorizationClient;
import auracore.key49.sri.model.SriAuthorizationResponse;
import auracore.key49.sri.model.SriMessage;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
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
    TenantRepository tenantRepository;

    @Inject
    TenantConnectionManager connectionManager;

    @Inject
    SriAuthorizationClient sriAuthorizationClient;

    @Inject
    ObjectMapper objectMapper;

    @Incoming("doc-authorize-in")
    @WithSession
    public Uni<Void> process(JsonObject json) {
        var event = DocumentEvent.fromJson(json);
        log.infof("AuthorizeConsumer: processing documentId=%s, tenant=%s",
                event.documentId(), event.tenantSchemaName());

        return tenantRepository.findBySchemaName(event.tenantSchemaName())
                .chain(tenant -> {
                    if (tenant == null) {
                        log.errorf("AuthorizeConsumer: tenant not found: %s", event.tenantSchemaName());
                        return Uni.createFrom().voidItem();
                    }
                    var sriEnv = SignConsumer.resolveEnvironment(tenant.environment);

                    return connectionManager.withTenantSession(event.tenantSchemaName(), session
                            -> session.find(Document.class, event.documentId())
                                    .map(doc -> doc != null
                                    ? new AuthInput(doc.id, doc.accessKey, doc.status)
                                    : null)
                    ).chain(input -> {
                        if (input == null) {
                            log.warnf("AuthorizeConsumer: document not found: %s", event.documentId());
                            return Uni.createFrom().voidItem();
                        }
                        if (!input.status.canTransitionTo(DocumentStatus.AUTHORIZED)) {
                            log.warnf("AuthorizeConsumer: skip document %s in state %s",
                                    input.id, input.status);
                            return Uni.createFrom().voidItem();
                        }
                        if (input.accessKey == null || input.accessKey.isBlank()) {
                            log.errorf("AuthorizeConsumer: no access key for document %s", input.id);
                            return Uni.createFrom().voidItem();
                        }

                        return Uni.createFrom()
                                .item(() -> sriAuthorizationClient.authorize(input.accessKey, sriEnv))
                                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                                .chain(response -> handleResponse(event, response))
                                .onFailure(SriException.class)
                                .recoverWithUni(ex -> handleInfraError(event, ex));
                    });
                })
                .onFailure().recoverWithUni(ex
                        -> errorHandler.persistError(event.documentId(), event.tenantSchemaName(),
                        "AuthorizeConsumer", ex))
                .replaceWithVoid();
    }

    private Uni<Void> handleResponse(DocumentEvent event, SriAuthorizationResponse response) {
        return connectionManager.withTenantTransaction(event.tenantSchemaName(), session
                -> session.find(Document.class, event.documentId())
                        .chain(doc -> {
                            if (doc == null) {
                                return Uni.createFrom().voidItem();
                            }

                            doc.sriMessages = serializeMessages(response.messages());
                            doc.updatedAt = Instant.now();

                            if (response.isAuthorized()) {
                                doc.transitionTo(DocumentStatus.AUTHORIZED);
                                doc.authorizationNumber = response.authorizationNumber();
                                if (response.authorizationDate() != null) {
                                    doc.authorizationDate = Instant.now();
                                }
                                // TODO: T-016 — Store authorized XML in MinIO
                                log.infof("AuthorizeConsumer: document %s authorized, authNum=%s",
                                        doc.id, doc.authorizationNumber);
                                var outbox = OutboxEvent.create(doc.id, "doc.notify", "{}");
                                return session.persist(outbox).replaceWithVoid();

                            } else if (response.hasBusinessErrors()) {
                                var targetStatus = doc.status.canTransitionTo(DocumentStatus.REJECTED)
                                        ? DocumentStatus.REJECTED : DocumentStatus.FAILED;
                                doc.transitionTo(targetStatus);
                                doc.lastErrorCode = SendConsumer.extractFirstErrorCode(response.messages());
                                doc.lastErrorMessage = SendConsumer.extractErrorSummary(response.messages());
                                log.warnf("AuthorizeConsumer: document %s %s: %s",
                                        doc.id, targetStatus, doc.lastErrorMessage);
                                return Uni.createFrom().voidItem();

                            } else {
                                return handleRetryTransition(doc,
                                        SendConsumer.extractErrorSummary(response.messages()),
                                        "AuthorizeConsumer");
                            }
                        })
        );
    }

    private Uni<Void> handleInfraError(DocumentEvent event, Throwable ex) {
        log.warnf(ex, "AuthorizeConsumer: SRI infrastructure error for document %s", event.documentId());

        return connectionManager.withTenantTransaction(event.tenantSchemaName(), session
                -> session.find(Document.class, event.documentId())
                        .chain(doc -> {
                            if (doc == null) {
                                return Uni.createFrom().voidItem();
                            }
                            return handleRetryTransition(doc, ex.getMessage(), "AuthorizeConsumer");
                        })
        );
    }

    private Uni<Void> handleRetryTransition(Document doc, String errorMessage, String consumer) {
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
        return Uni.createFrom().voidItem();
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
