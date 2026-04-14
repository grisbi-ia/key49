package auracore.key49.queue.mapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import auracore.key49.core.model.Document;
import auracore.key49.core.model.Tenant;
import auracore.key49.core.model.enums.DocumentStatus;
import auracore.key49.core.model.enums.DocumentType;
import auracore.key49.ride.generator.CreditNoteRideData;
import auracore.key49.ride.generator.CreditNoteRideGenerator;
import auracore.key49.ride.generator.DebitNoteRideData;
import auracore.key49.ride.generator.DebitNoteRideGenerator;
import auracore.key49.ride.generator.InvoiceRideGenerator;
import auracore.key49.ride.generator.PurchaseClearanceRideData;
import auracore.key49.ride.generator.PurchaseClearanceRideGenerator;
import auracore.key49.ride.generator.RideData;
import auracore.key49.ride.generator.WaybillRideData;
import auracore.key49.ride.generator.WaybillRideGenerator;
import auracore.key49.ride.generator.WithholdingRideData;
import auracore.key49.ride.generator.WithholdingRideGenerator;
import auracore.key49.storage.ObjectStorageService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Genera el RIDE (PDF) a partir de Document + Tenant.
 *
 * <p>
 * Parsea el {@code requestPayload} JSON (snake_case) y construye el record de
 * datos apropiado según el tipo de documento, luego invoca el generador RIDE
 * correspondiente.
 */
@ApplicationScoped
public class RideDataMapper {

    private static final org.jboss.logging.Logger log = org.jboss.logging.Logger.getLogger(RideDataMapper.class);

    private final ObjectMapper objectMapper;
    private final ObjectStorageService storageService;

    @Inject
    public RideDataMapper(ObjectMapper objectMapper, ObjectStorageService storageService) {
        this.objectMapper = objectMapper;
        this.storageService = storageService;
    }

    /**
     * Genera el RIDE (PDF) para el documento dado.
     *
     * @param doc documento autorizado
     * @param tenant tenant emisor
     * @return bytes del PDF generado
     */
    public byte[] generateRide(Document doc, Tenant tenant) {
        var docType = DocumentType.fromSriCode(doc.documentType);
        var logo = loadLogo(tenant);
        return switch (docType) {
            case INVOICE ->
                generateInvoiceRide(doc, tenant, logo);
            case CREDIT_NOTE ->
                generateCreditNoteRide(doc, tenant, logo);
            case DEBIT_NOTE ->
                generateDebitNoteRide(doc, tenant, logo);
            case WITHHOLDING ->
                generateWithholdingRide(doc, tenant, logo);
            case WAYBILL ->
                generateWaybillRide(doc, tenant, logo);
            case PURCHASE_CLEARANCE ->
                generatePurchaseClearanceRide(doc, tenant, logo);
        };
    }

    private byte[] loadLogo(Tenant tenant) {
        if (tenant.logoUrl == null || tenant.logoUrl.isBlank()) {
            return null;
        }
        try {
            return storageService.retrieve(tenant.logoUrl);
        } catch (Exception e) {
            log.warnf("Failed to load logo for tenant %s: %s", tenant.ruc, e.getMessage());
            return null;
        }
    }

    // ── Invoice ──
    private byte[] generateInvoiceRide(Document doc, Tenant tenant, byte[] logo) {
        var raw = parsePayload(doc.requestPayload, InvoicePayload.class,
                new InvoicePayload(List.of(), List.of(), Map.of()));
        var items = buildInvoiceItems(raw.items());
        var totalTaxes = aggregateInvoiceTotalTaxes(items);
        var payments = buildPayments(raw.payments());

        var data = new RideData(
                buildIssuer(tenant),
                doc.accessKey,
                doc.authorizationNumber,
                toLocalDateTime(doc.authorizationDate),
                resolveEnvironment(tenant),
                String.valueOf(tenant.emissionType),
                DocumentType.INVOICE.description(),
                doc.establishment,
                doc.issuePoint,
                doc.sequenceNumber,
                doc.issueDate,
                buildRecipient(doc),
                items,
                totalTaxes,
                payments,
                doc.subtotalBeforeTax,
                doc.totalDiscount,
                doc.tip,
                doc.totalAmount,
                doc.currency,
                raw.additionalInfo() != null ? raw.additionalInfo() : Map.of(),
                doc.status == DocumentStatus.AUTHORIZED,
                logo
        );
        return InvoiceRideGenerator.generate(data);
    }

