package auracore.key49.core.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import auracore.key49.core.model.Tenant;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class TenantRepository implements PanacheRepositoryBase<Tenant, UUID> {

    public Tenant findByRuc(String ruc) {
        return find("ruc", ruc).firstResult();
    }

    public Tenant findBySchemaName(String schemaName) {
        return find("schemaName", schemaName).firstResult();
    }

    public Tenant findByEmail(String email) {
        return find("email", email).firstResult();
    }

    public List<Tenant> findAllActive() {
        return find("status", "active").list();
    }

    public List<Tenant> findExpiredActive(Instant now) {
        return find("status = 'active' AND planExpiresAt IS NOT NULL AND planExpiresAt <= ?1", now).list();
    }
}
