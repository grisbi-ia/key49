package auracore.key49.queue.reconcile;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import auracore.key49.core.Key49Constants;
import auracore.key49.queue.consumer.TestSchemaHelper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;

/**
 * Test de integración para {@link ReconciliationPoller}.
 *
 * <p>Solo los documentos RECEIVED con reconciliación vencida vuelven a encolar
 * {@code doc.authorize}; los que aún no vencen o no están en RECEIVED se ignoran.
 * Se desactiva el scheduler para invocar el poller de forma determinista.</p>
 */
@QuarkusTest
@TestProfile(ReconciliationPollerTest.NoScheduler.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReconciliationPollerTest {

    private static final String SCHEMA = "tenant_reconcile_test";

    public static class NoScheduler implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.scheduler.enabled", "false",
                    "key49.reconcile.stale-minutes", "10",
                    "key49.recover.failed.cooldown-minutes", "15",
                    "key49.recover.failed.max-age-hours", "24");
        }
    }

    @Inject
    ReconciliationPoller poller;

    @Inject
    DataSource dataSource;

    private UUID docDue;
    private UUID docNotDue;
    private UUID docNoSchedule;
    private UUID docRetry;

    @BeforeAll
    void setup() throws Exception {
        try (var conn = dataSource.getConnection()) {
            try (var ps = conn.prepareStatement("""
                    INSERT INTO tenants (tenant_id, ruc, legal_name, trade_name, main_address, schema_name,
                        required_accounting, micro_enterprise_regime, environment,
                        emission_type, rate_limit_rpm, rate_limit_write_rpm, rate_limit_read_rpm, status, created_at, updated_at)
                    VALUES (?::uuid, '1792146739201', 'Reconcile Test', 'Reconcile', 'Guayaquil', ?,
                        false, false, 'test', 1, 10000, 10000, 10000, 'active', now(), now())
                    """)) {
                ps.setObject(1, UUID.randomUUID().toString());
                ps.setString(2, SCHEMA);
                ps.executeUpdate();
            }
            try (var stmt = conn.createStatement()) {
                stmt.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
                stmt.execute(TestSchemaHelper.documentsTableSql(SCHEMA));
                stmt.execute(TestSchemaHelper.outboxTableSql(SCHEMA));
            }

            docDue = insertDoc(conn, "000000001", "RECEIVED", "now() - interval '1 minute'", 3001);
            docNotDue = insertDoc(conn, "000000002", "RECEIVED", "now() + interval '10 minutes'", 3002);
            docNoSchedule = insertDoc(conn, "000000003", "RECEIVED", "NULL", 3003);
            docRetry = insertDoc(conn, "000000004", "RETRY", "now() - interval '1 minute'", 3004);
        }
    }

    @Test
    @DisplayName("reconcilia solo los RECEIVED vencidos")
    void shouldReconcileOnlyDueReceivedDocuments() throws Exception {
        poller.pollReconciliations();

        assertOutbox(docDue, "doc.authorize");
        assertNoOutbox(docNotDue);
        assertNoOutbox(docNoSchedule);
        assertNoOutbox(docRetry);

        // El next_retry_at se limpia para no reencolar en cada ciclo
        assertNextRetryAtNull(docDue);
    }

    @Test
    @DisplayName("recupera documentos atascados en CREATED/SIGNED/SENT")
    void shouldRecoverStaleTransientDocuments() throws Exception {
        UUID created;
        UUID signed;
        UUID sent;
        UUID fresh;
        try (var conn = dataSource.getConnection()) {
            created = insertTransientDoc(conn, "000000101", "CREATED", "now() - interval '1 hour'");
            signed = insertTransientDoc(conn, "000000102", "SIGNED", "now() - interval '1 hour'");
            sent = insertTransientDoc(conn, "000000103", "SENT", "now() - interval '1 hour'");
            fresh = insertTransientDoc(conn, "000000104", "SIGNED", "now()");
        }

        poller.pollReconciliations();

        assertOutbox(created, "doc.sign");
        assertOutbox(signed, "doc.send");
        assertOutbox(sent, "doc.authorize");
        assertNoOutbox(fresh);
    }

    @Test
    @DisplayName("recupera FAILED por infraestructura (con cooldown), no los de negocio")
    void shouldRecoverFailedInfraOnly() throws Exception {
        UUID infraOld;
        UUID businessFailed;
        UUID infraRecent;
        try (var conn = dataSource.getConnection()) {
            infraOld = insertFailedDoc(conn, "000000201", "now() - interval '30 minutes'", null);
            businessFailed = insertFailedDoc(conn, "000000202", "now() - interval '30 minutes'", "45");
            infraRecent = insertFailedDoc(conn, "000000203", "now() - interval '1 minute'", null);
        }

        poller.pollReconciliations();

        assertOutbox(infraOld, "doc.sign");
        assertNoOutbox(businessFailed);
        assertNoOutbox(infraRecent);
    }

    // ── Helpers ──

    private UUID insertDoc(Connection conn, String seq, String status,
            String nextRetryAtExpr, long accessKeySeed) throws SQLException {
        var docId = UUID.randomUUID();
        var accessKey = "%049d".formatted(accessKeySeed);
        try (var ps = conn.prepareStatement("""
                INSERT INTO %s.documents (document_id, document_type, establishment, issue_point,
                    sequence_number, recipient_id_type, recipient_id, recipient_name,
                    issue_date, status, access_key, sri_submission_date, next_retry_at, original_xml,
                    subtotal_before_tax, total_discount, tip, total_amount, vat_amount,
                    created_at, updated_at)
                VALUES (?::uuid, '01', '001', '001', ?, '04', '1792146739001', 'Test Corp',
                    ?, ?, ?, now(), %s, '<factura>signed</factura>',
                    50.00, 0.00, 0.00, 57.50, 7.50, now(), now())
                """.formatted(SCHEMA, nextRetryAtExpr))) {
            ps.setString(1, docId.toString());
            ps.setString(2, seq);
            ps.setObject(3, java.time.LocalDate.now(Key49Constants.EC_ZONE));
            ps.setString(4, status);
            ps.setString(5, accessKey);
            ps.executeUpdate();
        }
        return docId;
    }

    private UUID insertFailedDoc(Connection conn, String seq, String updatedAtExpr,
            String errorCode) throws SQLException {
        var docId = UUID.randomUUID();
        var accessKey = "%049d".formatted(7000 + Long.parseLong(seq));
        try (var ps = conn.prepareStatement("""
                INSERT INTO %s.documents (document_id, document_type, establishment, issue_point,
                    sequence_number, recipient_id_type, recipient_id, recipient_name,
                    issue_date, status, access_key, original_xml, created_at, updated_at,
                    last_error_code, last_error_message, retry_count, max_retries,
                    subtotal_before_tax, total_discount, tip, total_amount, vat_amount)
                VALUES (?::uuid, '01', '001', '001', ?, '04', '1792146739001', 'Test Corp',
                    ?, 'FAILED', ?, '<factura>signed</factura>', now(), %s,
                    %s, 'SRI circuit breaker open', 6, 6,
                    50.00, 0.00, 0.00, 57.50, 7.50)
                """.formatted(SCHEMA, updatedAtExpr,
                        errorCode == null ? "NULL" : "'" + errorCode + "'"))) {
            ps.setString(1, docId.toString());
            ps.setString(2, seq);
            ps.setObject(3, java.time.LocalDate.now(Key49Constants.EC_ZONE));
            ps.setString(4, accessKey);
            ps.executeUpdate();
        }
        return docId;
    }

    private UUID insertTransientDoc(Connection conn, String seq, String status,
            String updatedAtExpr) throws SQLException {
        var docId = UUID.randomUUID();
        var accessKey = "%049d".formatted(6000 + Long.parseLong(seq));
        try (var ps = conn.prepareStatement("""
                INSERT INTO %s.documents (document_id, document_type, establishment, issue_point,
                    sequence_number, recipient_id_type, recipient_id, recipient_name,
                    issue_date, status, access_key, original_xml, created_at, updated_at,
                    subtotal_before_tax, total_discount, tip, total_amount, vat_amount)
                VALUES (?::uuid, '01', '001', '001', ?, '04', '1792146739001', 'Test Corp',
                    ?, ?, ?, '<factura>signed</factura>', now(), %s,
                    50.00, 0.00, 0.00, 57.50, 7.50)
                """.formatted(SCHEMA, updatedAtExpr))) {
            ps.setString(1, docId.toString());
            ps.setString(2, seq);
            ps.setObject(3, java.time.LocalDate.now(Key49Constants.EC_ZONE));
            ps.setString(4, status);
            ps.setString(5, accessKey);
            ps.executeUpdate();
        }
        return docId;
    }

    private void assertOutbox(UUID docId, String expectedEventType) throws SQLException {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT event_type FROM %s.outbox WHERE aggregate_id = ?::uuid".formatted(SCHEMA))) {
            ps.setString(1, docId.toString());
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Debe existir evento outbox para " + docId);
                assertEquals(expectedEventType, rs.getString("event_type"));
            }
        }
    }

    private void assertNoOutbox(UUID docId) throws SQLException {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT count(*) FROM %s.outbox WHERE aggregate_id = ?::uuid".formatted(SCHEMA))) {
            ps.setString(1, docId.toString());
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "No debe haber eventos para " + docId);
            }
        }
    }

    private void assertNextRetryAtNull(UUID docId) throws SQLException {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT next_retry_at FROM %s.documents WHERE document_id = ?::uuid".formatted(SCHEMA))) {
            ps.setString(1, docId.toString());
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(null, rs.getTimestamp("next_retry_at"));
            }
        }
    }
}
