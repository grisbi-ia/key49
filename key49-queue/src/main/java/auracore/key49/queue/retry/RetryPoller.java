package auracore.key49.queue.retry;

import java.time.Instant;

import org.jboss.logging.Logger;

import auracore.key49.core.model.Document;
import auracore.key49.core.model.OutboxEvent;
import auracore.key49.core.model.enums.DocumentStatus;
import auracore.key49.core.repository.DocumentRepository;
import auracore.key49.core.repository.TenantRepository;
import auracore.key49.core.tenant.TenantConnectionManager;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Poller que busca documentos en estado RETRY cuyo {@code nextRetryAt} ha vencido
 * y los re-encola al pipeline creando un evento de outbox.
 *
 * <p>Ejecuta cada 5s (configurable). Determina el tipo de reintento según el estado
 * del documento: si ya fue enviado al SRI → doc.authorize, de lo contrario → doc.send.
 */
@ApplicationScoped
public class RetryPoller {

    @Inject
    Logger log;

    @Inject
    TenantRepository tenantRepository;

    @Inject
    TenantConnectionManager connectionManager;

    @Inject
    DocumentRepository documentRepository;

    @Scheduled(every = "${key49.retry.poll-interval:5s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    Uni<Void> pollRetries() {
        return tenantRepository.findAllActive()
                .onItem().transformToMulti(tenants -> Multi.createFrom().iterable(tenants))
                .onItem().transformToUniAndConcatenate(tenant ->
                        connectionManager.withTenantTransaction(tenant.schemaName, session ->
                                documentRepository.findRetryReady()
                                        .chain(docs -> {
                                            if (docs.isEmpty()) {
                                                return Uni.createFrom().voidItem();
                                            }
                                            log.infof("RetryPoller: %d retry-ready documents for tenant=%s",
                                                    docs.size(), tenant.schemaName);
                                            return Multi.createFrom().iterable(docs)
                                                    .onItem().transformToUniAndConcatenate(doc ->
                                                            requeueDocument(doc, session))
                                                    .toUni().replaceWithVoid();
                                        })
                        ).onFailure().invoke(ex ->
                                log.errorf(ex, "RetryPoller: error polling tenant=%s", tenant.schemaName))
                                .onFailure().recoverWithNull().replaceWithVoid()
                )
                .toUni().replaceWithVoid()
                .onFailure().invoke(ex -> log.errorf(ex, "RetryPoller: error during poll cycle"));
    }

    private Uni<Void> requeueDocument(Document doc, org.hibernate.reactive.mutiny.Mutiny.Session session) {
        if (RetryDelayCalculator.isExhausted(doc.retryCount, doc.maxRetries)) {
            log.warnf("RetryPoller: retries exhausted for document %s (retryCount=%d, maxRetries=%d)",
                    doc.id, doc.retryCount, doc.maxRetries);
            doc.transitionTo(DocumentStatus.FAILED);
            doc.lastErrorMessage = "Max retries exhausted (%d/%d)".formatted(doc.retryCount, doc.maxRetries);
            doc.updatedAt = Instant.now();
            return Uni.createFrom().voidItem();
        }

        var eventType = resolveRetryEventType(doc);
        var outbox = OutboxEvent.create(doc.id, eventType, "{}");
        log.infof("RetryPoller: requeuing document %s as %s (retry %d/%d)",
                doc.id, eventType, doc.retryCount, doc.maxRetries);
        return session.persist(outbox).replaceWithVoid();
    }

    /**
     * Determina a qué cola reenviar el documento según su contexto.
     * Si ya fue enviado al SRI (sriSubmissionDate != null) → doc.authorize.
     * Si no fue enviado → doc.send.
     */
    static String resolveRetryEventType(Document doc) {
        if (doc.sriSubmissionDate != null) {
            return "doc.authorize";
        }
        return "doc.send";
    }
}
