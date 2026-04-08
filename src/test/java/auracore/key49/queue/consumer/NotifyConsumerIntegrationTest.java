package auracore.key49.queue.consumer;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import static org.mockito.Mockito.when;

import auracore.key49.core.Key49Constants;
import auracore.key49.core.model.Document;
import auracore.key49.core.model.WebhookDelivery;
import auracore.key49.notify.webhook.WebhookDispatcher;
import auracore.key49.queue.event.DocumentEvent;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;

/**
 * Test de integración para NotifyConsumer.
 *
 * <p>Verifica la notificación de documentos autorizados con mock del WebhookDispatcher.
 * Escenarios: notificación exitosa (AUTHORIZED → NOTIFIED), sin webhook configurado,
 * fallo de webhook (no bloquea transición).</p>
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NotifyConsumerIntegrationTest {

    private static final String TENANT_SCHEMA = "tenant_notify_test";
    private static final String TENANT_RUC = "1792146739001";

    @Inject
    NotifyConsumer notifyConsumer;

    @Inject
    DataSource dataSource;

    @InjectMock
    WebhookDispatcher webhookDispatcher;

    private UUID tenantId;
    private UUID docIdNotify;
    private UUID docIdNoWebhook;
    private UUID docIdAlreadyNotified;

    @BeforeAll
    void setup() throws Exception {
        tenantId = UUID.randomUUID();
        var tenantIdNoWebhook = UUID.randomUUID();

        try (var conn = dataSource.getConnection()) {
            // Tenant con webhook
            try (var ps = conn.prepareStatement("""
                    INSERT INTO tenants (tenant_id, ruc, legal_name, trade_name, main_address, schema_name,
                        required_accounting, micro_enterprise_regime, environment,
                        emission_type, rate_limit_rpm, status, webhook_url, webhook_secret,
                        created_at, updated_at)
                    VALUES (?::uuid, ?, ?, ?, ?, ?, false, false, 'test', 1, 10000, 'active',
                        'https://example.com/webhook', 'secret123', now(), now())
                    """)) {
                ps.setObject(1, tenantId.toString());
                ps.setString(2, TENANT_RUC);
                ps.setString(3, "Notify Test Corp S.A.");
                ps.setString(4, "Notify Test");
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

            // Documento AUTHORIZED para notificar
            docIdNotify = UUID.randomUUID();
            insertAuthorizedDocument(conn, docIdNotify, "000000001");

            // Documento AUTHORIZED sin webhook (tendrá webhook_url null en tenant)
            docIdNoWebhook = UUID.randomUUID();
            insertAuthorizedDocument(conn, docIdNoWebhook, "000000002");

            // Documento ya NOTIFIED
            docIdAlreadyNotified = UUID.randomUUID();
            insertDocument(conn, docIdAlreadyNotified, "000000003", "NOTIFIED");
        }
    }

    @Test
    @Order(1)
    @DisplayName("documento AUTHORIZED → NOTIFIED con webhook entregado")
    void shouldTransitionToNotified_withWebhookDelivery() throws Exception {
        var delivery = WebhookDelivery.create(docIdNotify, "document.authorized",
                "https://example.com/webhook", "{}");
        delivery.markDelivered(200, "OK", 50);
        when(webhookDispatcher.dispatch(anyString(), anyString(), any(Document.class), anyString()))
                .thenReturn(delivery);

        notifyConsumer.process(toJson(docIdNotify));

        assertDocumentStatus(docIdNotify, "NOTIFIED");
    }

    @Test
    @Order(2)
    @DisplayName("webhook falla pero documento se marca NOTIFIED igual")
    void shouldStillNotify_whenWebhookFails() throws Exception {
        when(webhookDispatcher.dispatch(anyString(), anyString(), any(Document.class), anyString()))
                .thenThrow(new RuntimeException("Connection refused"));

        notifyConsumer.process(toJson(docIdNoWebhook));

        // NotifyConsumer catches webhook exceptions — transition still happens
        assertDocumentStatus(docIdNoWebhook, "NOTIFIED");
    }

    @Test
    @Order(3)
    @DisplayName("ignora documento ya notificado")
    void shouldSkipAlreadyNotified() throws Exception {
        notifyConsumer.process(toJson(docIdAlreadyNotified));

        assertDocumentStatus(docIdAlreadyNotified, "NOTIFIED");
    }

    @Test
    @Order(4)
    @DisplayName("tenant inexistente no genera excepción")
    void shouldHandleNonExistentTenant() {
        notifyConsumer.process(toJson(UUID.randomUUID(), "tenant_inexistente"));
    }

    @Test
    @Order(5)
    @DisplayName("documento inexistente no genera excepción")
    void shouldHandleNonExistentDocument() {
        notifyConsumer.process(toJson(UUID.randomUUID(), TENANT_SCHEMA));
    }

    // ── Helpers ──

    private JsonObject toJson(UUID documentId) {
        return toJson(documentId, TENANT_SCHEMA);
    }

    private JsonObject toJson(UUID documentId, String schema) {
        var event = DocumentEvent.of(documentId, schema, "doc.notify");
        return new JsonObject()
                .put("document_id", event.documentId().toString())
                .put("tenant_schema_name", event.tenantSchemaName())
                .put("event_type", event.eventType())
                .put("retry_count", event.retryCount())
                .put("timestamp", event.timestamp().toString());
    }

    private void insertAuthorizedDocument(java.sql.Connection conn, UUID docId,
                                          String seqNum) throws SQLException {
        insertDocument(conn, docId, seqNum, "AUTHORIZED");
    }

    private void insertDocument(java.sql.Connection conn, UUID docId,
                                String seqNum, String status) throws SQLException {
        var baseKey = "050420260117921467390011001001000000000";
        var accessKey = baseKey + "0".repeat(49 - baseKey.length() - seqNum.length()) + seqNum;
        try (var ps = conn.prepareStatement("""
                INSERT INTO %s.documents (document_id, document_type, establishment, issue_point,
                    sequence_number, recipient_id_type, recipient_id, recipient_name,
                    issue_date, status, access_key, authorization_number, original_xml,
                    subtotal_before_tax, total_discount, tip, total_amount, vat_amount,
                    created_at, updated_at)
                VALUES (?::uuid, '01', '001', '001', ?, '04', '1792146739001', 'Test Corp',
                    ?, ?, ?, ?,
                    '<factura>authorized</factura>',
                    50.00, 0.00, 0.00, 57.50, 7.50, now(), now())
                """.formatted(TENANT_SCHEMA))) {
            ps.setString(1, docId.toString());
            ps.setString(2, seqNum);
            ps.setObject(3, LocalDate.now(Key49Constants.EC_ZONE));
            ps.setString(4, status);
            ps.setString(5, accessKey);
            ps.setString(6, accessKey);
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
}
