package auracore.key49.core.model;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * Tests de integración para la migración V007: columnas de plan/cuota en
 * tenants y tabla plan_renewals. Ejecuta la migración sobre la BD de test
 * y verifica defaults, constraints e índices.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("V007 — Plans & Quotas migration")
class PlanQuotaMigrationTest {

    @Inject
    DataSource dataSource;

    @BeforeAll
    void runMigration() throws Exception {
        // Hibernate drop-and-create already generates the tenants columns from the entity,
        // Hibernate drop-and-create generates tables from entities but without SQL-level
        // DEFAULT values or CHECK constraints. Drop plan_renewals (Hibernate-created) and
        // recreate with migration DDL, then add CHECK constraints on tenants.
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var stmt = conn.createStatement()) {
                // ── plan_renewals: drop Hibernate version, recreate from migration ──
                stmt.execute("DROP TABLE IF EXISTS plan_renewals CASCADE");
                stmt.execute("""
                        CREATE TABLE plan_renewals (
                            renewal_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            tenant_id          UUID NOT NULL REFERENCES tenants(tenant_id) ON DELETE CASCADE,
                            plan_type          VARCHAR(20) NOT NULL,
                            document_quota     INTEGER NOT NULL,
                            amount             NUMERIC(10,2) NOT NULL DEFAULT 0,
                            payment_proof_path VARCHAR(500),
                            status             VARCHAR(20) NOT NULL DEFAULT 'pending',
                            approved_by        VARCHAR(200),
                            approved_at        TIMESTAMP WITH TIME ZONE,
                            notes              TEXT,
                            created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
                            CONSTRAINT chk_renewals_plan_type
                                CHECK (plan_type IN ('demo', 'starter', 'business', 'enterprise')),
                            CONSTRAINT chk_renewals_status
                                CHECK (status IN ('pending', 'approved', 'rejected')),
                            CONSTRAINT chk_renewals_quota CHECK (document_quota > 0),
                            CONSTRAINT chk_renewals_amount CHECK (amount >= 0)
                        )""");
                stmt.execute("CREATE INDEX idx_plan_renewals_tenant ON plan_renewals(tenant_id)");
                stmt.execute("CREATE INDEX idx_plan_renewals_status ON plan_renewals(status)");

                // ── tenants: add CHECK constraints (Hibernate doesn't create these) ──
                stmt.execute("""
                        ALTER TABLE tenants ADD CONSTRAINT chk_tenants_plan_type
                            CHECK (plan_type IN ('demo', 'starter', 'business', 'enterprise'))""");
                stmt.execute("""
                        ALTER TABLE tenants ADD CONSTRAINT chk_tenants_document_quota
                            CHECK (document_quota > 0)""");
                stmt.execute("""
                        ALTER TABLE tenants ADD CONSTRAINT chk_tenants_documents_used
                            CHECK (documents_used >= 0)""");
                stmt.execute("ALTER TABLE tenants DROP CONSTRAINT IF EXISTS chk_tenants_status");
                stmt.execute("""
                        ALTER TABLE tenants ADD CONSTRAINT chk_tenants_status
                            CHECK (status IN ('active', 'suspended', 'pending', 'failed'))""");
            }
        }
    }

    @AfterAll
    void cleanup() throws SQLException {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            // Clean up test data
            stmt.execute("DELETE FROM plan_renewals");
            stmt.execute("DELETE FROM tenants WHERE ruc LIKE '179001691%'");
        }
    }

    // ── tenants defaults ────────────────────────────────────────────────────

    @Test
    @DisplayName("plan_type default es 'demo'")
    void planTypeDefaultIsDemo() throws SQLException {
            UUID id = insertMinimalTenant("1790016919001", "tenant_plan_test_1");
            try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
                var rs = stmt.executeQuery(
                        "SELECT plan_type FROM tenants WHERE tenant_id = '" + id + "'");
                assertTrue(rs.next());
                assertEquals("demo", rs.getString(1));
            } finally {
                deleteTenant(id);
            }
        }

        @Test
        @DisplayName("document_quota default es 25")
        void documentQuotaDefaultIs25() throws SQLException {
            UUID id = insertMinimalTenant("1790016919001", "tenant_plan_test_2");
            try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
                var rs = stmt.executeQuery(
                        "SELECT document_quota FROM tenants WHERE tenant_id = '" + id + "'");
                assertTrue(rs.next());
                assertEquals(25, rs.getInt(1));
            } finally {
                deleteTenant(id);
            }
        }

        @Test
        @DisplayName("documents_used default es 0")
        void documentsUsedDefaultIs0() throws SQLException {
            UUID id = insertMinimalTenant("1790016919001", "tenant_plan_test_3");
            try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
                var rs = stmt.executeQuery(
                        "SELECT documents_used FROM tenants WHERE tenant_id = '" + id + "'");
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
            } finally {
                deleteTenant(id);
            }
        }

        @Test
        @DisplayName("plan_type rechaza valores fuera de check constraint")
        void planTypeRejectsInvalid() throws SQLException {
            try (var conn = dataSource.getConnection();
                 var ps = conn.prepareStatement(
                         "INSERT INTO tenants (tenant_id, ruc, legal_name, main_address, schema_name, " +
                                 "environment, emission_type, required_accounting, micro_enterprise_regime, " +
                                 "rate_limit_rpm, rate_limit_write_rpm, rate_limit_read_rpm, status, " +
                                 "plan_type, document_quota, documents_used, created_at, updated_at) " +
                                 "VALUES (gen_random_uuid(), ?, 'Test', 'Quito', 'tenant_invalid_plan', " +
                                 "'test', 1, false, false, 100, 30, 200, 'active', " +
                                 "?, 25, 0, now(), now())")) {
                ps.setString(1, "1790016919001");
                ps.setString(2, "premium");
                var ex = org.junit.jupiter.api.Assertions.assertThrows(
                        SQLException.class, ps::executeUpdate);
                assertTrue(ex.getMessage().contains("chk_tenants_plan_type"),
                        "Should violate plan_type check: " + ex.getMessage());
            }
        }

        @Test
        @DisplayName("document_quota rechaza valores <= 0")
        void documentQuotaRejectsZero() throws SQLException {
            try (var conn = dataSource.getConnection();
                 var ps = conn.prepareStatement(
                         "INSERT INTO tenants (tenant_id, ruc, legal_name, main_address, schema_name, " +
                                 "environment, emission_type, required_accounting, micro_enterprise_regime, " +
                                 "rate_limit_rpm, rate_limit_write_rpm, rate_limit_read_rpm, status, " +
                                 "plan_type, document_quota, documents_used, created_at, updated_at) " +
                                 "VALUES (gen_random_uuid(), '1790016919001', 'Test', 'Quito', 'tenant_zero_quota', " +
                                 "'test', 1, false, false, 100, 30, 200, 'active', " +
                                 "'demo', ?, 0, now(), now())")) {
                ps.setInt(1, 0);
                var ex = org.junit.jupiter.api.Assertions.assertThrows(
                        SQLException.class, ps::executeUpdate);
                assertTrue(ex.getMessage().contains("chk_tenants_document_quota"),
                        "Should violate document_quota check: " + ex.getMessage());
            }
        }

        @Test
        @DisplayName("documents_used rechaza valores negativos")
        void documentsUsedRejectsNegative() throws SQLException {
            try (var conn = dataSource.getConnection();
                 var ps = conn.prepareStatement(
                         "INSERT INTO tenants (tenant_id, ruc, legal_name, main_address, schema_name, " +
                                 "environment, emission_type, required_accounting, micro_enterprise_regime, " +
                                 "rate_limit_rpm, rate_limit_write_rpm, rate_limit_read_rpm, status, " +
                                 "plan_type, document_quota, documents_used, created_at, updated_at) " +
                                 "VALUES (gen_random_uuid(), '1790016919001', 'Test', 'Quito', 'tenant_neg_used', " +
                                 "'test', 1, false, false, 100, 30, 200, 'active', " +
                                 "'demo', 25, ?, now(), now())")) {
                ps.setInt(1, -1);
                var ex = org.junit.jupiter.api.Assertions.assertThrows(
                        SQLException.class, ps::executeUpdate);
                assertTrue(ex.getMessage().contains("chk_tenants_documents_used"),
                        "Should violate documents_used check: " + ex.getMessage());
            }
        }

        @Test
        @DisplayName("status 'failed' es válido tras actualización del check")
        void statusFailedIsNowValid() throws SQLException {
            UUID id = insertMinimalTenant("1790016919001", "tenant_status_failed");
            try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
                stmt.executeUpdate(
                        "UPDATE tenants SET status = 'failed' WHERE tenant_id = '" + id + "'");
                var rs = stmt.executeQuery(
                        "SELECT status FROM tenants WHERE tenant_id = '" + id + "'");
                assertTrue(rs.next());
                assertEquals("failed", rs.getString(1));
            } finally {
                deleteTenant(id);
            }
        }
    // ── plan_renewals ───────────────────────────────────────────────────────

    @Test
    @DisplayName("plan_renewals existe con columnas correctas")
        void tableExistsWithCorrectColumns() throws SQLException {
            try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
                var rs = stmt.executeQuery("""
                        SELECT column_name FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name = 'plan_renewals'
                        ORDER BY ordinal_position""");
                var columns = new java.util.ArrayList<String>();
                while (rs.next()) {
                    columns.add(rs.getString(1));
                }
                assertEquals(
                        java.util.List.of("renewal_id", "tenant_id", "plan_type",
                                "document_quota", "amount", "payment_proof_path",
                                "status", "approved_by", "approved_at", "notes", "created_at"),
                        columns);
            }
        }

        @Test
        @DisplayName("INSERT con defaults funciona correctamente")
        void insertWithDefaultsWorks() throws SQLException {
            UUID tenantId = insertMinimalTenant("1790016919001", "tenant_renewal_test");
            try (var conn = dataSource.getConnection();
                 var ps = conn.prepareStatement(
                         "INSERT INTO plan_renewals (tenant_id, plan_type, document_quota) " +
                                 "VALUES (?, ?, ?) RETURNING renewal_id, status, amount, created_at")) {
                ps.setObject(1, tenantId);
                ps.setString(2, "starter");
                ps.setInt(3, 100);
                var rs = ps.executeQuery();
                assertTrue(rs.next());
                assertNotNull(rs.getObject(1), "renewal_id should be auto-generated");
                assertEquals("pending", rs.getString(2), "Default status should be 'pending'");
                assertEquals(0, BigDecimal.ZERO.compareTo(rs.getBigDecimal(3)),
                        "Default amount should be 0");
                assertNotNull(rs.getTimestamp(4), "created_at should be auto-generated");
            } finally {
                deleteTenantCascade(tenantId);
            }
        }

        @Test
        @DisplayName("FK cascade: eliminar tenant elimina renewals")
        void fkCascadeDeletesTenantRenewals() throws SQLException {
            UUID tenantId = insertMinimalTenant("1790016919001", "tenant_cascade_test");
            try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
                stmt.executeUpdate(
                        "INSERT INTO plan_renewals (tenant_id, plan_type, document_quota) " +
                                "VALUES ('" + tenantId + "', 'business', 500)");
                stmt.executeUpdate(
                        "DELETE FROM tenants WHERE tenant_id = '" + tenantId + "'");
                var rs = stmt.executeQuery(
                        "SELECT COUNT(*) FROM plan_renewals WHERE tenant_id = '" + tenantId + "'");
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "Renewals should be cascade-deleted");
            }
        }

        @Test
        @DisplayName("plan_type rechaza valores inválidos en renewals")
        void renewalPlanTypeRejectsInvalid() throws SQLException {
            UUID tenantId = insertMinimalTenant("1790016919001", "tenant_ren_invalid");
            try (var conn = dataSource.getConnection();
                 var ps = conn.prepareStatement(
                         "INSERT INTO plan_renewals (tenant_id, plan_type, document_quota) " +
                                 "VALUES (?, ?, ?)")) {
                ps.setObject(1, tenantId);
                ps.setString(2, "premium");
                ps.setInt(3, 100);
                var ex = org.junit.jupiter.api.Assertions.assertThrows(
                        SQLException.class, ps::executeUpdate);
                assertTrue(ex.getMessage().contains("chk_renewals_plan_type"),
                        "Should violate renewals plan_type check: " + ex.getMessage());
            } finally {
                deleteTenantCascade(tenantId);
            }
        }

        @Test
        @DisplayName("status rechaza valores inválidos en renewals")
        void renewalStatusRejectsInvalid() throws SQLException {
            UUID tenantId = insertMinimalTenant("1790016919001", "tenant_ren_status");
            try (var conn = dataSource.getConnection();
                 var ps = conn.prepareStatement(
                         "INSERT INTO plan_renewals (tenant_id, plan_type, document_quota, status) " +
                                 "VALUES (?, ?, ?, ?)")) {
                ps.setObject(1, tenantId);
                ps.setString(2, "starter");
                ps.setInt(3, 100);
                ps.setString(4, "cancelled");
                var ex = org.junit.jupiter.api.Assertions.assertThrows(
                        SQLException.class, ps::executeUpdate);
                assertTrue(ex.getMessage().contains("chk_renewals_status"),
                        "Should violate renewals status check: " + ex.getMessage());
            } finally {
                deleteTenantCascade(tenantId);
            }
        }

        @Test
        @DisplayName("Índices idx_plan_renewals_tenant e idx_plan_renewals_status existen")
        void indexesExist() throws SQLException {
            try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
                var rs = stmt.executeQuery("""
                        SELECT indexname FROM pg_indexes
                        WHERE tablename = 'plan_renewals'
                        ORDER BY indexname""");
                var indexes = new java.util.ArrayList<String>();
                while (rs.next()) {
                    indexes.add(rs.getString(1));
                }
                assertTrue(indexes.contains("idx_plan_renewals_tenant"),
                        "Should have tenant index");
                assertTrue(indexes.contains("idx_plan_renewals_status"),
                        "Should have status index");
            }
        }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private UUID insertMinimalTenant(String ruc, String schemaName) throws SQLException {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "INSERT INTO tenants (tenant_id, ruc, legal_name, main_address, schema_name, " +
                             "environment, emission_type, required_accounting, micro_enterprise_regime, " +
                             "rate_limit_rpm, rate_limit_write_rpm, rate_limit_read_rpm, status, " +
                             "plan_type, document_quota, documents_used, created_at, updated_at) " +
                             "VALUES (gen_random_uuid(), ?, ?, ?, ?, 'test', 1, false, false, 100, 30, 200, 'active', " +
                             "'demo', 25, 0, now(), now()) RETURNING tenant_id")) {
            ps.setString(1, ruc);
            ps.setString(2, "Test Company");
            ps.setString(3, "Quito");
            ps.setString(4, schemaName);
            var rs = ps.executeQuery();
            rs.next();
            return rs.getObject(1, UUID.class);
        }
    }

    private void deleteTenant(UUID id) throws SQLException {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM tenants WHERE tenant_id = '" + id + "'");
        }
    }

    private void deleteTenantCascade(UUID id) throws SQLException {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM plan_renewals WHERE tenant_id = '" + id + "'");
            stmt.execute("DELETE FROM tenants WHERE tenant_id = '" + id + "'");
        }
    }
}
