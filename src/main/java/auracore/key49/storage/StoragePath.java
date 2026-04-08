package auracore.key49.storage;

import java.time.LocalDate;

/**
 * Construye las rutas de objetos en MinIO siguiendo la convención:
 * {@code {tenant_id}/{year}/{month}/{doc_type}/{access_key}/{filename}}
 *
 * <p>Ejemplo: {@code t_abc123/2026/04/01/2506202501099271531200110010020000000011234567813/signed.xml}</p>
 */
public final class StoragePath {

    private StoragePath() {}

    /**
     * Genera la ruta completa del objeto en MinIO.
     *
     * @param tenantId    identificador del tenant (schema_name o UUID corto)
     * @param issueDate   fecha de emisión del documento
     * @param docTypeCode código SRI del tipo de documento (01, 04, 05, etc.)
     * @param accessKey   clave de acceso de 49 dígitos
     * @param artifact    tipo de artefacto (XML sin firmar, firmado, autorizado, RIDE)
     * @return ruta del objeto para MinIO
     */
    public static String build(String tenantId, LocalDate issueDate, String docTypeCode,
                                String accessKey, DocumentArtifact artifact) {
        return "%s/%d/%02d/%s/%s/%s".formatted(
                tenantId,
                issueDate.getYear(),
                issueDate.getMonthValue(),
                docTypeCode,
                accessKey,
                artifact.filename()
        );
    }

    /**
     * Genera el prefijo de directorio para listar artefactos de un documento específico.
     *
     * @param tenantId    identificador del tenant
     * @param issueDate   fecha de emisión
     * @param docTypeCode código SRI del tipo de documento
     * @param accessKey   clave de acceso
     * @return prefijo del directorio (sin filename)
     */
    public static String prefix(String tenantId, LocalDate issueDate, String docTypeCode,
                                 String accessKey) {
        return "%s/%d/%02d/%s/%s/".formatted(
                tenantId,
                issueDate.getYear(),
                issueDate.getMonthValue(),
                docTypeCode,
                accessKey
        );
    }
}
