package auracore.key49.api.filter;

import auracore.key49.core.tenant.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;
import org.jboss.resteasy.reactive.server.ServerResponseFilter;

/**
 * Filtro de rate limiting granular por categoría de endpoint (WRITE/READ).
 *
 * <p>
 * Se ejecuta después del filtro de autenticación (prioridad mayor = después).
 * Solo aplica a requests autenticados con API key (TenantContext activo).
 * Agrega headers X-RateLimit-* a todas las respuestas autenticadas.</p>
 *
 * <p>
 * Aplica límites independientes para operaciones de escritura
 * (POST/PUT/PATCH/DELETE) y lectura (GET/HEAD/OPTIONS), configurables por
 * tenant.</p>
 */
public class RateLimitFilter {

    private static final String RATE_LIMIT_RESULT = "key49.rateLimit.result";

    @Inject
    Logger log;

    @Inject
    TenantContext tenantContext;

    @Inject
    RateLimiter rateLimiter;

    @Inject
    MeterRegistry meterRegistry;

    @ServerRequestFilter(priority = 20)
    public Response filter(ContainerRequestContext requestContext) {
        if (!tenantContext.isSet()) {
            return null;
        }

        var apiKeyPrefix = tenantContext.getApiKeyPrefix();
        if (apiKeyPrefix == null) {
            return null;
        }

        var category = EndpointCategory.fromHttpMethod(requestContext.getMethod());
        var maxRpm = switch (category) {
            case WRITE ->
                tenantContext.getRateLimitWriteRpm();
            case READ ->
                tenantContext.getRateLimitReadRpm();
        };
        var result = rateLimiter.checkLimit(apiKeyPrefix, maxRpm, category);

        // Almacenar resultado para los response headers
        requestContext.setProperty(RATE_LIMIT_RESULT, result);

        if (!result.allowed()) {
            log.warnf("Rate limit exceeded | tenant=%s category=%s limit=%d",
                    tenantContext.getTenantId(), category.keySuffix(), maxRpm);
            meterRegistry.counter("key49.rate_limit.rejected",
                    "tenant", tenantContext.getTenantId().toString(),
                    "category", category.keySuffix()).increment();
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
