package auracore.key49.xml.builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;

import auracore.key49.core.Key49Constants;

/**
 * Fábrica de datos de prueba para DebitNoteXmlBuilder.
 */

final class DebitNoteDataFixtures {

    private DebitNoteDataFixtures() {
    }

    static DebitNoteData.TaxpayerInfo defaultTaxpayer() {
        return new DebitNoteData.TaxpayerInfo(
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

    static DebitNoteData.Recipient defaultRecipient() {
        return new DebitNoteData.Recipient(
                "04",
                "1790567890001",
                "CLIENTE PRUEBA CIA. LTDA."
        );
    }

    static DebitNoteData.Reason defaultReason() {
        return new DebitNoteData.Reason(
                "Intereses por mora en pago",
                new BigDecimal("50.00")
        );
    }

    static DebitNoteData.Reason additionalReason() {
        return new DebitNoteData.Reason(
                "Gastos administrativos de cobranza",
                new BigDecimal("25.00")
        );
    }

    static DebitNoteData.Tax taxVat15(BigDecimal base, BigDecimal amount) {
        return new DebitNoteData.Tax(
                "2", "4", new BigDecimal("15.00"), base, amount
        );
    }

    static DebitNoteData.Tax taxVat0(BigDecimal base) {
        return new DebitNoteData.Tax(
                "2", "0", BigDecimal.ZERO, base, BigDecimal.ZERO
        );
    }

    static DebitNoteData.Payment defaultPayment() {
        return new DebitNoteData.Payment(
                "01", new BigDecimal("57.50"), 30, "dias"
        );
    }

    static DebitNoteData simpleDebitNote() {
        var today = LocalDate.now(Key49Constants.EC_ZONE);
        return new DebitNoteData(
                defaultTaxpayer(),
                "0404202605179001234500110010010000000421234567817",
                "001",
                "001",
                "000000042",
                today,
                defaultRecipient(),
                "01",
                "001-001-000000001",
                today.minusDays(5),
                new BigDecimal("50.00"),
                List.of(taxVat15(new BigDecimal("50.00"), new BigDecimal("7.50"))),
                new BigDecimal("57.50"),
                List.of(defaultPayment()),
                List.of(defaultReason()),
                new LinkedHashMap<>() {{
                    put("Dirección", "Guayaquil, Av. 9 de Octubre 456");
                    put("Email", "contabilidad@cliente.com");
                }}
        );
    }

    static DebitNoteData multiReasonDebitNote() {
        var today = LocalDate.now(Key49Constants.EC_ZONE);
        return new DebitNoteData(
                defaultTaxpayer(),
                "0404202605179001234500110010010000000431234567818",
                "001",
                "002",
                "000000043",
                today,
                defaultRecipient(),
                "01",
                "001-001-000000002",
                today.minusDays(10),
                new BigDecimal("75.00"),
                List.of(
                        taxVat15(new BigDecimal("50.00"), new BigDecimal("7.50")),
                        taxVat0(new BigDecimal("25.00"))
                ),
                new BigDecimal("82.50"),
                null,
                List.of(defaultReason(), additionalReason()),
                null
        );
    }

    static DebitNoteData minimalDebitNote() {
        var today = LocalDate.now(Key49Constants.EC_ZONE);
        return new DebitNoteData(
                new DebitNoteData.TaxpayerInfo(
                        "1", "1", "MINIMAL S.A.", null,
                        "1790012345001", "Quito",
                        null, false, null, null, null
                ),
                "0404202605179001234500110010010000000441234567819",
                "001",
                "001",
                "000000044",
                today,
                new DebitNoteData.Recipient("05", "1710034065", "Juan Pérez"),
                "01",
                "001-001-000000001",
                today.minusDays(1),
                new BigDecimal("10.00"),
                List.of(new DebitNoteData.Tax("2", "0", BigDecimal.ZERO,
                        new BigDecimal("10.00"), BigDecimal.ZERO)),
                new BigDecimal("10.00"),
                null,
                List.of(new DebitNoteData.Reason("Ajuste de precio", new BigDecimal("10.00"))),
                null
        );
    }
}
