package auracore.key49.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import auracore.key49.core.model.Document;

/**
 * DTO de respuesta para liquidación de compra electrónica. Usado tanto para el
 * detalle (GET /:id) como para el resumen (POST, GET lista).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PurchaseClearanceResponse(
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
        SupplierSummary supplier,
        BigDecimal subtotalBeforeTax,
        BigDecimal vatAmount,
        BigDecimal totalAmount,
        List<Object> sriMessages,
        DownloadLinks downloads,
        Integer retryCount,
        Instant createdAt,
        Instant updatedAt) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SupplierSummary(
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
    public static PurchaseClearanceResponse summary(Document doc) {
        return new PurchaseClearanceResponse(
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
                new SupplierSummary(null, doc.recipientId, doc.recipientName, null),
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
    public static PurchaseClearanceResponse detail(Document doc) {
        DownloadLinks links = null;
        if (doc.authorizedXmlPath != null || doc.ridePath != null) {
            links = new DownloadLinks(
                    doc.authorizedXmlPath != null ? "/v1/purchase-clearances/" + doc.id + "/xml" : null,
                    doc.ridePath != null ? "/v1/purchase-clearances/" + doc.id + "/ride" : null);
        }

        return new PurchaseClearanceResponse(
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
                new SupplierSummary(doc.recipientIdType, doc.recipientId, doc.recipientName, doc.recipientEmail),
                doc.subtotalBeforeTax,
                doc.vatAmount,
                doc.totalAmount,
                List.of(),
                links,
                (int) doc.retryCount,
                doc.createdAt,
                doc.updatedAt);
    }
}