    // ── Credit Note ──
    private byte[] generateCreditNoteRide(Document doc, Tenant tenant, byte[] logo) {
        var raw = parsePayload(doc.requestPayload, CreditNotePayload.class,
                new CreditNotePayload(null, null, null, null, List.of(), Map.of()));
        var items = buildCreditNoteItems(raw.items());
        var totalTaxes = aggregateTotalTaxesFromTaxes(items.stream()
                .flatMap(i -> i.taxes().stream()).toList());

        var data = new CreditNoteRideData(
                buildIssuer(tenant),
                doc.accessKey,
                doc.authorizationNumber,
                toLocalDateTime(doc.authorizationDate),
                resolveEnvironment(tenant),
                String.valueOf(tenant.emissionType),
                doc.establishment,
                doc.issuePoint,
                doc.sequenceNumber,
                doc.issueDate,
                buildRecipient(doc),
                raw.modifiedDocumentCode(),
                raw.modifiedDocumentNumber(),
                raw.modifiedDocumentDate(),
                raw.reason(),
                items,
                totalTaxes,
                doc.subtotalBeforeTax,
                doc.totalAmount,
                doc.currency,
                raw.additionalInfo() != null ? raw.additionalInfo() : Map.of(),
                doc.status == DocumentStatus.AUTHORIZED,
                logo
        );
        return CreditNoteRideGenerator.generate(data);
    }

    // ── Debit Note ──
    private byte[] generateDebitNoteRide(Document doc, Tenant tenant, byte[] logo) {
        var raw = parsePayload(doc.requestPayload, DebitNotePayload.class,
                new DebitNotePayload(null, null, null, List.of(), List.of(), List.of(), Map.of()));

        var reasons = raw.reasons() != null
                ? raw.reasons().stream()
                        .map(r -> new DebitNoteRideData.Reason(r.description(), r.amount()))
                        .toList()
                : List.<DebitNoteRideData.Reason>of();

        var taxes = buildDebitNoteTaxes(doc, raw.taxes());
        var payments = buildPayments(raw.payments());

        var data = new DebitNoteRideData(
                buildIssuer(tenant),
                doc.accessKey,
                doc.authorizationNumber,
                toLocalDateTime(doc.authorizationDate),
                resolveEnvironment(tenant),
                String.valueOf(tenant.emissionType),
                doc.establishment,
                doc.issuePoint,
                doc.sequenceNumber,
                doc.issueDate,
                buildRecipient(doc),
                raw.modifiedDocumentCode(),
                raw.modifiedDocumentNumber(),
                raw.modifiedDocumentDate(),
                reasons,
                taxes,
                doc.subtotalBeforeTax,
                doc.totalAmount,
                payments,
                raw.additionalInfo() != null ? raw.additionalInfo() : Map.of(),
                doc.status == DocumentStatus.AUTHORIZED,
                logo
        );
        return DebitNoteRideGenerator.generate(data);
    }

