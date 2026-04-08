package auracore.key49.queue.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;

import javax.sql.DataSource;

import auracore.key49.core.Key49Constants;
import auracore.key49.queue.event.DocumentEvent;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Test de integración para OutboxPoller.
 *
 * <p>Verifica que el poller lee eventos no publicados del outbox, los enruta
 * mediante OutboxEventRouter y los marca como publicados. Usa un mock del
 * OutboxEventRouter para capturar los eventos sin publicar a RabbitMQ real.</p>
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OutboxPollerIntegrationTest {

    private static final String TENANT_SCHEMA = "tenant_outbox_test";
    private static final String TENANT_RUC = "1792146739001";

    @Inject
    OutboxPoller outboxPoller;

    @Inject
    DataSource dataSource;

    @InjectMock
    OutboxEventRouter outboxEventRouter;

    private UUID tenantId;
    private UUID docId;
    private UUID outboxId;

    @BeforeAll
    void setup() throws Exception {
        tenantId = UUID.randomUUID();
        docId = UUID.randomUUID();

        try (var conn = dataSource.getConnection()) {
            try (var ps = conn.prepareStatement("""
                    INSERT INTO tenants (tenant_id, ruc, legal_name, trade_name, main_address, schema_name,
                        required_accounting, micro_enterprise_regime, environment,
                        emission_type, rate_limit_rpm, status, created_at, updated_at)
                    VALUES (?::uuid, ?, ?, ?, ?, ?, false, false, 'test', 1, 10000, 'active', now(), now())
                    """)) {
                ps.setObject(1, tenantId.toString());
                ps.setString(2, TENANT_RUC);
                ps.setString(3, "Outbox Test Corp S.A.");
                ps.setString(4, "Outbox Test");
                ps.setString(5, "Guayaquil");
                ps.setString(6, TENANT_SCHEMA);
                ps.executeUpdate();
            }

            try (var stmt = conn.createStatement()) {
                stmt.execute("CREATE SCHEMA IF NOT EXISTS " + TENANT_SCHEMA);
                stmt.execute(documentsTableSql(TENANT_SCHEMA));
                stmt.execute(outboxTableSql(TENANT_SCHEMA));
            }

            // Insertar documento origen
            try (var ps = conn.prepareStatement("""
                    INSERT INTO %s.documents (document_id, document_type, establishment, issue_point,
                        sequence_number, recipient_id_type, recipient_id, recipient_name,
                        issue_date, status,
                        subtotal_before_tax, total_discount, tip, total_amount, vat_amount,
                        created_at, updated_at)
                    VALUES (?::uuid, '01', '001', '001', '000000001', '04', '1792146739001', 'Test Corp',
                        ?, 'SIGNED',
                        50.00, 0.00, 0.00, 57.50, 7.50, now(), now())
                    """.formatted(TENANT_SCHEMA))) {
                ps.setString(1, docId.toString());
                ps.setObject(2, LocalDate.now(Key49Constants.EC_ZONE));
                ps.executeUpdate();
            }

            // Insertar evento outbox no publicado
            outboxId = UUID.randomUUID();
            try (var ps = conn.prepareStatement("""
                    INSERT INTO %s.outbox (outbox_id, aggregate_type, aggregate_id, event_type,
                        payload, published, created_at)
                    VALUES (?::uuid, 'Document', ?::uuid, 'doc.send', '{}'::jsonb, false, now())
                    """.formatted(TENANT_SCHEMA))) {
                ps.setString(1, outboxId.toString());
                ps.setString(2, docId.toString());
                ps.executeUpdate();
            }
        }
    }

    @Test
    @Order(1)
    @DisplayName("poll() enruta evento no publicado y lo marca como publicado")
    void shouldPollAndPublishOutboxEvents() throws Exception {
        doNothing().when(outboxEventRouter).route(any(DocumentEvent.class));

        // Invocar el método poll directamente (no esperar al scheduler)
        outboxPoller.poll();

        // Verificar que el router fue invocado
        verify(outboxEventRouter, atLeastOnce()).route(any(DocumentEvent.class));

        // Verificar que el evento se marcó como publicado
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT published, published_at FROM %s.outbox WHERE outbox_id = ?::uuid"
                             .formatted(TENANT_SCHEMA))) {
            ps.setString(1, outboxId.toString());
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Evento outbox debe existir");
                assertTrue(rs.getBoolean("published"), "Evento debe estar publicado");
                assertNotNull(rs.getTimestamp("published_at"), "Debe tener fecha de publicación");
            }
        }
    }

    @Test
    @Order(2)
    @DisplayName("poll() no falla cuando no hay eventos pendientes")
    void shouldHandleEmptyOutbox() {
        // Ya se publicó el evento en test anterior, no hay pendientes
        outboxPoller.poll();
    }

    @Test
    @Order(3)
    @DisplayName("poll() maneja tenant con error sin propagar excepción")
    void shouldHandleTenantError() throws Exception {
        // Insertar un tenant con esquema inexistente
        var invalidTenantId = UUID.randomUUID();
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("""
                     INSERT INTO tenants (tenant_id, ruc, legal_name, trade_name, main_address, schema_name,
                         required_accounting, micro_enterprise_regime, environment,
                         emission_type, rate_limit_rpm, status, created_at, updated_at)
                     VALUES (?::uuid, '1790016919001', 'Bad Tenant', 'Bad', 'Nowhere',
                         'tenant_bad_schema', false, false, 'test', 1, 10000, 'active', now(), now())
                     """)) {
            ps.setString(1, invalidTenantId.toString());
            ps.executeUpdate();
        }

        // poll no debe fallar aunque un tenant tenga problemas
        outboxPoller.poll();
    }

    // ── SQL Helpers ──

    private static String documentsTableSql(String schema) {
        return """
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
                    CONSTRAINT uq_%s_doc_number UNIQUE (document_type, establishment, issue_point, sequence_number),
                    CONSTRAINT uq_%s_doc_access_key UNIQUE (access_key),
                    CONSTRAINT uq_%s_doc_idempotency UNIQUE (idempotency_key)
                )
                """.formatted(schema, schema, schema, schema);
    }

    private static String outboxTableSql(String schema) {
        return """
                CREATE TABLE IF NOT EXISTS %s.outbox (
                    outbox_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    aggregate_type VARCHAR(50) NOT NULL,
                    aggregate_id UUID NOT NULL,
                    event_type VARCHAR(50) NOT NULL,
                    payload JSONB NOT NULL,
                    published BOOLEAN NOT NULL DEFAULT false,
                    published_at TIMESTAMPTZ,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """.formatted(schema);
    }
}
