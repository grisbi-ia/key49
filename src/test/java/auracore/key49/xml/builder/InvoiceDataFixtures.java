package auracore.key49.xml.builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;

import auracore.key49.core.Key49Constants;

/**
 * Fábrica de datos de prueba para InvoiceXmlBuilder.
 */
final class InvoiceDataFixtures {

    private InvoiceDataFixtures() {
    }

    static InvoiceData.TaxpayerInfo defaultTaxpayer() {
        return new InvoiceData.TaxpayerInfo(
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

    static InvoiceData.Recipient defaultRecipient() {
        return new InvoiceData.Recipient(
                "04",                   // RUC
                "1790567890001",
                "CLIENTE PRUEBA CIA. LTDA.",
                "Guayaquil, Av. 9 de Octubre 456"
        );
    }

    static InvoiceData.Recipient finalConsumerRecipient() {
        return new InvoiceData.Recipient(
                "07",
                "9999999999999",
                "CONSUMIDOR FINAL",
                null
        );
    }

    static InvoiceData.Item singleItemWithVat15() {
        return new InvoiceData.Item(
                "PROD-001",
                "7861234567890",
                "Servicio de hosting mensual",
                "UNIDAD",
                BigDecimal.ONE,
                new BigDecimal("50.00"),
                BigDecimal.ZERO,
                new BigDecimal("50.00"),
                List.of(new InvoiceData.Tax(
                        "2",    // IVA
                        "4",    // 15%
                        new BigDecimal("15.00"),
                        new BigDecimal("50.00"),
                        new BigDecimal("7.50")
                ))
        );
    }

    static InvoiceData.Item itemWithVat0() {
        return new InvoiceData.Item(
                "PROD-002",
                null,
                "Arroz premium 1kg",
                "KG",
                new BigDecimal("10"),
                new BigDecimal("1.25"),
                new BigDecimal("0.50"),
                new BigDecimal("12.00"),
                List.of(new InvoiceData.Tax(
                        "2",    // IVA
                        "0",    // 0%
                        BigDecimal.ZERO,
                        new BigDecimal("12.00"),
                        BigDecimal.ZERO
                ))
        );
    }

    static InvoiceData.TotalTax totalTaxVat15(BigDecimal base, BigDecimal amount) {
        return new InvoiceData.TotalTax("2", "4", null, base, new BigDecimal("15.00"), amount);
    }

    static InvoiceData.TotalTax totalTaxVat0(BigDecimal base) {
        return new InvoiceData.TotalTax("2", "0", null, base, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    static InvoiceData.Payment defaultPayment(BigDecimal total) {
        return new InvoiceData.Payment("20", total, 0, "dias");
    }

    static InvoiceData.Payment creditPayment(BigDecimal total) {
        return new InvoiceData.Payment("19", total, 30, "dias");
    }

    static InvoiceData simpleInvoice() {
        var today = LocalDate.now(Key49Constants.EC_ZONE);
        return new InvoiceData(
                defaultTaxpayer(),
                "0404202601179001234500110010010000000421234567817",
                "001",
                "001",
                "000000042",
                today,
                defaultRecipient(),
                List.of(singleItemWithVat15()),
                List.of(totalTaxVat15(new BigDecimal("50.00"), new BigDecimal("7.50"))),
                List.of(defaultPayment(new BigDecimal("57.50"))),
                new BigDecimal("50.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("57.50"),
                "DOLAR",
                new LinkedHashMap<>() {{
                    put("Dirección", "Guayaquil, Av. 9 de Octubre 456");
                    put("Email", "contabilidad@cliente.com");
                    put("Teléfono", "0991234567");
                }}
        );
    }

    static InvoiceData multiItemInvoice() {
        var today = LocalDate.now(Key49Constants.EC_ZONE);
        return new InvoiceData(
                defaultTaxpayer(),
                "0404202601179001234500110010010000000431234567815",
                "001",
                "001",
                "000000043",
                today,
                defaultRecipient(),
                List.of(singleItemWithVat15(), itemWithVat0()),
                List.of(
                        totalTaxVat15(new BigDecimal("50.00"), new BigDecimal("7.50")),
                        totalTaxVat0(new BigDecimal("12.00"))
                ),
                List.of(defaultPayment(new BigDecimal("69.50"))),
                new BigDecimal("62.00"),
                new BigDecimal("0.50"),
                BigDecimal.ZERO,
                new BigDecimal("69.50"),
                "DOLAR",
                null
        );
    }

    static InvoiceData minimalInvoice() {
        var today = LocalDate.now(Key49Constants.EC_ZONE);
        var taxpayer = new InvoiceData.TaxpayerInfo(
                "1", "1",
                "TIENDA MINIMA",
                null,   // no trade name
                "1710012345001",
                "Ambato, Centro",
                null,   // no establishment address
                false,  // no required accounting
                null, null, null
        );
        var item = new InvoiceData.Item(
                null, null,
                "Producto genérico",
                null,
                BigDecimal.ONE,
                new BigDecimal("10.00"),
                BigDecimal.ZERO,
                new BigDecimal("10.00"),
                List.of(new InvoiceData.Tax("2", "0", BigDecimal.ZERO, new BigDecimal("10.00"), BigDecimal.ZERO))
        );
        return new InvoiceData(
                taxpayer,
                "0404202601171001234500110010010000000011234567810",
                "001",
                "001",
                "000000001",
                today,
                finalConsumerRecipient(),
                List.of(item),
                List.of(totalTaxVat0(new BigDecimal("10.00"))),
                List.of(new InvoiceData.Payment("01", new BigDecimal("10.00"), null, null)),
                new BigDecimal("10.00"),
                BigDecimal.ZERO,
                null,
                new BigDecimal("10.00"),
                null,
                null
        );
    }
}
