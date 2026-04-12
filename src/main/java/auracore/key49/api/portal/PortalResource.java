package auracore.key49.api.portal;

import java.net.URI;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import auracore.key49.core.Key49Constants;
import auracore.key49.core.model.Document;
import auracore.key49.core.model.Tenant;
import auracore.key49.core.model.enums.DocumentStatus;
import auracore.key49.core.model.enums.DocumentType;
import auracore.key49.core.service.AuditService;
import auracore.key49.core.tenant.TenantConnectionManager;
import auracore.key49.queue.event.DocumentEvent;
import auracore.key49.queue.producer.DocumentEventProducer;
import auracore.key49.storage.ObjectStorageService;
import io.quarkus.qute.Location;
import io.vertx.core.http.HttpServerRequest;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;

/**
 * Controlador del portal web de consulta de documentos.
 *
 * <p>
 * Usa Qute para server-side rendering con Pico CSS + HTMX. Solo lectura — no
 * permite crear ni modificar documentos.</p>
 */
@Path("/portal")
public class PortalResource {

    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DISPLAY_DATETIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final int DEFAULT_PER_PAGE = 20;

    @Inject
    Logger log;

    @Inject
    @Location("portal/login")
    Template login;

    @Inject
    @Location("portal/dashboard")
    Template dashboard;

    @Inject
    @Location("portal/detail")
    Template detail;

    @Inject
    @Location("portal/metrics")
    Template metrics;

    @Inject
    @Location("portal/register")
    Template register;

    @Inject
    @Location("portal/register-step2")
    Template registerStep2;

    @Inject
    @Location("portal/register-step3")
    Template registerStep3;

    @Inject
    @Location("portal/register-step4")
    Template registerStep4;

    @Inject
    @Location("portal/register-success")
    Template registerSuccess;

    @Inject
    @Location("portal/forgot-password")
    Template forgotPassword;

    @Inject
    @Location("portal/reset-password")
    Template resetPassword;

    @Inject
    PortalSessionService sessionService;

    @Inject
    AuditService auditService;

    @Inject
    TenantConnectionManager tcm;

    @Inject
    ObjectStorageService storageService;

    @Inject
    DocumentEventProducer eventProducer;

    @Inject
    RegistrationService registrationService;

    @Inject
    PasswordResetService passwordResetService;

    @Inject
    EntityManager entityManager;

    @ConfigProperty(name = "key49.portal.secure-cookie", defaultValue = "false")
    boolean secureCookie;

    @Context
    ContainerRequestContext requestContext;

    // ── Forgot Password ──
    @GET
    @Path("/forgot-password")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance forgotPasswordPage() {
        return forgotPassword.data("error", null)
                .data("sent", false)
                .data("emailValue", "");
    }

    @POST
    @Path("/forgot-password")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance doForgotPassword(@FormParam("email") String email) {
        var result = passwordResetService.requestReset(email);

        if (!result.success()) {
            return forgotPassword.data("error", result.error())
                    .data("sent", false)
                    .data("emailValue", email != null ? email : "");
        }

        return forgotPassword.data("error", null)
                .data("sent", true)
                .data("emailValue", "");
    }

    // ── Reset Password ──
    @GET
    @Path("/reset-password")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance resetPasswordPage(@QueryParam("token") String token) {
        var validation = passwordResetService.validateToken(token);

        if (!validation.valid()) {
            return resetPassword.data("tokenError", validation.error())
                    .data("error", null)
                    .data("success", false)
                    .data("email", "")
                    .data("token", "");
        }

        return resetPassword.data("tokenError", null)
                .data("error", null)
                .data("success", false)
                .data("email", validation.email())
                .data("token", token);
    }

    @POST
    @Path("/reset-password")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance doResetPassword(@FormParam("token") String token,
            @FormParam("password") String password,
            @FormParam("confirmPassword") String confirmPassword) {
        var result = passwordResetService.resetPassword(token, password, confirmPassword);

        if (!result.success()) {
            // Re-validate token to check if it expired during the attempt
            var validation = passwordResetService.validateToken(token);
            if (!validation.valid()) {
                return resetPassword.data("tokenError", result.error())
                        .data("error", null)
                        .data("success", false)
                        .data("email", "")
                        .data("token", "");
            }
            return resetPassword.data("tokenError", null)
                    .data("error", result.error())
                    .data("success", false)
                    .data("email", validation.email())
                    .data("token", token);
        }

        return resetPassword.data("tokenError", null)
                .data("error", null)
                .data("success", true)
                .data("email", "")
                .data("token", "");
    }

