package auracore.key49.notify.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class EmailDataTest {

    private static final String ACCESS_KEY = "2506202501099271531200110010020000000011234567813";

    @Test
    void rideFilenameShouldUseAccessKey() {
        var data = createEmailData(ACCESS_KEY, List.of("test@example.com"));

        assertEquals(ACCESS_KEY + ".pdf", data.rideFilename());
    }

    @Test
    void xmlFilenameShouldUseAccessKey() {
        var data = createEmailData(ACCESS_KEY, List.of("test@example.com"));

        assertEquals(ACCESS_KEY + ".xml", data.xmlFilename());
    }

    @Test
    void parseEmailsShouldSplitBySemicolon() {
        var emails = EmailData.parseEmails("a@test.com;b@test.com;c@test.com");

        assertEquals(3, emails.size());
        assertEquals("a@test.com", emails.get(0));
        assertEquals("b@test.com", emails.get(1));
        assertEquals("c@test.com", emails.get(2));
    }

    @Test
    void parseEmailsShouldTrimWhitespace() {
        var emails = EmailData.parseEmails(" a@test.com ; b@test.com ; c@test.com ");

        assertEquals(3, emails.size());
        assertEquals("a@test.com", emails.get(0));
        assertEquals("b@test.com", emails.get(1));
        assertEquals("c@test.com", emails.get(2));
    }

    @Test
    void parseEmailsShouldHandleSingleEmail() {
        var emails = EmailData.parseEmails("solo@test.com");

        assertEquals(1, emails.size());
        assertEquals("solo@test.com", emails.get(0));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "\t"})
    void parseEmailsShouldReturnEmptyForBlankOrNull(String input) {
        var emails = EmailData.parseEmails(input);

        assertTrue(emails.isEmpty());
    }

    @Test
    void parseEmailsShouldSkipEmptyEntries() {
        var emails = EmailData.parseEmails("a@test.com;;b@test.com;");

        assertEquals(2, emails.size());
        assertEquals("a@test.com", emails.get(0));
        assertEquals("b@test.com", emails.get(1));
    }

    @ParameterizedTest
    @CsvSource({
            "2506202501099271531200110010020000000011234567813, 2506202501099271531200110010020000000011234567813.pdf",
            "0101202001099271531200110010010000000011234567810, 0101202001099271531200110010010000000011234567810.pdf"
    })
    void rideFilenameShouldMatchAccessKey(String accessKey, String expectedFilename) {
        var data = createEmailData(accessKey, List.of("test@example.com"));

        assertEquals(expectedFilename, data.rideFilename());
    }

    private EmailData createEmailData(String accessKey, List<String> emails) {
        return new EmailData(
                "Empresa Test S.A.",
                "0990012345001",
                "Cliente Test",
                emails,
                "Factura",
                "001-001-000000042",
                accessKey,
                LocalDate.of(2025, 6, 20),
                BigDecimal.valueOf(115.50),
                "DOLAR",
                null,
                null
        );
    }
}
