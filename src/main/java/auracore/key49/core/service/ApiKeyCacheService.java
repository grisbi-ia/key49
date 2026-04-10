package auracore.key49.core.service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.hash.HashCommands;
import io.quarkus.redis.datasource.keys.KeyCommands;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.SQLException;

/**
 * Caché de API keys en Redis para evitar consultas SQL en cada request HTTP.
 *
 * <p>
 * Almacena en Redis un hash con los datos de autenticación necesarios:
 * tenant_id, schema_name, rate_limit_rpm, key_status, tenant_status,
 * expires_at. TTL configurable vía {@code KEY49_API_KEY_CACHE_TTL_SECONDS}
 * (default 300s).</p>
 *
 * <p>
 * Si Redis no está disponible, degrada gracefully consultando BD
 * directamente.</p>
 */
@ApplicationScoped
public class ApiKeyCacheService {

    private static final String CACHE_PREFIX = "key49:apikey:";

    @Inject
    Logger log;

    @Inject
    RedisDataSource redisDS;

    @Inject
    DataSource dataSource;

    @ConfigProperty(name = "key49.api-key-cache.ttl-seconds", defaultValue = "300")
    int ttlSeconds;

    /**
     * Busca datos de autenticación de una API key. Primero intenta Redis, si no
     * existe consulta BD y popula Redis. Si Redis falla, consulta BD
     * directamente.
     *
     * @param keyHash SHA-256 hash de la API key
     * @return datos de autenticación o null si la key no existe
     */
    public CachedApiKeyData lookup(String keyHash) {
        // 1. Intentar Redis
        try {
            var cached = getFromRedis(keyHash);
            if (cached != null) {
                log.debugf("API key cache hit | hash=%s", keyHash.substring(0, 8));
                return cached;
            }
        } catch (Exception ex) {
            log.warnf("Redis unavailable for API key cache, falling back to DB: %s", ex.getMessage());
            // Fallback: consultar BD directamente sin intentar cachear
            return queryFromDatabase(keyHash);
        }

        // 2. Cache miss: consultar BD y cachear
        var data = queryFromDatabase(keyHash);
        if (data != null) {
            try {
                putInRedis(keyHash, data);
                log.debugf("API key cached | hash=%s", keyHash.substring(0, 8));
            } catch (Exception ex) {
                log.warnf("Failed to cache API key in Redis: %s", ex.getMessage());
            }
        }
        return data;
    }

    /**
     * Invalida la caché de una API key por su hash.
     */
    public void invalidate(String keyHash) {
        try {
            KeyCommands<String> keys = redisDS.key(String.class);
            keys.del(CACHE_PREFIX + keyHash);
            log.debugf("API key cache invalidated | hash=%s", keyHash.substring(0, 8));
        } catch (Exception ex) {
            log.warnf("Failed to invalidate API key cache: %s", ex.getMessage());
        }
    }

    /**
     * Invalida la caché de todas las API keys de un tenant. Se usa al
     * revocar/crear keys cuando solo se conoce el tenant_id.
     */
    public void invalidateByKeyHash(String keyHash) {
        invalidate(keyHash);
    }

    private CachedApiKeyData getFromRedis(String keyHash) {
        HashCommands<String, String, String> hash = redisDS.hash(String.class, String.class, String.class);
        var data = hash.hgetall(CACHE_PREFIX + keyHash);
        if (data == null || data.isEmpty() || !data.containsKey("tenant_id")) {
            return null;
        }
        return CachedApiKeyData.fromRedisHash(data);
    }

    private void putInRedis(String keyHash, CachedApiKeyData data) {
        String key = CACHE_PREFIX + keyHash;
        HashCommands<String, String, String> hash = redisDS.hash(String.class, String.class, String.class);
        hash.hset(key, data.toRedisHash());
        KeyCommands<String> keys = redisDS.key(String.class);
        keys.pexpire(key, Duration.ofSeconds(ttlSeconds).toMillis());
    }

    private CachedApiKeyData queryFromDatabase(String keyHash) {
        try (var conn = dataSource.getConnection(); var stmt = conn.prepareStatement("""
                     SELECT a.tenant_id, a.status AS key_status, a.expires_at,
                            t.schema_name, t.status AS tenant_status,
                            t.rate_limit_rpm, t.rate_limit_write_rpm, t.rate_limit_read_rpm
                     FROM api_keys a JOIN tenants t ON a.tenant_id = t.tenant_id
                     WHERE a.key_hash = ?""")) {
            stmt.setString(1, keyHash);
            try (var rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                var tenantId = rs.getObject("tenant_id", UUID.class);
                var schemaName = rs.getString("schema_name");
                var rateLimitRpm = rs.getInt("rate_limit_rpm");
                var rateLimitWriteRpm = rs.getInt("rate_limit_write_rpm");
                var rateLimitReadRpm = rs.getInt("rate_limit_read_rpm");
                var keyStatus = rs.getString("key_status");
                var tenantStatus = rs.getString("tenant_status");
                var expiresAt = rs.getTimestamp("expires_at");
                return new CachedApiKeyData(
                        tenantId,
                        schemaName,
                        rateLimitRpm,
                        rateLimitWriteRpm,
                        rateLimitReadRpm,
                        keyStatus,
                        tenantStatus,
                        expiresAt != null ? expiresAt.toInstant().toString() : null
                );
            }
        } catch (SQLException e) {
            log.errorf(e, "Database error looking up API key");
            return null;
        }
    }

    /**
     * Datos de autenticación de una API key cacheados en Redis.
     */
    public record CachedApiKeyData(
            UUID tenantId,
            String schemaName,
            int rateLimitRpm,
            int rateLimitWriteRpm,
            int rateLimitReadRpm,
            String keyStatus,
            String tenantStatus,
            String expiresAt
            ) {

        Map<String, String> toRedisHash() {
            var map = new java.util.HashMap<String, String>();
            map.put("tenant_id", tenantId.toString());
            map.put("schema_name", schemaName);
            map.put("rate_limit_rpm", String.valueOf(rateLimitRpm));
            map.put("rate_limit_write_rpm", String.valueOf(rateLimitWriteRpm));
            map.put("rate_limit_read_rpm", String.valueOf(rateLimitReadRpm));
            map.put("key_status", keyStatus);
            map.put("tenant_status", tenantStatus);
            if (expiresAt != null) {
                map.put("expires_at", expiresAt);
            }
            return Map.copyOf(map);
        }

        static CachedApiKeyData fromRedisHash(Map<String, String> data) {
            return new CachedApiKeyData(
                    UUID.fromString(data.get("tenant_id")),
                    data.get("schema_name"),
                    Integer.parseInt(data.get("rate_limit_rpm")),
                    Integer.parseInt(data.getOrDefault("rate_limit_write_rpm", "30")),
                    Integer.parseInt(data.getOrDefault("rate_limit_read_rpm", "200")),
                    data.get("key_status"),
                    data.get("tenant_status"),
                    data.get("expires_at")
            );
        }
    }
}
