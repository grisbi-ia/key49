package auracore.key49.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import auracore.key49.core.model.Document;
import auracore.key49.core.model.enums.DocumentStatus;

class InvoiceResponseTest {

    private Document sampleDocument() {
        var doc = new Document();
        doc.id = UUID.randomUUID();
        doc.documentType = "01";
        doc.establishment = "001";
        doc.issuePoint = "001";
        doc.sequenceNumber = "000000042";
        doc.accessKey = "0404202601179001234500110010010000000421234567817";
        doc.authorizationNumber = doc.accessKey;
        doc.status = DocumentStatus.AUTHORIZED;
        doc.issueDate = LocalDate.of(2026, 4, 4);
        doc.authorizationDate = Instant.parse("2026-04-04T15:30:15Z");
        doc.recipientIdType = "04";
        doc.recipientId = "1790012345001";
        doc.recipientName = "Empresa S.A.";
        doc.recipientEmail = "test@test.com";
        doc.subtotalBeforeTax = new BigDecimal("50.00");
        doc.vatAmount = new BigDecimal("7.50");
        doc.totalAmount = new BigDecimal("57.50");
        doc.retryCount = 0;
        doc.createdAt = Instant.parse("2026-04-04T15:30:00Z");
        doc.updatedAt = Instant.parse("2026-04-04T15:30:15Z");
        return doc;
    }

    @Test
    void summary_excludesDetailFields() {
        var doc = sampleDocument();
        var response = InvoiceResponse.summary(doc);

        assertNotNull(response.id());
        assertEquals("01", response.documentType());
        assertEquals("AUTHORIZED", response.status());
        assertEquals(doc.totalAmount, response.totalAmount());
        assertEquals(doc.createdAt, response.createdAt());

        // Detail fields should be null (excluded by @JsonInclude)
        assertNull(response.authorizationNumber());
        assertNull(response.authorizationDate());
        assertNull(response.subtotalBeforeTax());
        assertNull(response.vatAmount());
        assertNull(response.downloads());
        assertNull(response.sriMessages());
        assertNull(response.updatedAt());
    }

    @Test
    void summary_recipientHasOnlyIdAndName() {
        var doc = sampleDocument();
        var response = InvoiceResponse.summary(doc);

        assertNotNull(response.recipient());
        assertEquals(doc.recipientId, response.recipient().id());
        assertEquals(doc.recipientName, response.recipient().name());
        assertNull(response.recipient().idType());
        assertNull(response.recipient().email());
    }

    @Test
    void detail_includesAllFields() {
        var doc = sampleDocument();
        doc.authorizedXmlPath = "tenant/2026/04/01/key/authorized.xml";
        doc.ridePath = "tenant/2026/04/01/key/ride.pdf";

        var response = InvoiceResponse.detail(doc);

        assertNotNull(response.id());
        assertEquals(doc.authorizationNumber, response.authorizationNumber());
        assertEquals(doc.authorizationDate, response.authorizationDate());
        assertEquals(doc.subtotalBeforeTax, response.subtotalBeforeTax());
        assertEquals(doc.vatAmount, response.vatAmount());
        assertEquals(doc.totalAmount, response.totalAmount());
        assertEquals(0, response.retryCount());
        assertEquals(doc.updatedAt, response.updatedAt());
    }

    @Test
    void detail_recipientHasFullInfo() {
        var doc = sampleDocument();
        var response = InvoiceResponse.detail(doc);

        assertNotNull(response.recipient());
        assertEquals("04", response.recipient().idType());
        assertEquals(doc.recipientId, response.recipient().id());
        assertEquals(doc.recipientName, response.recipient().name());
        assertEquals(doc.recipientEmail, response.recipient().email());
    }

    @Test
    void detail_includesDownloadLinks() {
        var doc = sampleDocument();
        doc.authorizedXmlPath = "some/path/authorized.xml";
        doc.ridePath = "some/path/ride.pdf";

        var response = InvoiceResponse.detail(doc);

        assertNotNull(response.downloads());
        assertTrue(response.downloads().xml().contains(doc.id.toString()));
        assertTrue(response.downloads().ride().contains(doc.id.toString()));
    }

    @Test
    void detail_noDownloads_whenPathsNull() {
        var doc = sampleDocument();
        doc.authorizedXmlPath = null;
        doc.ridePath = null;

        var response = InvoiceResponse.detail(doc);

        assertNull(response.downloads());
    }

    @Test
    void detail_sriMessages_emptyByDefault() {
        var doc = sampleDocument();
        var response = InvoiceResponse.detail(doc);

        assertNotNull(response.sriMessages());
        assertTrue(response.sriMessages().isEmpty());
    }
}
