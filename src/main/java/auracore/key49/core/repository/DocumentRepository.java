package auracore.key49.core.repository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import auracore.key49.core.model.Document;
import auracore.key49.core.model.enums.DocumentStatus;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class DocumentRepository implements PanacheRepositoryBase<Document, UUID> {

    public Document findByAccessKey(String accessKey) {
        return find("accessKey", accessKey).firstResult();
    }

    public Document findByIdempotencyKey(String idempotencyKey) {
        return find("idempotencyKey", idempotencyKey).firstResult();
    }

    public List<Document> findByStatus(DocumentStatus status) {
        return find("status", status).list();
    }

    public List<Document> findRetryReady() {
        return find("status = ?1 AND nextRetryAt <= ?2", DocumentStatus.RETRY, Instant.now()).list();
    }

    /**
     * Documentos recibidos por el SRI cuya reconciliación de autorización está
     * vencida. Solo considera documentos recientes ({@code maxAgeDays}) para
     * acotar los reintentos de reconciliación.
     */
    public List<Document> findReconcileReady(int maxAgeDays) {
        var now = Instant.now();
        var cutoff = now.minus(Duration.ofDays(maxAgeDays));
        return find("status = ?1 AND nextRetryAt <= ?2 AND updatedAt >= ?3",
                DocumentStatus.RECEIVED, now, cutoff).list();
    }
}
