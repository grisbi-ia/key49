package auracore.key49.api.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import auracore.key49.core.model.Document;
import auracore.key49.core.model.enums.DocumentStatus;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Test unitario para lógica de exportación CSV.
 */

class DocumentExportTest {

    @Nested
    class EscapeCsvTests {

        @Test
        void shouldReturnEmptyForNull() {
            assertEquals("", DocumentExportResource.escapeCsv(null));
        }

        @Test
        void shouldReturnValueWithoutSpecialChars() {
            assertEquals("hello", DocumentExportResource.escapeCsv("hello"));
        }

        @Test
        void shouldEscapeComma() {
            assertEquals("\"hello,world\"", DocumentExportResource.escapeCsv("hello,world"));
        }

        @Test
        void shouldEscapeDoubleQuotes() {
            assertEquals("\"he said \"\"hi\"\"\"", DocumentExportResource.escapeCsv("he said \"hi\""));
        }

        @Test
        void shouldEscapeNewline() {
            assertEquals("\"line1\nline2\"", DocumentExportResource.escapeCsv("line1\nline2"));
        }

        @Test
        void shouldEscapeCarriageReturn() {
            assertEquals("\"line1\rline2\"", DocumentExportResource.escapeCsv("line1\rline2"));
        }

        @Test
        void shouldHandleEmptyString() {
            assertEquals("", DocumentExportResource.escapeCsv(""));
        }
    }

    @Nested
    class ToCsvRowTests {

        @Test
        void shouldFormatCompleteDocument() {
            var doc = new Document();
            doc.accessKey = "0604202501099000000000100100100000000011234567811";
            doc.documentType = "01";
            doc.establishment = "001";
            doc.issuePoint = "001";
            doc.sequenceNumber = "000000001";
            doc.recipientId = "0990000000001";
            doc.recipientName = "ACME Corp S.A.";
            doc.totalAmount = new BigDecimal("115.00");
            doc.status = DocumentStatus.AUTHORIZED;
            doc.issueDate = LocalDate.of(2025, 6, 4);
            doc.authorizationDate = Instant.parse("2025-06-04T15:30:00Z");

            var row = DocumentExportResource.toCsvRow(doc);
            var parts = row.split(",");

            assertEquals(11, parts.length);
            assertEquals("0604202501099000000000100100100000000011234567811", parts[0]);
            assertEquals("01", parts[1]);
            assertEquals("001", parts[2]);
            assertEquals("001", parts[3]);
            assertEquals("000000001", parts[4]);
            assertEquals("0990000000001", parts[5]);
            assertEquals("ACME Corp S.A.", parts[6]);
            assertEquals("115.00", parts[7]);
            assertEquals("AUTHORIZED", parts[8]);
            assertEquals("2025-06-04", parts[9]);
            assertEquals("2025-06-04T15:30:00Z", parts[10]);
        }

        @Test
        void shouldHandleNullFields() {
            var doc = new Document();
            doc.accessKey = null;
            doc.documentType = "01";
            doc.establishment = "001";
            doc.issuePoint = "001";
            doc.sequenceNumber = "000000001";
            doc.recipientId = "0990000000001";
            doc.recipientName = "Test";
            doc.totalAmount = null;
            doc.status = null;
            doc.issueDate = null;
            doc.authorizationDate = null;

            var row = DocumentExportResource.toCsvRow(doc);
            assertTrue(row.startsWith(",01,"));
            assertTrue(row.endsWith(",,,,"));
        }

        @Test
        void shouldEscapeRecipientNameWithComma() {
            var doc = new Document();
            doc.accessKey = "KEY123";
            doc.documentType = "01";
            doc.establishment = "001";
            doc.issuePoint = "001";
            doc.sequenceNumber = "000000001";
            doc.recipientId = "1234567890001";
            doc.recipientName = "Company, Inc.";
            doc.totalAmount = new BigDecimal("50.00");
            doc.status = DocumentStatus.CREATED;
            doc.issueDate = LocalDate.of(2025, 1, 15);
            doc.authorizationDate = null;

            var row = DocumentExportResource.toCsvRow(doc);
            assertTrue(row.contains("\"Company, Inc.\""));
        }
    }

    @Test
    void shouldRespectMaxExportRowsConstant() {
        assertEquals(10_000, DocumentExportResource.MAX_EXPORT_ROWS);
    }
}
