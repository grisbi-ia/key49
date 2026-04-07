package auracore.key49.xml.builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import auracore.key49.core.Key49Constants;

/**
 * Fábrica de datos de prueba para CreditNoteXmlBuilder.
 */

final class CreditNoteDataFixtures {

    private CreditNoteDataFixtures() {
    }

    static CreditNoteData.TaxpayerInfo defaultTaxpayer() {
        return new CreditNoteData.TaxpayerInfo(
                "1",
                "1",
                "EMPRESA DEMO S.A.",
                "DEMO",
                "1790012345001",
                "Quito, Av. Principal 123",
                "Sucursal Norte, Av. 10 de Agosto",
                true,
                null,
                null,
                "CONTRIBUYENTE RÉGIMEN RIMPE"
        );
    }

    static CreditNoteData.Recipient defaultRecipient() {
        return new CreditNoteData.Recipient(
                "04",
                "1790567890001",
                "CLIENTE PRUEBA CIA. LTDA."
        );
    }

    static CreditNoteData.Item singleItemWithVat15() {
        return new CreditNoteData.Item(
                "PROD-001",
                "7861234567890",
                "Servicio de hosting mensual",
                BigDecimal.ONE,
                new BigDecimal("50.00"),
                BigDecimal.ZERO,
                new BigDecimal("50.00"),
                List.of(new CreditNoteData.Tax(
                        "2",
                        "4",
                        new BigDecimal("15.00"),
                        new BigDecimal("50.00"),
                        new BigDecimal("7.50")
                ))
        );
    }

    static CreditNoteData.Item itemWithVat0() {
        return new CreditNoteData.Item(
                "PROD-002",
                null,
                "Arroz premium 1kg",
                new BigDecimal("10"),
                new BigDecimal("1.25"),
                new BigDecimal("0.50"),
                new BigDecimal("12.00"),
                List.of(new CreditNoteData.Tax(
                        "2",
                        "0",
                        BigDecimal.ZERO,
                        new BigDecimal("12.00"),
                        BigDecimal.ZERO
                ))
        );
    }

    static CreditNoteData.TotalTax totalTaxVat15(BigDecimal base, BigDecimal amount) {
        return new CreditNoteData.TotalTax("2", "4", base, amount);
    }

    static CreditNoteData.TotalTax totalTaxVat0(BigDecimal base) {
        return new CreditNoteData.TotalTax("2", "0", base, BigDecimal.ZERO);
    }

    static CreditNoteData simpleCreditNote() {
        var today = LocalDate.now(Key49Constants.EC_ZONE);
        return new CreditNoteData(
                defaultTaxpayer(),
                "0404202601179001234500110010010000000421234567817",
                "001",
                "001",
                "000000042",
                today,
                defaultRecipient(),
                "01",
                "001-001-000000001",
                today.minusDays(5),
                "Devolución de producto",
                List.of(singleItemWithVat15()),
                List.of(totalTaxVat15(new BigDecimal("50.00"), new BigDecimal("7.50"))),
                new BigDecimal("50.00"),
                new BigDecimal("57.50"),
                "DOLAR",
                new LinkedHashMap<>() {{
                    put("Dirección", "Guayaquil, Av. 9 de Octubre 456");
                    put("Email", "contabilidad@cliente.com");
                }}
        );
    }

    static CreditNoteData multiItemCreditNote() {
        var today = LocalDate.now(Key49Constants.EC_ZONE);
        return new CreditNoteData(
                defaultTaxpayer(),
                "0404202601179001234500110010010000000431234567818",
                "001",
                "002",
                "000000043",
                today,
                defaultRecipient(),
                "01",
                "001-001-000000002",
                today.minusDays(10),
                "Error en facturación",
                List.of(singleItemWithVat15(), itemWithVat0()),
                List.of(
                        totalTaxVat15(new BigDecimal("50.00"), new BigDecimal("7.50")),
                        totalTaxVat0(new BigDecimal("12.00"))
                ),
                new BigDecimal("62.00"),
                new BigDecimal("69.50"),
                "DOLAR",
                null
        );
    }

    static CreditNoteData minimalCreditNote() {
        var today = LocalDate.now(Key49Constants.EC_ZONE);
        return new CreditNoteData(
                new CreditNoteData.TaxpayerInfo(
                        "1", "1", "MINIMAL S.A.", null,
                        "1790012345001", "Quito",
                        null, false, null, null, null
                ),
                "0404202601179001234500110010010000000441234567819",
                "001",
                "001",
                "000000044",
                today,
                new CreditNoteData.Recipient("05", "1710034065", "Juan Pérez"),
                "01",
                "001-001-000000001",
                today.minusDays(1),
                "Devolución",
                List.of(new CreditNoteData.Item(
                        "001", null, "Producto",
                        BigDecimal.ONE, new BigDecimal("10.00"),
                        BigDecimal.ZERO, new BigDecimal("10.00"),
                        List.of(new CreditNoteData.Tax("2", "0", BigDecimal.ZERO,
                                new BigDecimal("10.00"), BigDecimal.ZERO))
                )),
                List.of(new CreditNoteData.TotalTax("2", "0",
                        new BigDecimal("10.00"), BigDecimal.ZERO)),
                new BigDecimal("10.00"),
                new BigDecimal("10.00"),
                "DOLAR",
                null
        );
    }
}
