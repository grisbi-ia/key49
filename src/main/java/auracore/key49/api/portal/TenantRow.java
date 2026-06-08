package auracore.key49.api.portal;

import java.util.UUID;

/**
 * View model para la tabla de tenants pendientes de aprobación en el portal admin.
 */
public record TenantRow(
        UUID id,
        String createdAt,
        String legalName,
        String ruc,
        String email,
        String environment,
        String status) {
}
