package auracore.key49.core.repository;

import java.util.List;
import java.util.UUID;

import auracore.key49.core.model.ApiKey;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class ApiKeyRepository implements PanacheRepositoryBase<ApiKey, UUID> {

    public Uni<ApiKey> findByKeyHash(String keyHash) {
        return find("keyHash", keyHash).firstResult();
    }

    public Uni<List<ApiKey>> findByTenantId(UUID tenantId) {
        return find("tenantId", tenantId).list();
    }
}
