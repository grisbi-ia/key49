package auracore.key49.api.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Respuesta con el estado del certificado de un tenant.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CertificateStatusResponse(
        String subject,
        String serial,
        Instant expiresAt,
        String issuer,
        boolean valid,
        long daysUntilExpiration) {

}
