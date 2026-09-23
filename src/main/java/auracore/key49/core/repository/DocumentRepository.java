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

    /**
     * Documentos atascados en un estado transitorio (CREATED/SIGNED/SENT) que no
     * han avanzado desde hace más de {@code staleMinutes}. Su recuperación
     * reencola la etapa correspondiente.
     */
    public List<Document> findStaleTransient(int staleMinutes) {
        var cutoff = Instant.now().minus(Duration.ofMinutes(staleMinutes));
        return find("status IN ?1 AND updatedAt <= ?2",
                List.of(DocumentStatus.CREATED, DocumentStatus.SIGNED, DocumentStatus.SENT),
                cutoff).list();
    }

    /**
     * Documentos {@code FAILED} por error de infraestructura (sin código de negocio)
     * que llevan más de {@code cooldownMinutes} sin reintentarse y dentro de la
     * ventana de {@code maxAgeHours}. Se recuperan automáticamente.
     */
    public List<Document> findRecoverableFailed(int cooldownMinutes, int maxAgeHours) {
        var now = Instant.now();
        var cooldownCutoff = now.minus(Duration.ofMinutes(cooldownMinutes));
        var maxAgeCutoff = now.minus(Duration.ofHours(maxAgeHours));
        return find("status = ?1 AND updatedAt <= ?2 AND updatedAt >= ?3"
                + " AND (lastErrorCode IS NULL OR lastErrorCode = '')",
                DocumentStatus.FAILED, cooldownCutoff, maxAgeCutoff).list();
    }

    /**
     * Documentos {@code REJECTED} con código {@code NO_REG} (Key49 no encontró la
     * clave en el SRI) que deben re-emitirse automáticamente. Se acotan por
     * cooldown, ventana máxima de antigüedad y número de intentos para no
     * reencolar indefinidamente.
     */
    public List<Document> findRecoverableNoReg(int cooldownMinutes, int maxAgeHours, int maxAttempts) {
        var now = Instant.now();
        var cooldownCutoff = now.minus(Duration.ofMinutes(cooldownMinutes));
        var maxAgeCutoff = now.minus(Duration.ofHours(maxAgeHours));
        return find("status = ?1 AND lastErrorCode = 'NO_REG' AND updatedAt <= ?2"
                + " AND updatedAt >= ?3 AND retryCount < ?4",
                DocumentStatus.REJECTED, cooldownCutoff, maxAgeCutoff, maxAttempts).list();
    }
}
