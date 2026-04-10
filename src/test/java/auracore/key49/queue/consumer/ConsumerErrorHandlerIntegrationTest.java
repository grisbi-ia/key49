package auracore.key49.queue.consumer;

import java.sql.SQLException;
import java.time.LocalDate;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import auracore.key49.core.Key49Constants;
import auracore.key49.core.model.Document;
import auracore.key49.core.model.WebhookDelivery;
import auracore.key49.notify.webhook.WebhookDispatcher;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * Test de integración para ConsumerErrorHandler.
 *
 * <p>
 * Verifica que errores inesperados de los consumers se persisten correctamente
 * en la tabla documents y que se transiciona a FAILED si el documento no está
 * en estado terminal.</p>
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConsumerErrorHandlerIntegrationTest {

    private static final String TENANT_SCHEMA = "tenant_errorhandler_test";
    private static final String TENANT_RUC = "1792146739001";

    @Inject
    ConsumerErrorHandler errorHandler;

    @Inject
    DataSource dataSource;

    @InjectMock
    WebhookDispatcher webhookDispatcher;

    private UUID tenantId;
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
                        emission_type, rate_limit_rpm, rate_limit_write_rpm, rate_limit_read_rpm, status, webhook_url, webhook_secret,
                        created_at, updated_at)
                    VALUES (?::uuid, ?, ?, ?, ?, ?, false, false, 'test', 1, 10000, 10000, 10000, 'active',
                        'https://example.com/webhook', 'secret123', now(), now())
                    """)) {
                ps.setObject(1, tenantId.toString());
                ps.setString(2, TENANT_RUC);
                ps.setString(3, "ErrorHandler Test S.A.");
                ps.setString(4, "EH Test");
                ps.setString(5, "Guayaquil");
                ps.setString(6, TENANT_SCHEMA);
                ps.executeUpdate();
            }

            try (var stmt = conn.createStatement()) {
                stmt.execute("CREATE SCHEMA IF NOT EXISTS " + TENANT_SCHEMA);
                stmt.execute(TestSchemaHelper.documentsTableSql(TENANT_SCHEMA));
                stmt.execute(TestSchemaHelper.outboxTableSql(TENANT_SCHEMA));
                stmt.execute(TestSchemaHelper.webhookDeliveriesTableSql(TENANT_SCHEMA));
            }

            // Documento en CREATED (no terminal, CREATED → FAILED es transición válida)
            docIdSigned = UUID.randomUUID();
            insertDocument(conn, docIdSigned, "000000001", "CREATED");

            // Documento ya FAILED (terminal)
            docIdAlreadyFailed = UUID.randomUUID();
            insertDocument(conn, docIdAlreadyFailed, "000000002", "FAILED");

            // Documento REJECTED (terminal)
            docIdRejected = UUID.randomUUID();
            insertDocument(conn, docIdRejected, "000000003", "REJECTED");
        }
    }

    @Test
    @Order(1)
    @DisplayName("persiste error y transiciona a FAILED desde CREATED")
    void shouldPersistErrorAndTransitionToFailed() throws Exception {
        var exception = new RuntimeException("NullPointerException in XML builder");

        errorHandler.persistError(docIdSigned, TENANT_SCHEMA, "TestConsumer", exception);

        assertDocumentStatus(docIdSigned, "FAILED");
        assertDocumentHasError(docIdSigned, "[TestConsumer] NullPointerException in XML builder");
    }

    @Test
    @Order(2)
    @DisplayName("persiste error en documento ya FAILED sin cambiar estado")
    void shouldPersistErrorOnAlreadyFailedDocument() throws Exception {
        var exception = new RuntimeException("Additional error");

        errorHandler.persistError(docIdAlreadyFailed, TENANT_SCHEMA, "TestConsumer", exception);

        assertDocumentStatus(docIdAlreadyFailed, "FAILED");
        assertDocumentHasError(docIdAlreadyFailed, "[TestConsumer] Additional error");
    }

    @Test
    @Order(3)
    @DisplayName("persiste error en documento REJECTED sin cambiar estado")
    void shouldPersistErrorOnRejectedDocument() throws Exception {
        var exception = new RuntimeException("Unexpected error on rejected doc");

        errorHandler.persistError(docIdRejected, TENANT_SCHEMA, "TestConsumer", exception);

        assertDocumentStatus(docIdRejected, "REJECTED");
        assertDocumentHasError(docIdRejected, "[TestConsumer] Unexpected error on rejected doc");
    }

    @Test
    @Order(4)
    @DisplayName("documento inexistente no genera excepción")
    void shouldHandleNonExistentDocument() {
        var exception = new RuntimeException("Some error");

        // No debe lanzar excepción
        errorHandler.persistError(UUID.randomUUID(), TENANT_SCHEMA, "TestConsumer", exception);
    }

    @Test
    @Order(5)
    @DisplayName("tenant inexistente no genera excepción")
    void shouldHandleNonExistentTenant() {
        var exception = new RuntimeException("Some error");

        // No debe lanzar excepción (withTenantTransaction falla pero se captura)
        errorHandler.persistError(UUID.randomUUID(), "tenant_inexistente", "TestConsumer", exception);
    }

    @Test
    @Order(6)
    @DisplayName("despacha webhook document.failed al transicionar a FAILED")
    void shouldDispatchWebhook_whenTransitioningToFailed() throws Exception {
        var docIdForWebhook = UUID.randomUUID();
        try (var conn = dataSource.getConnection()) {
            insertDocument(conn, docIdForWebhook, "000000004", "CREATED");
        }

        var delivery = WebhookDelivery.create(docIdForWebhook, "document.failed",
                "https://example.com/webhook", "{}");
        delivery.markDelivered(200, "OK", 50);
        when(webhookDispatcher.dispatch(anyString(), anyString(), any(Document.class), eq("document.failed")))
                .thenReturn(delivery);

        errorHandler.persistError(docIdForWebhook, TENANT_SCHEMA, "TestConsumer",
                new RuntimeException("Test failure for webhook"));

        assertDocumentStatus(docIdForWebhook, "FAILED");
        verify(webhookDispatcher).dispatch(
                eq("https://example.com/webhook"), eq("secret123"),
                any(Document.class), eq("document.failed"));
    }

    @Test
    @Order(7)
    @DisplayName("no despacha webhook si documento ya está en estado terminal")
    void shouldNotDispatchWebhook_whenAlreadyTerminal() throws Exception {
        var docIdTerminal = UUID.randomUUID();
        try (var conn = dataSource.getConnection()) {
            insertDocument(conn, docIdTerminal, "000000005", "FAILED");
        }

        reset(webhookDispatcher);

        errorHandler.persistError(docIdTerminal, TENANT_SCHEMA, "TestConsumer",
                new RuntimeException("Error on terminal doc"));

        verify(webhookDispatcher, never()).dispatch(anyString(), anyString(),
                any(Document.class), anyString());
    }

    // ── Helpers ──
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
        try (var conn = dataSource.getConnection(); var ps = conn.prepareStatement(
                "SELECT status FROM %s.documents WHERE document_id = ?::uuid"
                        .formatted(TENANT_SCHEMA))) {
            ps.setString(1, docId.toString());
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Documento debe existir");
                assertEquals(expectedStatus, rs.getString("status"));
            }
        }
    }

    private void assertDocumentHasError(UUID docId, String expectedMessage) throws SQLException {
        try (var conn = dataSource.getConnection(); var ps = conn.prepareStatement(
                "SELECT last_error_message FROM %s.documents WHERE document_id = ?::uuid"
                        .formatted(TENANT_SCHEMA))) {
            ps.setString(1, docId.toString());
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertNotNull(rs.getString("last_error_message"));
                assertEquals(expectedMessage, rs.getString("last_error_message"));
            }
        }
    }
}
