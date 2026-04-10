package auracore.key49.api.filter;

import auracore.key49.core.service.ApiKeyCacheService;
import auracore.key49.core.service.ApiKeyService;
import auracore.key49.core.tenant.TenantContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;

/**
 * Filtro de autenticación por API Key. Usa caché Redis para evitar consultas
 * SQL en cada request. Si Redis no está disponible, degrada a consulta directa
 * a BD.
 */
public class ApiKeyAuthFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String AUTH_HEADER = "Authorization";

    @Inject
    Logger log;

    @Inject
    DataSource dataSource;

    @Inject
    TenantContext tenantContext;

    @Inject
    ApiKeyCacheService apiKeyCacheService;

    @ServerRequestFilter(priority = 10)
    public Response authenticate(ContainerRequestContext requestContext) {
        var path = requestContext.getUriInfo().getPath();

        if (isPublicPath(path)) {
            return null;
        }

        var authHeader = requestContext.getHeaderString(AUTH_HEADER);
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            log.debug("Missing or invalid Authorization header");
            return unauthorizedResponse("Missing or invalid Authorization header");
        }

        var rawKey = authHeader.substring(BEARER_PREFIX.length()).trim();
        if (rawKey.isEmpty()) {
            return unauthorizedResponse("Empty API key");
        }

        var prefix = ApiKeyService.extractPrefix(rawKey);
        if (prefix == null) {
            return unauthorizedResponse("Invalid API key format");
        }

        var keyHash = ApiKeyService.sha256(rawKey);

        var cached = apiKeyCacheService.lookup(keyHash);
        if (cached == null) {
            return unauthorizedResponse("Invalid API key");
        }

        if (!"active".equals(cached.keyStatus())) {
            return forbiddenResponse("API key is not active");
        }

        if (cached.expiresAt() != null && Instant.now().isAfter(Instant.parse(cached.expiresAt()))) {
            return forbiddenResponse("API key has expired");
        }

        if (!"active".equals(cached.tenantStatus())) {
            return forbiddenResponse("Tenant is not active");
        }

        tenantContext.setTenant(cached.tenantId(), cached.schemaName());
        tenantContext.setRateLimitRpm(cached.rateLimitRpm());
        tenantContext.setRateLimitWriteRpm(cached.rateLimitWriteRpm());
        tenantContext.setRateLimitReadRpm(cached.rateLimitReadRpm());
        tenantContext.setApiKeyPrefix(prefix);
        log.debugf("Authenticated tenant=%s, schema=%s", cached.tenantId(), cached.schemaName());

        // Update last_used_at (fire-and-forget)
        updateLastUsedAt(keyHash);

        return null;
    }

    private void updateLastUsedAt(String keyHash) {
        try (var conn = dataSource.getConnection(); var stmt = conn.prepareStatement("UPDATE api_keys SET last_used_at = now() WHERE key_hash = ?")) {
            stmt.setString(1, keyHash);
            stmt.executeUpdate();
        } catch (SQLException e) {
            log.warnf("Failed to update last_used_at: %s", e.getMessage());
        }
    }

    private boolean isPublicPath(String path) {
        return path.startsWith("/q/")
                || path.startsWith("/portal")
                || path.startsWith("/v1/admin/")
                || path.equals("/openapi")
                || path.equals("/swagger-ui");
    }

    private Response unauthorizedResponse(String message) {
        return Response.status(Response.Status.UNAUTHORIZED)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(errorBody(message))
                .build();
    }

    private Response forbiddenResponse(String message) {
        return Response.status(Response.Status.FORBIDDEN)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(errorBody(message))
                .build();
    }

    private String errorBody(String message) {
        return "{\"error\":{\"code\":\"AUTHENTICATION_ERROR\",\"message\":\"" + message + "\"}}";
    }
}
