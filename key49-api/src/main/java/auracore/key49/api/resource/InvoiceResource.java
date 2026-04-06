package auracore.key49.api.resource;

import auracore.key49.api.dto.ApiResponse;
import auracore.key49.api.dto.CreateInvoiceRequest;
import auracore.key49.api.dto.InvoiceResponse;
import auracore.key49.api.dto.PagedResponse;
import auracore.key49.api.dto.VoidRequest;
import auracore.key49.api.exception.BusinessException;
import auracore.key49.api.service.InvoiceService;
import auracore.key49.storage.ObjectStorageService;
import io.smallrye.mutiny.Uni;
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
 * Endpoints REST para facturas electrónicas.
 */
@Path("/v1/invoices")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class InvoiceResource {

    @Inject
    Logger log;

    @Inject
    InvoiceService invoiceService;

    @Inject
    ObjectStorageService storageService;

    /**
     * POST /v1/invoices — Crear y enviar una factura al SRI.
     */
    @POST
    public Uni<Response> create(
            CreateInvoiceRequest request,
            @HeaderParam("X-Idempotency-Key") String idempotencyKey,
            @Context HttpServerRequest httpRequest) {

        String requestIp = httpRequest.remoteAddress() != null
                ? httpRequest.remoteAddress().host() : "unknown";
        String requestId = generateRequestId();

        return invoiceService.createInvoice(request, idempotencyKey, requestIp)
                .map(doc -> {
                    var body = ApiResponse.of(InvoiceResponse.summary(doc), requestId);
                    return Response.status(Response.Status.ACCEPTED)
                            .header("X-Request-Id", requestId)
                            .entity(body)
                            .build();
                });
    }

    /**
     * GET /v1/invoices/:id — Consultar factura por ID.
     */
    @GET
    @Path("/{id}")
    public Uni<Response> getById(@PathParam("id") UUID id) {
        String requestId = generateRequestId();
        return invoiceService.findById(id)
                .map(doc -> {
                    var body = ApiResponse.of(InvoiceResponse.detail(doc), requestId);
                    return Response.ok()
                            .header("X-Request-Id", requestId)
                            .entity(body)
                            .build();
                });
    }

    /**
     * GET /v1/invoices — Listar facturas con filtros y paginación.
     */
    @GET
    public Uni<Response> list(
            @QueryParam("status") String status,
            @QueryParam("date_from") LocalDate dateFrom,
            @QueryParam("date_to") LocalDate dateTo,
            @QueryParam("recipient_id") String recipientId,
            @QueryParam("access_key") String accessKey,
            @QueryParam("document_type") String documentType,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("per_page") @DefaultValue("20") int perPage,
            @QueryParam("sort") @DefaultValue("-issue_date") String sort) {

        String requestId = generateRequestId();
        return invoiceService.listInvoices(status, dateFrom, dateTo, recipientId, accessKey, documentType,
                page, perPage, sort)
                .map(result -> {
                    var responses = result.items().stream()
                            .map(InvoiceResponse::summary)
                            .toList();
                    var body = PagedResponse.of(responses, result.total(), page, perPage);
                    return Response.ok()
                            .header("X-Request-Id", requestId)
                            .entity(body)
                            .build();
                });
    }

    /**
     * GET /v1/invoices/:id/xml — Descargar XML autorizado.
     */
    @GET
    @Path("/{id}/xml")
    @Produces(MediaType.APPLICATION_XML)
    public Uni<Response> downloadXml(@PathParam("id") UUID id) {
        return invoiceService.findById(id)
                .chain(doc -> {
                    if (doc.authorizedXmlPath == null) {
                        return Uni.createFrom().failure(
                                new BusinessException("DOCUMENT_NOT_FOUND", "Authorized XML not available yet", 404));
                    }
                    return Uni.createFrom().item(() -> storageService.retrieve(doc.authorizedXmlPath))
                            .runSubscriptionOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool())
                            .map(bytes -> Response.ok(bytes)
                            .type(MediaType.APPLICATION_XML)
                            .header("Content-Disposition",
                                    "attachment; filename=\"%s.xml\"".formatted(
                                            doc.accessKey != null ? doc.accessKey : doc.id))
                            .build());
                });
    }

    /**
     * GET /v1/invoices/:id/ride — Descargar RIDE (PDF).
     */
    @GET
    @Path("/{id}/ride")
    @Produces("application/pdf")
    public Uni<Response> downloadRide(@PathParam("id") UUID id) {
        return invoiceService.findById(id)
                .chain(doc -> {
                    if (doc.ridePath == null) {
                        return Uni.createFrom().failure(
                                new BusinessException("DOCUMENT_NOT_FOUND", "RIDE not available yet", 404));
                    }
                    return Uni.createFrom().item(() -> storageService.retrieve(doc.ridePath))
                            .runSubscriptionOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool())
                            .map(bytes -> Response.ok(bytes)
                            .type("application/pdf")
                            .header("Content-Disposition",
                                    "attachment; filename=\"%s.pdf\"".formatted(
                                            doc.accessKey != null ? doc.accessKey : doc.id))
                            .build());
                });
    }

    /**
     * POST /v1/invoices/:id/resend-email — Reenviar email con RIDE y XML.
     */
    @POST
    @Path("/{id}/resend-email")
    public Uni<Response> resendEmail(@PathParam("id") UUID id) {
        String requestId = generateRequestId();
        return invoiceService.resendEmail(id)
                .map(sentAt -> {
                    record ResendData(String message, Instant sentAt2) {

                    }
                    // Use simple map for clean snake_case output
                    var data = java.util.Map.of(
                            "message", "Email reenviado exitosamente",
                            "sent_at", sentAt.toString());
                    var body = ApiResponse.of(data, requestId);
                    return Response.ok()
                            .header("X-Request-Id", requestId)
                            .entity(body)
                            .build();
                });
    }

    /**
     * POST /v1/invoices/:id/void — Anular factura localmente.
     */
    @POST
    @Path("/{id}/void")
    public Uni<Response> voidInvoice(@PathParam("id") UUID id, VoidRequest request) {
        String requestId = generateRequestId();
        return invoiceService.voidInvoice(id, request != null ? request.reason() : null)
                .map(doc -> {
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
                });
    }

    private String generateRequestId() {
        return "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
