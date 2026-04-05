package auracore.key49.core.tenant;

import java.util.UUID;

import jakarta.enterprise.context.RequestScoped;

/**
 * Contexto del tenant activo en el request actual.
 * Almacena el tenant_id y schema_name resueltos durante la autenticación.
 */
@RequestScoped
public class TenantContext {

    private UUID tenantId;
    private String schemaName;

    public UUID getTenantId() {
        return tenantId;
    }

    public String getSchemaName() {
        return schemaName;
    }

    /**
     * Establece el contexto del tenant para el request actual.
     * Valida el schema antes de aceptarlo.
     */
    public void setTenant(UUID tenantId, String schemaName) {
        TenantSchemaResolver.validate(schemaName);
        this.tenantId = tenantId;
        this.schemaName = schemaName;
    }

    public boolean isSet() {
        return tenantId != null && schemaName != null;
    }
}
