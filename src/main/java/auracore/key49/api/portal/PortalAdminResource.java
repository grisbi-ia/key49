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
import auracore.key49.core.service.TenantAdminService;
import auracore.key49.core.repository.TenantRepository;
import auracore.key49.notify.email.PlatformEmailService;
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

    @Location("portal/admin/tenants")
    Template adminTenants;

    @Inject
    RenewalAdminService renewalService;

    @Inject
    TenantRepository tenantRepository;

    @Inject
    TenantAdminService tenantAdminService;

    @Inject
    ObjectStorageService storageService;

    @Inject
    PlatformEmailService platformEmailService;

    @Inject
    EmailVerificationService emailVerificationService;

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

    // ── Tenant Approvals (Admin) ──

    /**
     * GET /portal/admin/tenants — Lista de tenants pendientes de aprobación.
     */
    @GET
    @Path("/tenants")
    public Response listPendingTenants(
            @QueryParam("token") String token,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("successMsg") String successMsg,
            @QueryParam("error") String error) {

        var authError = validateToken(token);
        if (authError != null) {
            return authError;
        }

        int perPage = 20;
        var result = tenantAdminService.listAll(page, perPage, Optional.of("pending_approval"));
        var pendingCount = tenantAdminService.countByStatus("pending_approval");

        return Response.ok(adminTenants
                .data("items", result.items().stream().map(t -> new TenantRow(
                        t.id,
                        formatDateTime(t.createdAt),
                        t.legalName,
                        t.ruc,
                        t.email,
                        t.environment,
                        t.status)).toList())
                .data("page", page)
                .data("total", result.total())
                .data("pages", (int) Math.ceil((double) result.total() / perPage))
                .data("pendingCount", pendingCount)
                .data("token", token)
                .data("successMsg", successMsg)
                .data("error", error)
                .data("title", "Aprobación de Tenants")
                .render()).build();
    }

    /**
     * POST /portal/admin/tenants/{id}/approve — Aprobar un tenant.
     */
    @POST
    @Path("/tenants/{id}/approve")
    public Response approveTenant(
            @PathParam("id") UUID id,
            @QueryParam("token") String token,
            @FormParam("notes") String notes) {

        var authError = validateToken(token);
        if (authError != null) {
            return authError;
        }

        try {
            var tenant = tenantAdminService.approve(id, notes);
            log.infof("Admin portal approved tenant | id=%s ruc=%s", id, tenant.ruc);
            return redirectToTenantList(token, "Tenant " + tenant.legalName + " aprobado exitosamente", null);
        } catch (TenantAdminService.TenantException e) {
            return redirectToTenantList(token, null, e.getMessage());
        }
    }

    /**
     * POST /portal/admin/tenants/{id}/reject — Rechazar un tenant.
     */
    @POST
    @Path("/tenants/{id}/reject")
    public Response rejectTenant(
            @PathParam("id") UUID id,
            @QueryParam("token") String token,
            @FormParam("reason") String reason) {

        var authError = validateToken(token);
        if (authError != null) {
            return authError;
        }

        if (reason == null || reason.isBlank()) {
            return redirectToTenantList(token, null, "El motivo de rechazo es obligatorio");
        }

        try {
            var tenant = tenantAdminService.reject(id, reason);
            log.infof("Admin portal rejected tenant | id=%s ruc=%s reason=%s", id, tenant.ruc, reason);
            return redirectToTenantList(token, "Tenant " + tenant.legalName + " rechazado", null);
        } catch (TenantAdminService.TenantException e) {
            return redirectToTenantList(token, null, e.getMessage());
        }
    }

    /**
     * POST /portal/admin/tenants/{id}/resend-verification — Reenviar email de
     * verificación a un tenant específico.
     */
    @POST
    @Path("/tenants/{id}/resend-verification")
    @Produces(MediaType.TEXT_HTML)
    public Response resendVerification(
            @PathParam("id") UUID id,
            @QueryParam("token") String token) {

        var authError = validateToken(token);
        if (authError != null) {
            return authError;
        }

        var result = emailVerificationService.resendVerificationByTenantId(id);

        if (!result.success()) {
            return redirectToTenantList(token, null, result.error());
        }

        log.infof("Admin manually resent verification email | tenantId=%s", id);
        return redirectToTenantList(token, "Email de verificación reenviado exitosamente", null);
    }

    // ── Email Test Endpoint ──

    /**
     * POST /portal/admin/test-email — Envía un email de prueba vía Plunk.
     * Útil para validar configuración del proveedor de email.
     *
     * Parámetros: token, to, subject (opcional), provider (opcional: smtp|plunk).
     */
    @POST
    @Path("/test-email")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_PLAIN)
    public Response testEmail(
            @FormParam("token") String token,
            @FormParam("to") String to,
            @FormParam("subject") String subject,
            @FormParam("body") String body) {

        var authError = validateToken(token);
        if (authError != null) {
            return authError;
        }

        if (to == null || to.isBlank()) {
            return Response.status(400)
                    .entity("ERROR: El parámetro 'to' (email destinatario) es obligatorio")
                    .build();
        }

        var emailTo = to.strip().toLowerCase();
        var emailSubject = subject != null && !subject.isBlank()
                ? subject.strip()
                : "Key49 — Email de prueba Plunk";
        var emailBody = body != null && !body.isBlank()
                ? body.strip()
                : """
                        <html><body style="font-family:sans-serif;padding:20px">
                        <h2>🧪 Email de prueba — Key49 + Plunk</h2>
                        <p>Si estás leyendo esto, la configuración de <strong>Plunk</strong> funciona correctamente.</p>
                        <hr>
                        <p style="color:#666;font-size:12px">
                            Enviado: %s<br>
                            Provider: %s<br>
                            Destinatario: %s
                        </p></body></html>
                        """.formatted(
                        java.time.ZonedDateTime.now(java.time.ZoneId.of("America/Guayaquil"))
                                .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss z")),
                        "Plunk",
                        emailTo);

        try {
            platformEmailService.sendHtml(emailTo, emailSubject, emailBody);
            log.infof("Admin test email sent successfully | to=%s", emailTo);
            return Response.ok("✅ Email enviado exitosamente a " + emailTo + " (asunto: " + emailSubject + ")").build();
        } catch (Exception e) {
            log.errorf(e, "Admin test email failed | to=%s", emailTo);
            return Response.status(500)
                    .entity("❌ Error al enviar email: " + e.getMessage())
                    .build();
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

    private Response redirectToTenantList(String token, String successMsg, String error) {
        var sb = new StringBuilder("/portal/admin/tenants?token=").append(encodeQuery(token));
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
