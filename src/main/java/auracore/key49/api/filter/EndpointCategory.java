package auracore.key49.api.filter;

/**
 * Categoría de endpoint para rate limiting granular.
 *
 * <p>
 * Cada categoría tiene un límite de requests independiente en Redis,
 * permitiendo aplicar restricciones más estrictas a operaciones de escritura y
 * más permisivas a lecturas.</p>
 */
public enum EndpointCategory {

    /**
     * Operaciones de escritura: POST para crear documentos, enviar XML,
     * gestionar API keys, subir certificados, etc.
     */
    WRITE("write"),
    /**
     * Operaciones de lectura: GET para listar y consultar documentos, descargar
     * XML/RIDE, ver métricas, etc.
     */
    READ("read");

    private final String keySuffix;

    EndpointCategory(String keySuffix) {
        this.keySuffix = keySuffix;
    }

    public String keySuffix() {
        return keySuffix;
    }

    /**
     * Resuelve la categoría a partir del método HTTP. POST, PUT, PATCH, DELETE
     * → WRITE; GET, HEAD, OPTIONS → READ.
     */
    public static EndpointCategory fromHttpMethod(String method) {
        return switch (method.toUpperCase()) {
            case "POST", "PUT", "PATCH", "DELETE" ->
                WRITE;
            default ->
                READ;
        };
    }
}
