package auracore.key49.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response con la configuración SMTP actual de un tenant (sin la contraseña).
 */
public record SmtpConfigResponse(
        String host,
        Integer port,
        String user,
        @JsonProperty("password_configured") boolean passwordConfigured,
        @JsonProperty("from") String fromAddress,
        boolean enabled) {

}
