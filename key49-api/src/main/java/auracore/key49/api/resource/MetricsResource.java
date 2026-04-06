package auracore.key49.api.resource;

import java.util.UUID;

import org.jboss.logging.Logger;

import auracore.key49.api.dto.ApiResponse;
import auracore.key49.api.service.MetricsService;
import auracore.key49.core.tenant.TenantContext;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Endpoint de métricas del dashboard para el tenant autenticado. GET
 * /v1/metrics/summary — resumen de actividad
 */
@Path("/v1/metrics")
@Produces(MediaType.APPLICATION_JSON)
public class MetricsResource {

    @Inject
    Logger log;

    @Inject
    MetricsService metricsService;

    @Inject
    TenantContext tenantContext;

    @GET
    @Path("/summary")
    public Uni<Response> summary() {
        var requestId = generateRequestId();

        if (!tenantContext.isSet()) {
            return Uni.createFrom().item(Response.status(401)
                    .entity(new ErrorBody("AUTHENTICATION_REQUIRED", "Tenant context not available"))
                    .build());
        }

        return metricsService.getSummary()
                .map(data -> {
                    var body = ApiResponse.of(data, requestId);
                    return Response.ok()
                            .header("X-Request-Id", requestId)
                            .entity(body)
                            .build();
                });
    }

    private static String generateRequestId() {
        return "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private record ErrorBody(String code, String message) {

    }
}
