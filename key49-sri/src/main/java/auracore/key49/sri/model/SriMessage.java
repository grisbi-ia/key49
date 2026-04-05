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
     * <p>Códigos de error de negocio del SRI: 35 (ya registrado), 45 (fecha fuera de rango),
     * 52 (estructura inválida), 65 (fecha futura).
     */
    public boolean isBusinessError() {
        return "ERROR".equalsIgnoreCase(type)
                && identifier != null
                && (identifier.equals("35") || identifier.equals("45")
                || identifier.equals("52") || identifier.equals("65"));
    }
}
