package auracore.key49.api.portal;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import auracore.key49.api.portal.PortalSessionService.PortalSession;
import auracore.key49.core.Key49Constants;
import auracore.key49.core.service.AuditService;
import auracore.key49.core.service.PasswordHasher;
import auracore.key49.core.service.TenantAdminService;
import auracore.key49.core.service.TenantAdminService.UpdateTenantData;
import auracore.key49.signer.CertificateEncryptor;
import auracore.key49.signer.CertificateMetadataExtractor;
import auracore.key49.signer.SigningException;
import auracore.key49.storage.ObjectStorageService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Controlador de configuración del portal: perfil, certificado, SMTP, webhook.
 */
@Path("/portal/settings")
public class PortalSettingsResource {

    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final long MAX_LOGO_SIZE = 512 * 1024; // 512 KB
    private static final java.util.Set<String> ALLOWED_LOGO_TYPES = java.util.Set.of(
            "image/png", "image/jpeg", "image/jpg");

    @Inject
    Logger log;

    @Inject
    @Location("portal/settings-profile")
    Template settingsProfile;

    @Inject
    @Location("portal/settings-certificate")
    Template settingsCertificate;

    @Inject
    @Location("portal/settings-smtp")
    Template settingsSmtp;

    @Inject
    @Location("portal/settings-webhook")
    Template settingsWebhook;

    @Inject
    @Location("portal/settings-delete")
    Template settingsDelete;

    @Inject
    TenantAdminService tenantService;

    @Inject
    PasswordHasher passwordHasher;

    @Inject
    AuditService auditService;

    @Inject
    ObjectStorageService storageService;

    @ConfigProperty(name = "key49.master-key")
    Optional<String> masterKeyBase64;

    @Context
    ContainerRequestContext requestContext;

    // ── Perfil de empresa ──
    // ── Perfil de empresa ──
    @GET
    @Path("/profile")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance profilePage(@QueryParam("error") String error,
            @QueryParam("success") String success) {
        var session = getSession();
        var tenant = tenantService.findById(session.tenantId());

        return settingsProfile.data("session", session)
                .data("tenant", tenant)
                .data("error", error)
                .data("successMsg", success);
    }

