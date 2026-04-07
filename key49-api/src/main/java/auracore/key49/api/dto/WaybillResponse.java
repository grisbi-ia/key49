package auracore.key49.api.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import auracore.key49.core.model.Document;

/**
 * DTO de respuesta para guía de remisión electrónica. Usado tanto para el
 * detalle (GET /:id) como para el resumen (POST, GET lista).
 *
 * <p>
 * A diferencia de facturas y notas, la guía de remisión no incluye montos
 * financieros. Los campos de transporte (transportista, placa, direcciones) se
 * encuentran en el payload del documento.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WaybillResponse(
        UUID id,
        String documentType,
        String establishment,
        String issuePoint,
        String sequenceNumber,
        String accessKey,
        String authorizationNumber,
        String status,
        LocalDate issueDate,
        Instant authorizationDate,
        CarrierSummary carrier,
        DownloadLinks downloads,
        Integer retryCount,
        Instant createdAt,
        Instant updatedAt) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CarrierSummary(
            String idType,
            String id,
            String name,
            String email) {

    }

    public record DownloadLinks(String xml, String ride) {

    }

    /**
     * Respuesta resumida para creación (POST) y listados.
     */
    public static WaybillResponse summary(Document doc) {
        return new WaybillResponse(
                doc.id,
                doc.documentType,
                doc.establishment,
                doc.issuePoint,
                doc.sequenceNumber,
                doc.accessKey,
                null,
                doc.status.name(),
                doc.issueDate,
                null,
                new CarrierSummary(null, doc.recipientId, doc.recipientName, null),
                null,
                null,
                doc.createdAt,
                null);
    }

    /**
     * Respuesta detallada para consulta individual (GET /:id).
     */
    public static WaybillResponse detail(Document doc) {
        DownloadLinks links = null;
        if (doc.authorizedXmlPath != null || doc.ridePath != null) {
            links = new DownloadLinks(
                    doc.authorizedXmlPath != null ? "/v1/waybills/" + doc.id + "/xml" : null,
                    doc.ridePath != null ? "/v1/waybills/" + doc.id + "/ride" : null);
        }

        return new WaybillResponse(
                doc.id,
                doc.documentType,
                doc.establishment,
                doc.issuePoint,
                doc.sequenceNumber,
                doc.accessKey,
                doc.authorizationNumber,
                doc.status.name(),
                doc.issueDate,
                doc.authorizationDate,
                new CarrierSummary(doc.recipientIdType, doc.recipientId, doc.recipientName, doc.recipientEmail),
                links,
                (int) doc.retryCount,
                doc.createdAt,
                doc.updatedAt);
    }
}
