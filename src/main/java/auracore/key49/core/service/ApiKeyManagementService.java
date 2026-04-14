package auracore.key49.core.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import auracore.key49.core.model.ApiKey;
import auracore.key49.core.repository.ApiKeyRepository;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Servicio de gestión de API keys para tenants. Generación, listado, consulta y
 * revocación.
 */
@ApplicationScoped
public class ApiKeyManagementService {

    @Inject
    ApiKeyRepository apiKeyRepository;

    @Inject
    ApiKeyCacheService apiKeyCacheService;

    @Transactional
    public CreatedApiKey create(UUID tenantId, CreateApiKeyData data) {
        validateCreateData(data);

        var generated = ApiKeyService.generate();

        var apiKey = new ApiKey();
        apiKey.tenantId = tenantId;
        apiKey.keyPrefix = generated.keyPrefix();
        apiKey.keyHash = generated.hash();
        apiKey.name = data.name();
        apiKey.permissions = data.permissions() != null ? data.permissions() : "*";
        apiKey.expiresAt = data.expiresAt();
        apiKey.status = "active";
        apiKey.createdAt = Instant.now();

        apiKeyRepository.persist(apiKey);
        Log.infof("API key created | tenantId=%s name=%s prefix=%s",
                tenantId, data.name(), generated.keyPrefix());
        return new CreatedApiKey(apiKey, generated.rawKey());
    }

    public List<ApiKey> listByTenant(UUID tenantId) {
        return apiKeyRepository.findByTenantId(tenantId);
    }

    public ApiKey findById(UUID tenantId, UUID apiKeyId) {
        ApiKey key = apiKeyRepository.findById(apiKeyId);
        if (key == null) {
            throw new ApiKeyException("API_KEY_NOT_FOUND",
                    "API key not found: " + apiKeyId, 404);
        }
        if (!key.tenantId.equals(tenantId)) {
            throw new ApiKeyException("API_KEY_NOT_FOUND",
                    "API key not found: " + apiKeyId, 404);
        }
        return key;
    }

    @Transactional
    public ApiKey revoke(UUID tenantId, UUID apiKeyId) {
        ApiKey key = findById(tenantId, apiKeyId);
        if ("revoked".equals(key.status)) {
            throw new ApiKeyException("ALREADY_REVOKED",
                    "API key is already revoked", 409);
        }
        key.status = "revoked";
        apiKeyCacheService.invalidate(key.keyHash);
        Log.infof("API key revoked | tenantId=%s apiKeyId=%s name=%s",
                tenantId, apiKeyId, key.name);
        return key;
    }

    private void validateCreateData(CreateApiKeyData data) {
        if (data.name() == null || data.name().isBlank()) {
            throw new ApiKeyException("VALIDATION_ERROR", "name is required", 400);
        }
        if (data.name().length() > 100) {
            throw new ApiKeyException("VALIDATION_ERROR",
                    "name must be max 100 characters", 400);
        }
    }

    public record CreateApiKeyData(String name,
            String permissions, Instant expiresAt) {

    }

    public record CreatedApiKey(ApiKey apiKey, String rawKey) {

    }

    public static class ApiKeyException extends RuntimeException {

        private final String code;
        private final int httpStatus;

        public ApiKeyException(String code, String message, int httpStatus) {
            super(message);
            this.code = code;
            this.httpStatus = httpStatus;
        }

        public String code() {
            return code;
        }

        public int httpStatus() {
            return httpStatus;
        }
    }
}
