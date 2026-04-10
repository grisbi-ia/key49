package auracore.key49.admin.alert.rules;

import auracore.key49.core.model.Tenant;
import auracore.key49.core.repository.TenantRepository;
import auracore.key49.core.tenant.TenantConnectionManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitarios para SlaAuthorizationAlertRule.
 */

class SlaAuthorizationAlertRuleTest {

    private SlaAuthorizationAlertRule rule;
    private SimpleMeterRegistry registry;
    private StubTenantRepository tenantRepository;
    private StubTenantConnectionManager connectionManager;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        tenantRepository = new StubTenantRepository();
        connectionManager = new StubTenantConnectionManager();

        rule = new SlaAuthorizationAlertRule();
        rule.registry = registry;
        rule.tenantRepository = tenantRepository;
        rule.tenantConnectionManager = connectionManager;
        rule.slaAuthorizationMinutes = 5;
    }

    @Test
    void shouldReturnOkWhenNoActiveTenants() {
        tenantRepository.tenants = Collections.emptyList();

        var result = rule.evaluate();

        assertFalse(result.firing());
        assertEquals("sla_authorization", result.name());
        assertTrue(result.summary().contains("Sin tenants activos"));
    }

    @Test
    void shouldReturnOkWhenNoStuckDocuments() {
        tenantRepository.tenants = List.of(createTenant("tenant_abc"));
        connectionManager.stuckCounts.put("tenant_abc", 0L);

        var result = rule.evaluate();

        assertFalse(result.firing());
        assertTrue(result.summary().contains("dentro del SLA"));
    }

    @Test
    void shouldFireWhenStuckDocumentsExist() {
        tenantRepository.tenants = List.of(createTenant("tenant_abc"));
        connectionManager.stuckCounts.put("tenant_abc", 3L);

        var result = rule.evaluate();

        assertTrue(result.firing());
        assertTrue(result.summary().contains("tenant_abc(3)"));
        assertTrue(result.summary().contains("5 min"));
    }

    @Test
    void shouldFireForMultipleTenantsWithBreaches() {
        tenantRepository.tenants = List.of(
                createTenant("tenant_abc"),
                createTenant("tenant_def"),
                createTenant("tenant_ghi"));
        connectionManager.stuckCounts.put("tenant_abc", 2L);
        connectionManager.stuckCounts.put("tenant_def", 0L);
        connectionManager.stuckCounts.put("tenant_ghi", 5L);

        var result = rule.evaluate();

        assertTrue(result.firing());
        assertTrue(result.summary().contains("tenant_abc(2)"));
        assertFalse(result.summary().contains("tenant_def"));
        assertTrue(result.summary().contains("tenant_ghi(5)"));
    }

    @Test
    void shouldIncrementSlaBreachMetric() {
        tenantRepository.tenants = List.of(createTenant("tenant_abc"));
        connectionManager.stuckCounts.put("tenant_abc", 4L);

        rule.evaluate();

        var counter = registry.find("key49.sla.breach")
                .tag("tenant", "tenant_abc")
                .tag("type", "authorization_latency")
                .counter();
        assertNotNull(counter);
        assertEquals(4.0, counter.count());
    }

    @Test
    void shouldNotIncrementMetricWhenNoBreaches() {
        tenantRepository.tenants = List.of(createTenant("tenant_abc"));
        connectionManager.stuckCounts.put("tenant_abc", 0L);

        rule.evaluate();

        var counter = registry.find("key49.sla.breach")
                .tag("tenant", "tenant_abc")
                .counter();
        assertNull(counter);
    }

    @Test
    void shouldUseConfiguredThreshold() {
        rule.slaAuthorizationMinutes = 10;
        tenantRepository.tenants = List.of(createTenant("tenant_abc"));
        connectionManager.stuckCounts.put("tenant_abc", 1L);

        var result = rule.evaluate();

        assertTrue(result.firing());
        assertTrue(result.summary().contains("10 min"));
    }

    @Test
    void shouldReturnOkOnException() {
        tenantRepository.tenants = List.of(createTenant("tenant_abc"));
        connectionManager.throwOnAccess = true;

        var result = rule.evaluate();

        assertFalse(result.firing());
        assertTrue(result.summary().contains("No se pudo evaluar"));
    }

    // ── Helpers ──

    private Tenant createTenant(String schemaName) {
        var tenant = new Tenant();
        tenant.id = UUID.randomUUID();
        tenant.schemaName = schemaName;
        tenant.status = "active";
        tenant.createdAt = Instant.now();
        tenant.updatedAt = Instant.now();
        return tenant;
    }

    // ── Stubs ──

    static class StubTenantRepository extends TenantRepository {
        List<Tenant> tenants = Collections.emptyList();

        @Override
        public List<Tenant> findAllActive() {
            return tenants;
        }
    }

    static class StubTenantConnectionManager extends TenantConnectionManager {
        final Map<String, Long> stuckCounts = new HashMap<>();
        boolean throwOnAccess = false;

        StubTenantConnectionManager() {
            // Don't inject EntityManager
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T withTenantSession(String schemaName, Function<EntityManager, T> work) {
            if (throwOnAccess) {
                throw new RuntimeException("DB connection failed");
            }
            var count = stuckCounts.getOrDefault(schemaName, 0L);
            // Return the count via a stub that bypasses EntityManager
            return (T) count;
        }
    }
}
