package auracore.key49.api.resource;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Optional;
import java.util.UUID;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import auracore.key49.api.dto.ApiResponse;
import auracore.key49.api.dto.CertificateStatusResponse;
import auracore.key49.api.dto.CreateTenantRequest;
import auracore.key49.api.dto.PagedResponse;
import auracore.key49.api.dto.SmtpConfigRequest;
import auracore.key49.api.dto.SmtpConfigResponse;
import auracore.key49.api.dto.TenantResponse;
import auracore.key49.api.dto.UpdateTenantRequest;
import auracore.key49.core.service.AuditService;
import auracore.key49.core.service.TenantAdminService;
import auracore.key49.core.service.TenantAdminService.CreateTenantData;
import auracore.key49.core.service.TenantAdminService.UpdateTenantData;
import auracore.key49.signer.CertificateEncryptor;
import auracore.key49.signer.CertificateMetadataExtractor;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Endpoints de administración de tenants.
 *
 * <p>
 * Estos endpoints son para uso interno del administrador de la plataforma. La
 * creación del esquema PostgreSQL se realiza automáticamente mediante
 * {@code clone_schema('tenant_template', schema)} al crear un tenant.
 */
@Path("/v1/admin/tenants")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TenantAdminResource {

    @Inject
    Logger log;

    @Inject
    TenantAdminService tenantService;

    @Inject
    AuditService auditService;

    @Inject
    auracore.key49.notify.email.SmtpClientFactory smtpClientFactory;

    @Inject
    Vertx vertx;

    @ConfigProperty(name = "key49.master-key")
    Optional<String> masterKeyBase64;

    /**
     * POST /v1/admin/tenants — Registrar un nuevo tenant.
     */
    @POST
    public Response create(CreateTenantRequest request,
            @Context HttpServerRequest httpRequest) {
        String requestId = generateRequestId();

        var data = new CreateTenantData(
                request.ruc(), request.legalName(), request.tradeName(),
                request.mainAddress(), request.requiredAccounting(),
                request.specialTaxpayer(), request.microEnterpriseRegime(),
                request.withholdingAgent(), request.environment(),
                request.schemaName());

        var tenant = tenantService.create(data);

        auditService.record(tenant.id, "admin", "tenant.created", "tenant",
                tenant.id, AuditService.resolveIp(httpRequest),
                """
                {"ruc":"%s","schema":"%s"}""".formatted(tenant.ruc, tenant.schemaName));

        var body = ApiResponse.of(TenantResponse.fromEntity(tenant), requestId);
        return Response.status(Response.Status.CREATED)
                .header("X-Request-Id", requestId)
                .entity(body)
                .build();
    }

    /**
     * GET /v1/admin/tenants — Listar tenants con paginación.
     */
    @GET
    public Response list(
            @QueryParam("status") String status,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("per_page") @DefaultValue("20") int perPage) {

        String requestId = generateRequestId();
        var statusFilter = Optional.ofNullable(status).filter(s -> !s.isBlank());

        var result = tenantService.listAll(page, perPage, statusFilter);
        var responses = result.items().stream()
                .map(TenantResponse::fromEntity)
                .toList();
        var body = PagedResponse.of(responses, result.total(), page, perPage);
        return Response.ok()
                .header("X-Request-Id", requestId)
                .entity(body)
                .build();
    }

    /**
     * GET /v1/admin/tenants/:id — Obtener tenant por ID.
     */
    @GET
    @Path("/{id}")
    public Response getById(@PathParam("id") UUID id) {
        String requestId = generateRequestId();
        var tenant = tenantService.findById(id);
        var body = ApiResponse.of(TenantResponse.fromEntity(tenant), requestId);
        return Response.ok()
                .header("X-Request-Id", requestId)
                .entity(body)
                .build();
    }

    /**
     * PUT /v1/admin/tenants/:id — Actualizar tenant.
     */
    @PUT
    @Path("/{id}")
    public Response update(@PathParam("id") UUID id, UpdateTenantRequest request,
            @Context HttpServerRequest httpRequest) {
        String requestId = generateRequestId();

        var data = new UpdateTenantData(
                request.legalName(), request.tradeName(), request.mainAddress(),
                request.requiredAccounting(), request.specialTaxpayer(),
                request.microEnterpriseRegime(), request.withholdingAgent(),
                request.environment(), request.webhookUrl(), request.webhookSecret(),
                request.rateLimitRpm(), request.rateLimitWriteRpm(),
                request.rateLimitReadRpm(), request.emailSenderName(),
                request.replyEmail(), request.status());

        var tenant = tenantService.update(id, data);

        auditService.record(id, "admin", "tenant.updated", "tenant",
                id, AuditService.resolveIp(httpRequest), null);

        var body = ApiResponse.of(TenantResponse.fromEntity(tenant), requestId);
        return Response.ok()
                .header("X-Request-Id", requestId)
                .entity(body)
                .build();
    }

    /**
     * POST /v1/admin/tenants/:id/certificate — Subir certificado .p12.
     */
    @POST
    @Path("/{id}/certificate")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response uploadCertificate(
            @PathParam("id") UUID id,
            @RestForm("certificate") FileUpload certificate,
            @RestForm("password") String password,
            @Context HttpServerRequest httpRequest) {

        String requestId = generateRequestId();

        if (certificate == null) {
            return errorResponse(requestId, "VALIDATION_ERROR",
                    "Certificate file is required", 400);
        }
        if (password == null || password.isBlank()) {
            return errorResponse(requestId, "VALIDATION_ERROR",
                    "Certificate password is required", 400);
        }

        byte[] p12Bytes;
        try {
            p12Bytes = Files.readAllBytes(certificate.filePath());
        } catch (IOException e) {
            return errorResponse(requestId, "VALIDATION_ERROR",
                    "Failed to read certificate file", 400);
        }

        // Verificar y extraer metadata
        var passwordChars = password.toCharArray();
        CertificateMetadataExtractor.CertificateMetadata metadata;
        try {
            metadata = CertificateMetadataExtractor.extract(p12Bytes, passwordChars);
        } catch (auracore.key49.signer.SigningException e) {
            log.warnf("Invalid certificate upload for tenant %s: %s", id, e.getMessage());
            return errorResponse(requestId, "INVALID_CERTIFICATE",
                    "Invalid certificate: " + e.getMessage(), 422);
        }

        if (!metadata.valid()) {
            return errorResponse(requestId, "CERTIFICATE_EXPIRED",
                    "Certificate is expired (expired at " + metadata.expiresAt() + ")", 422);
        }

        // Cifrar .p12 y contraseña con master key
        var masterKey = CertificateEncryptor.decodeMasterKey(
                masterKeyBase64.orElseThrow(()
                        -> new IllegalStateException("KEY49_MASTER_KEY not configured")));
        var encryptedP12 = CertificateEncryptor.encrypt(p12Bytes, masterKey);
        var encryptedPassword = CertificateEncryptor.encryptPassword(passwordChars, masterKey);

        var tenant = tenantService.uploadCertificate(
                id, encryptedP12, encryptedPassword,
                metadata.subject(), metadata.expiresAt(), metadata.serial());

        auditService.record(id, "admin", "certificate.uploaded", "certificate",
                id, AuditService.resolveIp(httpRequest),
                """
                {"subject":"%s","expires_at":"%s"}""".formatted(
                        metadata.subject(), metadata.expiresAt()));

        var certResponse = new CertificateStatusResponse(
                metadata.subject(), metadata.serial(),
                metadata.expiresAt(), metadata.issuer(),
                metadata.valid(), metadata.daysUntilExpiration());
        var body = ApiResponse.of(certResponse, requestId);
        return Response.ok()
                .header("X-Request-Id", requestId)
                .entity(body)
                .build();
    }

    /**
     * GET /v1/admin/tenants/:id/certificate/status — Estado del certificado.
     */
    @GET
    @Path("/{id}/certificate/status")
    public Response certificateStatus(@PathParam("id") UUID id) {
        String requestId = generateRequestId();

        var tenant = tenantService.findById(id);
        if (tenant.certificateP12 == null || tenant.certificateP12.length == 0) {
            return errorResponse(requestId, "CERTIFICATE_NOT_CONFIGURED",
                    "No certificate configured for this tenant", 422);
        }

        boolean valid = tenant.certificateExpiration != null
                && tenant.certificateExpiration.isAfter(java.time.Instant.now());
        long daysUntilExp = tenant.certificateExpiration != null
                ? java.time.Duration.between(java.time.Instant.now(), tenant.certificateExpiration).toDays()
                : 0;

        var certResponse = new CertificateStatusResponse(
                tenant.certificateSubject, tenant.certificateSerial,
                tenant.certificateExpiration, null, valid, daysUntilExp,
                buildPendingCert(tenant));
        var body = ApiResponse.of(certResponse, requestId);
        return Response.ok()
                .header("X-Request-Id", requestId)
                .entity(body)
                .build();
    }

    /**
     * POST /v1/admin/tenants/:id/certificate/rotate — Sube certificado
     * pendiente sin reemplazar el activo.
     */
    @POST
    @Path("/{id}/certificate/rotate")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response rotateCertificate(
            @PathParam("id") UUID id,
            @RestForm("certificate") FileUpload certificate,
            @RestForm("password") String password,
            @Context HttpServerRequest httpRequest) {

        String requestId = generateRequestId();

        if (certificate == null) {
            return errorResponse(requestId, "VALIDATION_ERROR",
                    "Certificate file is required", 400);
        }
        if (password == null || password.isBlank()) {
            return errorResponse(requestId, "VALIDATION_ERROR",
                    "Certificate password is required", 400);
        }

        byte[] p12Bytes;
        try {
            p12Bytes = Files.readAllBytes(certificate.filePath());
        } catch (IOException e) {
            return errorResponse(requestId, "VALIDATION_ERROR",
                    "Failed to read certificate file", 400);
        }

        var passwordChars = password.toCharArray();
        CertificateMetadataExtractor.CertificateMetadata metadata;
        try {
            metadata = CertificateMetadataExtractor.extract(p12Bytes, passwordChars);
        } catch (auracore.key49.signer.SigningException e) {
            log.warnf("Invalid certificate rotation for tenant %s: %s", id, e.getMessage());
            return errorResponse(requestId, "INVALID_CERTIFICATE",
                    "Invalid certificate: " + e.getMessage(), 422);
        }

        if (!metadata.valid()) {
            return errorResponse(requestId, "CERTIFICATE_EXPIRED",
                    "Certificate is expired (expired at " + metadata.expiresAt() + ")", 422);
        }

        var masterKey = CertificateEncryptor.decodeMasterKey(
                masterKeyBase64.orElseThrow(()
                        -> new IllegalStateException("KEY49_MASTER_KEY not configured")));
        var encryptedP12 = CertificateEncryptor.encrypt(p12Bytes, masterKey);
        var encryptedPassword = CertificateEncryptor.encryptPassword(passwordChars, masterKey);

        tenantService.rotateCertificate(
                id, encryptedP12, encryptedPassword,
                metadata.subject(), metadata.expiresAt(), metadata.serial());

        auditService.record(id, "admin", "certificate.rotated", "certificate",
                id, AuditService.resolveIp(httpRequest),
                """
                {"subject":"%s","expires_at":"%s"}""".formatted(
                        metadata.subject(), metadata.expiresAt()));

        var certResponse = new CertificateStatusResponse(
                metadata.subject(), metadata.serial(),
                metadata.expiresAt(), metadata.issuer(),
                metadata.valid(), metadata.daysUntilExpiration());
        var body = ApiResponse.of(certResponse, requestId);
        return Response.ok()
                .header("X-Request-Id", requestId)
                .entity(body)
                .build();
    }

    /**
     * POST /v1/admin/tenants/:id/certificate/activate — Activa el certificado
     * pendiente y reemplaza el activo.
     */
    @POST
    @Path("/{id}/certificate/activate")
    public Response activateCertificate(
            @PathParam("id") UUID id,
            @Context HttpServerRequest httpRequest) {

        String requestId = generateRequestId();
        var tenant = tenantService.activateCertificate(id);

        auditService.record(id, "admin", "certificate.activated", "certificate",
                id, AuditService.resolveIp(httpRequest),
                """
                {"subject":"%s","expires_at":"%s"}""".formatted(
                        tenant.certificateSubject, tenant.certificateExpiration));

        boolean valid = tenant.certificateExpiration != null
                && tenant.certificateExpiration.isAfter(java.time.Instant.now());
        long daysUntilExp = tenant.certificateExpiration != null
                ? java.time.Duration.between(java.time.Instant.now(), tenant.certificateExpiration).toDays()
                : 0;

        var certResponse = new CertificateStatusResponse(
                tenant.certificateSubject, tenant.certificateSerial,
                tenant.certificateExpiration, null, valid, daysUntilExp);
        var body = ApiResponse.of(certResponse, requestId);
        return Response.ok()
                .header("X-Request-Id", requestId)
                .entity(body)
                .build();
    }

    /**
     * PUT /v1/admin/tenants/:id/smtp — Configurar SMTP personalizado.
     */
    @PUT
    @Path("/{id}/smtp")
    public Response updateSmtpConfig(
            @PathParam("id") UUID id,
            SmtpConfigRequest request,
            @Context HttpServerRequest httpRequest) {

        String requestId = generateRequestId();

        if (request.port() != null && (request.port() < 1 || request.port() > 65535)) {
            return errorResponse(requestId, "VALIDATION_ERROR",
                    "SMTP port must be between 1 and 65535", 400);
        }

        byte[] encryptedPassword = null;
        if (request.password() != null && !request.password().isBlank()) {
            var masterKey = CertificateEncryptor.decodeMasterKey(
                    masterKeyBase64.orElseThrow(()
                            -> new IllegalStateException("KEY49_MASTER_KEY not configured")));
            var passwordChars = request.password().toCharArray();
            encryptedPassword = CertificateEncryptor.encryptPassword(passwordChars, masterKey);
            java.util.Arrays.fill(passwordChars, (char) 0);
        }

        var tenant = tenantService.updateSmtpConfig(
                id, request.host(), request.port(), request.user(),
                encryptedPassword, request.fromAddress(), request.enabled(),
                request.emailNotificationsEnabled());

        // Invalidate cached SMTP client if config changed
        smtpClientFactory.invalidate(id);

        auditService.record(id, "admin", "smtp.configured", "tenant",
                id, AuditService.resolveIp(httpRequest),
                """
                {"smtp_host":"%s","smtp_port":%s,"smtp_enabled":%s,"email_notifications":%s}""".formatted(
                        tenant.smtpHost, tenant.smtpPort, tenant.smtpEnabled,
                        tenant.emailNotificationsEnabled));

        var smtpResponse = new SmtpConfigResponse(
                tenant.smtpHost, tenant.smtpPort, tenant.smtpUser,
                tenant.smtpPasswordEnc != null && tenant.smtpPasswordEnc.length > 0,
                tenant.smtpFrom, tenant.smtpEnabled, tenant.emailNotificationsEnabled);
        var body = ApiResponse.of(smtpResponse, requestId);
        return Response.ok()
                .header("X-Request-Id", requestId)
                .entity(body)
                .build();
    }

    /**
     * POST /v1/admin/tenants/:id/smtp/test — Enviar email de prueba con la
     * configuración SMTP del tenant.
     */
    @POST
    @Path("/{id}/smtp/test")
    public Response testSmtp(
            @PathParam("id") UUID id,
            @Context HttpServerRequest httpRequest) {

        String requestId = generateRequestId();
        var tenant = tenantService.findById(id);

        if (tenant.smtpHost == null || tenant.smtpHost.isBlank()) {
            return errorResponse(requestId, "SMTP_NOT_CONFIGURED",
                    "SMTP is not configured for this tenant", 422);
        }

        // Test connection by opening a socket to smtp_host:smtp_port
        try (var socket = new java.net.Socket()) {
            socket.connect(
                    new java.net.InetSocketAddress(tenant.smtpHost, tenant.smtpPort),
                    5000);
        } catch (java.io.IOException e) {
            log.warnf("SMTP connection test failed for tenant %s: %s:%d - %s",
                    id, tenant.smtpHost, tenant.smtpPort, e.getMessage());
            return errorResponse(requestId, "SMTP_CONNECTION_FAILED",
                    "Cannot connect to %s:%d — %s".formatted(
                            tenant.smtpHost, tenant.smtpPort, e.getMessage()),
                    422);
        }

        // Send test email if reply_email is configured
        if (tenant.replyEmail != null && !tenant.replyEmail.isBlank()) {
            try {
                sendTestEmail(tenant);
                log.infof("SMTP test email sent | tenant=%s to=%s via=%s:%d",
                        id, tenant.replyEmail, tenant.smtpHost, tenant.smtpPort);
            } catch (Exception e) {
                log.warnf(e, "SMTP test email failed for tenant %s", id);
                return errorResponse(requestId, "SMTP_SEND_FAILED",
                        "Connection OK but email send failed: " + e.getMessage(), 422);
            }
        }

        auditService.record(id, "admin", "smtp.tested", "tenant",
                id, AuditService.resolveIp(httpRequest),
                """
                {"smtp_host":"%s","smtp_port":%d,"result":"success"}""".formatted(
                        tenant.smtpHost, tenant.smtpPort));

        record SmtpTestResult(boolean success, String message) {

        }
        var result = new SmtpTestResult(true,
                tenant.replyEmail != null
                        ? "Connection OK. Test email sent to " + tenant.replyEmail
                        : "Connection OK. No reply_email configured to send test email.");
        var body = ApiResponse.of(result, requestId);
        return Response.ok()
                .header("X-Request-Id", requestId)
                .entity(body)
                .build();
    }

    private void sendTestEmail(auracore.key49.core.model.Tenant tenant) {
        var masterKey = CertificateEncryptor.decodeMasterKey(
                masterKeyBase64.orElseThrow(()
                        -> new IllegalStateException("KEY49_MASTER_KEY not configured")));

        String smtpPassword = null;
        if (tenant.smtpPasswordEnc != null && tenant.smtpPasswordEnc.length > 0) {
            var passwordChars = CertificateEncryptor.decryptPassword(tenant.smtpPasswordEnc, masterKey);
            smtpPassword = new String(passwordChars);
            java.util.Arrays.fill(passwordChars, (char) 0);
        }

        var config = new io.vertx.ext.mail.MailConfig()
                .setHostname(tenant.smtpHost)
                .setPort(tenant.smtpPort);

        if (tenant.smtpPort == 465) {
            config.setSsl(true);
        } else if (tenant.smtpPort == 587) {
            config.setStarttls(io.vertx.ext.mail.StartTLSOptions.REQUIRED);
        }

        if (tenant.smtpUser != null && smtpPassword != null) {
            config.setUsername(tenant.smtpUser);
            config.setPassword(smtpPassword);
        }

        var from = tenant.smtpFrom != null ? tenant.smtpFrom : "noreply@key49.ec";
        var mailMessage = new io.vertx.ext.mail.MailMessage()
                .setFrom(from)
                .setTo(java.util.List.of(tenant.replyEmail))
                .setSubject("Key49 — Test de configuración SMTP")
                .setText("Este es un email de prueba desde Key49.\n\n"
                        + "Si recibiste este mensaje, la configuración SMTP de tu tenant es correcta.\n\n"
                        + "Servidor: " + tenant.smtpHost + ":" + tenant.smtpPort + "\n"
                        + "Fecha: " + java.time.Instant.now());

        var client = io.vertx.ext.mail.MailClient.create(vertx, config);
        try {
            client.sendMail(mailMessage).toCompletionStage().toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Failed to send test email: " + e.getMessage(), e);
        } finally {
            client.close();
        }
    }

    private static CertificateStatusResponse.PendingCertificate buildPendingCert(
            auracore.key49.core.model.Tenant tenant) {
        if (tenant.pendingCertificateP12 == null) {
            return null;
        }
        boolean valid = tenant.pendingCertificateExpiration != null
                && tenant.pendingCertificateExpiration.isAfter(java.time.Instant.now());
        long days = tenant.pendingCertificateExpiration != null
                ? java.time.Duration.between(java.time.Instant.now(),
                        tenant.pendingCertificateExpiration).toDays()
                : 0;
        return new CertificateStatusResponse.PendingCertificate(
                tenant.pendingCertificateSubject, tenant.pendingCertificateSerial,
                tenant.pendingCertificateExpiration, valid, days);
    }

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