    // ── Login ──
    @GET
    @Path("/login")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance loginPage(@QueryParam("error") String error) {
        return login.data("error", error);
    }

    @POST
    @Path("/login")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response doLogin(@FormParam("api_key") String apiKey,
            @FormParam("email") String email,
            @FormParam("password") String password,
            @Context HttpServerRequest httpRequest) {

        String sessionId;
        if (apiKey != null && !apiKey.isBlank()) {
            sessionId = sessionService.login(apiKey);
        } else if (email != null && !email.isBlank() && password != null && !password.isBlank()) {
            sessionId = sessionService.loginWithPassword(email, password);
        } else {
            sessionId = null;
        }

        if (sessionId == null) {
            return Response.seeOther(URI.create("/portal/login?error=invalid"))
                    .build();
        }

        var session = sessionService.validate(sessionId);
        if (session != null) {
            auditService.record(session.tenantId(), "portal", "portal.login",
                    "session", session.tenantId(),
                    AuditService.resolveIp(httpRequest), null);
        }

        return Response.seeOther(URI.create("/portal/"))
                .cookie(new NewCookie.Builder(PortalAuthFilter.SESSION_COOKIE)
                        .value(sessionId)
                        .path("/portal")
                        .httpOnly(true)
                        .secure(secureCookie)
                        .sameSite(NewCookie.SameSite.STRICT)
                        .build())
                .build();
    }

    // ── Logout ──
    @GET
    @Path("/logout")
    public Response logout(@Context HttpServerRequest httpRequest) {
        var cookies = requestContext.getCookies();
        var sessionCookie = cookies.get(PortalAuthFilter.SESSION_COOKIE);
        var sessionId = sessionCookie != null ? sessionCookie.getValue() : null;

        if (sessionId != null) {
            var session = sessionService.validate(sessionId);
            if (session != null) {
                auditService.record(session.tenantId(), "portal", "portal.logout",
                        "session", session.tenantId(),
                        AuditService.resolveIp(httpRequest), null);
            }
        }

        sessionService.logout(sessionId);
        return Response.seeOther(URI.create("/portal/login"))
                .cookie(new NewCookie.Builder(PortalAuthFilter.SESSION_COOKIE)
                        .value("")
                        .path("/portal")
                        .maxAge(0)
                        .httpOnly(true)
                        .secure(secureCookie)
                        .build())
                .build();
    }

    // ── Register ──
    @GET
    @Path("/register")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance registerPage(@QueryParam("error") String error) {
        return register.data("error", error)
                .data("ruc", null)
                .data("legalName", null)
                .data("email", null);
    }

    @POST
    @Path("/register/verify-ruc")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public String verifyRuc(@FormParam("ruc") String ruc) {
        var result = registrationService.verifyRuc(ruc);
        if (!result.valid()) {
            return "<span class=\"invalid\">" + escapeHtml(result.message()) + "</span>";
        }
        if (result.registered()) {
            return "<div class=\"registered\">"
                    + "<span>" + escapeHtml(result.message()) + "</span><br>"
                    + "<a href=\"/portal/forgot-password\">Recuperar contraseña</a>"
                    + "</div>";
        }
        return "<span class=\"valid\">✓ " + escapeHtml(result.message()) + "</span>";
    }

    @POST
    @Path("/register/step1")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response registerStep1(@FormParam("ruc") String ruc,
            @FormParam("legalName") String legalName,
            @FormParam("email") String email,
            @FormParam("password") String password,
            @FormParam("confirmPassword") String confirmPassword) {

        var result = registrationService.saveStep1(ruc, legalName, email, password, confirmPassword);

        if (!result.success()) {
            return Response.ok(
                    register.data("error", result.error())
                            .data("ruc", ruc)
                            .data("legalName", legalName)
                            .data("email", email))
                    .build();
        }

        // Paso 1 completado — redirigir a paso 2 con registrationId en cookie
        return Response.seeOther(URI.create("/portal/register/step2"))
                .cookie(new NewCookie.Builder("KEY49_REG")
                        .value(result.registrationId())
                        .path("/portal/register")
                        .httpOnly(true)
                        .secure(secureCookie)
                        .sameSite(NewCookie.SameSite.STRICT)
                        .maxAge(1800)
                        .build())
                .build();
    }

