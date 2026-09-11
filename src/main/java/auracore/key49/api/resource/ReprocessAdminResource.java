package auracore.key49.api.resource;

import java.util.UUID;

import auracore.key49.api.dto.ApiResponse;
import auracore.key49.api.dto.ReprocessRequest;
import auracore.key49.api.exception.BusinessException;
import auracore.key49.core.service.AuditService;
import auracore.key49.core.service.DocumentReprocessService;
import auracore.key49.core.service.TenantCacheService;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Reproceso en lote de documentos para operación (cross-tenant).
 *
 * <p>Protegido por {@code AdminAuthFilter} (X-Admin-Token). El tenant objetivo se
 * selecciona explícitamente con {@code tenant_id}; la respuesta solo devuelve
 * contadores, sin datos de documentos de otros tenants.</p>
 */
@Path("/v1/admin/documents")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ReprocessAdminResource {

    @Inject
    DocumentReprocessService reprocessService;

    @Inject
    TenantCacheService tenantCacheService;

    @Inject
    AuditService auditService;

    /**
     * POST /v1/admin/documents/reprocess?tenant_id=&lt;uuid&gt;
     */
    @POST
    @Path("/reprocess")
    public Response reprocess(@QueryParam("tenant_id") UUID tenantId, ReprocessRequest request) {
        var requestId = "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        if (tenantId == null) {
            throw new BusinessException("VALIDATION_ERROR", "Query parameter 'tenant_id' is required", 400);
        }
        var tenant = tenantCacheService.findById(tenantId);
        if (tenant == null) {
            throw new BusinessException("NOT_FOUND", "Tenant not found", 404);
        }

        var filters = request != null ? request
                : new ReprocessRequest(null, null, null, null, null);
        var result = reprocessService.reprocess(tenant.schemaName, filters);

        auditService.record(tenantId, "admin", "document.reprocess",
                "document", null, null,
                new JsonObject()
                        .put("matched", result.matched())
                        .put("queued", result.queued())
                        .encode());

        return Response.ok()
                .header("X-Request-Id", requestId)
                .entity(ApiResponse.of(result, requestId))
                .build();
    }
}
