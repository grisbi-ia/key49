package auracore.key49.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import auracore.key49.core.model.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * DTO de respuesta para comprobantes de retención.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WithholdingResponse(
        UUID id,
        String status,
        String accessKey,
        String establishment,
        String issuePoint,
        String sequenceNumber,
        LocalDate issueDate,
        SubjectSummary subject,
        String fiscalPeriod,
        BigDecimal totalRetained,
        String authorizationNumber,
        Instant authorizationDate,
        int retryCount,
        String lastErrorMessage,
        Instant voidedAt,
        String voidReason,
        DownloadLinks links,
        Instant createdAt,
        Instant updatedAt) {

    public record SubjectSummary(String idType, String id, String name) {

    }

    public record DownloadLinks(String xml, String ride) {

    }

    /**
     * Resumen del comprobante de retención (para listados).
     */
    public static WithholdingResponse summary(Document doc) {
        return new WithholdingResponse(
                doc.id,
                doc.status.name(),
                doc.accessKey,
                doc.establishment,
                doc.issuePoint,
                doc.sequenceNumber,
                doc.issueDate,
                new SubjectSummary(doc.recipientIdType, doc.recipientId, doc.recipientName),
                null,
                doc.totalAmount,
                doc.authorizationNumber,
                doc.authorizationDate,
                (int) doc.retryCount,
                doc.lastErrorMessage,
                doc.voidedAt,
                doc.voidReason,
                null,
                doc.createdAt,
                doc.updatedAt);
    }

    /**
     * Detalle del comprobante de retención (por ID).
     */
    public static WithholdingResponse detail(Document doc) {
        var links = new DownloadLinks(
                "/v1/withholdings/" + doc.id + "/xml",
                "/v1/withholdings/" + doc.id + "/ride");

        return new WithholdingResponse(
                doc.id,
                doc.status.name(),
                doc.accessKey,
                doc.establishment,
                doc.issuePoint,
                doc.sequenceNumber,
                doc.issueDate,
                new SubjectSummary(doc.recipientIdType, doc.recipientId, doc.recipientName),
                null,
                doc.totalAmount,
                doc.authorizationNumber,
                doc.authorizationDate,
                (int) doc.retryCount,
                doc.lastErrorMessage,
                doc.voidedAt,
                doc.voidReason,
                links,
                doc.createdAt,
                doc.updatedAt);
    }
}