    // ── Register Step 2: Certificate ──
    @GET
    @Path("/register/step2")
    @Produces(MediaType.TEXT_HTML)
    public Response registerStep2Page(@QueryParam("error") String error) {
        var regId = getRegistrationId();
        if (regId == null) {
            return Response.seeOther(URI.create("/portal/register?error=session_expired")).build();
        }
        var data = registrationService.getRegistrationData(regId);
        if (data == null) {
            return Response.seeOther(URI.create("/portal/register?error=session_expired")).build();
        }
        return Response.ok(registerStep2.data("error", error)
                .data("certMeta", null)
                .data("certExpires", null)
                .data("environment", null)).build();
    }

    @POST
    @Path("/register/step2")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.TEXT_HTML)
    public Response registerStep2Upload(@RestForm("certificate") FileUpload certificate,
            @RestForm("certPassword") String certPassword,
            @RestForm("environment") String environment) {
        var regId = getRegistrationId();
        if (regId == null) {
            return Response.seeOther(URI.create("/portal/register?error=session_expired")).build();
        }

        // Leer bytes del archivo subido
        byte[] p12Bytes;
        try {
            if (certificate == null || certificate.filePath() == null) {
                return Response.ok(registerStep2.data("error", "Debe seleccionar un archivo de certificado .p12")
                        .data("certMeta", null)
                        .data("certExpires", null)
                        .data("environment", environment)).build();
            }
            p12Bytes = Files.readAllBytes(certificate.filePath());
        } catch (java.io.IOException e) {
            return Response.ok(registerStep2.data("error", "Error al leer el archivo subido")
                    .data("certMeta", null)
                    .data("certExpires", null)
                    .data("environment", environment)).build();
        }

        var result = registrationService.saveStep2(regId, p12Bytes, certPassword, environment);

        if (!result.success()) {
            return Response.ok(registerStep2.data("error", result.error())
                    .data("certMeta", null)
                    .data("certExpires", null)
                    .data("environment", environment)).build();
        }

        // Mostrar resumen del certificado antes de continuar al paso 3
        var meta = result.metadata();
        var certExpires = DateTimeFormatter.ofPattern("dd/MM/yyyy")
                .withZone(ZoneId.of("America/Guayaquil"))
                .format(meta.expiresAt());

        return Response.ok(registerStep2.data("error", null)
                .data("certMeta", meta)
                .data("certExpires", certExpires)
                .data("environment", environment)).build();
    }

    // ── Register Step 3: SMTP & Webhook ──
    @GET
    @Path("/register/step3")
    @Produces(MediaType.TEXT_HTML)
    public Response registerStep3Page(@QueryParam("error") String error) {
        var regId = getRegistrationId();
        if (regId == null) {
            return Response.seeOther(URI.create("/portal/register?error=session_expired")).build();
        }
        var data = registrationService.getRegistrationData(regId);
        if (data == null) {
            return Response.seeOther(URI.create("/portal/register?error=session_expired")).build();
        }
        return Response.ok(registerStep3.data("error", error)
                .data("smtpHost", data.getOrDefault("smtp_host", ""))
                .data("smtpPort", data.getOrDefault("smtp_port", ""))
                .data("smtpUser", data.getOrDefault("smtp_user", ""))
                .data("smtpFromEmail", data.getOrDefault("smtp_from_email", ""))
                .data("webhookUrl", data.getOrDefault("webhook_url", ""))).build();
    }

    @POST
    @Path("/register/step3")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response registerStep3(@FormParam("smtpHost") String smtpHost,
            @FormParam("smtpPort") String smtpPort,
            @FormParam("smtpUser") String smtpUser,
            @FormParam("smtpPassword") String smtpPassword,
            @FormParam("smtpFromEmail") String smtpFromEmail,
            @FormParam("webhookUrl") String webhookUrl) {
        var regId = getRegistrationId();
        if (regId == null) {
            return Response.seeOther(URI.create("/portal/register?error=session_expired")).build();
        }

        var result = registrationService.saveStep3(regId, smtpHost, smtpPort,
                smtpUser, smtpPassword, smtpFromEmail, webhookUrl);

        if (!result.success()) {
            return Response.ok(registerStep3.data("error", result.error())
                    .data("smtpHost", smtpHost != null ? smtpHost : "")
                    .data("smtpPort", smtpPort != null ? smtpPort : "")
                    .data("smtpUser", smtpUser != null ? smtpUser : "")
                    .data("smtpFromEmail", smtpFromEmail != null ? smtpFromEmail : "")
                    .data("webhookUrl", webhookUrl != null ? webhookUrl : "")).build();
        }

        return Response.seeOther(URI.create("/portal/register/step4")).build();
    }

    @POST
    @Path("/register/test-smtp")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public String testSmtpConnection(@FormParam("smtpHost") String smtpHost,
            @FormParam("smtpPort") String smtpPort,
            @FormParam("smtpUser") String smtpUser,
            @FormParam("smtpPassword") String smtpPassword,
            @FormParam("smtpFromEmail") String smtpFromEmail) {
        if (smtpHost == null || smtpHost.isBlank() || smtpPort == null || smtpPort.isBlank()) {
            return "<span class=\"error\">Ingrese host y puerto SMTP para probar la conexión</span>";
        }

        int port;
        try {
            port = Integer.parseInt(smtpPort.strip());
        } catch (NumberFormatException e) {
            return "<span class=\"error\">Puerto inválido</span>";
        }

        try (var socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(smtpHost.strip(), port), 5000);
            socket.setSoTimeout(5000);
            // Leer banner SMTP
            var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(socket.getInputStream()));
            var banner = reader.readLine();
            if (banner != null && banner.startsWith("220")) {
                return "<span class=\"success\">✓ Conexión SMTP exitosa (" + escapeHtml(smtpHost.strip()) + ":" + port + ")</span>";
            }
            return "<span class=\"error\">✗ Respuesta inesperada del servidor: " + escapeHtml(banner) + "</span>";
        } catch (Exception e) {
            log.debugf("SMTP test failed: %s", e.getMessage());
            return "<span class=\"error\">✗ No se pudo conectar: "
                    + escapeHtml(e.getMessage()) + "</span>";
        }
    }

    // ── Register Step 4: Confirmation & Creation ──
    @GET
    @Path("/register/step4")
    @Produces(MediaType.TEXT_HTML)
    public Response registerStep4Page(@QueryParam("error") String error) {
        var regId = getRegistrationId();
        if (regId == null) {
            return Response.seeOther(URI.create("/portal/register?error=session_expired")).build();
        }
        var data = registrationService.getRegistrationData(regId);
        if (data == null) {
            return Response.seeOther(URI.create("/portal/register?error=session_expired")).build();
        }

        var certExpiresRaw = data.get("cert_expires_at");
        String certExpires = "";
        if (certExpiresRaw != null) {
            certExpires = DateTimeFormatter.ofPattern("dd/MM/yyyy")
                    .withZone(ZoneId.of("America/Guayaquil"))
                    .format(Instant.parse(certExpiresRaw));
        }

        return Response.ok(registerStep4.data("error", error)
                .data("ruc", data.getOrDefault("ruc", ""))
                .data("legalName", data.getOrDefault("legal_name", ""))
                .data("email", data.getOrDefault("email", ""))
                .data("certSubject", data.getOrDefault("cert_subject", ""))
                .data("certExpires", certExpires)
                .data("environment", data.getOrDefault("environment", "TEST"))
                .data("smtpHost", data.getOrDefault("smtp_host", ""))
                .data("smtpPort", data.getOrDefault("smtp_port", ""))
                .data("smtpUser", data.getOrDefault("smtp_user", ""))
                .data("smtpFrom", data.getOrDefault("smtp_from_email", ""))
                .data("webhookUrl", data.getOrDefault("webhook_url", ""))).build();
    }

    @POST
    @Path("/register/step4")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response registerStep4() {
        var regId = getRegistrationId();
        if (regId == null) {
            return Response.seeOther(URI.create("/portal/register?error=session_expired")).build();
        }

        var result = registrationService.completeRegistration(regId);

        if (!result.success()) {
            return Response.seeOther(URI.create("/portal/register/step4?error="
                    + java.net.URLEncoder.encode(result.error(), java.nio.charset.StandardCharsets.UTF_8))).build();
        }

        // Limpiar cookie de registro
        var expiredCookie = new NewCookie.Builder("KEY49_REG")
                .value("")
                .path("/portal")
                .maxAge(0)
                .secure(secureCookie)
                .httpOnly(true)
                .sameSite(NewCookie.SameSite.LAX)
                .build();

        return Response.ok(registerSuccess.data("apiKey", result.rawApiKey()))
                .cookie(expiredCookie)
                .build();
    }

    private String getRegistrationId() {
        var cookies = requestContext.getCookies();
        var regCookie = cookies.get("KEY49_REG");
        if (regCookie == null || regCookie.getValue().isBlank()) {
            return null;
        }
        return regCookie.getValue();
    }

    private static String escapeHtml(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    // ── Dashboard ──
    @GET
    @Path("/")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance dashboardPage(
            @QueryParam("status") String status,
            @QueryParam("doc_type") String docType,
            @QueryParam("date_from") String dateFromStr,
            @QueryParam("date_to") String dateToStr,
            @QueryParam("q") String searchQuery,
            @QueryParam("page") @DefaultValue("1") int page) {

        var session = getSession();
        var perPage = DEFAULT_PER_PAGE;

        return tcm.withTenantSession(session.schemaName(), em -> {
            var hql = new StringBuilder("FROM Document d WHERE 1=1");
            var countHql = new StringBuilder("SELECT count(d) FROM Document d WHERE 1=1");

            var conditions = buildFilterConditions(status, docType, dateFromStr, dateToStr, searchQuery);
            hql.append(conditions);
            countHql.append(conditions);
            hql.append(" ORDER BY d.issueDate DESC, d.createdAt DESC");

            var query = em.createQuery(hql.toString(), Document.class)
                    .setFirstResult((page - 1) * perPage)
                    .setMaxResults(perPage);

            var countQuery = em.createQuery(countHql.toString(), Long.class);

            applyFilterParameters(query, status, docType, dateFromStr, dateToStr, searchQuery);
            applyFilterParameters(countQuery, status, docType, dateFromStr, dateToStr, searchQuery);

            var total = countQuery.getSingleResult();
            var docs = query.getResultList();

            return buildDashboard(session, docs, total, page, perPage,
                    status, docType, dateFromStr, dateToStr, searchQuery);
        });
    }

    // ── Document detail ──
    @GET
    @Path("/documents/{id}")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance documentDetail(@PathParam("id") UUID id,
            @QueryParam("retry") String retryResult) {
        var session = getSession();

        var doc = tcm.withTenantSession(session.schemaName(), em
                -> em.find(Document.class, id)
        );

        if (doc == null) {
            return detail.data("session", session)
                    .data("doc", null)
                    .data("error", "Documento no encontrado")
                    .data("retryResult", null)
                    .data("todayEc", null);
        }
        return detail.data("session", session)
                .data("doc", doc)
                .data("error", null)
                .data("retryResult", retryResult)
                .data("docTypeLabel", documentTypeLabel(doc.documentType))
                .data("statusLabel", statusLabel(doc.status))
                .data("statusClass", statusClass(doc.status))
                .data("timeline", buildTimeline(doc))
                .data("formatDate", DISPLAY_DATE)
                .data("formatDateTime", DISPLAY_DATETIME)
                .data("ecZone", Key49Constants.EC_ZONE)
                .data("todayEc", LocalDate.now(Key49Constants.EC_ZONE));
    }

    // ── Retry manual ──
    @POST
    @Path("/documents/{id}/retry")
    public Response retryDocument(@PathParam("id") UUID id,
            @Context HttpServerRequest httpRequest) {
        var session = getSession();
        var detailUri = URI.create("/portal/documents/" + id);

        var doc = tcm.withTenantSession(session.schemaName(), em
                -> em.find(Document.class, id)
        );

        if (doc == null) {
            return Response.seeOther(detailUri).build();
        }

        if (doc.status != DocumentStatus.FAILED) {
            log.warnf("Retry rejected: document %s is in status %s, expected FAILED", id, doc.status);
            return Response.seeOther(URI.create(detailUri + "?retry=invalid_status")).build();
        }

        var today = LocalDate.now(Key49Constants.EC_ZONE);
        if (!doc.issueDate.equals(today)) {
            log.warnf("Retry rejected: document %s issue_date %s != today %s", id, doc.issueDate, today);
            return Response.seeOther(URI.create(detailUri + "?retry=invalid_date")).build();
        }

        tcm.withTenantTransaction(session.schemaName(), em -> {
            var managed = em.find(Document.class, id);
            managed.transitionTo(DocumentStatus.CREATED);
            managed.retryCount = 0;
            managed.nextRetryAt = null;
            managed.updatedAt = Instant.now();
            return null;
        });

        eventProducer.sendToSign(DocumentEvent.of(id, session.schemaName(), "SIGN"));

        auditService.record(session.tenantId(), "portal", "portal.retry",
                "document", id, AuditService.resolveIp(httpRequest), null);

        log.infof("Manual retry initiated: documentId=%s, tenant=%s", id, session.schemaName());

        return Response.seeOther(URI.create(detailUri + "?retry=ok")).build();
    }

    // ── HTMX partial: status badge refresh ──
    @GET
    @Path("/documents/{id}/status")
    @Produces(MediaType.TEXT_HTML)
    public String documentStatusBadge(@PathParam("id") UUID id) {
        var session = getSession();

        var doc = tcm.withTenantSession(session.schemaName(), em
                -> em.find(Document.class, id)
        );

        if (doc == null) {
            return "<span>—</span>";
        }
        var label = statusLabel(doc.status);
        var cls = statusClass(doc.status);
        return "<mark class=\"%s\">%s</mark>".formatted(cls, label);
    }

    // ── Document downloads ──
    @GET
    @Path("/documents/{id}/xml")
    @Produces(MediaType.APPLICATION_XML)
    public Response downloadXml(@PathParam("id") UUID id) {
        var session = getSession();
        var doc = tcm.withTenantSession(session.schemaName(), em -> em.find(Document.class, id));
        if (doc == null || doc.authorizedXmlPath == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .type(MediaType.TEXT_PLAIN)
                    .entity("XML autorizado no disponible")
                    .build();
        }
        byte[] bytes = storageService.retrieve(doc.authorizedXmlPath);
        return Response.ok(bytes)
                .type(MediaType.APPLICATION_XML)
                .header("Content-Disposition",
                        "attachment; filename=\"%s.xml\"".formatted(
                                doc.accessKey != null ? doc.accessKey : doc.id))
                .build();
    }

    @GET
    @Path("/documents/{id}/ride")
    @Produces("application/pdf")
    public Response downloadRide(@PathParam("id") UUID id) {
        var session = getSession();
        var doc = tcm.withTenantSession(session.schemaName(), em -> em.find(Document.class, id));
        if (doc == null || doc.ridePath == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .type(MediaType.TEXT_PLAIN)
                    .entity("RIDE no disponible")
                    .build();
        }
        byte[] bytes = storageService.retrieve(doc.ridePath);
        return Response.ok(bytes)
                .type("application/pdf")
                .header("Content-Disposition",
                        "attachment; filename=\"%s.pdf\"".formatted(
                                doc.accessKey != null ? doc.accessKey : doc.id))
                .build();
    }

    // ── Metrics dashboard ──
    @GET
    @Path("/metrics")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance metricsPage() {
        var session = getSession();

        // 1. Document counts by status group
        var statusCounts = tcm.withTenantSession(session.schemaName(), em -> {
            @SuppressWarnings("unchecked")
            var rows = (List<Object[]>) em.createNativeQuery(
                    "SELECT status, count(*) FROM documents GROUP BY status").getResultList();
            long authorized = 0, inProcess = 0, failed = 0, total = 0;
            for (var row : rows) {
                var status = (String) row[0];
                var count = ((Number) row[1]).longValue();
                total += count;
                switch (status) {
                    case "AUTHORIZED", "NOTIFIED" ->
                        authorized += count;
                    case "REJECTED", "FAILED" ->
                        failed += count;
                    default ->
                        inProcess += count;
                }
            }
            return Map.of("authorized", authorized, "inProcess", inProcess,
                    "failed", failed, "total", total);
        });

        // 2. Documents per day (last 30 days)
        var dailyData = tcm.withTenantSession(session.schemaName(), em -> {
            @SuppressWarnings("unchecked")
            var rows = (List<Object[]>) em.createNativeQuery("""
                    SELECT issue_date, count(*) FROM documents
                    WHERE issue_date >= (current_date - interval '29 days')
                    GROUP BY issue_date ORDER BY issue_date""").getResultList();
            var entries = new ArrayList<DailyCount>();
            long maxCount = 1;
            for (var row : rows) {
                var date = row[0] instanceof java.sql.Date sd ? sd.toLocalDate() : (LocalDate) row[0];
                var count = ((Number) row[1]).longValue();
                entries.add(new DailyCount(date, count, 0));
                if (count > maxCount) {
                    maxCount = count;
                }
            }
            for (int i = 0; i < entries.size(); i++) {
                var e = entries.get(i);
                entries.set(i, new DailyCount(e.date, e.count,
                        (int) (e.count * 100 / maxCount)));
            }
            return entries;
        });

        // 3. Last document
        var lastDoc = tcm.withTenantSession(session.schemaName(), em -> {
            var list = em.createQuery(
                    "FROM Document d ORDER BY d.createdAt DESC", Document.class)
                    .setMaxResults(1)
                    .getResultList();
            return list.isEmpty() ? null : list.getFirst();
        });

        // 4. Certificate status
        var tenant = entityManager.find(Tenant.class, session.tenantId());
        String certStatus;
        long certDaysLeft = -1;
        if (tenant == null || tenant.certificateExpiration == null) {
            certStatus = "none";
        } else {
            certDaysLeft = Duration.between(Instant.now(), tenant.certificateExpiration).toDays();
            if (certDaysLeft < 0) {
                certStatus = "expired";
            } else if (certDaysLeft <= 30) {
                certStatus = "expiring";
            } else {
                certStatus = "valid";
            }
        }

        return metrics.data("session", session)
                .data("authorized", statusCounts.get("authorized"))
                .data("inProcess", statusCounts.get("inProcess"))
                .data("failed", statusCounts.get("failed"))
                .data("total", statusCounts.get("total"))
                .data("dailyData", dailyData)
                .data("lastDoc", lastDoc)
                .data("lastDocStatusLabel", lastDoc != null ? statusLabel(lastDoc.status) : null)
                .data("lastDocStatusClass", lastDoc != null ? statusClass(lastDoc.status) : null)
                .data("lastDocTypeLabel", lastDoc != null ? documentTypeLabel(lastDoc.documentType) : null)
                .data("certStatus", certStatus)
                .data("certDaysLeft", certDaysLeft)
                .data("certExpiration", tenant != null ? tenant.certificateExpiration : null)
                .data("formatDate", DISPLAY_DATE)
                .data("formatDateTime", DISPLAY_DATETIME)
                .data("ecZone", Key49Constants.EC_ZONE);
    }

    record DailyCount(LocalDate date, long count, int pct) {

    }

    // ── Helpers ──
    private PortalSessionService.PortalSession getSession() {
        return (PortalSessionService.PortalSession) requestContext.getProperty(PortalAuthFilter.PORTAL_SESSION_ATTR);
    }

    private String buildFilterConditions(String status, String docType, String dateFrom, String dateTo, String q) {
        var sb = new StringBuilder();
        if (status != null && !status.isBlank()) {
            sb.append(" AND d.status = :status");
        }
        if (docType != null && !docType.isBlank()) {
            sb.append(" AND d.documentType = :docType");
        }
        if (dateFrom != null && !dateFrom.isBlank()) {
            sb.append(" AND d.issueDate >= :dateFrom");
        }
        if (dateTo != null && !dateTo.isBlank()) {
            sb.append(" AND d.issueDate <= :dateTo");
        }
        if (q != null && !q.isBlank()) {
            sb.append(" AND (d.recipientName LIKE :q OR d.recipientId LIKE :qExact OR d.accessKey LIKE :qExact)");
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private void applyFilterParameters(Object query, String status, String docType, String dateFrom, String dateTo, String q) {
        var jpaQuery = (jakarta.persistence.Query) query;
        if (status != null && !status.isBlank()) {
            jpaQuery.setParameter("status", DocumentStatus.valueOf(status));
        }
        if (docType != null && !docType.isBlank()) {
            jpaQuery.setParameter("docType", docType);
        }
        if (dateFrom != null && !dateFrom.isBlank()) {
            jpaQuery.setParameter("dateFrom", LocalDate.parse(dateFrom));
        }
        if (dateTo != null && !dateTo.isBlank()) {
            jpaQuery.setParameter("dateTo", LocalDate.parse(dateTo));
        }
        if (q != null && !q.isBlank()) {
            jpaQuery.setParameter("q", "%" + q + "%");
            jpaQuery.setParameter("qExact", q);
        }
    }

    private TemplateInstance buildDashboard(
            PortalSessionService.PortalSession session,
            List<Document> docs, long total, int page, int perPage,
            String status, String docType, String dateFrom, String dateTo, String q) {

        int totalPages = (int) Math.ceil((double) total / perPage);

        return dashboard.data("session", session)
                .data("docs", docs)
                .data("total", total)
                .data("page", page)
                .data("perPage", perPage)
                .data("totalPages", totalPages)
                .data("filterStatus", status)
                .data("filterDocType", docType)
                .data("filterDateFrom", dateFrom)
                .data("filterDateTo", dateTo)
                .data("filterQ", q)
                .data("statuses", DocumentStatus.values())
                .data("documentTypes", DocumentType.values())
                .data("docTypeLabels", docTypeLabelsMap())
                .data("formatDate", DISPLAY_DATE)
                .data("ecZone", Key49Constants.EC_ZONE);
    }

    record TimelineEntry(String label, String date, boolean completed, boolean active) {

    }

    private List<TimelineEntry> buildTimeline(Document doc) {
        var ecZone = Key49Constants.EC_ZONE;
        var fmt = DISPLAY_DATETIME;
        return List.of(
                entry("Creado", doc.createdAt, ecZone, fmt, true, doc.status == DocumentStatus.CREATED),
                entry("Firmado", doc.status.ordinal() >= DocumentStatus.SIGNED.ordinal() ? doc.updatedAt : null,
                        ecZone, fmt, doc.status.ordinal() >= DocumentStatus.SIGNED.ordinal(),
                        doc.status == DocumentStatus.SIGNED),
                entry("Enviado", doc.sriSubmissionDate, ecZone, fmt,
                        doc.status.ordinal() >= DocumentStatus.SENT.ordinal(),
                        doc.status == DocumentStatus.SENT),
                entry("Recibido", doc.status.ordinal() >= DocumentStatus.RECEIVED.ordinal() ? doc.sriSubmissionDate : null,
                        ecZone, fmt, doc.status.ordinal() >= DocumentStatus.RECEIVED.ordinal(),
                        doc.status == DocumentStatus.RECEIVED),
                entry("Autorizado", doc.authorizationDate, ecZone, fmt,
                        doc.status.ordinal() >= DocumentStatus.AUTHORIZED.ordinal(),
                        doc.status == DocumentStatus.AUTHORIZED),
                entry("Notificado", doc.status == DocumentStatus.NOTIFIED ? doc.emailSentAt : null,
                        ecZone, fmt, doc.status == DocumentStatus.NOTIFIED,
                        doc.status == DocumentStatus.NOTIFIED)
        );
    }

    private TimelineEntry entry(String label, java.time.Instant instant, ZoneId zone,
            DateTimeFormatter fmt, boolean completed, boolean active) {
        var dateStr = instant != null ? fmt.format(instant.atZone(zone)) : "—";
        return new TimelineEntry(label, dateStr, completed, active);
    }

    private static Map<String, String> docTypeLabelsMap() {
        return Arrays.stream(DocumentType.values())
                .collect(Collectors.toMap(DocumentType::sriCode, DocumentType::description));
    }

    static String documentTypeLabel(String code) {
        return switch (code) {
            case "01" ->
                "Factura";
            case "03" ->
                "Liquidación de Compra";
            case "04" ->
                "Nota de Crédito";
            case "05" ->
                "Nota de Débito";
            case "06" ->
                "Guía de Remisión";
            case "07" ->
                "Retención";
            default ->
                code;
        };
    }

    static String statusLabel(DocumentStatus s) {
        return switch (s) {
            case CREATED ->
                "Creado";
            case SIGNED ->
                "Firmado";
            case SENT ->
                "Enviado";
            case RECEIVED ->
                "Recibido";
            case AUTHORIZED ->
                "Autorizado";
            case NOTIFIED ->
                "Notificado";
            case REJECTED ->
                "Rechazado";
            case FAILED ->
                "Fallido";
            case RETRY ->
                "Reintentando";
            case VOIDED ->
                "Anulado";
        };
    }

    static String statusClass(DocumentStatus s) {
        return switch (s) {
            case AUTHORIZED, NOTIFIED ->
                "status-ok";
            case REJECTED, FAILED ->
                "status-error";
            case VOIDED ->
                "status-void";
            case RETRY ->
                "status-warn";
            default ->
                "status-pending";
        };
    }
}
