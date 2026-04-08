package auracore.key49.xml.builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Datos del comprobante de retención para generación de XML. Estructura basada
 * en XSD ComprobanteRetencion v2.0.0.
 */
public record WithholdingData(
        TaxpayerInfo taxpayer,
        String accessKey,
        String establishment,
        String issuePoint,
        String sequenceNumber,
        LocalDate issueDate,
        Subject subject,
        String fiscalPeriod,
        boolean relatedParty,
        List<SupportingDocument> supportingDocuments,
        Map<String, String> additionalInfo) {

    public record TaxpayerInfo(
            String environment, String emissionType, String legalName,
            String tradeName, String ruc, String mainAddress,
            String establishmentAddress, boolean requiredAccounting,
            String specialTaxpayer, String withholdingAgent,
            String rimpeContributor) {

    }

    public record Subject(
            String idType, String id, String name,
            String subjectType) {

    }

    public record SupportingDocument(
            String supportCode, String documentCode, String documentNumber,
            LocalDate issueDate, LocalDate accountingDate,
            String authorizationNumber, String paymentLocality,
            String regimeType, String paymentCountry,
            String doubleTaxation, String subjectToRetention,
            String fiscalRegime,
            BigDecimal totalWithoutTax, BigDecimal totalAmount,
            List<SupportingDocTax> taxes,
            List<WithholdingLine> withholdings,
            List<Payment> payments) {

    }

    public record SupportingDocTax(
            String taxCode, String rateCode,
            BigDecimal taxableBase, BigDecimal rate,
            BigDecimal amount) {

    }

    public record WithholdingLine(
            String code, String retentionCode,
            BigDecimal taxableBase, BigDecimal retentionRate,
            BigDecimal retainedAmount) {

    }

    public record Payment(String paymentMethod, BigDecimal total) {

    }
}
