package auracore.key49.core.repository;

import java.util.List;
import java.util.UUID;

import auracore.key49.core.model.PlanRenewal;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class PlanRenewalRepository implements PanacheRepositoryBase<PlanRenewal, UUID> {

    public List<PlanRenewal> findByTenantId(UUID tenantId) {
        return find("tenantId", tenantId).list();
    }

    public List<PlanRenewal> findPendingByTenantId(UUID tenantId) {
        return find("tenantId = ?1 AND status = 'pending'", tenantId).list();
    }

    public List<PlanRenewal> findByStatus(String status, int page, int perPage) {
        return find("status = ?1 ORDER BY createdAt DESC", status)
                .page(page - 1, perPage).list();
    }

    public long countByStatus(String status) {
        return count("status", status);
    }

    public List<PlanRenewal> findAllPaged(int page, int perPage) {
        return find("ORDER BY createdAt DESC")
                .page(page - 1, perPage).list();
    }
}
