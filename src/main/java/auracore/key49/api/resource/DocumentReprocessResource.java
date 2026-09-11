package auracore.key49.api.resource;

import java.util.UUID;

import auracore.key49.api.dto.ApiResponse;
import auracore.key49.api.dto.ReprocessRequest;
import auracore.key49.core.service.AuditService;
import auracore.key49.core.service.DocumentReprocessService;
import auracore.key49.core.tenant.TenantContext;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Reproceso en lote de documentos del tenant autenticado.
 *
 * <p>El tenant se resuelve exclusivamente del contexto de autenticación
 * ({@link TenantContext}); nunca del cuerpo de la petición, para evitar acceso
 * cruzado entre tenants.</p>
 */
@Path("/v1/documents")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DocumentReprocessResource {

    @Inject
    TenantContext tenantContext;

    @Inject
    DocumentReprocessService reprocessService;

    @Inject
    AuditService auditService;

    /**
     * POST /v1/documents/reprocess — reencola documentos FAILED/RECEIVED/RETRY.
     */
    @POST
    @Path("/reprocess")
    public Response reprocess(ReprocessRequest request) {
        var requestId = "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        if (!tenantContext.isSet()) {
            return Response.status(401)
                    .header("X-Request-Id", requestId)
                    .entity(new ErrorBody("AUTHENTICATION_REQUIRED", "Tenant context not available"))
                    .build();
        }

        var filters = request != null ? request
                : new ReprocessRequest(null, null, null, null, null);
        var result = reprocessService.reprocess(tenantContext.getSchemaName(), filters);

        auditService.record(tenantContext.getTenantId(), "api", "document.reprocess",
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

    private record ErrorBody(String code, String message) {
    }
}
