package auracore.key49.queue.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;

import javax.sql.DataSource;

import auracore.key49.core.Key49Constants;
import auracore.key49.queue.event.DocumentEvent;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Test de integración para DlqConsumer.
 *
 * <p>Verifica que documentos enviados a la Dead Letter Queue se marcan como FAILED
 * y que documentos en estado terminal se ignoran correctamente.</p>
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DlqConsumerIntegrationTest {

    private static final String TENANT_SCHEMA = "tenant_dlq_test";
    private static final String TENANT_RUC = "1792146739001";

    @Inject
    DlqConsumer dlqConsumer;

    @Inject
    DataSource dataSource;

    private UUID tenantId;
    private UUID docIdRetry;
    private UUID docIdSigned;
    private UUID docIdAlreadyFailed;
    private UUID docIdRejected;

    @BeforeAll
    void setup() throws Exception {
        tenantId = UUID.randomUUID();

        try (var conn = dataSource.getConnection()) {
            try (var ps = conn.prepareStatement("""
                    INSERT INTO tenants (tenant_id, ruc, legal_name, trade_name, main_address, schema_name,
                        required_accounting, micro_enterprise_regime, environment,
                        emission_type, rate_limit_rpm, status, created_at, updated_at)
                    VALUES (?::uuid, ?, ?, ?, ?, ?, false, false, 'test', 1, 10000, 'active', now(), now())
                    """)) {
                ps.setObject(1, tenantId.toString());
                ps.setString(2, TENANT_RUC);
                ps.setString(3, "DLQ Test Corp S.A.");
                ps.setString(4, "DLQ Test");
                ps.setString(5, "Guayaquil");
                ps.setString(6, TENANT_SCHEMA);
                ps.executeUpdate();
            }

            try (var stmt = conn.createStatement()) {
                stmt.execute("CREATE SCHEMA IF NOT EXISTS " + TENANT_SCHEMA);
                stmt.execute(TestSchemaHelper.documentsTableSql(TENANT_SCHEMA));
                stmt.execute(TestSchemaHelper.outboxTableSql(TENANT_SCHEMA));
            }

            // Documento en RETRY → debe ir a FAILED
            docIdRetry = UUID.randomUUID();
            insertDocument(conn, docIdRetry, "000000001", "RETRY");

            // Documento en SIGNED → debe ir a FAILED (SIGNED→RETRY→FAILED via canTransitionTo)
            docIdSigned = UUID.randomUUID();
            insertDocument(conn, docIdSigned, "000000002", "SIGNED");

            // Documento ya FAILED → debe mantener estado FAILED
            docIdAlreadyFailed = UUID.randomUUID();
            insertDocument(conn, docIdAlreadyFailed, "000000003", "FAILED");

            // Documento REJECTED → terminal, no debe cambiar
            docIdRejected = UUID.randomUUID();
            insertDocument(conn, docIdRejected, "000000004", "REJECTED");
        }
    }

    @Test
    @Order(1)
    @DisplayName("documento en RETRY → DLQ → FAILED con mensaje descriptivo")
    void shouldMarkRetryDocumentAsFailed() throws Exception {
        dlqConsumer.process(toJson(docIdRetry));

        assertDocumentStatus(docIdRetry, "FAILED");
        assertDocumentHasMessage(docIdRetry, "Exhausted all retries");
    }

    @Test
    @Order(2)
    @DisplayName("documento ya FAILED no cambia de estado")
    void shouldKeepAlreadyFailedDocument() throws Exception {
        dlqConsumer.process(toJson(docIdAlreadyFailed));

        assertDocumentStatus(docIdAlreadyFailed, "FAILED");
    }

    @Test
    @Order(3)
    @DisplayName("documento REJECTED (terminal) no cambia de estado")
    void shouldKeepRejectedDocument() throws Exception {
        dlqConsumer.process(toJson(docIdRejected));

        assertDocumentStatus(docIdRejected, "REJECTED");
    }

    @Test
    @Order(4)
    @DisplayName("tenant inválido no genera excepción")
    void shouldHandleInvalidTenantSchema() {
        dlqConsumer.process(toJson(UUID.randomUUID(), "tenant_inexistente"));
    }

    @Test
    @Order(5)
    @DisplayName("documento inexistente no genera excepción")
    void shouldHandleNonExistentDocument() {
        dlqConsumer.process(toJson(UUID.randomUUID(), TENANT_SCHEMA));
    }

    // ── Helpers ──

    private JsonObject toJson(UUID documentId) {
        return toJson(documentId, TENANT_SCHEMA);
    }

    private JsonObject toJson(UUID documentId, String schema) {
        var event = DocumentEvent.of(documentId, schema, "doc.dlq");
        return new JsonObject()
                .put("document_id", event.documentId().toString())
                .put("tenant_schema_name", event.tenantSchemaName())
                .put("event_type", event.eventType())
                .put("retry_count", event.retryCount())
                .put("timestamp", event.timestamp().toString());
    }

    private void insertDocument(java.sql.Connection conn, UUID docId,
                                String seqNum, String status) throws SQLException {
        try (var ps = conn.prepareStatement("""
                INSERT INTO %s.documents (document_id, document_type, establishment, issue_point,
                    sequence_number, recipient_id_type, recipient_id, recipient_name,
                    issue_date, status,
                    subtotal_before_tax, total_discount, tip, total_amount, vat_amount,
                    created_at, updated_at)
                VALUES (?::uuid, '01', '001', '001', ?, '04', '1792146739001', 'Test Corp',
                    ?, ?,
                    50.00, 0.00, 0.00, 57.50, 7.50, now(), now())
                """.formatted(TENANT_SCHEMA))) {
            ps.setString(1, docId.toString());
            ps.setString(2, seqNum);
            ps.setObject(3, LocalDate.now(Key49Constants.EC_ZONE));
            ps.setString(4, status);
            ps.executeUpdate();
        }
    }

    private void assertDocumentStatus(UUID docId, String expectedStatus) throws SQLException {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT status FROM %s.documents WHERE document_id = ?::uuid"
                             .formatted(TENANT_SCHEMA))) {
            ps.setString(1, docId.toString());
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Documento debe existir");
                assertEquals(expectedStatus, rs.getString("status"));
            }
        }
    }

    private void assertDocumentHasMessage(UUID docId, String expectedMessagePart) throws SQLException {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT last_error_message FROM %s.documents WHERE document_id = ?::uuid"
                             .formatted(TENANT_SCHEMA))) {
            ps.setString(1, docId.toString());
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next());
                var msg = rs.getString("last_error_message");
                assertTrue(msg != null && msg.contains(expectedMessagePart),
                        "Mensaje debe contener: " + expectedMessagePart);
            }
        }
    }
}
