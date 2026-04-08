package auracore.key49.xml.builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Datos de entrada para generar el XML de nota de crédito electrónica. Agrupa
 * la información tributaria del emisor con los datos del comprobante.
 */
public record CreditNoteData(
        TaxpayerInfo taxpayer,
        String accessKey,
        String establishment,
        String issuePoint,
        String sequenceNumber,
        LocalDate issueDate,
        Recipient recipient,
        String modifiedDocumentCode,
        String modifiedDocumentNumber,
        LocalDate modifiedDocumentDate,
        String reason,
        List<Item> items,
        List<TotalTax> totalTaxes,
        BigDecimal subtotalBeforeTax,
        BigDecimal modificationValue,
        String currency,
        Map<String, String> additionalInfo
        ) {

    /**
     * Información tributaria del emisor (reutiliza la misma estructura de
     * factura).
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
     * Datos del comprador/receptor.
     */
    public record Recipient(
            String idType,
            String id,
            String name
            ) {

    }

    /**
     * Línea de detalle de la nota de crédito. Nota: usa
     * codigoInterno/codigoAdicional en lugar de codigoPrincipal/codigoAuxiliar.
     */
    public record Item(
            String internalCode,
            String additionalCode,
            String description,
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
     * Impuesto totalizado para infoNotaCredito/totalConImpuestos.
     */
    public record TotalTax(
            String taxCode,
            String rateCode,
            BigDecimal taxableBase,
            BigDecimal amount
            ) {

    }
}
