package auracore.key49.sri.model;

import java.util.List;

/**
 * Respuesta parseada del servicio SOAP de Autorización del SRI.
 *
 * @param status              estado de la autorización (AUTORIZADO o NO_AUTORIZADO)
 * @param authorizationNumber número de autorización devuelto por el SRI (puede ser null si NO_AUTORIZADO)
 * @param authorizationDate   fecha/hora de autorización como String (formato SRI, puede ser null)
 * @param accessKey           clave de acceso del comprobante
 * @param authorizedXml       XML del comprobante autorizado (puede ser null si NO_AUTORIZADO)
 * @param messages            lista de mensajes del SRI (errores, advertencias, informativos)
 */
public record SriAuthorizationResponse(
        AuthorizationStatus status,
        String authorizationNumber,
        String authorizationDate,
        String accessKey,
        String authorizedXml,
        List<SriMessage> messages
) {

    public SriAuthorizationResponse {
        messages = messages != null ? List.copyOf(messages) : List.of();
    }

    /**
     * Indica si el comprobante fue autorizado.
     */
    public boolean isAuthorized() {
        return status == AuthorizationStatus.AUTORIZADO;
    }

    /**
     * Indica si la respuesta contiene errores de negocio (no reintentables).
     */
    public boolean hasBusinessErrors() {
        return messages.stream().anyMatch(SriMessage::isBusinessError);
    }
}
