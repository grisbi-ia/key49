package auracore.key49.core.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import auracore.key49.api.dto.ReprocessRequest;
import auracore.key49.api.exception.BusinessException;
import auracore.key49.core.Key49Constants;
import auracore.key49.queue.consumer.TestSchemaHelper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * Test de integración para {@link DocumentReprocessService}.
 *
 * <p>Verifica el reproceso en lote y el aislamiento multi-tenant: los documentos
 * de otro esquema no se tocan.</p>
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DocumentReprocessServiceTest {

    private static final String SCHEMA_A = "tenant_reproc_a";
    private static final String SCHEMA_B = "tenant_reproc_b";

    @Inject
    DocumentReprocessService service;

    @Inject
    DataSource dataSource;

    private UUID docFailedSubmitted;
    private UUID docFailedNotSubmitted;
    private UUID docReceived;
    private UUID docRetry;
    private UUID docRejected;
    private UUID docOtherTenant;

    @BeforeAll
    void setup() throws Exception {
        try (var conn = dataSource.getConnection()) {
            createTenant(conn, "1792146739101", SCHEMA_A);
            createTenant(conn, "1792146739102", SCHEMA_B);
            createSchema(conn, SCHEMA_A);
            createSchema(conn, SCHEMA_B);

            docFailedSubmitted = insertDoc(conn, SCHEMA_A, "000000001", "FAILED", true, 1001);
            docFailedNotSubmitted = insertDoc(conn, SCHEMA_A, "000000002", "FAILED", false, 1002);
            docReceived = insertDoc(conn, SCHEMA_A, "000000003", "RECEIVED", true, 1003);
            docRetry = insertDoc(conn, SCHEMA_A, "000000004", "RETRY", false, 1004);
            docRejected = insertDoc(conn, SCHEMA_A, "000000005", "REJECTED", false, 1005);
            docOtherTenant = insertDoc(conn, SCHEMA_B, "000000006", "FAILED", true, 2001);
        }
    }

    @Test
    @Order(1)
    @DisplayName("reprocesa FAILED/RECEIVED/RETRY y no toca otros tenants")
    void shouldReprocessBatch() throws Exception {
        var result = service.reprocess(SCHEMA_A, new ReprocessRequest(null, null, null, null, null));

        assertEquals(4, result.matched(), "Deben coincidir 4 documentos reprocesables");
        assertEquals(4, result.queued());
        assertEquals(0, result.skipped());

        // FAILED con envío previo → reconciliar (RECEIVED + doc.authorize)
        assertStatus(SCHEMA_A, docFailedSubmitted, "RECEIVED");
        assertOutbox(SCHEMA_A, docFailedSubmitted, "doc.authorize");

        // FAILED sin envío → re-firmar envío (CREATED + doc.sign)
        assertStatus(SCHEMA_A, docFailedNotSubmitted, "CREATED");
        assertOutbox(SCHEMA_A, docFailedNotSubmitted, "doc.sign");

        // RECEIVED → doc.authorize
        assertStatus(SCHEMA_A, docReceived, "RECEIVED");
        assertOutbox(SCHEMA_A, docReceived, "doc.authorize");

        // RETRY sin envío → doc.send
        assertStatus(SCHEMA_A, docRetry, "RETRY");
        assertOutbox(SCHEMA_A, docRetry, "doc.send");

        // REJECTED no se reprocesa
        assertStatus(SCHEMA_A, docRejected, "REJECTED");
        assertNoOutbox(SCHEMA_A, docRejected);

        // Aislamiento: el otro tenant queda intacto
        assertStatus(SCHEMA_B, docOtherTenant, "FAILED");
        assertNoOutbox(SCHEMA_B, docOtherTenant);
    }

    @Test
    @Order(2)
    @DisplayName("filtro por tipo de documento que no coincide → 0")
    void shouldReturnZeroForNonMatchingType() {
        var result = service.reprocess(SCHEMA_A,
                new ReprocessRequest(null, List.of("99"), null, null, null));
        assertEquals(0, result.matched());
    }

    @Test
    @Order(3)
    @DisplayName("REJECTED no es reprocesable → error de validación")
    void shouldRejectRejectedStatus() {
        var ex = assertThrows(BusinessException.class, () -> service.reprocess(SCHEMA_A,
                new ReprocessRequest(List.of("REJECTED"), null, null, null, null)));
        assertEquals(400, ex.httpStatus());
    }

    @Test
    @Order(4)
    @DisplayName("estado inválido → error de validación")
    void shouldRejectInvalidStatus() {
        assertThrows(BusinessException.class, () -> service.reprocess(SCHEMA_A,
                new ReprocessRequest(List.of("NOPE"), null, null, null, null)));
    }

    // ── Helpers ──

    private void createTenant(Connection conn, String ruc, String schema) throws SQLException {
        try (var ps = conn.prepareStatement("""
                INSERT INTO tenants (tenant_id, ruc, legal_name, trade_name, main_address, schema_name,
                    required_accounting, micro_enterprise_regime, environment,
                    emission_type, rate_limit_rpm, rate_limit_write_rpm, rate_limit_read_rpm, status, created_at, updated_at)
                VALUES (?::uuid, ?, ?, ?, ?, ?, false, false, 'test', 1, 10000, 10000, 10000, 'active', now(), now())
                """)) {
            ps.setObject(1, UUID.randomUUID().toString());
            ps.setString(2, ruc);
            ps.setString(3, "Reproc Test " + schema);
            ps.setString(4, "Reproc");
            ps.setString(5, "Guayaquil");
            ps.setString(6, schema);
            ps.executeUpdate();
        }
    }

    private void createSchema(Connection conn, String schema) throws SQLException {
        try (var stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
            stmt.execute(TestSchemaHelper.documentsTableSql(schema));
            stmt.execute(TestSchemaHelper.outboxTableSql(schema));
        }
    }

    private UUID insertDoc(Connection conn, String schema, String seq, String status,
            boolean submitted, long accessKeySeed) throws SQLException {
        var docId = UUID.randomUUID();
        var accessKey = "%049d".formatted(accessKeySeed);
        try (var ps = conn.prepareStatement("""
                INSERT INTO %s.documents (document_id, document_type, establishment, issue_point,
                    sequence_number, recipient_id_type, recipient_id, recipient_name,
                    issue_date, status, access_key, sri_submission_date, original_xml,
                    subtotal_before_tax, total_discount, tip, total_amount, vat_amount,
                    created_at, updated_at)
                VALUES (?::uuid, '01', '001', '001', ?, '04', '1792146739001', 'Test Corp',
                    ?, ?, ?, %s, '<factura>signed</factura>',
                    50.00, 0.00, 0.00, 57.50, 7.50, now(), now())
                """.formatted(schema, submitted ? "now()" : "NULL"))) {
            ps.setString(1, docId.toString());
            ps.setString(2, seq);
            ps.setObject(3, java.time.LocalDate.now(Key49Constants.EC_ZONE));
            ps.setString(4, status);
            ps.setString(5, accessKey);
            ps.executeUpdate();
        }
        return docId;
    }

    private void assertStatus(String schema, UUID docId, String expected) throws SQLException {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT status FROM %s.documents WHERE document_id = ?::uuid".formatted(schema))) {
            ps.setString(1, docId.toString());
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Documento debe existir en " + schema);
                assertEquals(expected, rs.getString("status"));
            }
        }
    }

    private void assertOutbox(String schema, UUID docId, String expectedEventType) throws SQLException {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT event_type FROM %s.outbox WHERE aggregate_id = ?::uuid".formatted(schema))) {
            ps.setString(1, docId.toString());
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Debe existir evento outbox para " + docId);
                assertEquals(expectedEventType, rs.getString("event_type"));
            }
        }
    }

    private void assertNoOutbox(String schema, UUID docId) throws SQLException {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT count(*) FROM %s.outbox WHERE aggregate_id = ?::uuid".formatted(schema))) {
            ps.setString(1, docId.toString());
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "No debe haber eventos para " + docId);
            }
        }
    }
}
