package auracore.key49.ride.generator;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests para RideData record.
 */
class RideDataTest {

    @Test
    void formattedDocumentNumberFormatsCorrectly() {
        var data = createMinimalRideData("001", "002", "000000001");

        assertEquals("001-002-000000001", data.formattedDocumentNumber());
    }

    @Test
    void formattedDocumentNumberWithDifferentValues() {
        var data = createMinimalRideData("003", "005", "000123456");

        assertEquals("003-005-000123456", data.formattedDocumentNumber());
    }

    @Test
    void recordComponentsAreAccessible() {
        var issuer = new RideData.Issuer("0990123456001", "Legal", "Trade", "Addr", null,
                true, "123", null, null);
        var recipient = new RideData.Recipient("04", "0991234567001", "Buyer", null);
        var item = new RideData.Item("C01", "Desc", BigDecimal.ONE, BigDecimal.TEN,
                BigDecimal.ZERO, BigDecimal.TEN, List.of());
        var tax = new RideData.TotalTax("2", "2", BigDecimal.TEN, new BigDecimal("12"), new BigDecimal("1.20"));
        var payment = new RideData.Payment("01", BigDecimal.TEN, 0, "dias");

        var data = new RideData(
                issuer, "1234567890123456789012345678901234567890123456789", null, null,
                "1", "1", "01", "001", "001", "000000001",
                LocalDate.of(2025, 1, 1), recipient,
                List.of(item), List.of(tax), List.of(payment),
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("11.20"),
                "DOLAR", Map.of("key", "value"), false, null
        );

        assertNotNull(data.issuer());
        assertNotNull(data.recipient());
        assertEquals(1, data.items().size());
        assertEquals(1, data.totalTaxes().size());
        assertEquals(1, data.payments().size());
        assertEquals("DOLAR", data.currency());
        assertEquals("01", data.documentType());
    }

    private static RideData createMinimalRideData(String est, String pt, String seq) {
        var issuer = new RideData.Issuer("0990000000001", "Test", null, "Addr", null,
                false, null, null, null);
        var recipient = new RideData.Recipient("05", "0912345678", "User", null);

        return new RideData(
                issuer, "1234567890123456789012345678901234567890123456789", null, null,
                "1", "1", "01", est, pt, seq,
                LocalDate.of(2025, 1, 1), recipient,
                List.of(), List.of(), List.of(),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                "DOLAR", Map.of(), false, null
        );
    }
}
