package auracore.key49.core.tenant;

import java.util.regex.Pattern;

/**
 * Valida y resuelve nombres de esquema de tenant para PostgreSQL. Previene SQL
 * injection validando contra un patrón seguro de caracteres.
 */
public final class TenantSchemaResolver {

    private static final Pattern SAFE_SCHEMA_PATTERN = Pattern.compile("^[a-z0-9_]+$");
    private static final int MAX_SCHEMA_LENGTH = 63;

    private TenantSchemaResolver() {
    }

    /**
     * Valida que el nombre de esquema contenga solo caracteres seguros.
     *
     * @param schemaName nombre del esquema a validar
     * @throws IllegalArgumentException si el nombre es inválido
     */
    public static void validate(String schemaName) {
        if (schemaName == null || schemaName.isBlank()) {
            throw new IllegalArgumentException("Schema name must not be null or blank");
        }
        if (schemaName.length() > MAX_SCHEMA_LENGTH) {
            throw new IllegalArgumentException("Schema name exceeds maximum length of " + MAX_SCHEMA_LENGTH);
        }
        if (!SAFE_SCHEMA_PATTERN.matcher(schemaName).matches()) {
            throw new IllegalArgumentException("Schema name contains invalid characters: " + schemaName);
        }
    }

    /**
     * Construye el comando SQL SET LOCAL search_path para un esquema de tenant.
     * Usa SET LOCAL para que el search_path se resetee automáticamente al
     * finalizar la transacción, lo cual es requerido para compatibilidad con
     * PgBouncer en modo transaction (la conexión se reutiliza entre
     * transacciones).
     *
     * @param schemaName nombre del esquema del tenant (ej: "tenant_abc123")
     * @return SQL command "SET LOCAL search_path TO 'tenant_abc123', public"
     * @throws IllegalArgumentException si el nombre es inválido
     */
    public static String buildSearchPathSql(String schemaName) {
        validate(schemaName);
        return "SET LOCAL search_path TO '" + schemaName + "', public";
    }
}
