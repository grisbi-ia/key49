package auracore.key49.xml.builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Datos de entrada para generar el XML de liquidación de compra electrónica.
 * Agrupa la información tributaria del emisor con los datos del comprobante.
 */
public record PurchaseClearanceData(
        TaxpayerInfo taxpayer,
        String accessKey,
        String establishment,
        String issuePoint,
        String sequenceNumber,
        LocalDate issueDate,
        Supplier supplier,
        List<Item> items,
        List<TotalTax> totalTaxes,
        List<Payment> payments,
        BigDecimal subtotalBeforeTax,
        BigDecimal totalDiscount,
        BigDecimal totalAmount,
        String currency,
        Map<String, String> additionalInfo
        ) {

    /**
     * Información tributaria del emisor (extraída del Tenant).
     */
    public record TaxpayerInfo(
            String environment,
            String emissionType,
            String legalName,
            String tradeName,
            String ruc,
            String mainAddress,
            String establishmentAddress,
            boolean requiredAccounting,
            String specialTaxpayer,
            String withholdingAgent,
            String rimpeContributor
            ) {

    }

    /**
     * Datos del proveedor (sujeto de la liquidación de compra).
     */
    public record Supplier(
            String idType,
            String id,
            String name,
            String address
            ) {

    }

    /**
     * Línea de detalle de la liquidación de compra.
     */
    public record Item(
            String mainCode,
            String auxiliaryCode,
            String description,
            String unitOfMeasure,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal discount,
            BigDecimal subtotalBeforeTax,
            List<Tax> taxes
            ) {

    }

    /**
     * Impuesto aplicado a un ítem de detalle.
     */
    public record Tax(
            String taxCode,
            String rateCode,
            BigDecimal rate,
            BigDecimal taxableBase,
            BigDecimal amount
            ) {

    }

    /**
     * Impuesto totalizado para infoLiquidacionCompra/totalConImpuestos.
     */
    public record TotalTax(
            String taxCode,
            String rateCode,
            BigDecimal discountAdditional,
            BigDecimal taxableBase,
            BigDecimal rate,
            BigDecimal amount
            ) {

    }

    /**
     * Forma de pago de la liquidación de compra.
     */
    public record Payment(
            String paymentMethod,
            BigDecimal total,
            Integer term,
            String timeUnit
            ) {

    }
}
