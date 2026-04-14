package auracore.key49.api.resource;

import java.util.UUID;

import org.jboss.logging.Logger;

import auracore.key49.api.dto.ApiKeyResponse;
import auracore.key49.api.dto.ApiResponse;
import auracore.key49.api.dto.CreateApiKeyRequest;
import auracore.key49.core.service.ApiKeyManagementService;
import auracore.key49.core.service.ApiKeyManagementService.ApiKeyException;
import auracore.key49.core.service.ApiKeyManagementService.CreateApiKeyData;
import auracore.key49.core.service.AuditService;
import auracore.key49.core.tenant.TenantContext;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Endpoints de gestión de API keys del tenant autenticado. POST
 * /v1/tenant/api-keys — crear nueva API key GET /v1/tenant/api-keys — listar
 * API keys del tenant GET /v1/tenant/api-keys/:id — consultar API key DELETE
 * /v1/tenant/api-keys/:id — revocar API key
 */
@Path("/v1/tenant/api-keys")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ApiKeyResource {

    @Inject
    Logger log;

    @Inject
    ApiKeyManagementService apiKeyService;

    @Inject
    TenantContext tenantContext;

    @Inject
    AuditService auditService;

    @POST
    public Response create(CreateApiKeyRequest request,
            @Context HttpServerRequest httpRequest) {
        var requestId = generateRequestId();
        var tenantId = requireTenantId();

        var data = new CreateApiKeyData(
                request.name(),
                request.permissions(), request.expiresAt());

        var created = apiKeyService.create(tenantId, data);

        auditService.record(tenantId, tenantContext.getApiKeyPrefix(),
                "api_key.created", "api_key", created.apiKey().id,
                AuditService.resolveIp(httpRequest),
                """
                {"name":"%s","prefix":"%s"}""".formatted(
                        created.apiKey().name, created.apiKey().keyPrefix));

        var body = ApiResponse.of(
                ApiKeyResponse.fromCreated(created.apiKey(), created.rawKey()),
                requestId);
        return Response.status(Response.Status.CREATED)
                .header("X-Request-Id", requestId)
                .entity(body)
                .build();
    }

    @GET
    public Response list() {
        var requestId = generateRequestId();
        var tenantId = requireTenantId();

        var keys = apiKeyService.listByTenant(tenantId);
        var responses = keys.stream()
                .map(ApiKeyResponse::fromEntity)
                .toList();
        var body = ApiResponse.of(responses, requestId);
        return Response.ok()
                .header("X-Request-Id", requestId)
                .entity(body)
                .build();
    }

    @GET
    @Path("/{id}")
    public Response getById(@PathParam("id") UUID id) {
        var requestId = generateRequestId();
        var tenantId = requireTenantId();

        var key = apiKeyService.findById(tenantId, id);
        var body = ApiResponse.of(ApiKeyResponse.fromEntity(key), requestId);
        return Response.ok()
                .header("X-Request-Id", requestId)
                .entity(body)
                .build();
    }

    @DELETE
    @Path("/{id}")
    public Response revoke(@PathParam("id") UUID id,
            @Context HttpServerRequest httpRequest) {
        var requestId = generateRequestId();
        var tenantId = requireTenantId();

        var key = apiKeyService.revoke(tenantId, id);

        auditService.record(tenantId, tenantContext.getApiKeyPrefix(),
                "api_key.revoked", "api_key", key.id,
                AuditService.resolveIp(httpRequest),
                """
                {"name":"%s","prefix":"%s"}""".formatted(key.name, key.keyPrefix));

        var body = ApiResponse.of(ApiKeyResponse.fromEntity(key), requestId);
        return Response.ok()
                .header("X-Request-Id", requestId)
                .entity(body)
                .build();
    }

    private UUID requireTenantId() {
        if (!tenantContext.isSet()) {
            throw new ApiKeyException(
                    "AUTHENTICATION_REQUIRED", "Tenant context not available", 401);
        }
        return tenantContext.getTenantId();
    }

    private static String generateRequestId() {
        return "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
