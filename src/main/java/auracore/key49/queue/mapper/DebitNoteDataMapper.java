package auracore.key49.queue.mapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import auracore.key49.core.model.Document;
import auracore.key49.core.model.Tenant;
import auracore.key49.xml.builder.DebitNoteData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Mapea Document + Tenant + accessKey a DebitNoteData para generar el XML de
 * nota de débito.
 *
 * <p>
 * Extrae motivos, impuestos, pagos, datos del doc modificado e información
 * adicional del {@code requestPayload} JSON del documento.
 */

@ApplicationScoped
public class DebitNoteDataMapper {

    private final ObjectMapper objectMapper;

    @Inject
    public DebitNoteDataMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Construye un DebitNoteData completo a partir de los datos del documento,
     * el tenant emisor y la clave de acceso generada.
     *
     * @param doc       documento con datos del comprobante
     * @param tenant    tenant emisor con información tributaria
     * @param accessKey clave de acceso de 49 dígitos
     * @return datos listos para generar el XML de nota de débito
     */
    public DebitNoteData build(Document doc, Tenant tenant, String accessKey) {
        var payload = parsePayload(doc.requestPayload);

        var reasons = mapReasons(payload.reasons());
        var taxes = computeTaxes(doc, payload.taxes());
        var payments = mapPayments(payload.payments());

        BigDecimal totalWithoutTax = doc.subtotalBeforeTax;
        BigDecimal totalValue = doc.totalAmount;

        return new DebitNoteData(
                buildTaxpayerInfo(tenant),
                accessKey,
                doc.establishment,
                doc.issuePoint,
                doc.sequenceNumber,
                doc.issueDate,
                new DebitNoteData.Recipient(
                        doc.recipientIdType,
                        doc.recipientId,
                        doc.recipientName),
                payload.modifiedDocumentCode(),
                payload.modifiedDocumentNumber(),
                payload.modifiedDocumentDate(),
                totalWithoutTax,
                taxes,
                totalValue,
                payments,
                reasons,
                payload.additionalInfo() != null ? payload.additionalInfo() : Map.of()
        );
    }

    private DebitNoteData.TaxpayerInfo buildTaxpayerInfo(Tenant tenant) {
        var envCode = "production".equals(tenant.environment) ? "2" : "1";
        return new DebitNoteData.TaxpayerInfo(
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
                tenant.microEnterpriseRegime ? "CONTRIBUYENTE RÉGIMEN RIMPE" : null
        );
    }

    private List<DebitNoteData.Reason> mapReasons(List<PayloadReason> payloadReasons) {
        if (payloadReasons == null || payloadReasons.isEmpty()) {
            return List.of();
        }
        return payloadReasons.stream()
                .map(r -> new DebitNoteData.Reason(r.description(), r.amount()))
                .toList();
    }

    private List<DebitNoteData.Tax> computeTaxes(Document doc, List<PayloadTax> payloadTaxes) {
        if (payloadTaxes == null || payloadTaxes.isEmpty()) {
            return List.of();
        }

        BigDecimal taxBase = doc.subtotalBeforeTax;
        var result = new ArrayList<DebitNoteData.Tax>();

        for (var tax : payloadTaxes) {
            BigDecimal rate = tax.rate() != null ? tax.rate() : BigDecimal.ZERO;
            BigDecimal amount = taxBase.multiply(rate)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

            result.add(new DebitNoteData.Tax(
                    tax.code(), tax.rateCode(), rate, taxBase, amount));
        }

        return List.copyOf(result);
    }

    private List<DebitNoteData.Payment> mapPayments(List<PayloadPayment> payloadPayments) {
        if (payloadPayments == null || payloadPayments.isEmpty()) {
            return List.of();
        }
        return payloadPayments.stream()
                .map(p -> new DebitNoteData.Payment(
                        p.paymentMethod(), p.total(), p.term(), p.timeUnit()))
                .toList();
    }

    private DebitNotePayload parsePayload(String requestPayload) {
        if (requestPayload == null || requestPayload.isBlank()) {
            return new DebitNotePayload(null, null, null, List.of(), List.of(), List.of(), Map.of());
        }
        try {
            var parsed = objectMapper.readValue(requestPayload, DebitNotePayload.class);
            return new DebitNotePayload(
                    parsed.modifiedDocumentCode(),
                    parsed.modifiedDocumentNumber(),
                    parsed.modifiedDocumentDate(),
                    parsed.reasons() != null ? parsed.reasons() : List.of(),
                    parsed.taxes() != null ? parsed.taxes() : List.of(),
                    parsed.payments() != null ? parsed.payments() : List.of(),
                    parsed.additionalInfo() != null ? parsed.additionalInfo() : Map.of()
            );
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid request payload JSON", e);
        }
    }

    /**
     * Estructura interna que coincide con la serialización JSON de CreateDebitNoteRequest.
     */
    record DebitNotePayload(
            String modifiedDocumentCode,
            String modifiedDocumentNumber,
            LocalDate modifiedDocumentDate,
            List<PayloadReason> reasons,
            List<PayloadTax> taxes,
            List<PayloadPayment> payments,
            Map<String, String> additionalInfo
    ) {}

    record PayloadReason(
            String description,
            BigDecimal amount
    ) {}

    record PayloadTax(
            String code,
            String rateCode,
            BigDecimal rate
    ) {}

    record PayloadPayment(
            String paymentMethod,
            BigDecimal total,
            Integer term,
            String timeUnit
    ) {}
}