    @GET
    @Path("/profile/logo/preview")
    public Response logoPreview() {
        var session = getSession();
        var tenant = tenantService.findById(session.tenantId());

        if (tenant.logoUrl == null || tenant.logoUrl.isBlank()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        try {
            var data = storageService.retrieve(tenant.logoUrl);
            var contentType = tenant.logoUrl.endsWith(".png") ? "image/png" : "image/jpeg";
            return Response.ok(data).type(contentType)
                    .header("Cache-Control", "private, max-age=3600")
                    .build();
        } catch (Exception e) {
            log.warnf("Failed to retrieve logo: %s", e.getMessage());
            return Response.status(Response.Status.NOT_FOUND).build();
        }
    }

    @POST
    @Path("/profile")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response updateProfile(
            @FormParam("legalName") String legalName,
            @FormParam("tradeName") String tradeName,
            @FormParam("mainAddress") String mainAddress,
            @FormParam("requiredAccounting") String requiredAccounting,
            @FormParam("specialTaxpayer") String specialTaxpayer,
            @FormParam("microEnterpriseRegime") String microEnterpriseRegime,
            @FormParam("withholdingAgent") String withholdingAgent,
            @FormParam("emailSenderName") String emailSenderName,
            @FormParam("replyEmail") String replyEmail,
            @Context HttpServerRequest httpRequest) {

        var session = getSession();

        if (legalName == null || legalName.isBlank()) {
            return Response.seeOther(URI.create("/portal/settings/profile?error="
                    + encodeQuery("La razón social es obligatoria"))).build();
        }
        if (mainAddress == null || mainAddress.isBlank()) {
            return Response.seeOther(URI.create("/portal/settings/profile?error="
                    + encodeQuery("La dirección principal es obligatoria"))).build();
        }

        var data = new UpdateTenantData(
                legalName.strip(),
                tradeName != null ? tradeName.strip() : null,
                mainAddress.strip(),
                "on".equals(requiredAccounting),
                specialTaxpayer != null && !specialTaxpayer.isBlank() ? specialTaxpayer.strip() : null,
                "on".equals(microEnterpriseRegime),
                withholdingAgent != null && !withholdingAgent.isBlank() ? withholdingAgent.strip() : null,
                null, null, null, null, null, null,
                emailSenderName != null && !emailSenderName.isBlank() ? emailSenderName.strip() : null,
                replyEmail != null && !replyEmail.isBlank() ? replyEmail.strip() : null,
                null, null);

        tenantService.update(session.tenantId(), data);

        auditService.record(session.tenantId(), "portal", "tenant.profile_updated",
                "tenant", session.tenantId(),
                AuditService.resolveIp(httpRequest), null);

        log.infof("Profile updated via portal | tenantId=%s", session.tenantId());
        return Response.seeOther(URI.create("/portal/settings/profile?success="
                + encodeQuery("Perfil actualizado correctamente"))).build();
    }

    @POST
    @Path("/profile/password")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response changePassword(
            @FormParam("currentPassword") String currentPassword,
            @FormParam("newPassword") String newPassword,
            @FormParam("confirmPassword") String confirmPassword,
            @Context HttpServerRequest httpRequest) {

        var session = getSession();
        var tenant = tenantService.findById(session.tenantId());

        if (currentPassword == null || currentPassword.isBlank()) {
            return Response.seeOther(URI.create("/portal/settings/profile?error="
                    + encodeQuery("La contraseña actual es obligatoria"))).build();
        }
        if (!passwordHasher.verify(currentPassword, tenant.portalPasswordHash)) {
            return Response.seeOther(URI.create("/portal/settings/profile?error="
                    + encodeQuery("La contraseña actual no es correcta"))).build();
        }
        if (newPassword == null || newPassword.length() < 8) {
            return Response.seeOther(URI.create("/portal/settings/profile?error="
                    + encodeQuery("La nueva contraseña debe tener al menos 8 caracteres"))).build();
        }
        if (!newPassword.equals(confirmPassword)) {
            return Response.seeOther(URI.create("/portal/settings/profile?error="
                    + encodeQuery("Las contraseñas no coinciden"))).build();
        }

        tenantService.setPortalCredentials(session.tenantId(), tenant.email, newPassword);

        auditService.record(session.tenantId(), "portal", "tenant.password_changed",
                "tenant", session.tenantId(),
                AuditService.resolveIp(httpRequest), null);

        log.infof("Password changed via portal | tenantId=%s", session.tenantId());
        return Response.seeOther(URI.create("/portal/settings/profile?success="
                + encodeQuery("Contraseña actualizada correctamente"))).build();
    }

    @POST
    @Path("/profile/logo")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.TEXT_HTML)
    public Response uploadLogo(
            @RestForm("logoFile") FileUpload logoFile,
            @Context HttpServerRequest httpRequest) {

        var session = getSession();

        if (logoFile == null || logoFile.size() == 0) {
            return Response.seeOther(URI.create("/portal/settings/profile?error="
                    + encodeQuery("Debe seleccionar una imagen"))).build();
        }
        if (logoFile.size() > MAX_LOGO_SIZE) {
            return Response.seeOther(URI.create("/portal/settings/profile?error="
                    + encodeQuery("La imagen no debe superar 512 KB"))).build();
        }

        var contentType = logoFile.contentType();
        if (contentType == null || !ALLOWED_LOGO_TYPES.contains(contentType.toLowerCase())) {
            return Response.seeOther(URI.create("/portal/settings/profile?error="
                    + encodeQuery("Solo se permiten imágenes PNG o JPEG"))).build();
        }

        byte[] imageBytes;
        try {
            imageBytes = Files.readAllBytes(logoFile.uploadedFile());
        } catch (IOException e) {
            return Response.seeOther(URI.create("/portal/settings/profile?error="
                    + encodeQuery("Error al leer la imagen"))).build();
        }

        var tenant = tenantService.findById(session.tenantId());

        // Delete previous logo if exists
        if (tenant.logoUrl != null && !tenant.logoUrl.isBlank()) {
            try {
                storageService.delete(tenant.logoUrl);
            } catch (Exception e) {
                log.warnf("Failed to delete old logo: %s", e.getMessage());
            }
        }

        var extension = contentType.contains("png") ? "png" : "jpg";
        var objectPath = "logos/%s/logo.%s".formatted(session.tenantId(), extension);
        storageService.storeRaw(objectPath, imageBytes, contentType);

        tenantService.update(session.tenantId(), new UpdateTenantData(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, objectPath));

        auditService.record(session.tenantId(), "portal", "tenant.logo_uploaded",
                "tenant", session.tenantId(),
                AuditService.resolveIp(httpRequest), null);

        log.infof("Logo uploaded via portal | tenantId=%s path=%s", session.tenantId(), objectPath);
        return Response.seeOther(URI.create("/portal/settings/profile?success="
                + encodeQuery("Logo actualizado correctamente"))).build();
    }

