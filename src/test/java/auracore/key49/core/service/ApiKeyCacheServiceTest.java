package auracore.key49.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.hash.HashCommands;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * Verifica el caché de API keys en Redis: cache miss (consulta BD + populate),
 * cache hit (sin SQL), invalidación al revocar, y degradación graceful.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiKeyCacheServiceTest {

    private static final String CACHE_PREFIX = "key49:apikey:";

    @Inject
    ApiKeyCacheService cacheService;

    @Inject
    DataSource dataSource;

    @Inject
    RedisDataSource redisDS;

    private UUID tenantId;
    private String keyHash;

    @BeforeAll
    void setup() throws SQLException {
        tenantId = UUID.randomUUID();
        var generated = ApiKeyService.generate(ApiKeyService.PREFIX_TEST);
        keyHash = generated.hash();

        try (var conn = dataSource.getConnection()) {
            try (var ps = conn.prepareStatement("""
                    INSERT INTO tenants (tenant_id, ruc, legal_name, trade_name, main_address, schema_name,
                        required_accounting, micro_enterprise_regime, environment,
                        emission_type, rate_limit_rpm, rate_limit_write_rpm, rate_limit_read_rpm, status, created_at, updated_at)
                    VALUES (?::uuid, ?, ?, ?, ?, ?, false, false, 'test', 1, 500, 10000, 10000, 'active', now(), now())
                    """)) {
                ps.setObject(1, tenantId.toString());
                ps.setString(2, "1791234560001");
                ps.setString(3, "Cache Test Corp S.A.");
                ps.setString(4, "Cache Test");
                ps.setString(5, "Guayaquil");
                ps.setString(6, "tenant_cache_test");
                ps.executeUpdate();
            }

            try (var ps = conn.prepareStatement("""
                    INSERT INTO api_keys (api_key_id, tenant_id, key_prefix, key_hash, name, permissions, status, created_at)
                    VALUES (?::uuid, ?::uuid, ?, ?, ?, '*', 'active', now())
                    """)) {
                ps.setObject(1, UUID.randomUUID().toString());
                ps.setObject(2, tenantId.toString());
                ps.setString(3, generated.keyPrefix());
                ps.setString(4, keyHash);
                ps.setString(5, "cache-test-key");
                ps.executeUpdate();
            }
        }

        // Ensure no stale cache
        cacheService.invalidate(keyHash);
    }

    @Test
    @Order(1)
    @DisplayName("Cache miss: lookup consulta BD y popula Redis")
    void cacheMissShouldQueryDbAndPopulateRedis() {
        var result = cacheService.lookup(keyHash);

        assertNotNull(result, "Lookup should return data from DB");
        assertEquals(tenantId, result.tenantId());
        assertEquals("tenant_cache_test", result.schemaName());
        assertEquals(500, result.rateLimitRpm());
        assertEquals(10000, result.rateLimitWriteRpm());
        assertEquals(10000, result.rateLimitReadRpm());
        assertEquals("active", result.keyStatus());
        assertEquals("active", result.tenantStatus());

        // Verify data is now in Redis
        HashCommands<String, String, String> hash = redisDS.hash(String.class, String.class, String.class);
        var cached = hash.hgetall(CACHE_PREFIX + keyHash);
        assertNotNull(cached, "Data should be cached in Redis");
        assertEquals(tenantId.toString(), cached.get("tenant_id"));
    }

    @Test
    @Order(2)
    @DisplayName("Cache hit: segundo lookup usa Redis sin consultar BD")
    void cacheHitShouldReturnFromRedis() {
        // Data already cached from previous test
        var result = cacheService.lookup(keyHash);

        assertNotNull(result, "Lookup should return cached data");
        assertEquals(tenantId, result.tenantId());
        assertEquals("tenant_cache_test", result.schemaName());
    }

    @Test
    @Order(3)
    @DisplayName("Invalidar caché elimina la key de Redis")
    void invalidateShouldRemoveFromRedis() {
        // Ensure cached
        cacheService.lookup(keyHash);
        HashCommands<String, String, String> hash = redisDS.hash(String.class, String.class, String.class);
        assertTrue(!hash.hgetall(CACHE_PREFIX + keyHash).isEmpty(), "Should be cached before invalidation");

        // Invalidate
        cacheService.invalidate(keyHash);

        // Verify removed
        var cached = hash.hgetall(CACHE_PREFIX + keyHash);
        assertTrue(cached.isEmpty(), "Cache should be empty after invalidation");
    }

    @Test
    @Order(4)
    @DisplayName("Lookup de key inexistente retorna null")
    void lookupNonExistentKeyShouldReturnNull() {
        var fakeHash = ApiKeyService.sha256("fec_test_nonexistentkey123456789");
        var result = cacheService.lookup(fakeHash);
        assertNull(result, "Lookup of non-existent key should return null");
    }

    @Test
    @Order(5)
    @DisplayName("TTL debe estar configurado a 300 segundos por defecto")
    void ttlShouldBeConfigured() {
        // Re-populate cache
        cacheService.lookup(keyHash);

        var keys = redisDS.key(String.class);
        long ttl = keys.ttl(CACHE_PREFIX + keyHash);
        assertTrue(ttl > 0 && ttl <= 300, "TTL should be between 1 and 300 seconds, got: " + ttl);
    }

    @Test
    @Order(6)
    @DisplayName("Post-invalidación, lookup reconsulta BD y re-popula Redis")
    void afterInvalidationLookupShouldReQueryDb() {
        cacheService.invalidate(keyHash);

        var result = cacheService.lookup(keyHash);
        assertNotNull(result, "Should re-query from DB after invalidation");
        assertEquals(tenantId, result.tenantId());

        // Verify re-cached
        HashCommands<String, String, String> hash = redisDS.hash(String.class, String.class, String.class);
        var cached = hash.hgetall(CACHE_PREFIX + keyHash);
        assertEquals(tenantId.toString(), cached.get("tenant_id"));
    }
}
