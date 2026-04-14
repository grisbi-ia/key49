package auracore.key49.api.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Request para crear una nueva API key.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreateApiKeyRequest(
        String name,
        String permissions,
        Instant expiresAt) {

}
