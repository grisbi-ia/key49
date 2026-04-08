package auracore.key49.api.resource;

import java.util.UUID;

import org.jboss.logging.Logger;

import auracore.key49.api.dto.ApiResponse;
import auracore.key49.api.dto.RawDocumentResponse;
import auracore.key49.api.service.RawDocumentService;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Endpoint para recibir comprobantes electrónicos como XML pre-armado.
 *
 * <p>
 * Canal avanzado para integradores que generan su propio XML conforme a la
 * ficha técnica del SRI. Key49 valida el XSD, genera la clave de acceso, firma,
 * envía y gestiona el ciclo de vida completo.
 */
@Path("/v1/documents/raw")
@Produces(MediaType.APPLICATION_JSON)
public class RawDocumentResource {

    @Inject
    Logger log;

    @Inject
    RawDocumentService rawDocumentService;

    /**
     * POST /v1/documents/raw — Enviar un comprobante como XML pre-armado.
     */
    @POST
    @Consumes(MediaType.APPLICATION_XML)
    public Response create(
            String xmlBody,
            @HeaderParam("X-Idempotency-Key") String idempotencyKey,
            @HeaderParam("X-Document-Type") String documentType,
            @Context HttpServerRequest httpRequest) {

        String requestIp = httpRequest.remoteAddress() != null
                ? httpRequest.remoteAddress().host() : "unknown";
        String requestId = generateRequestId();

        var doc = rawDocumentService.createFromRawXml(xmlBody, documentType, idempotencyKey, requestIp);
        var body = ApiResponse.of(RawDocumentResponse.summary(doc), requestId);
        return Response.status(Response.Status.ACCEPTED)
                .header("X-Request-Id", requestId)
                .entity(body)
                .build();
    }

    /**
     * GET /v1/documents/raw/:id — Consultar estado de un documento XML raw.
     */
    @GET
    @Path("/{id}")
    public Response getById(@PathParam("id") UUID id) {
        String requestId = generateRequestId();
        var doc = rawDocumentService.findById(id);
        var body = ApiResponse.of(RawDocumentResponse.detail(doc), requestId);
        return Response.ok()
                .header("X-Request-Id", requestId)
                .entity(body)
                .build();
    }

    private String generateRequestId() {
        return "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
