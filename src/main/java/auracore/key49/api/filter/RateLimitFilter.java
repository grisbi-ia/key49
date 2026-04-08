package auracore.key49.api.filter;

import auracore.key49.core.tenant.TenantContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;
import org.jboss.resteasy.reactive.server.ServerResponseFilter;

/**
 * Filtro de rate limiting por tenant con ventana deslizante en Redis.
 *
 * <p>
 * Se ejecuta después del filtro de autenticación (prioridad mayor = después).
 * Solo aplica a requests autenticados con API key (TenantContext activo).
 * Agrega headers X-RateLimit-* a todas las respuestas autenticadas.</p>
 */
public class RateLimitFilter {

    private static final String RATE_LIMIT_RESULT = "key49.rateLimit.result";

    @Inject
    Logger log;

    @Inject
    TenantContext tenantContext;

    @Inject
    RateLimiter rateLimiter;

    @ServerRequestFilter(priority = 20)
    public Response filter(ContainerRequestContext requestContext) {
        if (!tenantContext.isSet()) {
            return null;
        }

        var apiKeyPrefix = tenantContext.getApiKeyPrefix();
        if (apiKeyPrefix == null) {
            return null;
        }

        var maxRpm = tenantContext.getRateLimitRpm();
        var result = rateLimiter.checkLimit(apiKeyPrefix, maxRpm);

        // Almacenar resultado para los response headers
        requestContext.setProperty(RATE_LIMIT_RESULT, result);

        if (!result.allowed()) {
            log.warnf("Rate limit exceeded | tenant=%s limit=%d",
                    tenantContext.getTenantId(), maxRpm);
            return Response.status(429)
                    .type(MediaType.APPLICATION_JSON_TYPE)
                    .header("X-RateLimit-Limit", result.limit())
                    .header("X-RateLimit-Remaining", 0)
                    .header("X-RateLimit-Reset", result.resetEpochSeconds())
                    .header("Retry-After", result.retryAfterSeconds())
                    .entity(errorBody())
                    .build();
        }
        return null;
    }

    @ServerResponseFilter
    public void addHeaders(ContainerRequestContext requestContext,
            ContainerResponseContext responseContext) {
        var result = requestContext.getProperty(RATE_LIMIT_RESULT);
        if (result instanceof RateLimiter.RateLimitResult rlr) {
            responseContext.getHeaders().putSingle("X-RateLimit-Limit", rlr.limit());
            responseContext.getHeaders().putSingle("X-RateLimit-Remaining", rlr.remaining());
            responseContext.getHeaders().putSingle("X-RateLimit-Reset", rlr.resetEpochSeconds());
        }
    }

    private static String errorBody() {
        return "{\"error\":{\"code\":\"RATE_LIMIT_EXCEEDED\","
                + "\"message\":\"Se excedió el límite de requests por minuto\"}}";
    }
}
