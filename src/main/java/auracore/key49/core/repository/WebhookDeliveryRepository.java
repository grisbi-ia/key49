package auracore.key49.core.repository;

import java.util.List;
import java.util.UUID;

import auracore.key49.core.model.WebhookDelivery;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Repositorio para registros de entrega de webhooks.
 */
@ApplicationScoped
public class WebhookDeliveryRepository implements PanacheRepositoryBase<WebhookDelivery, UUID> {

    /**
     * Busca entregas pendientes con nextAttemptAt vencido, listas para reintento.
     */
    public List<WebhookDelivery> findPendingRetries() {
        return find("status = 'pending' AND nextAttemptAt IS NOT NULL AND nextAttemptAt <= now() ORDER BY nextAttemptAt ASC")
                .page(0, 50)
                .list();
    }

    /**
     * Busca entregas por documento.
     */
    public List<WebhookDelivery> findByDocumentId(UUID documentId) {
        return find("documentId", documentId).list();
    }
}
