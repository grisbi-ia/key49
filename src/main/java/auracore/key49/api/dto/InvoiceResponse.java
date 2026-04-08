package auracore.key49.api.dto;

import auracore.key49.core.model.Document;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * DTO de respuesta para factura electrónica.
 * Usado tanto para el detalle (GET /:id) como para el resumen (POST, GET lista).
 * Los campos nulos se excluyen del JSON.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InvoiceResponse(
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
        RecipientSummary recipient,
        BigDecimal subtotalBeforeTax,
        BigDecimal vatAmount,
        BigDecimal totalAmount,
        List<Object> sriMessages,
        DownloadLinks downloads,
        Integer retryCount,
        Instant createdAt,
        Instant updatedAt) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RecipientSummary(
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
    public static InvoiceResponse summary(Document doc) {
        return new InvoiceResponse(
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
                new RecipientSummary(null, doc.recipientId, doc.recipientName, null),
                null,
                null,
                doc.totalAmount,
                null,
                null,
                null,
                doc.createdAt,
                null);
    }

    /**
     * Respuesta detallada para consulta individual (GET /:id).
     */
    public static InvoiceResponse detail(Document doc) {
        DownloadLinks links = null;
        if (doc.authorizedXmlPath != null || doc.ridePath != null) {
            links = new DownloadLinks(
                    doc.authorizedXmlPath != null ? "/v1/invoices/" + doc.id + "/xml" : null,
                    doc.ridePath != null ? "/v1/invoices/" + doc.id + "/ride" : null);
        }

        List<Object> messages = List.of();
        // sriMessages is stored as JSONB string — returned as empty list since raw parsing
        // happens at the resource layer if needed

        return new InvoiceResponse(
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
                new RecipientSummary(doc.recipientIdType, doc.recipientId, doc.recipientName, doc.recipientEmail),
                doc.subtotalBeforeTax,
                doc.vatAmount,
                doc.totalAmount,
                messages,
                links,
                (int) doc.retryCount,
                doc.createdAt,
                doc.updatedAt);
    }
}
