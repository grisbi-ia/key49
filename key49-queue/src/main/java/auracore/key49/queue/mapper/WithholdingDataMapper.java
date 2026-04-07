package auracore.key49.queue.mapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import auracore.key49.core.model.Document;
import auracore.key49.core.model.Tenant;
import auracore.key49.xml.builder.WithholdingData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Mapea Document + Tenant + accessKey a WithholdingData para generar el XML de
 * comprobante de retención.
 */

@ApplicationScoped
public class WithholdingDataMapper {

    private final ObjectMapper objectMapper;

    @Inject
    public WithholdingDataMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Construye un WithholdingData completo a partir de los datos del documento,
     * el tenant emisor y la clave de acceso generada.
     */
    public WithholdingData build(Document doc, Tenant tenant, String accessKey) {
        var payload = parsePayload(doc.requestPayload);

        var subject = new WithholdingData.Subject(
                doc.recipientIdType,
                doc.recipientId,
                doc.recipientName,
                payload.subject() != null ? payload.subject().subjectType() : null);

        var supportingDocs = mapSupportingDocuments(payload.supportingDocuments());

        return new WithholdingData(
                buildTaxpayerInfo(tenant),
                accessKey,
                doc.establishment,
                doc.issuePoint,
                doc.sequenceNumber,
                doc.issueDate,
                subject,
                payload.fiscalPeriod(),
                payload.relatedParty(),
                supportingDocs,
                payload.additionalInfo() != null ? payload.additionalInfo() : Map.of());
    }

    private WithholdingData.TaxpayerInfo buildTaxpayerInfo(Tenant tenant) {
        var envCode = "production".equals(tenant.environment) ? "2" : "1";
        return new WithholdingData.TaxpayerInfo(
                envCode,
                String.valueOf(tenant.emissionType),
                tenant.legalName,
                tenant.tradeName,
                tenant.ruc,
                tenant.mainAddress,
                null,
                tenant.requiredAccounting,
                tenant.specialTaxpayer,
                tenant.withholdingAgent,
                tenant.microEnterpriseRegime ? "CONTRIBUYENTE RÉGIMEN RIMPE" : null);
    }

    private List<WithholdingData.SupportingDocument> mapSupportingDocuments(
            List<PayloadSupportingDocument> payloadDocs) {
        if (payloadDocs == null || payloadDocs.isEmpty()) {
            return List.of();
        }
        return payloadDocs.stream().map(this::mapSupportingDocument).toList();
    }

    private WithholdingData.SupportingDocument mapSupportingDocument(
            PayloadSupportingDocument sd) {
        return new WithholdingData.SupportingDocument(
                sd.supportCode(),
                sd.documentCode(),
                sd.documentNumber(),
                sd.issueDate(),
                sd.accountingDate(),
                sd.authorizationNumber(),
                sd.paymentLocality(),
                sd.regimeType(),
                sd.paymentCountry(),
                sd.doubleTaxation(),
                sd.subjectToRetention(),
                sd.fiscalRegime(),
                sd.totalWithoutTax(),
                sd.totalAmount(),
                mapTaxes(sd.taxes()),
                mapWithholdingLines(sd.withholdings()),
                mapPayments(sd.payments()));
    }

    private List<WithholdingData.SupportingDocTax> mapTaxes(
            List<PayloadSupportingDocTax> taxes) {
        if (taxes == null || taxes.isEmpty()) {
            return List.of();
        }
        return taxes.stream()
                .map(t -> new WithholdingData.SupportingDocTax(
                        t.taxCode(), t.rateCode(), t.taxableBase(), t.rate(), t.amount()))
                .toList();
    }

    private List<WithholdingData.WithholdingLine> mapWithholdingLines(
            List<PayloadWithholdingLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        return lines.stream()
                .map(wh -> new WithholdingData.WithholdingLine(
                        wh.code(), wh.retentionCode(), wh.taxableBase(),
                        wh.retentionRate(), wh.retainedAmount()))
                .toList();
    }

    private List<WithholdingData.Payment> mapPayments(List<PayloadPayment> payments) {
        if (payments == null || payments.isEmpty()) {
            return List.of();
        }
        return payments.stream()
                .map(p -> new WithholdingData.Payment(p.paymentMethod(), p.total()))
                .toList();
    }

    private WithholdingPayload parsePayload(String requestPayload) {
        if (requestPayload == null || requestPayload.isBlank()) {
            return new WithholdingPayload(null, null, false, List.of(), Map.of());
        }
        try {
            var parsed = objectMapper.readValue(requestPayload, WithholdingPayload.class);
            return new WithholdingPayload(
                    parsed.subject(),
                    parsed.fiscalPeriod(),
                    parsed.relatedParty(),
                    parsed.supportingDocuments() != null ? parsed.supportingDocuments() : List.of(),
                    parsed.additionalInfo() != null ? parsed.additionalInfo() : Map.of());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid request payload JSON", e);
        }
    }

    // ── Payload records ──

    record WithholdingPayload(
            PayloadSubject subject,
            String fiscalPeriod,
            boolean relatedParty,
            List<PayloadSupportingDocument> supportingDocuments,
            Map<String, String> additionalInfo) {
    }

    record PayloadSubject(
            String idType, String id, String name, String subjectType) {
    }

    record PayloadSupportingDocument(
            String supportCode, String documentCode, String documentNumber,
            LocalDate issueDate, LocalDate accountingDate,
            String authorizationNumber, String paymentLocality,
            String regimeType, String paymentCountry,
            String doubleTaxation, String subjectToRetention,
            String fiscalRegime,
            BigDecimal totalWithoutTax, BigDecimal totalAmount,
            List<PayloadSupportingDocTax> taxes,
            List<PayloadWithholdingLine> withholdings,
            List<PayloadPayment> payments) {
    }

    record PayloadSupportingDocTax(
            String taxCode, String rateCode,
            BigDecimal taxableBase, BigDecimal rate, BigDecimal amount) {
    }

    record PayloadWithholdingLine(
            String code, String retentionCode,
            BigDecimal taxableBase, BigDecimal retentionRate,
            BigDecimal retainedAmount) {
    }

    record PayloadPayment(String paymentMethod, BigDecimal total) {
    }
}
