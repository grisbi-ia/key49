package auracore.key49.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import auracore.key49.core.model.ApiKey;

/**
 * Respuesta con datos de una API key. El campo rawKey solo se incluye al
 * momento de la creación.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiKeyResponse(
        UUID id,
        String keyPrefix,
        String name,
        String permissions,
        Instant lastUsedAt,
        Instant expiresAt,
        String status,
        Instant createdAt,
        String rawKey) {

    public static ApiKeyResponse fromEntity(ApiKey key) {
        return new ApiKeyResponse(
                key.id, key.keyPrefix, key.name, key.permissions,
                key.lastUsedAt, key.expiresAt, key.status, key.createdAt,
                null);
    }

    public static ApiKeyResponse fromCreated(ApiKey key, String rawKey) {
        return new ApiKeyResponse(
                key.id, key.keyPrefix, key.name, key.permissions,
                key.lastUsedAt, key.expiresAt, key.status, key.createdAt,
                rawKey);
    }
}
