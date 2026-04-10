package auracore.key49.core.tenant;

import java.util.UUID;

import jakarta.enterprise.context.RequestScoped;

/**
 * Contexto del tenant activo en el request actual. Almacena el tenant_id y
 * schema_name resueltos durante la autenticación.
 */
@RequestScoped
public class TenantContext {

    private UUID tenantId;
    private String schemaName;
    private int rateLimitRpm = 100;
    private int rateLimitWriteRpm = 30;
    private int rateLimitReadRpm = 200;
    private String apiKeyPrefix;

    public UUID getTenantId() {
        return tenantId;
    }

    public String getSchemaName() {
        return schemaName;
    }

    public int getRateLimitRpm() {
        return rateLimitRpm;
    }

    public int getRateLimitWriteRpm() {
        return rateLimitWriteRpm;
    }

    public int getRateLimitReadRpm() {
        return rateLimitReadRpm;
    }

    public String getApiKeyPrefix() {
        return apiKeyPrefix;
    }

    /**
     * Establece el contexto del tenant para el request actual. Valida el schema
     * antes de aceptarlo.
     */
    public void setTenant(UUID tenantId, String schemaName) {
        TenantSchemaResolver.validate(schemaName);
        this.tenantId = tenantId;
        this.schemaName = schemaName;
    }

    /**
     * Establece el rate limit configurado para el tenant.
     */
    public void setRateLimitRpm(int rateLimitRpm) {
        this.rateLimitRpm = rateLimitRpm;
    }

    public void setRateLimitWriteRpm(int rateLimitWriteRpm) {
        this.rateLimitWriteRpm = rateLimitWriteRpm;
    }

    public void setRateLimitReadRpm(int rateLimitReadRpm) {
        this.rateLimitReadRpm = rateLimitReadRpm;
    }

    /**
     * Establece el prefijo de la API key usada en la autenticación.
     */
    public void setApiKeyPrefix(String apiKeyPrefix) {
        this.apiKeyPrefix = apiKeyPrefix;
    }

    public boolean isSet() {
        return tenantId != null && schemaName != null;
    }
}
