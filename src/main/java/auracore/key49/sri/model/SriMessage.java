package auracore.key49.sri.model;

/**
 * Mensaje individual devuelto por el SRI en la respuesta SOAP.
 *
 * @param identifier     código del mensaje (ej: "35", "45", "52", "65")
 * @param message        descripción del mensaje
 * @param additionalInfo información adicional (puede ser null)
 * @param type           tipo de mensaje: ERROR, ADVERTENCIA, INFORMATIVO
 */
public record SriMessage(
        String identifier,
        String message,
        String additionalInfo,
        String type
) {

    /**
     * Indica si este mensaje es un error de negocio que NO se debe reintentar.
     *
     * <p>Códigos de error de negocio del SRI (no reintentables):
     * <ul>
     *   <li>35 — comprobante ya registrado</li>
     *   <li>45 — fecha fuera de rango</li>
     *   <li>52 — estructura inválida</li>
     *   <li>65 — fecha futura</li>
     *   <li>96 — no es agente de retención (solo comprobantes tipo 07)</li>
     * </ul></p>
     */
    public boolean isBusinessError() {
        return "ERROR".equalsIgnoreCase(type)
                && identifier != null
                && (identifier.equals("35") || identifier.equals("45")
                || identifier.equals("52") || identifier.equals("65")
                || identifier.equals("96"));
    }
}
