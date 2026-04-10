package auracore.key49.queue.consumer;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import auracore.key49.core.Key49Constants;
import auracore.key49.core.model.enums.SriEnvironment;
import auracore.key49.queue.event.DocumentEvent;
import auracore.key49.sri.SriException;
import auracore.key49.sri.client.SriReceptionClient;
import auracore.key49.sri.model.ReceptionStatus;
import auracore.key49.sri.model.SriMessage;
import auracore.key49.sri.model.SriReceptionResponse;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;

/**
 * Test de integración para SendConsumer.
 *
 * <p>Verifica el envío al SRI vía SOAP con mock del SriReceptionClient.
 * Escenarios: recepción exitosa, error de negocio (REJECTED), error de
 * infraestructura (RETRY), reintentos agotados (FAILED).</p>
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SendConsumerIntegrationTest {

    private static final String TENANT_SCHEMA = "tenant_send_test";
    private static final String TENANT_RUC = "1792146739001";

    @Inject
    SendConsumer sendConsumer;

    @Inject
    DataSource dataSource;

    @InjectMock
    SriReceptionClient sriReceptionClient;

    private UUID tenantId;
    private UUID docIdReceived;
    private UUID docIdBusinessError;
    private UUID docIdInfraError;
    private UUID docIdRetriesExhausted;
    private UUID docIdNoXml;

    @BeforeAll
    void setup() throws Exception {
        tenantId = UUID.randomUUID();

        try (var conn = dataSource.getConnection()) {
            // Insertar tenant
            try (var ps = conn.prepareStatement("""
                    INSERT INTO tenants (tenant_id, ruc, legal_name, trade_name, main_address, schema_name,
                        required_accounting, micro_enterprise_regime, environment,
                        emission_type, rate_limit_rpm, rate_limit_write_rpm, rate_limit_read_rpm, status, created_at, updated_at)
                    VALUES (?::uuid, ?, ?, ?, ?, ?, false, false, 'test', 1, 10000, 10000, 10000, 'active', now(), now())
                    """)) {
                ps.setObject(1, tenantId.toString());
                ps.setString(2, TENANT_RUC);
                ps.setString(3, "Send Test Corp S.A.");
                ps.setString(4, "Send Test");
                ps.setString(5, "Guayaquil");
                ps.setString(6, TENANT_SCHEMA);
                ps.executeUpdate();
            }

            // Crear esquema con tablas
            try (var stmt = conn.createStatement()) {
                stmt.execute("CREATE SCHEMA IF NOT EXISTS " + TENANT_SCHEMA);
                stmt.execute(TestSchemaHelper.documentsTableSql(TENANT_SCHEMA));
                stmt.execute(TestSchemaHelper.outboxTableSql(TENANT_SCHEMA));
            }

            // Documento firmado listo para enviar al SRI
            docIdReceived = UUID.randomUUID();
            insertSignedDocument(conn, docIdReceived, "000000001", "SIGNED", "1");

            // Documento para error de negocio
            docIdBusinessError = UUID.randomUUID();
            insertSignedDocument(conn, docIdBusinessError, "000000002", "SIGNED", "2");

            // Documento para error de infraestructura
            docIdInfraError = UUID.randomUUID();
            insertSignedDocument(conn, docIdInfraError, "000000003", "SIGNED", "3");

            // Documento con reintentos agotados (en RETRY, que permite transición a FAILED)
            docIdRetriesExhausted = UUID.randomUUID();
            insertSignedDocument(conn, docIdRetriesExhausted, "000000004", "RETRY", "4");
            try (var ps = conn.prepareStatement(
                    "UPDATE %s.documents SET retry_count = 5 WHERE document_id = ?::uuid"
                            .formatted(TENANT_SCHEMA))) {
                ps.setString(1, docIdRetriesExhausted.toString());
                ps.executeUpdate();
            }

            // Documento sin XML firmado
            docIdNoXml = UUID.randomUUID();
            insertSignedDocument(conn, docIdNoXml, "000000005", "SIGNED", "5");
            try (var ps = conn.prepareStatement(
                    "UPDATE %s.documents SET original_xml = NULL WHERE document_id = ?::uuid"
                            .formatted(TENANT_SCHEMA))) {
                ps.setString(1, docIdNoXml.toString());
                ps.executeUpdate();
            }
        }
    }

    @Test
    @Order(1)
    @DisplayName("SRI recibe documento → transición SIGNED → RECEIVED")
    void shouldTransitionToReceived_whenSriAccepts() throws Exception {
        var response = new SriReceptionResponse(
                ReceptionStatus.RECIBIDA, null, List.of());
        when(sriReceptionClient.send(any(String.class), eq(SriEnvironment.TEST)))
                .thenReturn(response);

        sendConsumer.process(toJson(docIdReceived));

        assertDocumentStatus(docIdReceived, "RECEIVED");
        assertOutboxEventCreated(docIdReceived, "doc.authorize");
    }

    @Test
    @Order(2)
    @DisplayName("SRI rechaza con error de negocio → REJECTED")
    void shouldTransitionToRejected_whenBusinessError() throws Exception {
        var messages = List.of(
                new SriMessage("35", "COMPROBANTE YA REGISTRADO", null, "ERROR"));
        var response = new SriReceptionResponse(
                ReceptionStatus.DEVUELTA, null, messages);
        when(sriReceptionClient.send(any(String.class), eq(SriEnvironment.TEST)))
                .thenReturn(response);

        sendConsumer.process(toJson(docIdBusinessError));

        assertDocumentStatus(docIdBusinessError, "REJECTED");
        assertDocumentHasError(docIdBusinessError, "35");
    }

    @Test
    @Order(3)
    @DisplayName("error de infraestructura SRI → RETRY con backoff")
    void shouldTransitionToRetry_whenInfraError() throws Exception {
        when(sriReceptionClient.send(any(String.class), eq(SriEnvironment.TEST)))
                .thenThrow(new SriException("Connection refused"));

        sendConsumer.process(toJson(docIdInfraError));

        assertDocumentStatus(docIdInfraError, "RETRY");
        // Debe tener next_retry_at calculado
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT next_retry_at, retry_count FROM %s.documents WHERE document_id = ?::uuid"
                             .formatted(TENANT_SCHEMA))) {
            ps.setString(1, docIdInfraError.toString());
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertNotNull(rs.getTimestamp("next_retry_at"), "Debe programar próximo reintento");
                assertTrue(rs.getInt("retry_count") > 0, "retry_count debe incrementarse");
            }
        }
    }

    @Test
    @Order(4)
    @DisplayName("reintentos agotados → FAILED")
    void shouldTransitionToFailed_whenRetriesExhausted() throws Exception {
        when(sriReceptionClient.send(any(String.class), eq(SriEnvironment.TEST)))
                .thenThrow(new SriException("SRI timeout"));

        sendConsumer.process(toJson(docIdRetriesExhausted));

        assertDocumentStatus(docIdRetriesExhausted, "FAILED");
    }

    @Test
    @Order(5)
    @DisplayName("documento sin XML firmado → permanece SIGNED con error (state machine no permite SIGNED→FAILED)")
    void shouldFailDocument_whenNoSignedXml() throws Exception {
        sendConsumer.process(toJson(docIdNoXml));

        // SIGNED→FAILED no es transición válida; el consumer registra el error pero no cambia estado
        assertDocumentStatus(docIdNoXml, "SIGNED");
        assertDocumentHasError(docIdNoXml, null); // no error_code, solo lastErrorMessage
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT last_error_message FROM %s.documents WHERE document_id = ?::uuid"
                             .formatted(TENANT_SCHEMA))) {
            ps.setString(1, docIdNoXml.toString());
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertNotNull(rs.getString("last_error_message"),
                        "Debe tener mensaje de error registrado");
                assertTrue(rs.getString("last_error_message").contains("No signed XML"),
                        "Mensaje debe indicar la causa");
            }
        }
    }

    @Test
    @Order(6)
    @DisplayName("tenant inexistente no genera excepción")
    void shouldHandleNonExistentTenant() {
        sendConsumer.process(toJson(UUID.randomUUID(), "tenant_inexistente"));
    }

    @Test
    @Order(7)
    @DisplayName("documento inexistente no genera excepción")
    void shouldHandleNonExistentDocument() {
        sendConsumer.process(toJson(UUID.randomUUID(), TENANT_SCHEMA));
    }

    // ── Helpers ──

    private JsonObject toJson(UUID documentId) {
        return toJson(documentId, TENANT_SCHEMA);
    }

    private JsonObject toJson(UUID documentId, String schema) {
        var event = DocumentEvent.of(documentId, schema, "doc.send");
        return new JsonObject()
                .put("document_id", event.documentId().toString())
                .put("tenant_schema_name", event.tenantSchemaName())
                .put("event_type", event.eventType())
                .put("retry_count", event.retryCount())
                .put("timestamp", event.timestamp().toString());
    }

    private void insertSignedDocument(java.sql.Connection conn, UUID docId,
                                      String seqNum, String status,
                                      String accessKeySuffix) throws SQLException {
        // Generate a unique 49-digit access key per document
        var baseKey = "050420260117921467390011001001000000000";
        var accessKey = baseKey + "0".repeat(49 - baseKey.length() - accessKeySuffix.length()) + accessKeySuffix;
        try (var ps = conn.prepareStatement("""
                INSERT INTO %s.documents (document_id, document_type, establishment, issue_point,
                    sequence_number, recipient_id_type, recipient_id, recipient_name,
                    issue_date, status, original_xml, access_key,
                    subtotal_before_tax, total_discount, tip, total_amount, vat_amount,
                    created_at, updated_at)
                VALUES (?::uuid, '01', '001', '001', ?, '04', '1792146739001', 'Test Corp',
                    ?, ?, '<factura>signed</factura>', ?,
                    50.00, 0.00, 0.00, 57.50, 7.50, now(), now())
                """.formatted(TENANT_SCHEMA))) {
            ps.setString(1, docId.toString());
            ps.setString(2, seqNum);
            ps.setObject(3, LocalDate.now(Key49Constants.EC_ZONE));
            ps.setString(4, status);
            ps.setString(5, accessKey);
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

    private void assertDocumentHasError(UUID docId, String expectedErrorCode) throws SQLException {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT last_error_code, last_error_message FROM %s.documents WHERE document_id = ?::uuid"
                             .formatted(TENANT_SCHEMA))) {
            ps.setString(1, docId.toString());
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(expectedErrorCode, rs.getString("last_error_code"));
                assertNotNull(rs.getString("last_error_message"));
            }
        }
    }

    private void assertOutboxEventCreated(UUID docId, String expectedEventType) throws SQLException {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT event_type FROM %s.outbox WHERE aggregate_id = ?::uuid"
                             .formatted(TENANT_SCHEMA))) {
            ps.setString(1, docId.toString());
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Debe existir evento outbox");
                assertEquals(expectedEventType, rs.getString("event_type"));
            }
        }
    }
}
