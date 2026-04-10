package auracore.key49.core.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import auracore.key49.core.model.AuditLog;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;


@ApplicationScoped
public class AuditLogRepository implements PanacheRepositoryBase<AuditLog, UUID> {

    public List<AuditLog> findFiltered(UUID tenantId, String action, Instant from, Instant to,
            int page, int perPage) {
        var query = new StringBuilder("1=1");
        var params = new java.util.HashMap<String, Object>();

        if (tenantId != null) {
            query.append(" AND tenantId = :tenantId");
            params.put("tenantId", tenantId);
        }
        if (action != null && !action.isBlank()) {
            query.append(" AND action = :action");
            params.put("action", action);
        }
        if (from != null) {
            query.append(" AND createdAt >= :from");
            params.put("from", from);
        }
        if (to != null) {
            query.append(" AND createdAt <= :to");
            params.put("to", to);
        }

        return find(query.toString(), Sort.descending("createdAt"), params)
                .page(page - 1, perPage)
                .list();
    }

    public long countFiltered(UUID tenantId, String action, Instant from, Instant to) {
        var query = new StringBuilder("1=1");
        var params = new java.util.HashMap<String, Object>();

        if (tenantId != null) {
            query.append(" AND tenantId = :tenantId");
            params.put("tenantId", tenantId);
        }
        if (action != null && !action.isBlank()) {
            query.append(" AND action = :action");
            params.put("action", action);
        }
        if (from != null) {
            query.append(" AND createdAt >= :from");
            params.put("from", from);
        }
        if (to != null) {
            query.append(" AND createdAt <= :to");
            params.put("to", to);
        }

        return count(query.toString(), params);
    }
}
