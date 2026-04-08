package auracore.key49.api.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import auracore.key49.core.model.Document;
import auracore.key49.core.model.InvalidStateTransitionException;
import auracore.key49.core.model.enums.DocumentStatus;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.sql.SQLException;

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentCrudTest {

    private static final String TENANT_SCHEMA = "tenant_doc_crud";

    @Inject
    EntityManager em;

    @Inject
    javax.sql.DataSource dataSource;

    @BeforeAll
    void createTenantSchema() throws SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA IF NOT EXISTS " + TENANT_SCHEMA);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS %s.documents (
                    document_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    document_type VARCHAR(2) NOT NULL,
                    establishment VARCHAR(3) NOT NULL,
                    issue_point VARCHAR(3) NOT NULL,
                    sequence_number VARCHAR(9) NOT NULL,
                    access_key VARCHAR(49),
                    authorization_number VARCHAR(49),
                    request_origin VARCHAR(10) NOT NULL DEFAULT 'JSON',
                    recipient_id_type VARCHAR(2) NOT NULL,
                    recipient_id VARCHAR(20) NOT NULL,
                    recipient_name VARCHAR(300) NOT NULL,
                    recipient_email VARCHAR(500),
                    recipient_address VARCHAR(300),
                    recipient_phone VARCHAR(50),
                    subtotal_before_tax NUMERIC(14,2) NOT NULL DEFAULT 0,
                    total_discount NUMERIC(14,2) NOT NULL DEFAULT 0,
                    subtotal_vat_0 NUMERIC(14,2) NOT NULL DEFAULT 0,
                    subtotal_vat_12 NUMERIC(14,2) NOT NULL DEFAULT 0,
                    subtotal_vat_15 NUMERIC(14,2) NOT NULL DEFAULT 0,
                    subtotal_non_taxable NUMERIC(14,2) NOT NULL DEFAULT 0,
                    subtotal_exempt NUMERIC(14,2) NOT NULL DEFAULT 0,
                    vat_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
                    ice_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
                    tip NUMERIC(14,2) NOT NULL DEFAULT 0,
                    total_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
                    currency VARCHAR(15) NOT NULL DEFAULT 'DOLAR',
                    issue_date DATE NOT NULL,
                    authorization_date TIMESTAMPTZ,
                    sri_submission_date TIMESTAMPTZ,
                    status VARCHAR(20) NOT NULL DEFAULT 'CREATED',
                    retry_count SMALLINT NOT NULL DEFAULT 0,
                    max_retries SMALLINT NOT NULL DEFAULT 6,
                    next_retry_at TIMESTAMPTZ,
                    last_error_code VARCHAR(10),
                    last_error_message TEXT,
                    sri_messages JSONB,
                    unsigned_xml_path VARCHAR(500),
                    signed_xml_path VARCHAR(500),
                    authorized_xml_path VARCHAR(500),
                    ride_path VARCHAR(500),
                    request_payload JSONB,
                    original_xml TEXT,
                    request_ip VARCHAR(45),
                    idempotency_key VARCHAR(50),
                    email_sent_at TIMESTAMPTZ,
                    email_status VARCHAR(20),
                    email_error VARCHAR(500),
                    voided_at TIMESTAMPTZ,
                    void_reason VARCHAR(500),
                    version INT NOT NULL DEFAULT 1,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    CONSTRAINT uq_documents_number UNIQUE (document_type, establishment, issue_point, sequence_number)
                )""".formatted(TENANT_SCHEMA));
        }
    }

    @Test
    @Transactional
    void shouldPersistAndFindDocument() {
        em.createNativeQuery("SET search_path TO '%s', public".formatted(TENANT_SCHEMA))
                .executeUpdate();

        var doc = buildTestDocument("000000001");
        em.persist(doc);
        em.flush();

        assertNotNull(doc.id);
        assertEquals(DocumentStatus.CREATED, doc.status);

        em.clear();
        var found = em.find(Document.class, doc.id);

        assertNotNull(found);
        assertEquals("01", found.documentType);
        assertEquals("001", found.establishment);
        assertEquals("001", found.issuePoint);
        assertEquals("000000001", found.sequenceNumber);
        assertEquals(new BigDecimal("100.00"), found.totalAmount);
    }

    @Test
    @Transactional
    void shouldTransitionDocumentState() {
        em.createNativeQuery("SET search_path TO '%s', public".formatted(TENANT_SCHEMA))
                .executeUpdate();

        var doc = buildTestDocument("000000002");
        em.persist(doc);
        em.flush();

        assertEquals(DocumentStatus.CREATED, doc.status);

        doc.transitionTo(DocumentStatus.SIGNED);
        assertEquals(DocumentStatus.SIGNED, doc.status);

        doc.transitionTo(DocumentStatus.SENT);
        assertEquals(DocumentStatus.SENT, doc.status);
    }

    @Test
    void shouldRejectInvalidTransition() {
        var doc = buildTestDocument("000000003");
        doc.status = DocumentStatus.REJECTED;

        assertThrows(InvalidStateTransitionException.class,
                () -> doc.transitionTo(DocumentStatus.SIGNED));
    }

    private Document buildTestDocument(String sequenceNumber) {
        var doc = new Document();
        doc.documentType = "01";
        doc.establishment = "001";
        doc.issuePoint = "001";
        doc.sequenceNumber = sequenceNumber;
        doc.recipientIdType = "05";
        doc.recipientId = "1712345678";
        doc.recipientName = "Juan Perez";
        doc.totalAmount = new BigDecimal("100.00");
        doc.issueDate = LocalDate.now();
        doc.createdAt = Instant.now();
        doc.updatedAt = Instant.now();
        return doc;
    }
}
