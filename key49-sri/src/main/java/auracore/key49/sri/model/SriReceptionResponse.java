package auracore.key49.sri.model;

import java.util.List;

/**
 * Respuesta parseada del servicio SOAP de Recepción del SRI.
 *
 * @param status    estado de la recepción (RECIBIDA o DEVUELTA)
 * @param accessKey clave de acceso del comprobante (puede ser null si el SRI no la devuelve)
 * @param messages  lista de mensajes del SRI (errores, advertencias, informativos)
 */
public record SriReceptionResponse(
        ReceptionStatus status,
        String accessKey,
        List<SriMessage> messages
) {

    public SriReceptionResponse {
        messages = messages != null ? List.copyOf(messages) : List.of();
    }

    /**
     * Indica si la respuesta contiene errores de negocio (no reintentables).
     */
    public boolean hasBusinessErrors() {
        return messages.stream().anyMatch(SriMessage::isBusinessError);
    }

    /**
     * Indica si el comprobante fue recibido exitosamente.
     */
    public boolean isReceived() {
        return status == ReceptionStatus.RECIBIDA;
    }
}
