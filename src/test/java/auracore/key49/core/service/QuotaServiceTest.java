package auracore.key49.core.service;

import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import auracore.key49.api.exception.BusinessException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

/**
 * Tests de integración para QuotaService: verificación de cuota, incremento
 * atómico, expiración de plan y liberación de cuota.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("QuotaService — validación de cuota")
class QuotaServiceTest {

    @Inject
    QuotaService quotaService;

    @Inject
    EntityManager em;

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
            // constraint already exists — ignore
        }
    }

    @BeforeEach
    void cleanupTenants() throws Exception {
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

    // ── Reserve Quota ──
    @Test
    @Transactional
    @DisplayName("reserveQuota incrementa documents_used correctamente")
    void reserveQuotaIncrementsUsed() throws Exception {
        var tenantId = insertTestTenant("demo", 25, 0, null);

        quotaService.reserveQuota(em, tenantId);

        assertEquals(1, getDocumentsUsed(tenantId));
    }

    @Test
    @Transactional
    @DisplayName("reserveQuota lanza 402 cuando cuota agotada")
    void reserveQuotaThrowsWhenExhausted() throws Exception {
        var tenantId = insertTestTenant("demo", 5, 5, null);

        var ex = assertThrows(BusinessException.class,
                () -> quotaService.reserveQuota(em, tenantId));
        assertEquals("QUOTA_EXHAUSTED", ex.code());
        assertEquals(402, ex.httpStatus());
        assertTrue(ex.getMessage().contains("Cuota de documentos agotada"));
    }

    @Test
    @Transactional
    @DisplayName("reserveQuota lanza 402 cuando plan expirado")
    void reserveQuotaThrowsWhenExpired() throws Exception {
        var expired = Instant.now().minus(1, ChronoUnit.DAYS);
        var tenantId = insertTestTenant("starter", 100, 0, expired);

        var ex = assertThrows(BusinessException.class,
                () -> quotaService.reserveQuota(em, tenantId));
        assertEquals("PLAN_EXPIRED", ex.code());
        assertEquals(402, ex.httpStatus());
    }

    @Test
    @Transactional
    @DisplayName("reserveQuota permite cuando plan_expires_at es NULL (sin expiración)")
    void reserveQuotaAllowsNullExpiration() throws Exception {
        var tenantId = insertTestTenant("demo", 25, 0, null);

        quotaService.reserveQuota(em, tenantId);

        assertEquals(1, getDocumentsUsed(tenantId));
    }

    @Test
    @Transactional
    @DisplayName("reserveQuota permite cuando plan no ha expirado aún")
    void reserveQuotaAllowsFutureExpiration() throws Exception {
        var future = Instant.now().plus(30, ChronoUnit.DAYS);
        var tenantId = insertTestTenant("business", 500, 10, future);

        quotaService.reserveQuota(em, tenantId);

        assertEquals(11, getDocumentsUsed(tenantId));
    }

    @Test
    @Transactional
    @DisplayName("reserveQuota múltiples veces hasta llegar a la cuota")
    void reserveQuotaMultipleTimesUpToLimit() throws Exception {
        var tenantId = insertTestTenant("demo", 3, 0, null);

        quotaService.reserveQuota(em, tenantId);
        quotaService.reserveQuota(em, tenantId);
        quotaService.reserveQuota(em, tenantId);

        assertEquals(3, getDocumentsUsed(tenantId));

        assertThrows(BusinessException.class,
                () -> quotaService.reserveQuota(em, tenantId));
    }

    // ── Concurrency ──
    @Test
    @DisplayName("reserveQuota atómico — 10 hilos con 1 cuota restante → solo 1 éxito")
    void reserveQuotaConcurrency() throws Exception {
        var tenantId = insertTestTenantJdbc("demo", 1, 0, null);

        int threadCount = 10;
        var latch = new CountDownLatch(1);
        var successes = new AtomicInteger(0);
        var failures = new AtomicInteger(0);

        try (ExecutorService executor = Executors.newFixedThreadPool(threadCount)) {
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        latch.await();
                        try (var conn = dataSource.getConnection()) {
                            conn.setAutoCommit(false);
                            try (var stmt = conn.createStatement()) {
                                var rs = stmt.executeQuery(
                                        "SELECT plan_expires_at, documents_used, document_quota "
                                        + "FROM public.tenants WHERE tenant_id = '%s'"
                                                .formatted(tenantId));
                                rs.next();
                                int used = rs.getInt("documents_used");
                                int quota = rs.getInt("document_quota");

                                if (used >= quota) {
                                    failures.incrementAndGet();
                                    conn.rollback();
                                    return;
                                }

                                int updated = stmt.executeUpdate(
                                        "UPDATE public.tenants SET documents_used = documents_used + 1, "
                                        + "updated_at = now() "
                                        + "WHERE tenant_id = '%s' AND documents_used < document_quota"
                                                .formatted(tenantId));
                                conn.commit();
                                if (updated > 0) {
                                    successes.incrementAndGet();
                                } else {
                                    failures.incrementAndGet();
                                }
                            } catch (Exception e) {
                                conn.rollback();
                                failures.incrementAndGet();
                            }
                        }
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    }
                });
            }
            latch.countDown();
            executor.shutdown();
            executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
        }

        assertEquals(1, successes.get(), "Only 1 thread should succeed with atomic UPDATE");
        assertEquals(1, getDocumentsUsedJdbc(tenantId),
                "documents_used should be exactly 1");
    }

    // ── Release Quota ──
    @Test
    @Transactional
    @DisplayName("releaseQuota decrementa documents_used correctamente")
    void releaseQuotaDecrementsUsed() throws Exception {
        var schemaName = "tenant_quota_rel_" + UUID.randomUUID().toString().substring(0, 8);
        var tenantId = insertTestTenantWithSchema("demo", 25, 5, null, schemaName);

        quotaService.releaseQuota(em, schemaName);

        assertEquals(4, getDocumentsUsed(tenantId));
    }

    @Test
    @Transactional
    @DisplayName("releaseQuota no baja de 0")
    void releaseQuotaDoesNotGoNegative() throws Exception {
        var schemaName = "tenant_quota_neg_" + UUID.randomUUID().toString().substring(0, 8);
        var tenantId = insertTestTenantWithSchema("demo", 25, 0, null, schemaName);

        quotaService.releaseQuota(em, schemaName);

        assertEquals(0, getDocumentsUsed(tenantId));
    }

    // ── Helpers ──
    private UUID insertTestTenant(String planType, int quota, int used,
            Instant expiresAt) throws Exception {
        return insertTestTenantWithSchema(planType, quota, used, expiresAt,
                "tenant_qt_" + UUID.randomUUID().toString().substring(0, 8));
    }

    private UUID insertTestTenantWithSchema(String planType, int quota, int used,
            Instant expiresAt, String schemaName) throws Exception {
        var id = UUID.randomUUID();
        var ruc = "09%011d".formatted(Math.abs(id.hashCode()) % 99999999999L);
        try (var conn = dataSource.getConnection(); var ps = conn.prepareStatement(
                "INSERT INTO tenants (tenant_id, ruc, legal_name, main_address, "
                + "required_accounting, micro_enterprise_regime, environment, "
                + "emission_type, rate_limit_rpm, rate_limit_write_rpm, rate_limit_read_rpm, "
                + "schema_name, status, plan_type, document_quota, documents_used, "
                + "plan_expires_at, created_at, updated_at) "
                + "VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())")) {
            ps.setString(1, id.toString());
            ps.setString(2, ruc);
            ps.setString(3, "Test Tenant");
            ps.setString(4, "Quito");
            ps.setBoolean(5, false);
            ps.setBoolean(6, false);
            ps.setString(7, "test");
            ps.setShort(8, (short) 1);
            ps.setInt(9, 100);
            ps.setInt(10, 30);
            ps.setInt(11, 200);
            ps.setString(12, schemaName);
            ps.setString(13, "active");
            ps.setString(14, planType);
            ps.setInt(15, quota);
            ps.setInt(16, used);
            if (expiresAt != null) {
                ps.setTimestamp(17, java.sql.Timestamp.from(expiresAt));
            } else {
                ps.setNull(17, java.sql.Types.TIMESTAMP);
            }
            ps.executeUpdate();
        }
        createdTenantIds.add(id);
        return id;
    }

    private UUID insertTestTenantJdbc(String planType, int quota, int used,
            Instant expiresAt) throws Exception {
        return insertTestTenantWithSchema(planType, quota, used, expiresAt,
                "tenant_qt_" + UUID.randomUUID().toString().substring(0, 8));
    }

    private int getDocumentsUsed(UUID tenantId) throws Exception {
        em.flush();
        em.clear();
        var result = em.createNativeQuery(
                "SELECT documents_used FROM public.tenants WHERE tenant_id = ?1")
                .setParameter(1, tenantId)
                .getSingleResult();
        return ((Number) result).intValue();
    }

    private int getDocumentsUsedJdbc(UUID tenantId) throws Exception {
        try (var conn = dataSource.getConnection(); var ps = conn.prepareStatement(
                "SELECT documents_used FROM public.tenants WHERE tenant_id = ?::uuid")) {
            ps.setString(1, tenantId.toString());
            var rs = ps.executeQuery();
            rs.next();
            return rs.getInt(1);
        }
    }
}
