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
     *   <li>35 — estructura XML inválida</li>
     *   <li>45 — secuencial registrado / fecha fuera de rango</li>
     *   <li>52 — estructura inválida</li>
     *   <li>65 — fecha futura</li>
     *   <li>96 — no es agente de retención (solo comprobantes tipo 07)</li>
     * </ul></p>
     *
     * <p>Excepción: el {@code 35} con detalle de desajuste de ambiente
     * ({@link #isEnvironmentMismatch()}) NO es de negocio: el endpoint de
     * producción del SRI lo devuelve de forma intermitente para comprobantes
     * válidos, por lo que se reintenta.</p>
     */
    public boolean isBusinessError() {
        if (!"ERROR".equalsIgnoreCase(type) || identifier == null) {
            return false;
        }
        if (identifier.equals("35") && isEnvironmentMismatch()) {
            return false;
        }
        return identifier.equals("35") || identifier.equals("45")
                || identifier.equals("52") || identifier.equals("65")
                || identifier.equals("96");
    }

    /**
     * Indica si el mensaje es el error {@code 35} cuyo detalle es un desajuste de
     * ambiente ("El ambiente de la solicitud ... no coincide con el de ejecución
     * ...").
     *
     * <p>El servicio de Recepción <b>de producción</b> del SRI devuelve este
     * error de forma intermitente para comprobantes válidos (el mismo XML da
     * {@code RECIBIDA}/{@code 43}/{@code 35} en segundos), por lo que es un fallo
     * transitorio de infraestructura del SRI y debe reintentarse.</p>
     */
    public boolean isEnvironmentMismatch() {
        return "ERROR".equalsIgnoreCase(type)
                && "35".equals(identifier)
                && additionalInfo != null
                && additionalInfo.toLowerCase(java.util.Locale.ROOT).contains("no coincide");
    }

    /**
     * Indica si el mensaje corresponde al código {@code 43} ("CLAVE ACCESO
     * REGISTRADA"), es decir, el comprobante con esa clave ya fue recibido por
     * el SRI. No es un error para Key49: el documento debe reconciliarse
     * consultando su autorización, sin reenviarlo.
     */
    public boolean isAlreadyRegistered() {
        return "ERROR".equalsIgnoreCase(type) && "43".equals(identifier);
    }

    /**
     * Indica si el mensaje corresponde al código {@code 70} ("CLAVE DE ACCESO
     * EN PROCESAMIENTO"): el SRI aún está procesando el comprobante. Es un
     * estado transitorio que debe reintentarse más tarde.
     */
    public boolean isInProcessing() {
        return "ERROR".equalsIgnoreCase(type) && "70".equals(identifier);
    }
}
