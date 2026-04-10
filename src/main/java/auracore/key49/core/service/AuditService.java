package auracore.key49.core.service;

import java.time.Instant;
import java.util.UUID;

import auracore.key49.core.model.AuditLog;
import auracore.key49.core.repository.AuditLogRepository;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Servicio centralizado de audit log para operaciones sensitivas. Registra
 * entradas en public.audit_log independientemente del search_path activo.
 */

@ApplicationScoped
public class AuditService {

    @Inject
    AuditLogRepository auditLogRepository;

    @Transactional
    public void record(UUID tenantId, String actor, String action,
            String resource, UUID resourceId, String ipAddress, String details) {
        var entry = new AuditLog();
        entry.tenantId = tenantId;
        entry.actor = actor;
        entry.action = action;
        entry.resource = resource;
        entry.resourceId = resourceId;
        entry.ipAddress = ipAddress;
        entry.details = details;
        entry.createdAt = Instant.now();
        auditLogRepository.persist(entry);
        Log.debugf("Audit | tenant=%s actor=%s action=%s resource=%s/%s",
                tenantId, actor, action, resource, resourceId);
    }

    /**
     * Extrae la IP del cliente desde el header X-Forwarded-For o la dirección
     * remota.
     */
    public static String resolveIp(io.vertx.core.http.HttpServerRequest request) {
        if (request == null) {
            return "unknown";
        }
        var forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.remoteAddress() != null
                ? request.remoteAddress().host() : "unknown";
    }
}
