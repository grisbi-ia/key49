package auracore.key49.core.service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Set;

import org.jboss.logging.Logger;

import auracore.key49.api.dto.ReprocessRequest;
import auracore.key49.api.dto.ReprocessResult;
import auracore.key49.api.exception.BusinessException;
import auracore.key49.core.model.Document;
import auracore.key49.core.model.OutboxEvent;
import auracore.key49.core.model.enums.DocumentStatus;
import auracore.key49.core.tenant.TenantConnectionManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

/**
 * Reproceso en lote de documentos atascados o fallidos.
 *
 * <p>Aislamiento multi-tenant: el {@code schemaName} lo provee el llamador
 * (endpoint autenticado por API key o endpoint admin), y toda la operación se
 * ejecuta con {@code SET search_path} al esquema del tenant. Nunca se consultan
 * documentos de otro tenant.</p>
 *
 * <p>Reglas de reproceso:</p>
 * <ul>
 *   <li>{@code RECEIVED} o documento ya enviado al SRI ({@code sriSubmissionDate != null}):
 *       se marca {@code RECEIVED} y se encola {@code doc.authorize} para
 *       <b>reconciliar</b> su autorización sin reenviarlo.</li>
 *   <li>{@code RETRY} o {@code SIGNED} sin envío previo: se encola {@code doc.send}.</li>
 *   <li>{@code CREATED} o {@code FAILED} sin envío previo: se marca {@code CREATED}
 *       y se encola {@code doc.sign} (re-firma y reenvía).</li>
 *   <li>{@code REJECTED} con código {@code NO_REG} (no registrado en el SRI, código
 *       propio de Key49): se reconcilia. Otros rechazos (de negocio del SRI) NO se
 *       reprocesan (requieren un comprobante nuevo).</li>
 * </ul>
 */
@ApplicationScoped
public class DocumentReprocessService {

    static final int DEFAULT_LIMIT = 500;
    static final int MAX_LIMIT = 2000;
    private static final Set<DocumentStatus> DEFAULT_STATUSES =
            EnumSet.of(DocumentStatus.FAILED, DocumentStatus.RECEIVED, DocumentStatus.RETRY);

    @Inject
    Logger log;

    @Inject
    TenantConnectionManager connectionManager;

    /**
     * Reencola hasta {@code limit} documentos que coincidan con los filtros.
     * La operación es idempotente aguas abajo: los consumers validan el estado
     * del documento, por lo que re-ejecutarla no genera duplicados ante el SRI.
     */
    public ReprocessResult reprocess(String schemaName, ReprocessRequest request) {
        var statuses = resolveStatuses(request);
        int limit = resolveLimit(request.limit());

        var result = connectionManager.withTenantTransaction(schemaName, em -> {
            var hql = new StringBuilder("FROM Document d WHERE d.status IN :statuses");
            var params = new LinkedHashMap<String, Object>();
            params.put("statuses", statuses);

            if (request.documentTypes() != null && !request.documentTypes().isEmpty()) {
                hql.append(" AND d.documentType IN :types");
                params.put("types", request.documentTypes());
            }
            if (request.dateFrom() != null) {
                hql.append(" AND d.issueDate >= :dateFrom");
                params.put("dateFrom", request.dateFrom());
            }
            if (request.dateTo() != null) {
                hql.append(" AND d.issueDate <= :dateTo");
                params.put("dateTo", request.dateTo());
            }
            hql.append(" ORDER BY d.updatedAt ASC");

            var query = em.createQuery(hql.toString(), Document.class)
                    .setMaxResults(limit);
            params.forEach(query::setParameter);
            var docs = query.getResultList();

            var byStatus = new LinkedHashMap<String, Integer>();
            int queued = 0;
            int skipped = 0;
            for (var doc : docs) {
                var originalStatus = doc.status;
                var eventType = enqueue(doc, em);
                if (eventType == null) {
                    skipped++;
                    continue;
                }
                byStatus.merge(originalStatus.name(), 1, Integer::sum);
                queued++;
            }
            return new ReprocessResult(docs.size(), queued, skipped, byStatus);
        });

        log.infof("DocumentReprocess: schema=%s matched=%d queued=%d skipped=%d byStatus=%s",
                schemaName, result.matched(), result.queued(), result.skipped(), result.byStatus());
        return result;
    }

