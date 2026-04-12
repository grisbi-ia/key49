package auracore.key49.api.portal;

import java.math.BigDecimal;
import java.net.URI;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import auracore.key49.core.model.PlanRenewal;
import auracore.key49.core.model.Tenant;
import auracore.key49.core.service.AuditService;
import auracore.key49.core.service.RenewalAdminService;
import auracore.key49.core.repository.TenantRepository;
import auracore.key49.storage.ObjectStorageService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
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
 * Controlador del portal web de administración de renovaciones de plan.
 *
 * <p>
 * Autenticación vía query parameter {@code token} validado contra
 * {@code key49.admin.token}. No usa sesiones de tenant.</p>
 */
@Path("/portal/admin")
@Produces(MediaType.TEXT_HTML)
public class PortalAdminResource {

    private static final DateTimeFormatter DISPLAY_DATETIME = DateTimeFormatter
            .ofPattern("dd/MM/yyyy HH:mm")
            .withZone(ZoneId.of("America/Guayaquil"));

    @Inject
    Logger log;

    @Inject
    @Location("portal/admin/renewals")
    Template renewals;

    @Inject
    RenewalAdminService renewalService;

    @Inject
    TenantRepository tenantRepository;

    @Inject
    ObjectStorageService storageService;

    @Inject
    AuditService auditService;

    @ConfigProperty(name = "key49.admin.token")
    Optional<String> adminToken;

    /**
     * GET /portal/admin/renewals — Vista de administración de renovaciones.
     */
    @GET
    @Path("/renewals")
    public Response listRenewals(
            @QueryParam("token") String token,
            @QueryParam("status") String status,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("successMsg") String successMsg,
            @QueryParam("error") String error) {

        var authError = validateToken(token);
        if (authError != null) {
            return authError;
        }

        int perPage = 20;
        var result = renewalService.list(status, page, perPage);
        var pendingCount = renewalService.list("pending", 1, 1).total();

        var items = result.items().stream()
                .map(r -> {
                    var tenant = tenantRepository.findById(r.tenantId);
                    return new RenewalRow(
                            r.id,
                            formatDateTime(r.createdAt),
                            tenant != null ? tenant.legalName : "—",
                            tenant != null ? tenant.ruc : "—",
                            r.planType,
                            r.documentQuota,
                            r.amount,
                            r.paymentProofPath,
                            r.status,
                            r.approvedBy,
                            formatDateTime(r.approvedAt));
                })
                .toList();

        long totalPages = (result.total() + perPage - 1) / perPage;

        TemplateInstance instance = renewals
                .data("token", token)
                .data("renewals", items)
                .data("statusFilter", status != null ? status : "")
                .data("page", page)
                .data("totalPages", totalPages)
                .data("pendingCount", pendingCount)
                .data("totalCount", result.total())
                .data("successMsg", successMsg)
                .data("error", error);

        return Response.ok(instance).build();
    }

    /**
     * POST /portal/admin/renewals/:id/approve — Aprobar una renovación desde el
     * portal admin.
     */
    @POST
    @Path("/renewals/{id}/approve")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response approveRenewal(
            @PathParam("id") UUID id,
            @FormParam("token") String token,
            @Context HttpServerRequest httpRequest) {

        var authError = validateToken(token);
        if (authError != null) {
            return authError;
        }

        var result = renewalService.approve(id, "admin");

        if (!result.success()) {
            return redirectToList(token, null, result.error());
        }

        auditService.record(result.renewal().tenantId, "admin", "plan.renewal_approved",
                "plan_renewal", result.renewal().id,
                AuditService.resolveIp(httpRequest),
                """
                {"plan_type":"%s","document_quota":%d}"""
                        .formatted(result.renewal().planType, result.renewal().documentQuota));

        log.infof("Admin portal approved renewal=%s tenant=%s", id, result.renewal().tenantId);

        return redirectToList(token,
                "Renovación aprobada — Plan %s activado".formatted(result.renewal().planType),
                null);
    }

