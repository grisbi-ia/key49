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
import auracore.key49.api.dto.TenantResponse;
import auracore.key49.api.dto.UpdateProfileRequest;
import auracore.key49.core.service.TenantAdminService;
import auracore.key49.core.service.TenantAdminService.UpdateTenantData;
import auracore.key49.core.tenant.TenantContext;
import auracore.key49.signer.CertificateEncryptor;
import auracore.key49.signer.CertificateMetadataExtractor;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Endpoints de perfil del tenant autenticado.
 * A diferencia de TenantAdminResource, estos endpoints operan sobre el tenant
 * resuelto por el API key del request (TenantContext).
 */
@Path("/v1/tenant")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TenantProfileResource {

    @Inject
    Logger log;

    @Inject
    TenantAdminService tenantService;

    @Inject
    TenantContext tenantContext;

    @ConfigProperty(name = "key49.master-key")
    Optional<String> masterKeyBase64;

    @GET
    @Path("/profile")
    public Uni<Response> getProfile() {
        var requestId = generateRequestId();
        var tenantId = requireTenantId();

        return tenantService.findById(tenantId)
                .map(tenant -> {
                    var body = ApiResponse.of(TenantResponse.fromEntity(tenant), requestId);
                    return Response.ok()
                            .header("X-Request-Id", requestId)
                            .entity(body)
                            .build();
                });
    }

    @PUT
    @Path("/profile")
    public Uni<Response> updateProfile(UpdateProfileRequest request) {
        var requestId = generateRequestId();
        var tenantId = requireTenantId();

        var data = new UpdateTenantData(
                request.legalName(), request.tradeName(), request.mainAddress(),
                request.requiredAccounting(), request.specialTaxpayer(),
                request.microEnterpriseRegime(), request.withholdingAgent(),
                request.environment(), request.webhookUrl(), request.webhookSecret(),
                null, request.emailSenderName(),
                request.replyEmail(), null);

        return tenantService.update(tenantId, data)
                .map(tenant -> {
                    var body = ApiResponse.of(TenantResponse.fromEntity(tenant), requestId);
                    return Response.ok()
                            .header("X-Request-Id", requestId)
                            .entity(body)
                            .build();
                });
    }

    @POST
    @Path("/certificate")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Uni<Response> uploadCertificate(
            @RestForm("certificate") FileUpload certificate,
            @RestForm("password") String password) {

        var requestId = generateRequestId();
        var tenantId = requireTenantId();

        if (certificate == null) {
            return Uni.createFrom().item(errorResponse(requestId, "VALIDATION_ERROR",
                    "Certificate file is required", 400));
        }
        if (password == null || password.isBlank()) {
            return Uni.createFrom().item(errorResponse(requestId, "VALIDATION_ERROR",
                    "Certificate password is required", 400));
        }

        byte[] p12Bytes;
        try {
            p12Bytes = Files.readAllBytes(certificate.filePath());
        } catch (IOException e) {
            return Uni.createFrom().item(errorResponse(requestId, "VALIDATION_ERROR",
                    "Failed to read certificate file", 400));
        }

        var passwordChars = password.toCharArray();
        CertificateMetadataExtractor.CertificateMetadata metadata;
        try {
            metadata = CertificateMetadataExtractor.extract(p12Bytes, passwordChars);
        } catch (auracore.key49.signer.SigningException e) {
            log.warnf("Invalid certificate upload for tenant %s: %s", tenantId, e.getMessage());
            return Uni.createFrom().item(errorResponse(requestId, "INVALID_CERTIFICATE",
                    "Invalid certificate: " + e.getMessage(), 422));
        }

        if (!metadata.valid()) {
            return Uni.createFrom().item(errorResponse(requestId, "CERTIFICATE_EXPIRED",
                    "Certificate is expired (expired at " + metadata.expiresAt() + ")", 422));
        }

        var masterKey = CertificateEncryptor.decodeMasterKey(
                masterKeyBase64.orElseThrow(()
                        -> new IllegalStateException("KEY49_MASTER_KEY not configured")));
        var encryptedP12 = CertificateEncryptor.encrypt(p12Bytes, masterKey);
        var encryptedPassword = CertificateEncryptor.encryptPassword(passwordChars, masterKey);

        return tenantService.uploadCertificate(
                tenantId, encryptedP12, encryptedPassword,
                metadata.subject(), metadata.expiresAt(), metadata.serial())
                .map(tenant -> {
                    var certResponse = new CertificateStatusResponse(
                            metadata.subject(), metadata.serial(),
                            metadata.expiresAt(), metadata.issuer(),
                            metadata.valid(), metadata.daysUntilExpiration());
                    var body = ApiResponse.of(certResponse, requestId);
                    return Response.ok()
                            .header("X-Request-Id", requestId)
                            .entity(body)
                            .build();
                });
    }

    @GET
    @Path("/certificate/status")
    public Uni<Response> certificateStatus() {
        var requestId = generateRequestId();
        var tenantId = requireTenantId();

        return tenantService.findById(tenantId)
                .map(tenant -> {
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
                            tenant.certificateExpiration, null, valid, daysUntilExp);
                    var body = ApiResponse.of(certResponse, requestId);
                    return Response.ok()
                            .header("X-Request-Id", requestId)
                            .entity(body)
                            .build();
                });
    }

    private UUID requireTenantId() {
        if (!tenantContext.isSet()) {
            throw new TenantAdminService.TenantException(
                    "AUTHENTICATION_REQUIRED", "Tenant context not available", 401);
        }
        return tenantContext.getTenantId();
    }

    private static String generateRequestId() {
        return "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static Response errorResponse(String requestId, String code, String message, int status) {
        record ErrorDetail(String code, String message) {}
        record ErrorWrapper(ErrorDetail error) {}
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .header("X-Request-Id", requestId)
                .entity(new ErrorWrapper(new ErrorDetail(code, message)))
                .build();
    }
}
