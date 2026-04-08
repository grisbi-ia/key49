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
import auracore.key49.sri.client.SriAuthorizationClient;
import auracore.key49.sri.model.AuthorizationStatus;
import auracore.key49.sri.model.SriAuthorizationResponse;
import auracore.key49.sri.model.SriMessage;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;

/**
 * Test de integración para AuthorizeConsumer.
 *
 * <p>Verifica la consulta de autorización al SRI con mock del SriAuthorizationClient.
 * Escenarios: autorización exitosa (AUTHORIZED), rechazo (REJECTED), error de
 * infraestructura (RETRY), reintentos agotados (FAILED).</p>
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthorizeConsumerIntegrationTest {

    private static final String TENANT_SCHEMA = "tenant_auth_test";
    private static final String TENANT_RUC = "1792146739001";
    private static final String ACCESS_KEY = "0504202601179214673900110010010000000011234567813";

    @Inject
    AuthorizeConsumer authorizeConsumer;

    @Inject
    DataSource dataSource;

    @InjectMock
    SriAuthorizationClient sriAuthorizationClient;

    private UUID tenantId;
    private UUID docIdAuthorized;
    private UUID docIdBusinessError;
    private UUID docIdInfraError;
    private UUID docIdRetriesExhausted;

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
                ps.setString(3, "Auth Test Corp S.A.");
                ps.setString(4, "Auth Test");
                ps.setString(5, "Guayaquil");
                ps.setString(6, TENANT_SCHEMA);
                ps.executeUpdate();
            }

            try (var stmt = conn.createStatement()) {
                stmt.execute("CREATE SCHEMA IF NOT EXISTS " + TENANT_SCHEMA);
                stmt.execute(TestSchemaHelper.documentsTableSql(TENANT_SCHEMA));
                stmt.execute(TestSchemaHelper.outboxTableSql(TENANT_SCHEMA));
            }

            // Documento en RECEIVED listo para autorización
            docIdAuthorized = UUID.randomUUID();
            insertReceivedDocument(conn, docIdAuthorized, "000000001",
                    ACCESS_KEY.substring(0, 48) + "3");

            // Documento para error de negocio
            docIdBusinessError = UUID.randomUUID();
            insertReceivedDocument(conn, docIdBusinessError, "000000002",
                    ACCESS_KEY.substring(0, 48) + "4");

            // Documento para error de infraestructura
            docIdInfraError = UUID.randomUUID();
            insertReceivedDocument(conn, docIdInfraError, "000000003",
                    ACCESS_KEY.substring(0, 48) + "5");

            // Documento con reintentos agotados (en RETRY, que sí puede transicionar a FAILED)
            docIdRetriesExhausted = UUID.randomUUID();
            insertDocument(conn, docIdRetriesExhausted, "000000004",
                    ACCESS_KEY.substring(0, 48) + "6", "RETRY");
            try (var ps = conn.prepareStatement(
                    "UPDATE %s.documents SET retry_count = 5 WHERE document_id = ?::uuid"
                            .formatted(TENANT_SCHEMA))) {
                ps.setString(1, docIdRetriesExhausted.toString());
                ps.executeUpdate();
            }
        }
    }

    @Test
    @Order(1)
    @DisplayName("SRI autoriza documento → RECEIVED → AUTHORIZED + outbox doc.notify")
    void shouldTransitionToAuthorized_whenSriAuthorizes() throws Exception {
        var response = new SriAuthorizationResponse(
                AuthorizationStatus.AUTORIZADO,
                "0504202601179214673900110010010000000011234567813",
                "05/04/2026 10:30:00",
                ACCESS_KEY.substring(0, 48) + "3",
                "<factura>authorized</factura>",
                List.of());
        when(sriAuthorizationClient.authorize(any(String.class), eq(SriEnvironment.TEST)))
                .thenReturn(response);

        authorizeConsumer.process(toJson(docIdAuthorized));

        assertDocumentStatus(docIdAuthorized, "AUTHORIZED");
        assertOutboxEventCreated(docIdAuthorized, "doc.notify");
    }

    @Test
    @Order(2)
    @DisplayName("SRI rechaza con error de negocio → REJECTED")
    void shouldTransitionToRejected_whenBusinessError() throws Exception {
        var messages = List.of(
                new SriMessage("45", "FECHA FUERA DE RANGO", null, "ERROR"));
        var response = new SriAuthorizationResponse(
                AuthorizationStatus.NO_AUTORIZADO, null, null,
                ACCESS_KEY.substring(0, 48) + "4", null, messages);
        when(sriAuthorizationClient.authorize(any(String.class), eq(SriEnvironment.TEST)))
                .thenReturn(response);

        authorizeConsumer.process(toJson(docIdBusinessError));

        assertDocumentStatus(docIdBusinessError, "REJECTED");
        assertDocumentHasError(docIdBusinessError, "45");
    }

    @Test
    @Order(3)
    @DisplayName("error de infraestructura SRI → RETRY")
    void shouldTransitionToRetry_whenInfraError() throws Exception {
        when(sriAuthorizationClient.authorize(any(String.class), eq(SriEnvironment.TEST)))
                .thenThrow(new SriException("Connection timeout"));

        authorizeConsumer.process(toJson(docIdInfraError));

        assertDocumentStatus(docIdInfraError, "RETRY");
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT next_retry_at, retry_count FROM %s.documents WHERE document_id = ?::uuid"
                             .formatted(TENANT_SCHEMA))) {
            ps.setString(1, docIdInfraError.toString());
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertNotNull(rs.getTimestamp("next_retry_at"));
                assertTrue(rs.getInt("retry_count") > 0);
            }
        }
    }

    @Test
    @Order(4)
    @DisplayName("reintentos agotados → FAILED")
    void shouldTransitionToFailed_whenRetriesExhausted() throws Exception {
        when(sriAuthorizationClient.authorize(any(String.class), eq(SriEnvironment.TEST)))
                .thenThrow(new SriException("SRI unavailable"));

        authorizeConsumer.process(toJson(docIdRetriesExhausted));

        assertDocumentStatus(docIdRetriesExhausted, "FAILED");
    }

    @Test
    @Order(5)
    @DisplayName("tenant inexistente no genera excepción")
    void shouldHandleNonExistentTenant() {
        authorizeConsumer.process(toJson(UUID.randomUUID(), "tenant_inexistente"));
    }

    @Test
    @Order(6)
    @DisplayName("documento inexistente no genera excepción")
    void shouldHandleNonExistentDocument() {
        authorizeConsumer.process(toJson(UUID.randomUUID(), TENANT_SCHEMA));
    }

    // ── Helpers ──

    private JsonObject toJson(UUID documentId) {
        return toJson(documentId, TENANT_SCHEMA);
    }

    private JsonObject toJson(UUID documentId, String schema) {
        var event = DocumentEvent.of(documentId, schema, "doc.authorize");
        return new JsonObject()
                .put("document_id", event.documentId().toString())
                .put("tenant_schema_name", event.tenantSchemaName())
                .put("event_type", event.eventType())
                .put("retry_count", event.retryCount())
                .put("timestamp", event.timestamp().toString());
    }

    private void insertReceivedDocument(java.sql.Connection conn, UUID docId,
                                        String seqNum, String accessKey) throws SQLException {
        insertDocument(conn, docId, seqNum, accessKey, "RECEIVED");
    }

    private void insertDocument(java.sql.Connection conn, UUID docId,
                                String seqNum, String accessKey, String status) throws SQLException {
        try (var ps = conn.prepareStatement("""
                INSERT INTO %s.documents (document_id, document_type, establishment, issue_point,
                    sequence_number, recipient_id_type, recipient_id, recipient_name,
                    issue_date, status, access_key, original_xml,
                    subtotal_before_tax, total_discount, tip, total_amount, vat_amount,
                    created_at, updated_at)
                VALUES (?::uuid, '01', '001', '001', ?, '04', '1792146739001', 'Test Corp',
                    ?, ?, ?, '<factura>signed</factura>',
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
                     "SELECT last_error_code FROM %s.documents WHERE document_id = ?::uuid"
                             .formatted(TENANT_SCHEMA))) {
            ps.setString(1, docId.toString());
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(expectedErrorCode, rs.getString("last_error_code"));
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
