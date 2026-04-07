package auracore.key49.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * DTO de entrada para crear un comprobante de retención electrónico.
 */
public record CreateWithholdingRequest(
        String establishment,
        String issuePoint,
        String sequenceNumber,
        LocalDate issueDate,
        SubjectRequest subject,
        String fiscalPeriod,
        boolean relatedParty,
        List<SupportingDocumentRequest> supportingDocuments,
        Map<String, String> additionalInfo) {

    public record SubjectRequest(
            String idType, String id, String name,
            String subjectType, String email, String phone) {
    }

    public record SupportingDocumentRequest(
            String supportCode, String documentCode, String documentNumber,
            LocalDate issueDate, LocalDate accountingDate,
            String authorizationNumber, String paymentLocality,
            String regimeType, String paymentCountry,
            String doubleTaxation, String subjectToRetention,
            String fiscalRegime,
            BigDecimal totalWithoutTax, BigDecimal totalAmount,
            List<SupportingDocTaxRequest> taxes,
            List<WithholdingLineRequest> withholdings,
            List<PaymentRequest> payments) {
    }

    public record SupportingDocTaxRequest(
            String taxCode, String rateCode,
            BigDecimal taxableBase, BigDecimal rate,
            BigDecimal amount) {
    }

    public record WithholdingLineRequest(
            String code, String retentionCode,
            BigDecimal taxableBase, BigDecimal retentionRate,
            BigDecimal retainedAmount) {
    }

    public record PaymentRequest(
            String paymentMethod, BigDecimal total) {
    }
}
