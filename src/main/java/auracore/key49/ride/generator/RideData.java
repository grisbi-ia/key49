package auracore.key49.ride.generator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Datos necesarios para generar el RIDE (PDF) de una factura.
 * Orientado a la presentación — contiene la información ya resuelta para renderizar.
 */
public record RideData(
        Issuer issuer,
        String accessKey,
        String authorizationNumber,
        LocalDateTime authorizationDate,
        String environment,
        String emissionType,
        String documentType,
        String establishment,
        String issuePoint,
        String sequenceNumber,
        LocalDate issueDate,
        Recipient recipient,
        List<Item> items,
        List<TotalTax> totalTaxes,
        List<Payment> payments,
        BigDecimal subtotalBeforeTax,
        BigDecimal totalDiscount,
        BigDecimal tip,
        BigDecimal totalAmount,
        String currency,
        Map<String, String> additionalInfo,
        boolean authorized,
        byte[] logo
) {

    /**
     * Datos del emisor para el encabezado del RIDE.
     */
    public record Issuer(
            String ruc,
            String legalName,
            String tradeName,
            String mainAddress,
            String establishmentAddress,
            boolean requiredAccounting,
            String specialTaxpayer,
            String withholdingAgent,
            String rimpeContributor
    ) {}

    /**
     * Datos del receptor/comprador.
     */
    public record Recipient(
            String idType,
            String id,
            String name,
            String address
    ) {}

    /**
     * Línea de detalle del comprobante.
     */
    public record Item(
            String mainCode,
            String description,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal discount,
            BigDecimal subtotal,
            List<Tax> taxes
    ) {}

    /**
     * Impuesto de un ítem.
     */
    public record Tax(
            String taxCode,
            String rateCode,
            BigDecimal rate,
            BigDecimal taxableBase,
            BigDecimal amount
    ) {}

    /**
     * Impuesto totalizado.
     */
    public record TotalTax(
            String taxCode,
            String rateCode,
            BigDecimal taxableBase,
            BigDecimal rate,
            BigDecimal amount
    ) {}

    /**
     * Forma de pago.
     */
    public record Payment(
            String paymentMethod,
            BigDecimal total,
            Integer term,
            String timeUnit
    ) {}

    /**
     * Número de documento formateado: EST-PTO-SEQ (ej: 001-001-000000001).
     */
    public String formattedDocumentNumber() {
        return establishment + "-" + issuePoint + "-" + sequenceNumber;
    }
}
