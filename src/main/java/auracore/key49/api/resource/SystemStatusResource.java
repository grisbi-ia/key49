package auracore.key49.api.resource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.eclipse.microprofile.health.HealthCheckResponse;
import org.jboss.logging.Logger;

import org.eclipse.microprofile.health.Liveness;
import org.eclipse.microprofile.health.Readiness;

import auracore.key49.admin.health.DatasourcePoolHealthCheck;
import auracore.key49.admin.health.MinioHealthCheck;
import auracore.key49.admin.health.QueueDepthHealthCheck;
import auracore.key49.admin.health.SriAuthorizationHealthCheck;
import auracore.key49.admin.health.SriReceptionHealthCheck;
import auracore.key49.api.dto.ApiResponse;
import auracore.key49.core.tenant.TenantContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Estado actual del sistema para el tenant autenticado.
 *
 * <p>
 * {@code GET /v1/system/status} agrega los health checks de SRI, MinIO,
 * PostgreSQL y colas RabbitMQ en una respuesta unificada.</p>
 */
@Path("/v1/system")
@Produces(MediaType.APPLICATION_JSON)
public class SystemStatusResource {

    @Inject
    Logger log;

    @Inject
    TenantContext tenantContext;

    @Inject
    @Liveness
    SriReceptionHealthCheck sriReceptionHealth;

    @Inject
    @Liveness
    SriAuthorizationHealthCheck sriAuthorizationHealth;

    @Inject
    @Readiness
    MinioHealthCheck minioHealth;

    @Inject
    @Readiness
    DatasourcePoolHealthCheck datasourceHealth;

    @Inject
    @Readiness
    QueueDepthHealthCheck queueHealth;

    @GET
    @Path("/status")
    public Response status() {
        var requestId = generateRequestId();

        if (!tenantContext.isSet()) {
            return Response.status(401)
                    .entity(new ErrorBody("AUTHENTICATION_REQUIRED", "Tenant context not available"))
                    .build();
        }

        var components = new LinkedHashMap<String, ComponentStatus>();
        components.put("sri_reception", toComponentStatus(sriReceptionHealth.call()));
        components.put("sri_authorization", toComponentStatus(sriAuthorizationHealth.call()));
        components.put("storage", toComponentStatus(minioHealth.call()));
        components.put("database", toComponentStatus(datasourceHealth.call()));
        components.put("queues", toComponentStatus(queueHealth.call()));

        var overall = resolveOverall(components);

        log.debugf("System status check: overall=%s requestId=%s", overall, requestId);

        var body = ApiResponse.of(new SystemStatus(overall, components), requestId);
        return Response.ok()
                .header("X-Request-Id", requestId)
                .entity(body)
                .build();
    }

    static String resolveOverall(Map<String, ComponentStatus> components) {
        var hasDown = components.values().stream()
                .anyMatch(c -> "down".equals(c.status()));
        if (hasDown) {
            return "outage";
        }
        return "operational";
    }

    static ComponentStatus toComponentStatus(HealthCheckResponse hcr) {
        var status = hcr.getStatus() == HealthCheckResponse.Status.UP ? "operational" : "down";
        var details = hcr.getData()
                .map(d -> (Map<String, Object>) new LinkedHashMap<>(d))
                .orElse(Map.of());
        return new ComponentStatus(status, hcr.getName(), details);
    }

    record SystemStatus(String overall, Map<String, ComponentStatus> components) {

    }

    record ComponentStatus(String status, String name, Map<String, Object> details) {

    }

    private record ErrorBody(String code, String message) {

    }

    private static String generateRequestId() {
        return "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
