package auracore.key49.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import auracore.key49.core.model.Document;

/**
 * DTO de respuesta para documentos enviados por el canal XML raw.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RawDocumentResponse(
        UUID id,
        String documentType,
        String establishment,
        String issuePoint,
        String sequenceNumber,
        String accessKey,
        String authorizationNumber,
        String status,
        String origin,
        LocalDate issueDate,
        Instant authorizationDate,
        BigDecimal totalAmount,
        InvoiceResponse.RecipientSummary recipient,
        InvoiceResponse.DownloadLinks downloads,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * Respuesta resumida para creación (POST 202).
     */
    public static RawDocumentResponse summary(Document doc) {
        return new RawDocumentResponse(
                doc.id,
                doc.documentType,
                doc.establishment,
                doc.issuePoint,
                doc.sequenceNumber,
                doc.accessKey,
                null,
                doc.status.name(),
                doc.requestOrigin,
                doc.issueDate,
                null,
                doc.totalAmount,
                new InvoiceResponse.RecipientSummary(null, doc.recipientId, doc.recipientName, null),
                null,
                doc.createdAt,
                null);
    }

    /**
     * Respuesta detallada para consulta (GET /:id).
     */
    public static RawDocumentResponse detail(Document doc) {
        InvoiceResponse.DownloadLinks links = null;
        if (doc.authorizedXmlPath != null || doc.ridePath != null) {
            links = new InvoiceResponse.DownloadLinks(
                    doc.authorizedXmlPath != null ? "/v1/documents/raw/" + doc.id + "/xml" : null,
                    doc.ridePath != null ? "/v1/documents/raw/" + doc.id + "/ride" : null);
        }

        return new RawDocumentResponse(
                doc.id,
                doc.documentType,
                doc.establishment,
                doc.issuePoint,
                doc.sequenceNumber,
                doc.accessKey,
                doc.authorizationNumber,
                doc.status.name(),
                doc.requestOrigin,
                doc.issueDate,
                doc.authorizationDate,
                doc.totalAmount,
                new InvoiceResponse.RecipientSummary(doc.recipientIdType, doc.recipientId,
                        doc.recipientName, doc.recipientEmail),
                links,
                doc.createdAt,
                doc.updatedAt);
    }
}
