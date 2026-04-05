package auracore.key49.core.repository;

import auracore.key49.core.model.Document;
import auracore.key49.core.model.enums.DocumentStatus;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class DocumentRepository implements PanacheRepositoryBase<Document, UUID> {

    public Uni<Document> findByAccessKey(String accessKey) {
        return find("accessKey", accessKey).firstResult();
    }

    public Uni<Document> findByIdempotencyKey(String idempotencyKey) {
        return find("idempotencyKey", idempotencyKey).firstResult();
    }

    public Uni<List<Document>> findByStatus(DocumentStatus status) {
        return find("status", status).list();
    }

    public Uni<List<Document>> findRetryReady() {
        return find("status = ?1 AND nextRetryAt <= now()", DocumentStatus.RETRY).list();
    }
}
