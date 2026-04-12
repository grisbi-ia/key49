package auracore.key49.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request para configurar credenciales del portal (email + contraseña).
 */
public record PortalCredentialsRequest(
        @JsonProperty("email")
        String email,
        @JsonProperty("password")
        String password) {

}
