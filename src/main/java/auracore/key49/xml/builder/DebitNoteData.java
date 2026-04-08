package auracore.key49.xml.builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Datos de entrada para generar el XML de nota de débito electrónica. Agrupa la
 * información tributaria del emisor con los datos del comprobante conforme al
 * XSD NotaDebito v1.0.0 del SRI.
 *
 * <p>
 * Diferencia clave con nota de crédito: usa {@code motivos} (lista de razón +
 * valor) en lugar de {@code detalles} (ítems).
 */
public record DebitNoteData(
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
        BigDecimal totalWithoutTax,
        List<Tax> taxes,
        BigDecimal totalValue,
        List<Payment> payments,
        List<Reason> reasons,
        Map<String, String> additionalInfo) {

    /**
     * Información tributaria del emisor.
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
            String rimpeContributor) {

    }

    /**
     * Datos del comprador/receptor.
     */
    public record Recipient(
            String idType,
            String id,
            String name) {

    }

    /**
     * Impuesto aplicado al total de la nota de débito. Incluye tarifa
     * obligatoria según XSD.
     */
    public record Tax(
            String taxCode,
            String rateCode,
            BigDecimal rate,
            BigDecimal taxableBase,
            BigDecimal amount) {

    }

    /**
     * Forma de pago opcional.
     */
    public record Payment(
            String paymentMethod,
            BigDecimal total,
            Integer term,
            String timeUnit) {

    }

    /**
     * Motivo de la nota de débito (razón + valor).
     */
    public record Reason(
            String description,
            BigDecimal amount) {

    }
}
