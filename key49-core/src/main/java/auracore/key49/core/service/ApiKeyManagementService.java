package auracore.key49.core.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.hibernate.reactive.mutiny.Mutiny;

import auracore.key49.core.model.ApiKey;
import auracore.key49.core.repository.ApiKeyRepository;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Servicio de gestión de API keys para tenants. Generación, listado, consulta y
 * revocación.
 */
@ApplicationScoped
public class ApiKeyManagementService {

    @Inject
    ApiKeyRepository apiKeyRepository;

    @Inject
    Mutiny.SessionFactory sessionFactory;

    public Uni<CreatedApiKey> create(UUID tenantId, CreateApiKeyData data) {
        validateCreateData(data);

        var prefix = "production".equals(data.environment())
                ? ApiKeyService.PREFIX_LIVE
                : ApiKeyService.PREFIX_TEST;
        var generated = ApiKeyService.generate(prefix);

        var apiKey = new ApiKey();
        apiKey.tenantId = tenantId;
        apiKey.keyPrefix = generated.keyPrefix();
        apiKey.keyHash = generated.hash();
        apiKey.name = data.name();
        apiKey.permissions = data.permissions() != null ? data.permissions() : "*";
        apiKey.expiresAt = data.expiresAt();
        apiKey.status = "active";
        apiKey.createdAt = Instant.now();

        return sessionFactory.withTransaction(session
                -> apiKeyRepository.persist(apiKey)
                        .map(persisted -> {
                            Log.infof("API key created | tenantId=%s name=%s prefix=%s",
                                    tenantId, data.name(), generated.keyPrefix());
                            return new CreatedApiKey(persisted, generated.rawKey());
                        })
        );
    }

    public Uni<List<ApiKey>> listByTenant(UUID tenantId) {
        return apiKeyRepository.findByTenantId(tenantId);
    }

    public Uni<ApiKey> findById(UUID tenantId, UUID apiKeyId) {
        return apiKeyRepository.findById(apiKeyId)
                .onItem().ifNull().failWith(()
                        -> new ApiKeyException("API_KEY_NOT_FOUND",
                        "API key not found: " + apiKeyId, 404))
                .map(key -> {
                    if (!key.tenantId.equals(tenantId)) {
                        throw new ApiKeyException("API_KEY_NOT_FOUND",
                                "API key not found: " + apiKeyId, 404);
                    }
                    return key;
                });
    }

    public Uni<ApiKey> revoke(UUID tenantId, UUID apiKeyId) {
        return sessionFactory.withTransaction(session
                -> findById(tenantId, apiKeyId)
                        .map(key -> {
                            if ("revoked".equals(key.status)) {
                                throw new ApiKeyException("ALREADY_REVOKED",
                                        "API key is already revoked", 409);
                            }
                            key.status = "revoked";
                            Log.infof("API key revoked | tenantId=%s apiKeyId=%s name=%s",
                                    tenantId, apiKeyId, key.name);
                            return key;
                        })
        );
    }

    private void validateCreateData(CreateApiKeyData data) {
        if (data.name() == null || data.name().isBlank()) {
            throw new ApiKeyException("VALIDATION_ERROR", "name is required", 400);
        }
        if (data.name().length() > 100) {
            throw new ApiKeyException("VALIDATION_ERROR",
                    "name must be max 100 characters", 400);
        }
        if (data.environment() != null
                && !"test".equals(data.environment())
                && !"production".equals(data.environment())) {
            throw new ApiKeyException("VALIDATION_ERROR",
                    "environment must be 'test' or 'production'", 400);
        }
    }

    public record CreateApiKeyData(String name, String environment,
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


        




                                            
        
    
    
        
    