package auracore.key49.ride.generator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Datos necesarios para generar el RIDE (PDF) de un comprobante de retención.
 * Incluye datos del sujeto retenido y documentos de sustento con sus
 * retenciones.
 */
public record WithholdingRideData(
        RideData.Issuer issuer,
        String accessKey,
        String authorizationNumber,
        LocalDateTime authorizationDate,
        String environment,
        String emissionType,
        String establishment,
        String issuePoint,
        String sequenceNumber,
        LocalDate issueDate,
        RideData.Recipient subject,
        String fiscalPeriod,
        boolean relatedParty,
        List<SupportingDocumentSummary> supportingDocuments,
        BigDecimal totalRetained,
        Map<String, String> additionalInfo,
        boolean authorized,
        byte[] logo) {

    /**
     * Resumen de un documento de sustento con sus retenciones.
     */
    public record SupportingDocumentSummary(
            String supportCode, String documentCode, String documentNumber,
            LocalDate issueDate,
            BigDecimal totalWithoutTax, BigDecimal totalAmount,
            List<WithholdingLineSummary> withholdings,
            List<RideData.Payment> payments) {

    }

    /**
     * Línea de retención aplicada a un documento de sustento.
     */
    public record WithholdingLineSummary(
            String code, String retentionCode,
            BigDecimal taxableBase, BigDecimal retentionRate,
            BigDecimal retainedAmount) {

    }

    /**
     * Número de documento formateado: EST-PTO-SEQ (ej: 001-001-000000001).
     */
    public String formattedDocumentNumber() {
        return establishment + "-" + issuePoint + "-" + sequenceNumber;
    }
}
