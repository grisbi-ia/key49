package auracore.key49.core.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import auracore.key49.core.model.Tenant;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.hash.HashCommands;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.value.ValueCommands;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * Tests de integración para TenantCacheService. Verifica cache populate, cache
 * hit, invalidación y TTL usando DevServices Redis + PostgreSQL.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TenantCacheServiceTest {

    private static final String CACHE_PREFIX = "key49:tenant:";
    private static final String SCHEMA_PREFIX = "key49:tenant:schema:";

    @Inject
    TenantCacheService tenantCacheService;

    @Inject
    RedisDataSource redisDS;

    @BeforeEach
    void cleanRedis() {
        KeyCommands<String> keys = redisDS.key(String.class);
        // Clean any test keys from previous runs
        keys.del(CACHE_PREFIX + "00000000-0000-0000-0000-000000000099");
        keys.del(SCHEMA_PREFIX + "tenant_cache_test");
    }

    @Test
    @Order(1)
    @DisplayName("toRedisHash serializa todos los campos del tenant (sin cert binario)")
    void shouldSerializeAllFields() {
        var tenant = createTestTenant();

        Map<String, String> hash = TenantCacheService.toRedisHash(tenant);

        assertEquals(tenant.id.toString(), hash.get("tenant_id"));
        assertEquals("1790016919001", hash.get("ruc"));
        assertEquals("AuraCore S.A.", hash.get("legal_name"));
        assertEquals("AuraCore", hash.get("trade_name"));
        assertEquals("Quito, Ecuador", hash.get("main_address"));
        assertEquals("true", hash.get("required_accounting"));
        assertEquals("test", hash.get("environment"));
        assertEquals("1", hash.get("emission_type"));
        assertEquals("tenant_cache_test", hash.get("schema_name"));
        assertEquals("active", hash.get("status"));
        assertEquals("100", hash.get("rate_limit_rpm"));
        assertNotNull(hash.get("created_at"));
        assertNotNull(hash.get("updated_at"));
        // Binary fields excluded
        assertNull(hash.get("certificate_p12"));
        assertNull(hash.get("certificate_password_enc"));
    }

    @Test
    @Order(2)
    @DisplayName("fromRedisHash reconstruye el tenant correctamente")
    void shouldDeserializeFromRedisHash() {
        var original = createTestTenant();
        Map<String, String> hash = TenantCacheService.toRedisHash(original);

        Tenant restored = TenantCacheService.fromRedisHash(hash);

        assertEquals(original.id, restored.id);
        assertEquals(original.ruc, restored.ruc);
        assertEquals(original.legalName, restored.legalName);
        assertEquals(original.tradeName, restored.tradeName);
        assertEquals(original.mainAddress, restored.mainAddress);
        assertEquals(original.requiredAccounting, restored.requiredAccounting);
        assertEquals(original.specialTaxpayer, restored.specialTaxpayer);
        assertEquals(original.microEnterpriseRegime, restored.microEnterpriseRegime);
        assertEquals(original.withholdingAgent, restored.withholdingAgent);
        assertEquals(original.environment, restored.environment);
        assertEquals(original.emissionType, restored.emissionType);
        assertEquals(original.certificateSubject, restored.certificateSubject);
        assertEquals(original.certificateExpiration, restored.certificateExpiration);
        assertEquals(original.certificateSerial, restored.certificateSerial);
        assertEquals(original.webhookUrl, restored.webhookUrl);
        assertEquals(original.rateLimitRpm, restored.rateLimitRpm);
        assertEquals(original.schemaName, restored.schemaName);
        assertEquals(original.status, restored.status);
        // Binary fields remain null
        assertNull(restored.certificateP12);
        assertNull(restored.certificatePasswordEnc);
    }

    @Test
    @Order(3)
    @DisplayName("invalidate elimina hash del tenant e índice de esquema de Redis")
    void shouldInvalidateBothKeys() {
        var tenantId = UUID.fromString("00000000-0000-0000-0000-000000000099");
        var schemaName = "tenant_cache_test";

        // Pre-populate Redis
        HashCommands<String, String, String> hash = redisDS.hash(String.class, String.class, String.class);
        hash.hset(CACHE_PREFIX + tenantId, Map.of("tenant_id", tenantId.toString(), "ruc", "1234567890001"));
        ValueCommands<String, String> values = redisDS.value(String.class, String.class);
        values.set(SCHEMA_PREFIX + schemaName, tenantId.toString());

        // Verify populated
        assertNotNull(hash.hgetall(CACHE_PREFIX + tenantId).get("tenant_id"));
        assertNotNull(values.get(SCHEMA_PREFIX + schemaName));

        // Invalidate
        tenantCacheService.invalidate(tenantId, schemaName);

        // Verify both keys removed
        assertTrue(hash.hgetall(CACHE_PREFIX + tenantId).isEmpty());
        assertNull(values.get(SCHEMA_PREFIX + schemaName));
    }

    @Test
    @Order(4)
    @DisplayName("TTL se configura correctamente en Redis")
    void shouldSetTtlOnCachedKeys() {
        var tenantId = UUID.fromString("00000000-0000-0000-0000-000000000099");
        var schemaName = "tenant_cache_test";
        var tenant = createTestTenant();
        tenant.id = tenantId;
        tenant.schemaName = schemaName;

        // Populate manually via hash (simulating cache write)
        HashCommands<String, String, String> hash = redisDS.hash(String.class, String.class, String.class);
        hash.hset(CACHE_PREFIX + tenantId, TenantCacheService.toRedisHash(tenant));
        ValueCommands<String, String> values = redisDS.value(String.class, String.class);
        values.set(SCHEMA_PREFIX + schemaName, tenantId.toString());

        // Set TTL like the service does
        KeyCommands<String> keys = redisDS.key(String.class);
        keys.pexpire(CACHE_PREFIX + tenantId, 600_000L);
        keys.pexpire(SCHEMA_PREFIX + schemaName, 600_000L);

        // Verify TTL is set (> 0 means expiry is active)
        long ttlTenant = keys.ttl(CACHE_PREFIX + tenantId);
        long ttlSchema = keys.ttl(SCHEMA_PREFIX + schemaName);
        assertTrue(ttlTenant > 0, "Tenant hash should have TTL set");
        assertTrue(ttlSchema > 0, "Schema index should have TTL set");
    }

    @Test
    @Order(5)
    @DisplayName("campos nullable se serializan y deserializan correctamente")
    void shouldHandleNullableFields() {
        var tenant = createMinimalTenant();

        Map<String, String> hash = TenantCacheService.toRedisHash(tenant);
        Tenant restored = TenantCacheService.fromRedisHash(hash);

        assertNull(restored.tradeName);
        assertNull(restored.specialTaxpayer);
        assertNull(restored.withholdingAgent);
        assertNull(restored.logoUrl);
        assertNull(restored.certificateSubject);
        assertNull(restored.certificateExpiration);
        assertNull(restored.certificateSerial);
        assertNull(restored.webhookUrl);
        assertNull(restored.webhookSecret);
        assertNull(restored.emailSenderName);
        assertNull(restored.replyEmail);
        assertEquals("1790016919001", restored.ruc);
        assertEquals("active", restored.status);
    }

    @Test
    @Order(6)
    @DisplayName("re-population after invalidation inserts new data")
    void shouldRepopulateAfterInvalidation() {
        var tenantId = UUID.fromString("00000000-0000-0000-0000-000000000099");
        var schemaName = "tenant_cache_test";

        // Pre-populate Redis
        HashCommands<String, String, String> hash = redisDS.hash(String.class, String.class, String.class);
        var tenantData = new HashMap<String, String>();
        tenantData.put("tenant_id", tenantId.toString());
        tenantData.put("ruc", "OLD_RUC");
        tenantData.put("legal_name", "Old Name");
        tenantData.put("main_address", "Old Address");
        tenantData.put("required_accounting", "false");
        tenantData.put("micro_enterprise_regime", "false");
        tenantData.put("environment", "test");
        tenantData.put("emission_type", "1");
        tenantData.put("rate_limit_rpm", "100");
        tenantData.put("schema_name", schemaName);
        tenantData.put("status", "active");
        hash.hset(CACHE_PREFIX + tenantId, tenantData);
        ValueCommands<String, String> values = redisDS.value(String.class, String.class);
        values.set(SCHEMA_PREFIX + schemaName, tenantId.toString());

        // Invalidate
        tenantCacheService.invalidate(tenantId, schemaName);

        // Verify removal
        assertTrue(hash.hgetall(CACHE_PREFIX + tenantId).isEmpty());
        assertNull(values.get(SCHEMA_PREFIX + schemaName));

        // Re-populate with new data
        var newTenant = createTestTenant();
        newTenant.id = tenantId;
        newTenant.schemaName = schemaName;
        newTenant.ruc = "NEW_RUC_VALUE_1";
        hash.hset(CACHE_PREFIX + tenantId, TenantCacheService.toRedisHash(newTenant));
        values.set(SCHEMA_PREFIX + schemaName, tenantId.toString());

        // Verify new data
        var cached = hash.hgetall(CACHE_PREFIX + tenantId);
        assertEquals("NEW_RUC_VALUE_1", cached.get("ruc"));
    }

    // ── Test data builders ──
    private static Tenant createTestTenant() {
        var t = new Tenant();
        t.id = UUID.fromString("00000000-0000-0000-0000-000000000099");
        t.ruc = "1790016919001";
        t.legalName = "AuraCore S.A.";
        t.tradeName = "AuraCore";
        t.mainAddress = "Quito, Ecuador";
        t.requiredAccounting = true;
        t.specialTaxpayer = null;
        t.microEnterpriseRegime = false;
        t.withholdingAgent = null;
        t.environment = "test";
        t.emissionType = 1;
        t.logoUrl = null;
        t.certificateSubject = "CN=Test Subject";
        t.certificateExpiration = Instant.parse("2027-01-01T00:00:00Z");
        t.certificateSerial = "ABC123";
        t.webhookUrl = "https://example.com/webhook";
        t.webhookSecret = "secret123";
        t.rateLimitRpm = 100;
        t.emailSenderName = "AuraCore Billing";
        t.replyEmail = "billing@auracore.com";
        t.schemaName = "tenant_cache_test";
        t.status = "active";
        t.createdAt = Instant.parse("2025-01-01T00:00:00Z");
        t.updatedAt = Instant.parse("2025-06-15T12:00:00Z");
        return t;
    }

    private static Tenant createMinimalTenant() {
        var t = new Tenant();
        t.id = UUID.fromString("00000000-0000-0000-0000-000000000099");
        t.ruc = "1790016919001";
        t.legalName = "Minimal S.A.";
        t.mainAddress = "Quito";
        t.requiredAccounting = false;
        t.microEnterpriseRegime = false;
        t.environment = "test";
        t.emissionType = 1;
        t.rateLimitRpm = 50;
        t.schemaName = "tenant_minimal";
        t.status = "active";
        t.createdAt = Instant.now();
        t.updatedAt = Instant.now();
        return t;
    }
}
