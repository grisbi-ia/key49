package auracore.key49.api.resource;

import auracore.key49.api.dto.ApiResponse;
import auracore.key49.api.dto.CreatePurchaseClearanceRequest;
import auracore.key49.api.dto.PurchaseClearanceResponse;
import auracore.key49.api.dto.PagedResponse;
import auracore.key49.api.dto.VoidRequest;
import auracore.key49.api.exception.BusinessException;
import auracore.key49.api.service.PurchaseClearanceService;
import auracore.key49.storage.ObjectStorageService;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Endpoints REST para liquidaciones de compra electrónicas.
 */
@Path("/v1/purchase-clearances")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PurchaseClearanceResource {

    @Inject
    Logger log;

    @Inject
    PurchaseClearanceService purchaseClearanceService;

    @Inject
    ObjectStorageService storageService;

    /**
     * POST /v1/purchase-clearances — Crear y enviar una liquidación de compra al SRI.
     */
    @POST
    public Response create(
            CreatePurchaseClearanceRequest request,
            @HeaderParam("X-Idempotency-Key") String idempotencyKey,
            @Context HttpServerRequest httpRequest) {

        String requestIp = httpRequest.remoteAddress() != null
                ? httpRequest.remoteAddress().host() : "unknown";
        String requestId = generateRequestId();

        var doc = purchaseClearanceService.createPurchaseClearance(request, idempotencyKey, requestIp);
        var body = ApiResponse.of(PurchaseClearanceResponse.summary(doc), requestId);
        return Response.status(Response.Status.ACCEPTED)
                .header("X-Request-Id", requestId)
                .entity(body)
                .build();
    }

    /**
     * GET /v1/purchase-clearances/:id — Consultar liquidación de compra por ID.
     */
    @GET
    @Path("/{id}")
    public Response getById(@PathParam("id") UUID id) {
        String requestId = generateRequestId();
        var doc = purchaseClearanceService.findById(id);
        var body = ApiResponse.of(PurchaseClearanceResponse.detail(doc), requestId);
        return Response.ok()
                .header("X-Request-Id", requestId)
                .entity(body)
                .build();
    }

    /**
     * GET /v1/purchase-clearances — Listar liquidaciones de compra con filtros y paginación.
     */
    @GET
    public Response list(
            @QueryParam("status") String status,
            @QueryParam("date_from") LocalDate dateFrom,
            @QueryParam("date_to") LocalDate dateTo,
            @QueryParam("recipient_id") String recipientId,
            @QueryParam("access_key") String accessKey,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("per_page") @DefaultValue("20") int perPage,
            @QueryParam("sort") @DefaultValue("-issue_date") String sort) {

        String requestId = generateRequestId();
        var result = purchaseClearanceService.listPurchaseClearances(status, dateFrom, dateTo, recipientId, accessKey,
                page, perPage, sort);
        var responses = result.items().stream()
                .map(PurchaseClearanceResponse::summary)
                .toList();
        var body = PagedResponse.of(responses, result.total(), page, perPage);
        return Response.ok()
                .header("X-Request-Id", requestId)
                .entity(body)
                .build();
    }

    /**
     * GET /v1/purchase-clearances/:id/xml — Descargar XML autorizado.
     */
    @GET
    @Path("/{id}/xml")
    @Produces(MediaType.APPLICATION_XML)
    public Response downloadXml(@PathParam("id") UUID id) {
        var doc = purchaseClearanceService.findById(id);
        if (doc.authorizedXmlPath == null) {
            throw new BusinessException("DOCUMENT_NOT_FOUND", "Authorized XML not available yet", 404);
        }
        byte[] bytes = storageService.retrieve(doc.authorizedXmlPath);
        return Response.ok(bytes)
                .type(MediaType.APPLICATION_XML)
                .header("Content-Disposition",
                        "attachment; filename=\"%s.xml\"".formatted(
                                doc.accessKey != null ? doc.accessKey : doc.id))
                .build();
    }

    /**
     * GET /v1/purchase-clearances/:id/ride — Descargar RIDE (PDF).
     */
    @GET
    @Path("/{id}/ride")
    @Produces("application/pdf")
    public Response downloadRide(@PathParam("id") UUID id) {
        var doc = purchaseClearanceService.findById(id);
        if (doc.ridePath == null) {
            throw new BusinessException("DOCUMENT_NOT_FOUND", "RIDE not available yet", 404);
        }
        byte[] bytes = storageService.retrieve(doc.ridePath);
        return Response.ok(bytes)
                .type("application/pdf")
                .header("Content-Disposition",
                        "attachment; filename=\"%s.pdf\"".formatted(
                                doc.accessKey != null ? doc.accessKey : doc.id))
                .build();
    }

    /**
     * POST /v1/purchase-clearances/:id/resend-email — Reenviar email con RIDE y XML.
     */
    @POST
    @Path("/{id}/resend-email")
    public Response resendEmail(@PathParam("id") UUID id) {
        String requestId = generateRequestId();
        var sentAt = purchaseClearanceService.resendEmail(id);
        var data = java.util.Map.of(
                "message", "Email reenviado exitosamente",
                "sent_at", sentAt.toString());
        var body = ApiResponse.of(data, requestId);
        return Response.ok()
                .header("X-Request-Id", requestId)
                .entity(body)
                .build();
    }

    /**
     * POST /v1/purchase-clearances/:id/void — Anular liquidación de compra localmente.
     */
    @POST
    @Path("/{id}/void")
    public Response voidPurchaseClearance(@PathParam("id") UUID id, VoidRequest request) {
        String requestId = generateRequestId();
        var doc = purchaseClearanceService.voidPurchaseClearance(id, request != null ? request.reason() : null);
        var data = new java.util.LinkedHashMap<String, Object>();
        data.put("id", doc.id);
        data.put("status", doc.status.name());
        data.put("voided_at", doc.voidedAt.toString());
        data.put("void_reason", doc.voidReason);
        data.put("access_key", doc.accessKey != null ? doc.accessKey : "");
        var body = ApiResponse.of(data, requestId);
        return Response.ok()
                .header("X-Request-Id", requestId)
                .entity(body)
                .build();
    }

    private String generateRequestId() {
        return "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