    /**
     * POST /portal/admin/renewals/:id/reject — Rechazar una renovación desde el
     * portal admin.
     */
    @POST
    @Path("/renewals/{id}/reject")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response rejectRenewal(
            @PathParam("id") UUID id,
            @FormParam("token") String token,
            @FormParam("reason") String reason,
            @Context HttpServerRequest httpRequest) {

        var authError = validateToken(token);
        if (authError != null) {
            return authError;
        }

        if (reason == null || reason.isBlank()) {
            return redirectToList(token, null, "El motivo de rechazo es obligatorio");
        }

        var result = renewalService.reject(id, reason, "admin");

        if (!result.success()) {
            return redirectToList(token, null, result.error());
        }

        auditService.record(result.renewal().tenantId, "admin", "plan.renewal_rejected",
                "plan_renewal", result.renewal().id,
                AuditService.resolveIp(httpRequest),
                """
                {"reason":"%s"}""".formatted(reason.replace("\"", "\\\"").replace("\n", "\\n")));

        log.infof("Admin portal rejected renewal=%s tenant=%s reason=%s", id,
                result.renewal().tenantId, reason);

        return redirectToList(token, "Renovación rechazada", null);
    }

    /**
     * GET /portal/admin/renewals/:id/proof — Descargar comprobante de pago.
     */
    @GET
    @Path("/renewals/{id}/proof")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response downloadProof(
            @PathParam("id") UUID id,
            @QueryParam("token") String token) {

        var authError = validateToken(token);
        if (authError != null) {
            return authError;
        }

        var detail = renewalService.getDetail(id);
        if (detail == null || detail.renewal().paymentProofPath == null) {
            return Response.status(404).entity("Comprobante no encontrado").build();
        }

        try {
            byte[] content = storageService.retrieve(detail.renewal().paymentProofPath);
            String contentType = guessContentType(detail.renewal().paymentProofPath);
            return Response.ok(content)
                    .type(contentType)
                    .header("Content-Disposition",
                            "inline; filename=\"proof-%s%s\"".formatted(
                                    id.toString().substring(0, 8), extensionFrom(contentType)))
                    .build();
        } catch (Exception e) {
            log.errorf(e, "Failed to retrieve payment proof | renewal=%s path=%s",
                    id, detail.renewal().paymentProofPath);
            return Response.status(500).entity("Error al descargar el comprobante").build();
        }
    }

    // ── Helpers ──
    private Response validateToken(String token) {
        if (adminToken.isEmpty() || adminToken.get().isBlank()) {
            return Response.status(403)
                    .entity("Admin access not configured")
                    .type(MediaType.TEXT_PLAIN_TYPE)
                    .build();
        }
        if (token == null || token.isBlank() || !timeSafeEquals(adminToken.get(), token)) {
            return Response.status(403)
                    .entity("Token de administración inválido")
                    .type(MediaType.TEXT_PLAIN_TYPE)
                    .build();
        }
        return null;
    }

    private static boolean timeSafeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private Response redirectToList(String token, String successMsg, String error) {
        var sb = new StringBuilder("/portal/admin/renewals?token=").append(encodeQuery(token));
        if (successMsg != null) {
            sb.append("&successMsg=").append(encodeQuery(successMsg));
        }
        if (error != null) {
            sb.append("&error=").append(encodeQuery(error));
        }
        return Response.seeOther(URI.create(sb.toString())).build();
    }

    private static String encodeQuery(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String formatDateTime(Instant instant) {
        if (instant == null) {
            return "—";
        }
        return DISPLAY_DATETIME.format(instant);
    }

    private static String guessContentType(String path) {
        if (path.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (path.endsWith(".png")) {
            return "image/png";
        }
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        return "application/octet-stream";
    }

    private static String extensionFrom(String contentType) {
        return switch (contentType) {
            case "application/pdf" ->
                ".pdf";
            case "image/png" ->
                ".png";
            case "image/jpeg" ->
                ".jpg";
            default ->
                "";
        };
    }

    // ── View Models ──
    record RenewalRow(UUID id, String createdAt, String tenantName, String tenantRuc,
            String planType, int documentQuota, BigDecimal amount,
            String paymentProofPath, String status, String approvedBy,
            String approvedAt) {

    }
}
