package auracore.key49.queue.mapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import auracore.key49.core.model.Document;
import auracore.key49.core.model.Tenant;
import auracore.key49.xml.builder.CreditNoteData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Mapea Document + Tenant + accessKey a CreditNoteData para generar el XML de
 * nota de crédito.
 *
 * <p>
 * Extrae ítems, impuestos totalizados, datos del doc modificado e información
 * adicional del {@code requestPayload} JSON del documento.
 */

@ApplicationScoped
public class CreditNoteDataMapper {

    private final ObjectMapper objectMapper;

    @Inject
    public CreditNoteDataMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Construye un CreditNoteData completo a partir de los datos del documento,
     * el tenant emisor y la clave de acceso generada.
     *
     * @param doc       documento con datos del comprobante
     * @param tenant    tenant emisor con información tributaria
     * @param accessKey clave de acceso de 49 dígitos
     * @return datos listos para generar el XML de nota de crédito
     */
    public CreditNoteData build(Document doc, Tenant tenant, String accessKey) {
        var payload = parsePayload(doc.requestPayload);

        var items = mapItems(payload.items());
        var totalTaxes = computeTotalTaxes(items);

        return new CreditNoteData(
                buildTaxpayerInfo(tenant),
                accessKey,
                doc.establishment,
                doc.issuePoint,
                doc.sequenceNumber,
                doc.issueDate,
                new CreditNoteData.Recipient(
                        doc.recipientIdType,
                        doc.recipientId,
                        doc.recipientName),
                payload.modifiedDocumentCode(),
                payload.modifiedDocumentNumber(),
                payload.modifiedDocumentDate(),
                payload.reason(),
                items,
                totalTaxes,
                doc.subtotalBeforeTax,
                doc.totalAmount,
                doc.currency,
                payload.additionalInfo() != null ? payload.additionalInfo() : Map.of()
        );
    }

    private CreditNoteData.TaxpayerInfo buildTaxpayerInfo(Tenant tenant) {
        var envCode = "production".equals(tenant.environment) ? "2" : "1";
        return new CreditNoteData.TaxpayerInfo(
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

    private List<CreditNoteData.Item> mapItems(List<PayloadItem> payloadItems) {
        if (payloadItems == null || payloadItems.isEmpty()) {
            return List.of();
        }
        return payloadItems.stream()
                .map(pi -> {
                    var taxes = pi.taxes() != null
                            ? pi.taxes().stream()
                            .map(t -> new CreditNoteData.Tax(
                                    t.code(), t.rateCode(), t.rate(),
                                    t.taxableBase(), t.amount()))
                            .toList()
                            : List.<CreditNoteData.Tax>of();

                    BigDecimal lineTotal = pi.quantity().multiply(pi.unitPrice());
                    BigDecimal discount = pi.discount() != null ? pi.discount() : BigDecimal.ZERO;
                    BigDecimal subtotal = lineTotal.subtract(discount);

                    return new CreditNoteData.Item(
                            pi.internalCode(), pi.additionalCode(),
                            pi.description(), pi.quantity(), pi.unitPrice(),
                            discount, subtotal, taxes);
                })
                .toList();
    }

    /**
     * Calcula impuestos totalizados agrupados por (taxCode, rateCode).
     */
    private List<CreditNoteData.TotalTax> computeTotalTaxes(List<CreditNoteData.Item> items) {
        var taxMap = new HashMap<String, BigDecimal[]>();
        for (var item : items) {
            if (item.taxes() == null) continue;
            for (var tax : item.taxes()) {
                var key = tax.taxCode() + "|" + tax.rateCode();
                var accum = taxMap.computeIfAbsent(key, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                if (tax.taxableBase() != null) {
                    accum[0] = accum[0].add(tax.taxableBase());
                }
                if (tax.amount() != null) {
                    accum[1] = accum[1].add(tax.amount());
                } else if (tax.rate() != null && tax.taxableBase() != null) {
                    accum[1] = accum[1].add(
                            tax.taxableBase().multiply(tax.rate())
                                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
                }
            }
        }

        var result = new ArrayList<CreditNoteData.TotalTax>();
        for (var entry : taxMap.entrySet()) {
            var parts = entry.getKey().split("\\|", 2);
            result.add(new CreditNoteData.TotalTax(
                    parts[0], parts.length > 1 ? parts[1] : null,
                    entry.getValue()[0].setScale(2, RoundingMode.HALF_UP),
                    entry.getValue()[1].setScale(2, RoundingMode.HALF_UP)));
        }
        return List.copyOf(result);
    }

    private CreditNotePayload parsePayload(String requestPayload) {
        if (requestPayload == null || requestPayload.isBlank()) {
            return new CreditNotePayload(null, null, null, null, List.of(), Map.of());
        }
        try {
            var parsed = objectMapper.readValue(requestPayload, CreditNotePayload.class);
            return new CreditNotePayload(
                    parsed.modifiedDocumentCode(),
                    parsed.modifiedDocumentNumber(),
                    parsed.modifiedDocumentDate(),
                    parsed.reason(),
                    parsed.items() != null ? parsed.items() : List.of(),
                    parsed.additionalInfo() != null ? parsed.additionalInfo() : Map.of()
            );
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid request payload JSON", e);
        }
    }

    /**
     * Estructura interna que coincide con la serialización JSON de CreateCreditNoteRequest.
     */
    record CreditNotePayload(
            String modifiedDocumentCode,
            String modifiedDocumentNumber,
            LocalDate modifiedDocumentDate,
            String reason,
            List<PayloadItem> items,
            Map<String, String> additionalInfo
    ) {}

    record PayloadItem(
            String internalCode,
            String additionalCode,
            String description,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal discount,
            List<PayloadTax> taxes
    ) {}

    record PayloadTax(
            String code,
            String rateCode,
            BigDecimal rate,
            BigDecimal taxableBase,
            BigDecimal amount
    ) {}
}
