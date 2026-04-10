package auracore.key49.core.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import auracore.key49.core.model.OutboxEvent;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

/**
 * Repositorio para operaciones sobre la tabla outbox del tenant activo.
 */
@ApplicationScoped
public class OutboxRepository implements PanacheRepositoryBase<OutboxEvent, UUID> {

    @Inject
    EntityManager em;

    /**
     * Obtiene eventos no publicados en orden FIFO.
     *
     * @param limit máximo de eventos a retornar
     */
    public List<OutboxEvent> findUnpublished(int limit) {
        return find("published = false ORDER BY createdAt ASC")
                .page(0, limit)
                .list();
    }

    /**
     * Obtiene eventos no publicados con bloqueo FOR UPDATE SKIP LOCKED.
     * Permite concurrencia segura entre múltiples instancias del poller.
     *
     * @param limit máximo de eventos a retornar
     */
    @SuppressWarnings("unchecked")
    public List<OutboxEvent> findUnpublishedForUpdate(int limit) {
        return em.createNativeQuery(
                        "SELECT * FROM outbox WHERE published = false ORDER BY created_at ASC LIMIT :limit FOR UPDATE SKIP LOCKED",
                        OutboxEvent.class)
                .setParameter("limit", limit)
                .getResultList();
    }

    /**
     * Marca un evento como publicado.
     */
    public int markAsPublished(UUID outboxId) {
        return update("published = true, publishedAt = ?1 WHERE id = ?2",
                Instant.now(), outboxId);
    }

    /**
     * Elimina eventos publicados anteriores a la fecha indicada.
     *
     * @param olderThan fecha límite (se eliminan eventos publicados antes de esta fecha)
     * @return cantidad de registros eliminados
     */
    public long deleteOldPublished(Instant olderThan) {
        return delete("published = true AND publishedAt < ?1", olderThan);
    }
}