    // ── Withholding ──
    private byte[] generateWithholdingRide(Document doc, Tenant tenant, byte[] logo) {
        var raw = parsePayload(doc.requestPayload, WithholdingPayload.class,
                new WithholdingPayload(null, null, false, List.of(), Map.of()));

        var supportingDocs = raw.supportingDocuments() != null
                ? raw.supportingDocuments().stream().map(this::mapWithholdingSupportDoc).toList()
                : List.<WithholdingRideData.SupportingDocumentSummary>of();

        BigDecimal totalRetained = supportingDocs.stream()
                .flatMap(sd -> sd.withholdings().stream())
                .map(WithholdingRideData.WithholdingLineSummary::retainedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        var data = new WithholdingRideData(
                buildIssuer(tenant),
                doc.accessKey,
                doc.authorizationNumber,
                toLocalDateTime(doc.authorizationDate),
                resolveEnvironment(tenant),
                String.valueOf(tenant.emissionType),
                doc.establishment,
                doc.issuePoint,
                doc.sequenceNumber,
                doc.issueDate,
                buildRecipient(doc),
                raw.fiscalPeriod(),
                raw.relatedParty(),
                supportingDocs,
                totalRetained,
                raw.additionalInfo() != null ? raw.additionalInfo() : Map.of(),
                doc.status == DocumentStatus.AUTHORIZED,
                logo
        );
        return WithholdingRideGenerator.generate(data);
    }

    // ── Waybill ──
    private byte[] generateWaybillRide(Document doc, Tenant tenant, byte[] logo) {
        var raw = parsePayload(doc.requestPayload, WaybillPayload.class,
                new WaybillPayload(null, null, null, null, null, List.of(), Map.of()));

        var addressees = raw.addressees() != null
                ? raw.addressees().stream().map(this::mapWaybillAddressee).toList()
                : List.<WaybillRideData.AddresseeSummary>of();

        var data = new WaybillRideData(
                buildIssuer(tenant),
                doc.accessKey,
                doc.authorizationNumber,
                toLocalDateTime(doc.authorizationDate),
                resolveEnvironment(tenant),
                String.valueOf(tenant.emissionType),
                doc.establishment,
                doc.issuePoint,
                doc.sequenceNumber,
                doc.issueDate,
                raw.departureAddress(),
                raw.carrier() != null ? raw.carrier().name() : doc.recipientName,
                raw.carrier() != null ? raw.carrier().idType() : doc.recipientIdType,
                raw.carrier() != null ? raw.carrier().id() : doc.recipientId,
                raw.transportStartDate(),
                raw.transportEndDate(),
                raw.licensePlate(),
                addressees,
                raw.additionalInfo() != null ? raw.additionalInfo() : Map.of(),
                doc.status == DocumentStatus.AUTHORIZED,
                logo
        );
        return WaybillRideGenerator.generate(data);
    }

    // ── Purchase Clearance ──
    private byte[] generatePurchaseClearanceRide(Document doc, Tenant tenant, byte[] logo) {
        var raw = parsePayload(doc.requestPayload, PurchaseClearancePayload.class,
                new PurchaseClearancePayload(List.of(), List.of(), Map.of()));
        var items = buildPurchaseClearanceItems(raw.items());
        var totalTaxes = aggregatePcTotalTaxes(items);
        var payments = buildPayments(raw.payments());

        var data = new PurchaseClearanceRideData(
                buildIssuer(tenant),
                doc.accessKey,
                doc.authorizationNumber,
                toLocalDateTime(doc.authorizationDate),
                resolveEnvironment(tenant),
                String.valueOf(tenant.emissionType),
                doc.establishment,
                doc.issuePoint,
                doc.sequenceNumber,
                doc.issueDate,
                new PurchaseClearanceRideData.Supplier(
                        doc.recipientIdType,
                        doc.recipientId,
                        doc.recipientName,
                        doc.recipientAddress),
                items,
                totalTaxes,
                payments,
                doc.subtotalBeforeTax,
                doc.totalDiscount,
                doc.totalAmount,
                doc.currency,
                raw.additionalInfo() != null ? raw.additionalInfo() : Map.of(),
                doc.status == DocumentStatus.AUTHORIZED,
                logo
        );
        return PurchaseClearanceRideGenerator.generate(data);
    }

    // ── Shared helpers ──
    private RideData.Issuer buildIssuer(Tenant tenant) {
        return new RideData.Issuer(
                tenant.ruc,
                tenant.legalName,
                tenant.tradeName,
                tenant.mainAddress,
                null,
                tenant.requiredAccounting,
                tenant.specialTaxpayer,
                tenant.withholdingAgent,
                tenant.microEnterpriseRegime ? "CONTRIBUYENTE RÉGIMEN RIMPE" : null
        );
    }

    private RideData.Recipient buildRecipient(Document doc) {
        return new RideData.Recipient(
                doc.recipientIdType,
                doc.recipientId,
                doc.recipientName,
                doc.recipientAddress
        );
    }

    private String resolveEnvironment(Tenant tenant) {
        return "production".equals(tenant.environment) ? "PRODUCCIÓN" : "PRUEBAS";
    }

    private LocalDateTime toLocalDateTime(java.time.Instant instant) {
        if (instant == null) {
            return null;
        }
        return LocalDateTime.ofInstant(instant, auracore.key49.core.Key49Constants.EC_ZONE);
    }

    // ── Invoice items & taxes ──
    private List<RideData.Item> buildInvoiceItems(List<RawItem> rawItems) {
        if (rawItems == null || rawItems.isEmpty()) {
            return List.of();
        }
        var result = new ArrayList<RideData.Item>(rawItems.size());
        for (var raw : rawItems) {
            var qty = raw.quantity() != null ? raw.quantity() : BigDecimal.ZERO;
            var price = raw.unitPrice() != null ? raw.unitPrice() : BigDecimal.ZERO;
            var discount = raw.discount() != null ? raw.discount() : BigDecimal.ZERO;
            var subtotal = qty.multiply(price).subtract(discount).setScale(2, RoundingMode.HALF_UP);
            var taxes = buildTaxes(raw.taxes(), subtotal);

            result.add(new RideData.Item(
                    raw.mainCode(), raw.description(), qty, price, discount, subtotal, taxes));
        }
        return List.copyOf(result);
    }

    private List<RideData.TotalTax> aggregateInvoiceTotalTaxes(List<RideData.Item> items) {
        var map = new LinkedHashMap<String, TaxAccumulator>();
        for (var item : items) {
            for (var tax : item.taxes()) {
                var key = tax.taxCode() + "|" + tax.rateCode();
                map.computeIfAbsent(key, k -> new TaxAccumulator(tax.taxCode(), tax.rateCode(), tax.rate()))
                        .add(tax.taxableBase(), tax.amount());
            }
        }
        return map.values().stream()
                .map(a -> new RideData.TotalTax(a.taxCode, a.rateCode,
                a.base.setScale(2, RoundingMode.HALF_UP), a.rate,
                a.amount.setScale(2, RoundingMode.HALF_UP)))
                .toList();
    }

    // ── Credit Note items ──
    private List<CreditNoteRideData.Item> buildCreditNoteItems(List<RawCreditNoteItem> rawItems) {
        if (rawItems == null || rawItems.isEmpty()) {
            return List.of();
        }
        var result = new ArrayList<CreditNoteRideData.Item>(rawItems.size());
        for (var raw : rawItems) {
            var qty = raw.quantity() != null ? raw.quantity() : BigDecimal.ZERO;
            var price = raw.unitPrice() != null ? raw.unitPrice() : BigDecimal.ZERO;
            var discount = raw.discount() != null ? raw.discount() : BigDecimal.ZERO;
            var subtotal = qty.multiply(price).subtract(discount).setScale(2, RoundingMode.HALF_UP);
            var taxes = buildTaxes(raw.taxes(), subtotal);

            result.add(new CreditNoteRideData.Item(
                    raw.internalCode(), raw.description(), qty, price, discount, subtotal, taxes));
        }
        return List.copyOf(result);
    }

    private List<RideData.TotalTax> aggregateTotalTaxesFromTaxes(List<RideData.Tax> allTaxes) {
        var map = new LinkedHashMap<String, TaxAccumulator>();
        for (var tax : allTaxes) {
            var key = tax.taxCode() + "|" + tax.rateCode();
            map.computeIfAbsent(key, k -> new TaxAccumulator(tax.taxCode(), tax.rateCode(), tax.rate()))
                    .add(tax.taxableBase(), tax.amount());
        }
        return map.values().stream()
                .map(a -> new RideData.TotalTax(a.taxCode, a.rateCode,
                a.base.setScale(2, RoundingMode.HALF_UP), a.rate,
                a.amount.setScale(2, RoundingMode.HALF_UP)))
                .toList();
    }

    // ── Debit Note taxes ──
    private List<RideData.TotalTax> buildDebitNoteTaxes(Document doc, List<RawTax> rawTaxes) {
        if (rawTaxes == null || rawTaxes.isEmpty()) {
            return List.of();
        }
        var taxBase = doc.subtotalBeforeTax;
        return rawTaxes.stream().map(t -> {
            var rate = t.rate() != null ? t.rate() : BigDecimal.ZERO;
            var amount = taxBase.multiply(rate)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            return new RideData.TotalTax(t.code(), t.rateCode(), taxBase, rate, amount);
        }).toList();
    }

    // ── Purchase Clearance items ──
    private List<PurchaseClearanceRideData.Item> buildPurchaseClearanceItems(List<RawItem> rawItems) {
        if (rawItems == null || rawItems.isEmpty()) {
            return List.of();
        }
        var result = new ArrayList<PurchaseClearanceRideData.Item>(rawItems.size());
        for (var raw : rawItems) {
            var qty = raw.quantity() != null ? raw.quantity() : BigDecimal.ZERO;
            var price = raw.unitPrice() != null ? raw.unitPrice() : BigDecimal.ZERO;
            var discount = raw.discount() != null ? raw.discount() : BigDecimal.ZERO;
            var subtotal = qty.multiply(price).subtract(discount).setScale(2, RoundingMode.HALF_UP);
            var taxes = buildTaxes(raw.taxes(), subtotal);

            result.add(new PurchaseClearanceRideData.Item(
                    raw.mainCode(), raw.description(), qty, price, discount, subtotal, taxes));
        }
        return List.copyOf(result);
    }

    private List<RideData.TotalTax> aggregatePcTotalTaxes(List<PurchaseClearanceRideData.Item> items) {
        var map = new LinkedHashMap<String, TaxAccumulator>();
        for (var item : items) {
            for (var tax : item.taxes()) {
                var key = tax.taxCode() + "|" + tax.rateCode();
                map.computeIfAbsent(key, k -> new TaxAccumulator(tax.taxCode(), tax.rateCode(), tax.rate()))
                        .add(tax.taxableBase(), tax.amount());
            }
        }
        return map.values().stream()
                .map(a -> new RideData.TotalTax(a.taxCode, a.rateCode,
                a.base.setScale(2, RoundingMode.HALF_UP), a.rate,
                a.amount.setScale(2, RoundingMode.HALF_UP)))
                .toList();
    }

    // ── Shared tax builder ──
    private List<RideData.Tax> buildTaxes(List<RawTax> rawTaxes, BigDecimal taxableBase) {
        if (rawTaxes == null || rawTaxes.isEmpty()) {
            return List.of();
        }
        var result = new ArrayList<RideData.Tax>(rawTaxes.size());
        for (var raw : rawTaxes) {
            var rate = raw.rate() != null ? raw.rate() : BigDecimal.ZERO;
            var amount = taxableBase.multiply(rate)
                    .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
            result.add(new RideData.Tax(raw.code(), raw.rateCode(), rate, taxableBase, amount));
        }
        return List.copyOf(result);
    }

    // ── Shared payment builder ──
    private List<RideData.Payment> buildPayments(List<RawPayment> rawPayments) {
        if (rawPayments == null || rawPayments.isEmpty()) {
            return List.of();
        }
        return rawPayments.stream()
                .map(p -> new RideData.Payment(p.paymentMethod(), p.total(), p.term(), p.timeUnit()))
                .toList();
    }

    // ── Withholding helpers ──
    private WithholdingRideData.SupportingDocumentSummary mapWithholdingSupportDoc(
            PayloadSupportingDocument sd) {
        var withholdings = sd.withholdings() != null
                ? sd.withholdings().stream()
                        .map(w -> new WithholdingRideData.WithholdingLineSummary(
                        w.code(), w.retentionCode(), w.taxableBase(),
                        w.retentionRate(), w.retainedAmount()))
                        .toList()
                : List.<WithholdingRideData.WithholdingLineSummary>of();

        var payments = sd.payments() != null
                ? sd.payments().stream()
                        .map(p -> new RideData.Payment(p.paymentMethod(), p.total(), null, null))
                        .toList()
                : List.<RideData.Payment>of();

        return new WithholdingRideData.SupportingDocumentSummary(
                sd.supportCode(), sd.documentCode(), sd.documentNumber(),
                sd.issueDate(),
                sd.totalWithoutTax(), sd.totalAmount(),
                withholdings, payments);
    }

    // ── Waybill helpers ──
    private WaybillRideData.AddresseeSummary mapWaybillAddressee(PayloadAddressee addr) {
        var items = addr.items() != null
                ? addr.items().stream()
                        .map(i -> new WaybillRideData.ItemSummary(
                        i.mainCode(), i.description(), i.quantity()))
                        .toList()
                : List.<WaybillRideData.ItemSummary>of();

        return new WaybillRideData.AddresseeSummary(
                addr.id(), addr.name(), addr.address(),
                addr.transferReason(), addr.supportDocumentNumber(), items);
    }

    // ── JSON parsing ──
    private <T> T parsePayload(String requestPayload, Class<T> type, T fallback) {
        if (requestPayload == null || requestPayload.isBlank()) {
            return fallback;
        }
        try {
            return objectMapper.readValue(requestPayload, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid request payload JSON", e);
        }
    }

    // ── Payload records (snake_case by Jackson global config) ──
    record InvoicePayload(
            List<RawItem> items,
            List<RawPayment> payments,
            Map<String, String> additionalInfo) {

    }

    record CreditNotePayload(
            String modifiedDocumentCode,
            String modifiedDocumentNumber,
            LocalDate modifiedDocumentDate,
            String reason,
            List<RawCreditNoteItem> items,
            Map<String, String> additionalInfo) {

    }

    record DebitNotePayload(
            String modifiedDocumentCode,
            String modifiedDocumentNumber,
            LocalDate modifiedDocumentDate,
            List<PayloadReason> reasons,
            List<RawTax> taxes,
            List<RawPayment> payments,
            Map<String, String> additionalInfo) {

    }

    record WithholdingPayload(
            PayloadSubject subject,
            String fiscalPeriod,
            boolean relatedParty,
            List<PayloadSupportingDocument> supportingDocuments,
            Map<String, String> additionalInfo) {

    }

    record WaybillPayload(
            String departureAddress,
            PayloadCarrier carrier,
            LocalDate transportStartDate,
            LocalDate transportEndDate,
            String licensePlate,
            List<PayloadAddressee> addressees,
            Map<String, String> additionalInfo) {

    }

    record PurchaseClearancePayload(
            List<RawItem> items,
            List<RawPayment> payments,
            Map<String, String> additionalInfo) {

    }

    // ── Shared raw records ──
    record RawItem(
            String mainCode, String auxiliaryCode, String description,
            String unitOfMeasure, BigDecimal quantity, BigDecimal unitPrice,
            BigDecimal discount, List<RawTax> taxes) {

    }

    record RawCreditNoteItem(
            String internalCode, String additionalCode, String description,
            BigDecimal quantity, BigDecimal unitPrice, BigDecimal discount,
            List<RawTax> taxes) {

    }

    record RawTax(String code, String rateCode, BigDecimal rate) {

    }

    record RawPayment(String paymentMethod, BigDecimal total, Integer term, String timeUnit) {

    }

    record PayloadReason(String description, BigDecimal amount) {

    }

    record PayloadSubject(String idType, String id, String name, String subjectType) {

    }

    record PayloadSupportingDocument(
            String supportCode, String documentCode, String documentNumber,
            LocalDate issueDate,
            BigDecimal totalWithoutTax, BigDecimal totalAmount,
            List<PayloadWithholdingLine> withholdings,
            List<PayloadWhPayment> payments) {

    }

    record PayloadWithholdingLine(
            String code, String retentionCode,
            BigDecimal taxableBase, BigDecimal retentionRate,
            BigDecimal retainedAmount) {

    }

    record PayloadWhPayment(String paymentMethod, BigDecimal total) {

    }

    record PayloadCarrier(String idType, String id, String name) {

    }

    record PayloadAddressee(
            String id, String name, String address, String transferReason,
            String supportDocumentNumber,
            List<PayloadAddresseeItem> items) {

    }

    record PayloadAddresseeItem(String mainCode, String description, BigDecimal quantity) {

    }

    // ── Tax accumulator ──
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
