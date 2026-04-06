package auracore.key49.core.repository;

import java.util.List;
import java.util.UUID;

import auracore.key49.core.model.Tenant;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class TenantRepository implements PanacheRepositoryBase<Tenant, UUID> {

    public Uni<Tenant> findByRuc(String ruc) {
        return find("ruc", ruc).firstResult();
    }

    public Uni<Tenant> findBySchemaName(String schemaName) {
        return find("schemaName", schemaName).firstResult();
    }

    public Uni<List<Tenant>> findAllActive() {
        return find("status", "active").list();
    }
}
