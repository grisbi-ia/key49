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
import auracore.key49.api.dto.TenantResponse;
import auracore.key49.api.dto.UpdateTenantRequest;
import auracore.key49.core.service.TenantAdminService;
import auracore.key49.core.service.TenantAdminService.CreateTenantData;
import auracore.key49.core.service.TenantAdminService.UpdateTenantData;
import auracore.key49.signer.CertificateEncryptor;
import auracore.key49.signer.CertificateMetadataExtractor;
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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Endpoints de administración de tenants.
 *
 * <p>
 * Estos endpoints son para uso interno del administrador de la plataforma. La
 * creación del esquema PostgreSQL y sus tablas es responsabilidad del DBA.
 */
@Path("/v1/admin/tenants")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TenantAdminResource {

    @Inject
    Logger log;

    @Inject
    TenantAdminService tenantService;

    @ConfigProperty(name = "key49.master-key")
    Optional<String> masterKeyBase64;

    /**
     * POST /v1/admin/tenants — Registrar un nuevo tenant.
     */
    @POST
    public Response create(CreateTenantRequest request) {
        String requestId = generateRequestId();

        var data = new CreateTenantData(
                request.ruc(), request.legalName(), request.tradeName(),
                request.mainAddress(), request.requiredAccounting(),
                request.specialTaxpayer(), request.microEnterpriseRegime(),
                request.withholdingAgent(), request.environment(),
                request.schemaName());

        var tenant = tenantService.create(data);
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
    public Response update(@PathParam("id") UUID id, UpdateTenantRequest request) {
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
            @RestForm("password") String password) {

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
                tenant.certificateExpiration, null, valid, daysUntilExp);
        var body = ApiResponse.of(certResponse, requestId);
        return Response.ok()
                .header("X-Request-Id", requestId)
                .entity(body)
                .build();
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
