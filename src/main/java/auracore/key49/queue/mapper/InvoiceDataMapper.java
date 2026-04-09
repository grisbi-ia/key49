package auracore.key49.queue.mapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import auracore.key49.core.model.Document;
import auracore.key49.core.model.Tenant;
import auracore.key49.xml.builder.InvoiceData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Mapea Document + Tenant + accessKey a InvoiceData para generar el XML de
 * factura.
 *
 * <p>
 * El {@code requestPayload} almacenado contiene la estructura de
 * {@code CreateInvoiceRequest} (serializado con SNAKE_CASE). Este mapper parsea
 * esa estructura y computa los campos derivados que requiere el XML del SRI:
 * subtotalBeforeTax por ítem, taxableBase y amount por impuesto, y los
 * totalTaxes agregados.
 */
@ApplicationScoped
public class InvoiceDataMapper {

    private final ObjectMapper objectMapper;

    @Inject
    public InvoiceDataMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Construye un InvoiceData completo a partir de los datos del documento, el
     * tenant emisor y la clave de acceso generada.
     *
     * @param doc documento con datos del comprobante
     * @param tenant tenant emisor con información tributaria
     * @param accessKey clave de acceso de 49 dígitos
     * @return datos listos para generar el XML de factura
     */
    public InvoiceData build(Document doc, Tenant tenant, String accessKey) {
        var raw = parsePayload(doc.requestPayload);
        var items = buildItems(raw.items());
        var totalTaxes = aggregateTotalTaxes(items);
        var payments = buildPayments(raw.payments());

        return new InvoiceData(
                buildTaxpayerInfo(tenant),
                accessKey,
                doc.establishment,
                doc.issuePoint,
                doc.sequenceNumber,
                doc.issueDate,
                new InvoiceData.Recipient(
                        doc.recipientIdType,
                        doc.recipientId,
                        doc.recipientName,
                        doc.recipientAddress),
                items,
                totalTaxes,
                payments,
                doc.subtotalBeforeTax,
                doc.totalDiscount,
                doc.tip,
                doc.totalAmount,
                doc.currency,
                raw.additionalInfo() != null ? raw.additionalInfo() : Map.of()
        );
    }

    private InvoiceData.TaxpayerInfo buildTaxpayerInfo(Tenant tenant) {
        var envCode = "production".equals(tenant.environment) ? "2" : "1";
        return new InvoiceData.TaxpayerInfo(
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

    private List<InvoiceData.Item> buildItems(List<RawItem> rawItems) {
        if (rawItems == null || rawItems.isEmpty()) {
            return List.of();
        }
        var result = new ArrayList<InvoiceData.Item>(rawItems.size());
        for (var raw : rawItems) {
            var qty = raw.quantity() != null ? raw.quantity() : BigDecimal.ZERO;
            var price = raw.unitPrice() != null ? raw.unitPrice() : BigDecimal.ZERO;
            var discount = raw.discount() != null ? raw.discount() : BigDecimal.ZERO;
            var subtotal = qty.multiply(price).subtract(discount).setScale(2, RoundingMode.HALF_UP);

            var taxes = buildItemTaxes(raw.taxes(), subtotal);

            result.add(new InvoiceData.Item(
                    raw.mainCode(),
                    raw.auxiliaryCode(),
                    raw.description(),
                    raw.unitOfMeasure(),
                    qty,
                    price,
                    discount,
                    subtotal,
                    taxes
            ));
        }
        return List.copyOf(result);
    }

    private List<InvoiceData.Tax> buildItemTaxes(List<RawTax> rawTaxes, BigDecimal taxableBase) {
        if (rawTaxes == null || rawTaxes.isEmpty()) {
            return List.of();
        }
        var result = new ArrayList<InvoiceData.Tax>(rawTaxes.size());
        for (var raw : rawTaxes) {
            var rate = raw.rate() != null ? raw.rate() : BigDecimal.ZERO;
            var amount = taxableBase
                    .multiply(rate)
                    .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);

            result.add(new InvoiceData.Tax(
                    raw.code(),
                    raw.rateCode(),
                    rate,
                    taxableBase,
                    amount
            ));
        }
        return List.copyOf(result);
    }

    private List<InvoiceData.TotalTax> aggregateTotalTaxes(List<InvoiceData.Item> items) {
        // Agrupa por (taxCode, rateCode) y suma baseImponible y valor
        var map = new LinkedHashMap<String, TaxAccumulator>();
        for (var item : items) {
            for (var tax : item.taxes()) {
                var key = tax.taxCode() + "|" + tax.rateCode();
                map.computeIfAbsent(key, k -> new TaxAccumulator(tax.taxCode(), tax.rateCode(), tax.rate()))
                        .add(tax.taxableBase(), tax.amount());
            }
        }
        var result = new ArrayList<InvoiceData.TotalTax>(map.size());
        for (var acc : map.values()) {
            result.add(new InvoiceData.TotalTax(
                    acc.taxCode,
                    acc.rateCode,
                    null,
                    acc.base.setScale(2, RoundingMode.HALF_UP),
                    acc.rate,
                    acc.amount.setScale(2, RoundingMode.HALF_UP)
            ));
        }
        return List.copyOf(result);
    }

    private List<InvoiceData.Payment> buildPayments(List<RawPayment> rawPayments) {
        if (rawPayments == null || rawPayments.isEmpty()) {
            return List.of();
        }
        var result = new ArrayList<InvoiceData.Payment>(rawPayments.size());
        for (var raw : rawPayments) {
            result.add(new InvoiceData.Payment(
                    raw.paymentMethod(),
                    raw.total(),
                    raw.term(),
                    raw.timeUnit()
            ));
        }
        return List.copyOf(result);
    }

    private RawPayload parsePayload(String requestPayload) {
        if (requestPayload == null || requestPayload.isBlank()) {
            return new RawPayload(List.of(), List.of(), Map.of());
        }
        try {
            var parsed = objectMapper.readValue(requestPayload, RawPayload.class);
            return new RawPayload(
                    parsed.items() != null ? parsed.items() : List.of(),
                    parsed.payments() != null ? parsed.payments() : List.of(),
                    parsed.additionalInfo() != null ? parsed.additionalInfo() : Map.of()
            );
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid request payload JSON", e);
        }
    }

    // ── Records internos que reflejan la estructura de CreateInvoiceRequest ──
    record RawPayload(
            List<RawItem> items,
            List<RawPayment> payments,
            Map<String, String> additionalInfo
            ) {

    }

    record RawItem(
            String mainCode,
            String auxiliaryCode,
            String description,
            String unitOfMeasure,
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

    record RawPayment(
            String paymentMethod,
            BigDecimal total,
            Integer term,
            String timeUnit
            ) {

    }

    private static class TaxAccumulator {

        final String taxCode;
        final String rateCode;
        final BigDecimal rate;
        BigDecimal base = BigDecimal.ZERO;
        BigDecimal amount = BigDecimal.ZERO;

        TaxAccumulator(String taxCode, String rateCode, BigDecimal rate) {
            this.taxCode = taxCode;
            this.rateCode = rateCode;
            this.rate = rate;
        }

        void add(BigDecimal taxableBase, BigDecimal taxAmount) {
            this.base = this.base.add(taxableBase);
            this.amount = this.amount.add(taxAmount);
        }
    }
}
