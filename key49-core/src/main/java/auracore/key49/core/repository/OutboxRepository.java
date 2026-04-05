package auracore.key49.core.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import auracore.key49.core.model.OutboxEvent;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Repositorio para operaciones sobre la tabla outbox del tenant activo.
 */
@ApplicationScoped
public class OutboxRepository implements PanacheRepositoryBase<OutboxEvent, UUID> {

    /**
     * Obtiene eventos no publicados en orden FIFO.
     *
     * @param limit máximo de eventos a retornar
     */
    public Uni<List<OutboxEvent>> findUnpublished(int limit) {
        return find("published = false ORDER BY createdAt ASC")
                .page(0, limit)
                .list();
    }

    /**
     * Marca un evento como publicado.
     */
    public Uni<Integer> markAsPublished(UUID outboxId) {
        return update("published = true, publishedAt = ?1 WHERE id = ?2",
                Instant.now(), outboxId);
    }

    /**
     * Elimina eventos publicados anteriores a la fecha indicada.
     *
     * @param olderThan fecha límite (se eliminan eventos publicados antes de esta fecha)
     * @return cantidad de registros eliminados
     */
    public Uni<Long> deleteOldPublished(Instant olderThan) {
        return delete("published = true AND publishedAt < ?1", olderThan);
    }
}
