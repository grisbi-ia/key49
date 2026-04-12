package auracore.key49.notify.plan;

import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * Tests de integración para PlanAlertService: umbrales de cuota, envío de
 * emails, y job de planes por vencer.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("PlanAlertService — alertas de plan y cuota")
class PlanAlertServiceTest {

    @Inject
    PlanAlertService planAlertService;

    @Inject
    DataSource dataSource;

    private final List<UUID> createdTenantIds = new ArrayList<>();

    @BeforeAll
    void addCheckConstraints() throws Exception {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var stmt = conn.createStatement()) {
                safeExec(stmt, "ALTER TABLE tenants ADD CONSTRAINT chk_tenants_plan_type "
                        + "CHECK (plan_type IN ('demo', 'starter', 'business', 'enterprise'))");
                safeExec(stmt, "ALTER TABLE tenants ADD CONSTRAINT chk_tenants_document_quota "
                        + "CHECK (document_quota > 0)");
                safeExec(stmt, "ALTER TABLE tenants ADD CONSTRAINT chk_tenants_documents_used "
                        + "CHECK (documents_used >= 0)");
            }
        }
    }

    private void safeExec(java.sql.Statement stmt, String sql) {
        try {
            stmt.execute(sql);
        } catch (SQLException e) {
            // already exists
        }
    }

    @BeforeEach
    void cleanupBeforeEach() throws Exception {
        planAlertService.getFiredAlerts().clear();
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            for (UUID id : createdTenantIds) {
                stmt.execute("DELETE FROM tenants WHERE tenant_id = '%s'".formatted(id));
            }
        }
        createdTenantIds.clear();
    }

    @AfterAll
    void finalCleanup() throws Exception {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            for (UUID id : createdTenantIds) {
                stmt.execute("DELETE FROM tenants WHERE tenant_id = '%s'".formatted(id));
            }
        }
    }

    // ── Threshold detection (unit logic) ────────────────────────────────────
    @Test
    @DisplayName("crossedWarningThreshold detecta cruce del 80%")
    void crossedWarningThreshold() {
        // quota=100, threshold=80: 79→80 crosses
        assertTrue(planAlertService.crossedWarningThreshold(79, 80, 100));
    }

    @Test
    @DisplayName("crossedWarningThreshold no dispara si ya estaba sobre 80%")
    void crossedWarningThresholdAlreadyAbove() {
        assertFalse(planAlertService.crossedWarningThreshold(80, 81, 100));
    }

    @Test
    @DisplayName("crossedWarningThreshold no dispara si aún no llega al 80%")
    void crossedWarningThresholdBelowStill() {
        assertFalse(planAlertService.crossedWarningThreshold(50, 51, 100));
    }

    @Test
    @DisplayName("crossedWarningThreshold quota=25 → umbral en 20")
    void crossedWarningThresholdSmallQuota() {
        // 25 * 0.8 = 20
        assertTrue(planAlertService.crossedWarningThreshold(19, 20, 25));
        assertFalse(planAlertService.crossedWarningThreshold(18, 19, 25));
    }

    @Test
    @DisplayName("justExhausted detecta cuota agotada")
    void justExhaustedDetected() {
        assertTrue(planAlertService.justExhausted(100, 100));
    }

    @Test
    @DisplayName("justExhausted no dispara si queda cuota")
    void justExhaustedNotYet() {
        assertFalse(planAlertService.justExhausted(99, 100));
    }

    // ── fireAlert observable ────────────────────────────────────────────────
    @Test
    @DisplayName("fireAlert registra evento en firedAlerts")
    void fireAlertRecordsEvent() {
        planAlertService.fireAlert("plan.quota_warning", null, null,
                "{}", null, "Test Subject", "Test body");
        assertEquals(1, planAlertService.getFiredAlerts().size());
        assertEquals("plan.quota_warning", planAlertService.getFiredAlerts().getFirst());
    }

    // ── checkQuotaThresholds with email ─────────────────────────────────────
    @Test
    @DisplayName("checkQuotaThresholds dispara WARNING al cruzar 80%")
    void checkQuotaThresholdsSendsWarningAlert() throws Exception {
        var tenantId = insertTestTenant("starter", 100, 79, null, "quota-warn@test.com");

        planAlertService.checkQuotaThresholds(tenantId, 79, 80, 100);

        assertTrue(planAlertService.getFiredAlerts().contains("plan.quota_warning"));
        assertFalse(planAlertService.getFiredAlerts().contains("plan.quota_exhausted"));
    }

    @Test
    @DisplayName("checkQuotaThresholds dispara EXHAUSTED al agotar cuota")
    void checkQuotaThresholdsSendsExhaustedAlert() throws Exception {
        var tenantId = insertTestTenant("starter", 100, 99, null, "quota-exhausted@test.com");

        planAlertService.checkQuotaThresholds(tenantId, 99, 100, 100);

        // 99 >= 80 so warning NOT fired. Only exhausted.
        assertTrue(planAlertService.getFiredAlerts().contains("plan.quota_exhausted"));
        assertFalse(planAlertService.getFiredAlerts().contains("plan.quota_warning"));
    }

    @Test
    @DisplayName("checkQuotaThresholds dispara WARNING + EXHAUSTED si salta de <80% a 100%")
    void checkQuotaThresholdsSendsBothAlerts() throws Exception {
        // quota=5: threshold = (int)(5*0.8) = 4. From 3→5 crosses 4 AND exhausts.
        var tenantId = insertTestTenant("demo", 5, 3, null, "both-alerts@test.com");

        planAlertService.checkQuotaThresholds(tenantId, 3, 5, 5);

        assertEquals(2, planAlertService.getFiredAlerts().size());
        assertTrue(planAlertService.getFiredAlerts().contains("plan.quota_warning"));
        assertTrue(planAlertService.getFiredAlerts().contains("plan.quota_exhausted"));
    }

    @Test
    @DisplayName("checkQuotaThresholds no dispara alertas si no cruza umbral")
    void checkQuotaThresholdsNoAlertBelowThreshold() throws Exception {
        var tenantId = insertTestTenant("business", 500, 50, null, "no-alert@test.com");

        planAlertService.checkQuotaThresholds(tenantId, 50, 51, 500);

        assertTrue(planAlertService.getFiredAlerts().isEmpty());
    }

    @Test
    @DisplayName("checkQuotaThresholds dispara alerta aunque reply_email sea null")
    void checkQuotaThresholdsFiresAlertEvenIfNoEmail() throws Exception {
        var tenantId = insertTestTenant("starter", 100, 79, null, null);

        planAlertService.checkQuotaThresholds(tenantId, 79, 80, 100);

        // Alert event is still fired (webhook attempt), just no email sent
        assertTrue(planAlertService.getFiredAlerts().contains("plan.quota_warning"));
    }

    // ── checkExpiringPlans (scheduled job) ──────────────────────────────────
    @Test
    @DisplayName("checkExpiringPlans dispara alerta para planes que vencen en ≤7 días")
    void checkExpiringPlansSendsAlert() throws Exception {
        var expiresIn5Days = Instant.now().plus(5, ChronoUnit.DAYS);
        insertTestTenant("starter", 100, 10, expiresIn5Days, "expiring@test.com");

        planAlertService.checkExpiringPlans();

        assertTrue(planAlertService.getFiredAlerts().contains("plan.expiring"));
    }

    @Test
    @DisplayName("checkExpiringPlans no alerta planes que vencen en >7 días")
    void checkExpiringPlansDoesNotAlertFarFuture() throws Exception {
        var expiresIn30Days = Instant.now().plus(30, ChronoUnit.DAYS);
        insertTestTenant("business", 500, 10, expiresIn30Days, "not-expiring@test.com");

        planAlertService.checkExpiringPlans();

        assertFalse(planAlertService.getFiredAlerts().contains("plan.expiring"));
    }

    @Test
    @DisplayName("checkExpiringPlans no alerta planes ya expirados")
    void checkExpiringPlansDoesNotAlertExpired() throws Exception {
        var expiredYesterday = Instant.now().minus(1, ChronoUnit.DAYS);
        insertTestTenant("demo", 25, 10, expiredYesterday, "already-expired@test.com");

        planAlertService.checkExpiringPlans();

        assertFalse(planAlertService.getFiredAlerts().contains("plan.expiring"));
    }

    // ── Payload builders ────────────────────────────────────────────────────
    @Test
    @DisplayName("quotaPayload genera JSON válido con campos correctos")
    void quotaPayloadFormat() {
        var id = UUID.randomUUID();
        var payload = planAlertService.quotaPayload("plan.quota_warning", id, "Test Corp", 80, 100, 80);

        assertTrue(payload.contains("\"event\":\"plan.quota_warning\""));
        assertTrue(payload.contains("\"documents_used\":80"));
        assertTrue(payload.contains("\"document_quota\":100"));
        assertTrue(payload.contains("\"usage_percent\":80"));
        assertTrue(payload.contains("\"tenant_id\":\"%s\"".formatted(id)));
    }

    @Test
    @DisplayName("expiringPayload genera JSON válido")
    void expiringPayloadFormat() {
        var id = UUID.randomUUID();
        var expiresAt = Instant.now().plus(5, ChronoUnit.DAYS);
        var payload = planAlertService.expiringPayload(id, "Test Corp", 5, expiresAt);

        assertTrue(payload.contains("\"event\":\"plan.expiring\""));
        assertTrue(payload.contains("\"days_remaining\":5"));
    }

    @Test
    @DisplayName("escapeJson escapa comillas y backslash")
    void escapeJsonSpecialChars() {
        assertEquals("test \\\"value\\\"", PlanAlertService.escapeJson("test \"value\""));
        assertEquals("path\\\\file", PlanAlertService.escapeJson("path\\file"));
        assertEquals("", PlanAlertService.escapeJson(null));
    }

    @Test
    @DisplayName("computeHmac genera firma HMAC-SHA256 válida")
    void computeHmacValid() {
        var signature = PlanAlertService.computeHmac("test-body", "test-secret");
        assertFalse(signature.isEmpty());
        assertEquals(64, signature.length()); // SHA-256 hex = 64 chars
    }

    // ── Helpers ─────────────────────────────────────────────────────────────
    private UUID insertTestTenant(String planType, int quota, int used,
            Instant expiresAt, String email) throws Exception {
        var id = UUID.randomUUID();
        var ruc = "09%011d".formatted(Math.abs(id.hashCode()) % 99999999999L);
        var schema = "tenant_pa_" + UUID.randomUUID().toString().substring(0, 8);

        try (var conn = dataSource.getConnection(); var ps = conn.prepareStatement(
                "INSERT INTO tenants (tenant_id, ruc, legal_name, main_address, "
                + "required_accounting, micro_enterprise_regime, environment, "
                + "emission_type, rate_limit_rpm, rate_limit_write_rpm, rate_limit_read_rpm, "
                + "schema_name, status, plan_type, document_quota, documents_used, "
                + "plan_expires_at, reply_email, created_at, updated_at) "
                + "VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())")) {
            ps.setString(1, id.toString());
            ps.setString(2, ruc);
            ps.setString(3, "Test Company S.A.");
            ps.setString(4, "Quito");
            ps.setBoolean(5, false);
            ps.setBoolean(6, false);
            ps.setString(7, "test");
            ps.setShort(8, (short) 1);
            ps.setInt(9, 100);
            ps.setInt(10, 30);
            ps.setInt(11, 200);
            ps.setString(12, schema);
            ps.setString(13, "active");
            ps.setString(14, planType);
            ps.setInt(15, quota);
            ps.setInt(16, used);
            if (expiresAt != null) {
                ps.setTimestamp(17, java.sql.Timestamp.from(expiresAt));
            } else {
                ps.setNull(17, java.sql.Types.TIMESTAMP);
            }
            if (email != null) {
                ps.setString(18, email);
            } else {
                ps.setNull(18, java.sql.Types.VARCHAR);
            }
            ps.executeUpdate();
        }
        createdTenantIds.add(id);
        return id;
    }
}
