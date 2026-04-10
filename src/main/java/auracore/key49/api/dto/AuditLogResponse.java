package auracore.key49.api.dto;

import java.time.Instant;
import java.util.UUID;

import auracore.key49.core.model.AuditLog;

public record AuditLogResponse(
        UUID id,
        UUID tenantId,
        String actor,
        String action,
        String resource,
        UUID resourceId,
        String ipAddress,
        Object details,
        Instant createdAt) {

    public static AuditLogResponse fromEntity(AuditLog entry) {
        return new AuditLogResponse(
                entry.id,
                entry.tenantId,
                entry.actor,
                entry.action,
                entry.resource,
                entry.resourceId,
                entry.ipAddress,
                entry.details,
                entry.createdAt);
    }
}
