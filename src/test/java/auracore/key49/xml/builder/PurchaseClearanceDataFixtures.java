package auracore.key49.xml.builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;

import auracore.key49.core.Key49Constants;

/**
 * Fábrica de datos de prueba para PurchaseClearanceXmlBuilder.
 */

final class PurchaseClearanceDataFixtures {

    private PurchaseClearanceDataFixtures() {
    }

    static PurchaseClearanceData.TaxpayerInfo defaultTaxpayer() {
        return new PurchaseClearanceData.TaxpayerInfo(
                "1",                    // test environment
                "1",                    // normal emission
                "EMPRESA DEMO S.A.",
                "DEMO",
                "1790012345001",
                "Quito, Av. Principal 123",
                "Sucursal Norte, Av. 10 de Agosto",
                true,
                null,                   // no contribuyente especial
                null,                   // no agente retención
                "CONTRIBUYENTE RÉGIMEN RIMPE"
        );
    }

    static PurchaseClearanceData.Supplier defaultSupplier() {
        return new PurchaseClearanceData.Supplier(
                "05",                   // Cédula
                "1710034065",
                "PROVEEDOR RURAL DEMO",
                "Vía a Santo Domingo km 5"
        );
    }

    static PurchaseClearanceData.Supplier supplierWithRuc() {
        return new PurchaseClearanceData.Supplier(
                "04",                   // RUC
                "1790567890001",
                "PROVEEDOR CIA. LTDA.",
                "Guayaquil, Av. 9 de Octubre 456"
        );
    }

    static PurchaseClearanceData.Item singleItemWithVat15() {
        return new PurchaseClearanceData.Item(
                "PROD-001",
                "7861234567890",
                "Cacao en grano 50kg",
                "QUINTAL",
                BigDecimal.ONE,
                new BigDecimal("50.00"),
                BigDecimal.ZERO,
                new BigDecimal("50.00"),
                List.of(new PurchaseClearanceData.Tax(
                        "2",    // IVA
                        "4",    // 15%
                        new BigDecimal("15.00"),
                        new BigDecimal("50.00"),
                        new BigDecimal("7.50")
                ))
        );
    }

    static PurchaseClearanceData.Item itemWithVat0() {
        return new PurchaseClearanceData.Item(
                "PROD-002",
                null,
                "Leche fresca 1L",
                "LITRO",
                new BigDecimal("10"),
                new BigDecimal("1.25"),
                new BigDecimal("0.50"),
                new BigDecimal("12.00"),
                List.of(new PurchaseClearanceData.Tax(
                        "2",    // IVA
                        "0",    // 0%
                        BigDecimal.ZERO,
                        new BigDecimal("12.00"),
                        BigDecimal.ZERO
                ))
        );
    }

    static PurchaseClearanceData.TotalTax totalTaxVat15(BigDecimal base, BigDecimal amount) {
        return new PurchaseClearanceData.TotalTax("2", "4", null, base, new BigDecimal("15.00"), amount);
    }

    static PurchaseClearanceData.TotalTax totalTaxVat0(BigDecimal base) {
        return new PurchaseClearanceData.TotalTax("2", "0", null, base, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    static PurchaseClearanceData.Payment defaultPayment(BigDecimal total) {
        return new PurchaseClearanceData.Payment("20", total, 0, "dias");
    }

    static PurchaseClearanceData.Payment creditPayment(BigDecimal total) {
        return new PurchaseClearanceData.Payment("19", total, 30, "dias");
    }

    static PurchaseClearanceData simplePurchaseClearance() {
        var today = LocalDate.now(Key49Constants.EC_ZONE);
        return new PurchaseClearanceData(
                defaultTaxpayer(),
                "0404202603179001234500110010010000000421234567817",
                "001",
                "001",
                "000000042",
                today,
                defaultSupplier(),
                List.of(singleItemWithVat15()),
                List.of(totalTaxVat15(new BigDecimal("50.00"), new BigDecimal("7.50"))),
                List.of(defaultPayment(new BigDecimal("57.50"))),
                new BigDecimal("50.00"),
                BigDecimal.ZERO,
                new BigDecimal("57.50"),
                "DOLAR",
                new LinkedHashMap<>() {{
                    put("Dirección", "Vía a Santo Domingo km 5");
                    put("Email", "proveedor@demo.com");
                    put("Teléfono", "0991234567");
                }}
        );
    }

    static PurchaseClearanceData multiItemPurchaseClearance() {
        var today = LocalDate.now(Key49Constants.EC_ZONE);
        return new PurchaseClearanceData(
                defaultTaxpayer(),
                "0404202603179001234500110010010000000431234567815",
                "001",
                "001",
                "000000043",
                today,
                supplierWithRuc(),
                List.of(singleItemWithVat15(), itemWithVat0()),
                List.of(
                        totalTaxVat15(new BigDecimal("50.00"), new BigDecimal("7.50")),
                        totalTaxVat0(new BigDecimal("12.00"))
                ),
                List.of(defaultPayment(new BigDecimal("69.50"))),
                new BigDecimal("62.00"),
                new BigDecimal("0.50"),
                new BigDecimal("69.50"),
                "DOLAR",
                null
        );
    }

    static PurchaseClearanceData minimalPurchaseClearance() {
        var today = LocalDate.now(Key49Constants.EC_ZONE);
        var taxpayer = new PurchaseClearanceData.TaxpayerInfo(
                "1", "1",
                "TIENDA MINIMA",
                null,   // no trade name
                "1710012345001",
                "Ambato, Centro",
                null,   // no establishment address
                false,  // no required accounting
                null, null, null
        );
        var supplier = new PurchaseClearanceData.Supplier(
                "05", "1710034065", "PROVEEDOR MINIMO", null);
        var item = new PurchaseClearanceData.Item(
                null, null,
                "Producto genérico",
                null,
                BigDecimal.ONE,
                new BigDecimal("10.00"),
                BigDecimal.ZERO,
                new BigDecimal("10.00"),
                List.of(new PurchaseClearanceData.Tax("2", "0", BigDecimal.ZERO, new BigDecimal("10.00"), BigDecimal.ZERO))
        );
        return new PurchaseClearanceData(
                taxpayer,
                "0404202603171001234500110010010000000011234567810",
                "001",
                "001",
                "000000001",
                today,
                supplier,
                List.of(item),
                List.of(totalTaxVat0(new BigDecimal("10.00"))),
                List.of(new PurchaseClearanceData.Payment("01", new BigDecimal("10.00"), null, null)),
                new BigDecimal("10.00"),
                BigDecimal.ZERO,
                new BigDecimal("10.00"),
                null,
                null
        );
    }
}
