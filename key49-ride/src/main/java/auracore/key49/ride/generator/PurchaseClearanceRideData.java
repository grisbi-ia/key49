package auracore.key49.ride.generator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Datos necesarios para generar el RIDE (PDF) de una liquidación de compra.
 * Incluye datos del proveedor (en lugar de comprador), ítems, impuestos, pagos
 * y totales.
 */
public record PurchaseClearanceRideData(
        RideData.Issuer issuer,
        String accessKey,
        String authorizationNumber,
        LocalDateTime authorizationDate,
        String environment,
        String emissionType,
        String establishment,
        String issuePoint,
        String sequenceNumber,
        LocalDate issueDate,
        Supplier supplier,
        List<Item> items,
        List<RideData.TotalTax> totalTaxes,
        List<RideData.Payment> payments,
        BigDecimal subtotalBeforeTax,
        BigDecimal totalDiscount,
        BigDecimal totalAmount,
        String currency,
        Map<String, String> additionalInfo,
        boolean authorized,
        byte[] logo
        ) {

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
            String description,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal discount,
            BigDecimal subtotal,
            List<RideData.Tax> taxes
            ) {

    }

    /**
     * Número de documento formateado: EST-PTO-SEQ (ej: 001-001-000000001).
     */
    public String formattedDocumentNumber() {
        return establishment + "-" + issuePoint + "-" + sequenceNumber;
    }
}
