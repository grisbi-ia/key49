package auracore.key49.queue.mapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * El {@code requestPayload} almacenado contiene la estructura de
 * {@code CreateCreditNoteRequest} (serializado con SNAKE_CASE). Este mapper
 * parsea esa estructura y computa los campos derivados que requiere el XML del
 * SRI: subtotalBeforeTax por ítem, taxableBase y amount por impuesto, y los
 * totalTaxes agregados.
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
     * @param doc documento con datos del comprobante
     * @param tenant tenant emisor con información tributaria
     * @param accessKey clave de acceso de 49 dígitos
     * @return datos listos para generar el XML de nota de crédito
     */
    public CreditNoteData build(Document doc, Tenant tenant, String accessKey) {
        var raw = parsePayload(doc.requestPayload);
        var items = buildItems(raw.items());
        var totalTaxes = aggregateTotalTaxes(items);

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
                raw.modifiedDocumentCode(),
                raw.modifiedDocumentNumber(),
                raw.modifiedDocumentDate(),
                raw.reason(),
                items,
                totalTaxes,
                doc.subtotalBeforeTax,
                doc.totalAmount,
                doc.currency,
                raw.additionalInfo() != null ? raw.additionalInfo() : Map.of()
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

    private List<CreditNoteData.Item> buildItems(List<RawItem> rawItems) {
        if (rawItems == null || rawItems.isEmpty()) {
            return List.of();
        }
        var result = new ArrayList<CreditNoteData.Item>(rawItems.size());
        for (var raw : rawItems) {
            var qty = raw.quantity() != null ? raw.quantity() : BigDecimal.ZERO;
            var price = raw.unitPrice() != null ? raw.unitPrice() : BigDecimal.ZERO;
            var discount = raw.discount() != null ? raw.discount() : BigDecimal.ZERO;
            var subtotal = qty.multiply(price).subtract(discount).setScale(2, RoundingMode.HALF_UP);

            var taxes = buildItemTaxes(raw.taxes(), subtotal);

            result.add(new CreditNoteData.Item(
                    raw.internalCode(),
                    raw.additionalCode(),
                    raw.description(),
                    qty,
                    price,
                    discount,
                    subtotal,
                    taxes
            ));
        }
        return List.copyOf(result);
    }

    private List<CreditNoteData.Tax> buildItemTaxes(List<RawTax> rawTaxes, BigDecimal taxableBase) {
        if (rawTaxes == null || rawTaxes.isEmpty()) {
            return List.of();
        }
        var result = new ArrayList<CreditNoteData.Tax>(rawTaxes.size());
        for (var raw : rawTaxes) {
            var rate = raw.rate() != null ? raw.rate() : BigDecimal.ZERO;
            var amount = taxableBase
                    .multiply(rate)
                    .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);

            result.add(new CreditNoteData.Tax(
                    raw.code(),
                    raw.rateCode(),
                    rate,
                    taxableBase,
                    amount
            ));
        }
        return List.copyOf(result);
    }

    private List<CreditNoteData.TotalTax> aggregateTotalTaxes(List<CreditNoteData.Item> items) {
        var map = new LinkedHashMap<String, TaxAccumulator>();
        for (var item : items) {
            for (var tax : item.taxes()) {
                var key = tax.taxCode() + "|" + tax.rateCode();
                map.computeIfAbsent(key, k -> new TaxAccumulator(tax.taxCode(), tax.rateCode()))
                        .add(tax.taxableBase(), tax.amount());
            }
        }
        var result = new ArrayList<CreditNoteData.TotalTax>(map.size());
        for (var acc : map.values()) {
            result.add(new CreditNoteData.TotalTax(
                    acc.taxCode,
                    acc.rateCode,
                    acc.base.setScale(2, RoundingMode.HALF_UP),
                    acc.amount.setScale(2, RoundingMode.HALF_UP)
            ));
        }
        return List.copyOf(result);
    }

    private RawPayload parsePayload(String requestPayload) {
        if (requestPayload == null || requestPayload.isBlank()) {
            return new RawPayload(null, null, null, null, List.of(), Map.of());
        }
        try {
            var parsed = objectMapper.readValue(requestPayload, RawPayload.class);
            return new RawPayload(
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

    // ── Records internos que reflejan la estructura de CreateCreditNoteRequest ──
    record RawPayload(
            String modifiedDocumentCode,
            String modifiedDocumentNumber,
            LocalDate modifiedDocumentDate,
            String reason,
            List<RawItem> items,
            Map<String, String> additionalInfo
            ) {

    }

    record RawItem(
            String internalCode,
            String additionalCode,
            String description,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal discount,
            List<RawTax> taxes
            ) {

    }

    record RawTax(
            String code,
            String rateCode,
            BigDecimal rate
            ) {

    }

    private static class TaxAccumulator {

        final String taxCode;
        final String rateCode;
        BigDecimal base = BigDecimal.ZERO;
        BigDecimal amount = BigDecimal.ZERO;

        TaxAccumulator(String taxCode, String rateCode) {
            this.taxCode = taxCode;
            this.rateCode = rateCode;
        }

        void add(BigDecimal taxableBase, BigDecimal taxAmount) {
            this.base = this.base.add(taxableBase);
            this.amount = this.amount.add(taxAmount);
        }
    }
}