    @POST
    @Path("/profile/logo/delete")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response deleteLogo(@Context HttpServerRequest httpRequest) {
        var session = getSession();
        var tenant = tenantService.findById(session.tenantId());

        if (tenant.logoUrl != null && !tenant.logoUrl.isBlank()) {
            try {
                storageService.delete(tenant.logoUrl);
            } catch (Exception e) {
                log.warnf("Failed to delete logo from storage: %s", e.getMessage());
            }
            // Clear logoUrl with empty string (service converts blank to null)
            tenantService.update(session.tenantId(), new UpdateTenantData(
                    null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, ""));

            auditService.record(session.tenantId(), "portal", "tenant.logo_deleted",
                    "tenant", session.tenantId(),
                    AuditService.resolveIp(httpRequest), null);

            log.infof("Logo deleted via portal | tenantId=%s", session.tenantId());
        }

        return Response.seeOther(URI.create("/portal/settings/profile?success="
                + encodeQuery("Logo eliminado correctamente"))).build();
    }

    // ── Certificado digital ──
    @GET
    @Path("/certificate")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance certificatePage(@QueryParam("error") String error,
            @QueryParam("success") String success) {
        var session = getSession();
        var tenant = tenantService.findById(session.tenantId());

        String certStatus = "none";
        long certDaysLeft = 0;
        if (tenant.certificateExpiration != null) {
            certDaysLeft = java.time.Duration.between(Instant.now(), tenant.certificateExpiration).toDays();
            certStatus = certDaysLeft <= 0 ? "expired" : certDaysLeft <= 30 ? "warning" : "ok";
        }

        boolean hasPending = tenant.pendingCertificateP12 != null;

        return settingsCertificate.data("session", session)
                .data("tenant", tenant)
                .data("certStatus", certStatus)
                .data("certDaysLeft", certDaysLeft)
                .data("hasPending", hasPending)
                .data("formatDate", DISPLAY_DATE)
                .data("ecZone", Key49Constants.EC_ZONE)
                .data("error", error)
                .data("successMsg", success);
    }

