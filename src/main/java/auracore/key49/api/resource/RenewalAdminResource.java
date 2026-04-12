package auracore.key49.api.resource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.jboss.logging.Logger;

import auracore.key49.api.dto.ApiResponse;
import auracore.key49.api.dto.PagedResponse;
import auracore.key49.core.service.AuditService;
import auracore.key49.core.service.RenewalAdminService;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Endpoints de administración de renovaciones de plan.
 *
 * <p>
 * Protegidos por {@code AdminAuthFilter} con header {@code X-Admin-Token}.</p>
 */
@Path("/v1/admin/renewals")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RenewalAdminResource {

    @Inject
    Logger log;

    @Inject
    RenewalAdminService renewalService;

    @Inject
    AuditService auditService;

    /**
     * GET /v1/admin/renewals — Listar renovaciones con filtro y paginación.
     */
    @GET
    public Response list(
            @QueryParam("status") String status,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("per_page") @DefaultValue("20") int perPage) {

        String requestId = generateRequestId();
        var result = renewalService.list(status, page, perPage);
        var responses = result.items().stream()
                .map(RenewalResponse::fromEntity)
                .toList();
        var body = PagedResponse.of(responses, result.total(), page, perPage);
        return Response.ok()
                .header("X-Request-Id", requestId)
                .entity(body)
                .build();
    }

    /**
     * GET /v1/admin/renewals/:id — Detalle de renovación con datos del tenant.
     */
    @GET
    @Path("/{id}")
    public Response getById(@PathParam("id") UUID id) {
        String requestId = generateRequestId();
        var detail = renewalService.getDetail(id);
        if (detail == null) {
            return errorResponse(requestId, "RENEWAL_NOT_FOUND", "Renovación no encontrada", 404);
        }
        var body = ApiResponse.of(RenewalDetailResponse.from(detail), requestId);
        return Response.ok()
                .header("X-Request-Id", requestId)
                .entity(body)
                .build();
    }

    /**
     * POST /v1/admin/renewals/:id/approve — Aprobar renovación.
     */
    @POST
    @Path("/{id}/approve")
    public Response approve(@PathParam("id") UUID id, @Context HttpServerRequest httpRequest) {
        String requestId = generateRequestId();
        var result = renewalService.approve(id, "admin");

        if (!result.success()) {
            return errorResponse(requestId, "RENEWAL_APPROVE_FAILED", result.error(), 422);
        }

        auditService.record(result.renewal().tenantId, "admin", "plan.renewal_approved",
                "plan_renewal", result.renewal().id,
                AuditService.resolveIp(httpRequest),
                """
                {"plan_type":"%s","document_quota":%d}"""
                        .formatted(result.renewal().planType, result.renewal().documentQuota));

        var body = ApiResponse.of(RenewalResponse.fromEntity(result.renewal()), requestId);
        return Response.ok()
                .header("X-Request-Id", requestId)
                .entity(body)
                .build();
    }

    /**
     * POST /v1/admin/renewals/:id/reject — Rechazar renovación con motivo.
     */
    @POST
    @Path("/{id}/reject")
    public Response reject(@PathParam("id") UUID id, RejectRequest request,
            @Context HttpServerRequest httpRequest) {
        String requestId = generateRequestId();

        if (request == null || request.reason() == null || request.reason().isBlank()) {
            return errorResponse(requestId, "REASON_REQUIRED",
                    "El motivo de rechazo es obligatorio", 400);
        }

        var result = renewalService.reject(id, request.reason(), "admin");

        if (!result.success()) {
            return errorResponse(requestId, "RENEWAL_REJECT_FAILED", result.error(), 422);
        }

        auditService.record(result.renewal().tenantId, "admin", "plan.renewal_rejected",
                "plan_renewal", result.renewal().id,
                AuditService.resolveIp(httpRequest),
                """
                {"reason":"%s"}""".formatted(request.reason().replace("\"", "\\\"").replace("\n", "\\n")));

        var body = ApiResponse.of(RenewalResponse.fromEntity(result.renewal()), requestId);
        return Response.ok()
                .header("X-Request-Id", requestId)
                .entity(body)
                .build();
    }

    // ── DTOs ──
    record RejectRequest(String reason) {

    }

    record RenewalResponse(UUID renewalId, UUID tenantId, String planType,
            int documentQuota, BigDecimal amount, String paymentProofPath,
            String status, String approvedBy, Instant approvedAt,
            String notes, Instant createdAt) {

        static RenewalResponse fromEntity(auracore.key49.core.model.PlanRenewal r) {
            return new RenewalResponse(r.id, r.tenantId, r.planType, r.documentQuota,
                    r.amount, r.paymentProofPath, r.status, r.approvedBy, r.approvedAt,
                    r.notes, r.createdAt);
        }
    }

    record RenewalDetailResponse(RenewalResponse renewal, TenantSummary tenant) {

        static RenewalDetailResponse from(RenewalAdminService.RenewalDetail detail) {
            var r = RenewalResponse.fromEntity(detail.renewal());
            var t = detail.tenant() != null
                    ? new TenantSummary(detail.tenant().id, detail.tenant().ruc,
                            detail.tenant().legalName, detail.tenant().planType,
                            detail.tenant().documentQuota, detail.tenant().documentsUsed,
                            detail.tenant().email, detail.tenant().status)
                    : null;
            return new RenewalDetailResponse(r, t);
        }
    }

    record TenantSummary(UUID tenantId, String ruc, String legalName, String currentPlan,
            int documentQuota, int documentsUsed, String email, String status) {

    }

    // ── Helpers ──
    private static String generateRequestId() {
        return "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static Response errorResponse(String requestId, String code, String message, int status) {
        record ErrorDetail(String code, String message) {

        }
        record ErrorWrapper(ErrorDetail error) {

        }
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .header("X-Request-Id", requestId)
                .entity(new ErrorWrapper(new ErrorDetail(code, message)))
                .build();
    }
}
