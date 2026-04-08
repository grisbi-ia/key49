package auracore.key49.core.repository;

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
}