    @POST
    @Path("/certificate")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.TEXT_HTML)
    public Response uploadCertificate(
            @RestForm("certFile") FileUpload certFile,
            @RestForm("certPassword") String certPassword,
            @RestForm("environment") String environment,
            @Context HttpServerRequest httpRequest) {

        var session = getSession();

        if (certFile == null || certFile.size() == 0) {
            return Response.seeOther(URI.create("/portal/settings/certificate?error="
                    + encodeQuery("Debe seleccionar un archivo .p12"))).build();
        }
        if (certPassword == null || certPassword.isBlank()) {
            return Response.seeOther(URI.create("/portal/settings/certificate?error="
                    + encodeQuery("La contraseña del certificado es obligatoria"))).build();
        }
        if (environment == null || (!environment.equals("test") && !environment.equals("production"))) {
            return Response.seeOther(URI.create("/portal/settings/certificate?error="
                    + encodeQuery("Debe seleccionar un ambiente válido"))).build();
        }

        byte[] p12Bytes;
        try {
            p12Bytes = Files.readAllBytes(certFile.uploadedFile());
        } catch (IOException e) {
            return Response.seeOther(URI.create("/portal/settings/certificate?error="
                    + encodeQuery("Error al leer el archivo"))).build();
        }

        CertificateMetadataExtractor.CertificateMetadata metadata;
        try {
            metadata = CertificateMetadataExtractor.extract(p12Bytes, certPassword.toCharArray());
        } catch (SigningException e) {
            return Response.seeOther(URI.create("/portal/settings/certificate?error="
                    + encodeQuery("Certificado inválido: " + e.getMessage()))).build();
        }

        if (!metadata.valid()) {
            return Response.seeOther(URI.create("/portal/settings/certificate?error="
                    + encodeQuery("El certificado ya expiró el "
                            + DISPLAY_DATE.format(metadata.expiresAt().atZone(Key49Constants.EC_ZONE))))).build();
        }

        var masterKey = CertificateEncryptor.decodeMasterKey(
                masterKeyBase64.orElseThrow(()
                        -> new IllegalStateException("KEY49_MASTER_KEY not configured")));

        var encryptedP12 = CertificateEncryptor.encrypt(p12Bytes, masterKey);
        var encryptedPassword = CertificateEncryptor.encryptPassword(certPassword.toCharArray(), masterKey);

        var tenant = tenantService.findById(session.tenantId());
        if (tenant.certificateP12 != null) {
            tenantService.rotateCertificate(session.tenantId(), encryptedP12, encryptedPassword,
                    metadata.subject(), metadata.expiresAt(), metadata.serial());
            tenantService.activateCertificate(session.tenantId());
        } else {
            tenantService.uploadCertificate(session.tenantId(), encryptedP12, encryptedPassword,
                    metadata.subject(), metadata.expiresAt(), metadata.serial());
        }

        // Update environment
        tenantService.update(session.tenantId(), new UpdateTenantData(
                null, null, null, null, null, null, null,
                environment, null, null, null, null, null, null, null, null, null));

        auditService.record(session.tenantId(), "portal", "certificate.uploaded",
                "tenant", session.tenantId(),
                AuditService.resolveIp(httpRequest),
                "{\"subject\":\"%s\",\"expires\":\"%s\"}".formatted(
                        metadata.subject(), metadata.expiresAt()));

        log.infof("Certificate uploaded via portal | tenantId=%s subject=%s",
                session.tenantId(), metadata.subject());
        return Response.seeOther(URI.create("/portal/settings/certificate?success="
                + encodeQuery("Certificado actualizado correctamente"))).build();
    }

    // ── Email (SMTP / Plunk) ──
    @GET
    @Path("/smtp")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance smtpPage(@QueryParam("error") String error,
            @QueryParam("success") String success) {
        var session = getSession();
        var tenant = tenantService.findById(session.tenantId());

        return settingsSmtp.data("session", session)
                .data("tenant", tenant)
                .data("error", error)
                .data("successMsg", success);
    }

    @POST
    @Path("/smtp")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response updateSmtp(
            @FormParam("smtpHost") String smtpHost,
            @FormParam("smtpPort") String smtpPortStr,
            @FormParam("smtpUser") String smtpUser,
            @FormParam("smtpPassword") String smtpPassword,
            @FormParam("smtpFrom") String smtpFrom,
            @FormParam("emailNotificationsEnabled") String emailNotificationsEnabled,
            @FormParam("notifyFinalConsumer") String notifyFinalConsumer,
            @Context HttpServerRequest httpRequest) {

        var session = getSession();

        boolean emailNotif = "on".equals(emailNotificationsEnabled);
        boolean notifyFc = "on".equals(notifyFinalConsumer);

        Integer smtpPort = null;
        if (smtpPortStr != null && !smtpPortStr.isBlank()) {
            try {
                smtpPort = Integer.parseInt(smtpPortStr.strip());
            } catch (NumberFormatException e) {
                return Response.seeOther(URI.create("/portal/settings/smtp?error="
                        + encodeQuery("El puerto debe ser un número válido"))).build();
            }
        }

        boolean hasHost = smtpHost != null && !smtpHost.isBlank();
        if (hasHost && smtpPort == null) {
            return Response.seeOther(URI.create("/portal/settings/smtp?error="
                    + encodeQuery("El puerto SMTP es obligatorio cuando se configura un host"))).build();
        }

        byte[] encryptedPassword = null;
        if (smtpPassword != null && !smtpPassword.isBlank()) {
            var masterKey = CertificateEncryptor.decodeMasterKey(
                    masterKeyBase64.orElseThrow(()
                            -> new IllegalStateException("KEY49_MASTER_KEY not configured")));
            encryptedPassword = CertificateEncryptor.encryptPassword(smtpPassword.toCharArray(), masterKey);
        }

        tenantService.updateSmtpConfig(session.tenantId(),
                hasHost ? smtpHost.strip() : null,
                smtpPort,
                smtpUser != null && !smtpUser.isBlank() ? smtpUser.strip() : null,
                encryptedPassword,
                smtpFrom != null && !smtpFrom.isBlank() ? smtpFrom.strip() : null,
                emailNotif, notifyFc);

        auditService.record(session.tenantId(), "portal", "smtp.updated",
                "tenant", session.tenantId(),
                AuditService.resolveIp(httpRequest), null);

        log.infof("SMTP config updated via portal | tenantId=%s", session.tenantId());
        return Response.seeOther(URI.create("/portal/settings/smtp?success="
                + encodeQuery("Configuración SMTP actualizada correctamente"))).build();
    }

    @POST
    @Path("/smtp/test")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response testSmtp(@Context HttpServerRequest httpRequest) {
        var session = getSession();
        var tenant = tenantService.findById(session.tenantId());

        if (tenant.smtpHost == null || tenant.smtpHost.isBlank()) {
            return Response.seeOther(URI.create("/portal/settings/smtp?error="
                    + encodeQuery("Primero configure el servidor SMTP"))).build();
        }

        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(tenant.smtpHost, tenant.smtpPort), 5000);
        } catch (IOException e) {
            return Response.seeOther(URI.create("/portal/settings/smtp?error="
                    + encodeQuery("No se pudo conectar a %s:%d — %s".formatted(
                            tenant.smtpHost, tenant.smtpPort, e.getMessage())))).build();
        }

        auditService.record(session.tenantId(), "portal", "smtp.tested",
                "tenant", session.tenantId(),
                AuditService.resolveIp(httpRequest), null);

        return Response.seeOther(URI.create("/portal/settings/smtp?success="
                + encodeQuery("Conexión exitosa a %s:%d".formatted(
                        tenant.smtpHost, tenant.smtpPort)))).build();
    }

    @POST
    @Path("/email-provider")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response updateEmailProvider(
            @FormParam("emailProvider") String emailProvider,
            @FormParam("plunkApiKey") String plunkApiKey,
            @Context HttpServerRequest httpRequest) {

        var session = getSession();

        if (emailProvider == null || emailProvider.isBlank()) {
            return Response.seeOther(URI.create("/portal/settings/smtp?error="
                    + encodeQuery("Debe seleccionar un proveedor de email"))).build();
        }

        var provider = emailProvider.strip().toLowerCase();
        if (!provider.equals("smtp") && !provider.equals("plunk")) {
            return Response.seeOther(URI.create("/portal/settings/smtp?error="
                    + encodeQuery("Proveedor de email no válido"))).build();
        }

        if ("plunk".equals(provider) && (plunkApiKey == null || plunkApiKey.isBlank())) {
            // If switching to Plunk, require API key unless one is already stored
            var tenant = tenantService.findById(session.tenantId());
            if (tenant.plunkApiKeyEnc == null || tenant.plunkApiKeyEnc.length == 0) {
                return Response.seeOther(URI.create("/portal/settings/smtp?error="
                        + encodeQuery("La API key de Plunk es obligatoria para usar este proveedor"))).build();
            }
        }

        byte[] encryptedPlunkKey = null;
        if (plunkApiKey != null && !plunkApiKey.isBlank()) {
            var masterKey = CertificateEncryptor.decodeMasterKey(
                    masterKeyBase64.orElseThrow(()
                            -> new IllegalStateException("KEY49_MASTER_KEY not configured")));
            encryptedPlunkKey = CertificateEncryptor.encrypt(
                    plunkApiKey.strip().getBytes(java.nio.charset.StandardCharsets.UTF_8), masterKey);
        }

        tenantService.updateEmailProvider(session.tenantId(), provider, encryptedPlunkKey);

        auditService.record(session.tenantId(), "portal", "email_provider.updated",
                "tenant", session.tenantId(),
                AuditService.resolveIp(httpRequest),
                "{\"provider\":\"%s\"}".formatted(provider));

        log.infof("Email provider updated via portal | tenantId=%s provider=%s",
                session.tenantId(), provider);
        return Response.seeOther(URI.create("/portal/settings/smtp?success="
                + encodeQuery("Proveedor de email actualizado a: " + provider.toUpperCase()))).build();
    }

    // ── Webhook ──
    @GET
    @Path("/webhook")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance webhookPage(@QueryParam("error") String error,
            @QueryParam("success") String success) {
        var session = getSession();
        var tenant = tenantService.findById(session.tenantId());

        return settingsWebhook.data("session", session)
                .data("tenant", tenant)
                .data("error", error)
                .data("successMsg", success);
    }

    @POST
    @Path("/webhook")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response updateWebhook(
            @FormParam("webhookUrl") String webhookUrl,
            @FormParam("webhookSecret") String webhookSecret,
            @Context HttpServerRequest httpRequest) {

        var session = getSession();

        if (webhookUrl != null && !webhookUrl.isBlank()) {
            var url = webhookUrl.strip();
            if (!url.startsWith("https://")) {
                return Response.seeOther(URI.create("/portal/settings/webhook?error="
                        + encodeQuery("La URL del webhook debe usar HTTPS"))).build();
            }
        }

        var data = new UpdateTenantData(
                null, null, null, null, null, null, null, null,
                webhookUrl != null && !webhookUrl.isBlank() ? webhookUrl.strip() : "",
                webhookSecret != null && !webhookSecret.isBlank() ? webhookSecret.strip() : "",
                null, null, null, null, null, null, null);

        tenantService.update(session.tenantId(), data);

        auditService.record(session.tenantId(), "portal", "webhook.updated",
                "tenant", session.tenantId(),
                AuditService.resolveIp(httpRequest), null);

        log.infof("Webhook config updated via portal | tenantId=%s", session.tenantId());
        return Response.seeOther(URI.create("/portal/settings/webhook?success="
                + encodeQuery("Configuración de webhook actualizada correctamente"))).build();
    }

    // ── Eliminar cuenta ──
    @GET
    @Path("/delete")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance deletePage(@QueryParam("error") String error) {
        var session = getSession();
        var tenant = tenantService.findById(session.tenantId());

        return settingsDelete.data("session", session)
                .data("tenant", tenant)
                .data("error", error);
    }

    @POST
    @Path("/delete")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response deleteAccount(
            @FormParam("confirmPassword") String confirmPassword,
            @FormParam("confirmText") String confirmText,
            @Context HttpServerRequest httpRequest) {

        var session = getSession();
        var tenant = tenantService.findById(session.tenantId());

        if (confirmPassword == null || confirmPassword.isBlank()) {
            return Response.seeOther(URI.create("/portal/settings/delete?error="
                    + encodeQuery("Debe ingresar su contraseña"))).build();
        }

        if (tenant.portalPasswordHash == null
                || !passwordHasher.verify(confirmPassword, tenant.portalPasswordHash)) {
            return Response.seeOther(URI.create("/portal/settings/delete?error="
                    + encodeQuery("Contraseña incorrecta"))).build();
        }

        if (!"ELIMINAR".equals(confirmText)) {
            return Response.seeOther(URI.create("/portal/settings/delete?error="
                    + encodeQuery("Debe escribir ELIMINAR para confirmar"))).build();
        }

        // Mark tenant as suspended — actual deletion is a manual DBA process
        tenantService.update(session.tenantId(), new UpdateTenantData(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, "suspended", null));

        auditService.record(session.tenantId(), "portal", "tenant.deletion_requested",
                "tenant", session.tenantId(),
                AuditService.resolveIp(httpRequest), null);

        log.infof("Account deletion requested via portal | tenantId=%s ruc=%s",
                session.tenantId(), tenant.ruc);

        // Redirect to login — session will be invalid after logout
        return Response.seeOther(URI.create("/portal/login?info="
                + encodeQuery("Su cuenta ha sido marcada para eliminación. Contacte soporte para completar el proceso."))).build();
    }

    // ── Helpers ──
    private PortalSession getSession() {
        return (PortalSession) requestContext.getProperty(PortalAuthFilter.PORTAL_SESSION_ATTR);
    }

    private static String encodeQuery(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