    private String enqueue(Document doc, EntityManager em) {
        var original = doc.status;

        // REJECTED: solo se reconcilian los NO_REG (clave no registrada en el SRI,
        // código propio de Key49). Los rechazos de negocio del SRI no se reprocesan.
        if (original == DocumentStatus.REJECTED) {
            if (!"NO_REG".equals(doc.lastErrorCode)) {
                return null;
            }
            doc.retryCount = 0;
            doc.nextRetryAt = null;
            doc.lastErrorMessage = null;
            doc.updatedAt = Instant.now();
            if (doc.sriSubmissionDate == null) {
                // La recepción nunca fue confirmada por el SRI: reconciliar la
                // autorización no sirve (seguiría devolviendo numeroComprobantes=0).
                // Se re-emite: re-firma y reenvía a recepción.
                doc.lastErrorCode = null;
                doc.transitionTo(DocumentStatus.CREATED);
                em.persist(OutboxEvent.create(doc.id, "doc.sign", "{}"));
                return "doc.sign";
            }
            doc.transitionTo(DocumentStatus.RECEIVED);
            em.persist(OutboxEvent.create(doc.id, "doc.authorize", "{}"));
            return "doc.authorize";
        }

        if (!isReprocessable(original)) {
            return null;
        }

        doc.retryCount = 0;
        doc.nextRetryAt = null;
        doc.lastErrorCode = null;
        doc.lastErrorMessage = null;
        doc.updatedAt = Instant.now();

        String eventType;
        if (original == DocumentStatus.RECEIVED
                || original == DocumentStatus.SENT
                || doc.sriSubmissionDate != null) {
            // Ya fue enviado al SRI: reconciliar autorización sin reenviar.
            if (original != DocumentStatus.RECEIVED) {
                doc.transitionTo(DocumentStatus.RECEIVED);
            }
            eventType = "doc.authorize";
        } else if (original == DocumentStatus.RETRY || original == DocumentStatus.SIGNED) {
            eventType = "doc.send";
        } else {
            // CREATED o FAILED sin envío previo: re-firmar y reenviar.
            if (original == DocumentStatus.FAILED) {
                doc.transitionTo(DocumentStatus.CREATED);
            }
            eventType = "doc.sign";
        }

        em.persist(OutboxEvent.create(doc.id, eventType, "{}"));
        return eventType;
    }

    private boolean isReprocessable(DocumentStatus status) {
        return status == DocumentStatus.FAILED
                || status == DocumentStatus.RECEIVED
                || status == DocumentStatus.RETRY
                || status == DocumentStatus.CREATED
                || status == DocumentStatus.SIGNED
                || status == DocumentStatus.SENT;
    }

    private Set<DocumentStatus> resolveStatuses(ReprocessRequest request) {
        if (request.statuses() == null || request.statuses().isEmpty()) {
            return DEFAULT_STATUSES;
        }
        var resolved = EnumSet.noneOf(DocumentStatus.class);
        for (var raw : request.statuses()) {
            DocumentStatus status;
            try {
                status = DocumentStatus.valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new BusinessException("VALIDATION_ERROR", "Invalid status: " + raw, 400);
            }
            if (status == DocumentStatus.REJECTED) {
                // Permitido: enqueue reconcilia solo los NO_REG; el resto se omite.
                resolved.add(status);
                continue;
            }
            resolved.add(status);
        }
        return resolved;
    }

    private int resolveLimit(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requested, MAX_LIMIT);
    }
}
