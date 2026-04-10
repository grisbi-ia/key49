package auracore.key49.core.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import auracore.key49.core.model.Tenant;
import auracore.key49.core.repository.TenantRepository;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.hash.HashCommands;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Caché de metadatos de tenant en Redis para evitar consultas SQL repetitivas.
 *
 * <p>
 * Almacena en Redis un hash con los datos del tenant (excluyendo certificado
 * binario). Mantiene un índice secundario {@code schema_name → tenant_id} para
 * lookups por nombre de esquema. TTL configurable vía
 * {@code KEY49_TENANT_CACHE_TTL_SECONDS} (default 600s = 10 min).</p>
 *
 * <p>
 * Si Redis no está disponible, degrada gracefully consultando BD
 * directamente.</p>
 */

@ApplicationScoped
public class TenantCacheService {

    private static final String CACHE_PREFIX = "key49:tenant:";
    private static final String SCHEMA_PREFIX = "key49:tenant:schema:";

    @Inject
    Logger log;

    @Inject
    RedisDataSource redisDS;

    @Inject
    TenantRepository tenantRepository;

    @ConfigProperty(name = "key49.tenant-cache.ttl-seconds", defaultValue = "600")
    int ttlSeconds;

    /**
     * Busca un tenant por nombre de esquema. Primero consulta el índice
     * Redis {@code schema → tenant_id}, luego el hash del tenant.
     * Si falla Redis, consulta BD directamente.
     *
     * @param schemaName nombre del esquema PostgreSQL del tenant
     * @return entidad Tenant (sin certificado binario si viene de caché) o null
     */
    public Tenant findBySchemaName(String schemaName) {
        try {
            var tenantId = getSchemaIndex(schemaName);
            if (tenantId != null) {
                var tenant = getFromRedis(tenantId);
                if (tenant != null) {
                    log.debugf("Tenant cache hit | schema=%s", schemaName);
                    return tenant;
                }
            }
        } catch (Exception ex) {
            log.warnf("Redis unavailable for tenant cache, falling back to DB: %s", ex.getMessage());
            return tenantRepository.findBySchemaName(schemaName);
        }

        var tenant = tenantRepository.findBySchemaName(schemaName);
        if (tenant != null) {
            try {
                putInRedis(tenant);
                log.debugf("Tenant cached | id=%s schema=%s", tenant.id, schemaName);
            } catch (Exception ex) {
                log.warnf("Failed to cache tenant in Redis: %s", ex.getMessage());
            }
        }
        return tenant;
    }

    /**
     * Busca un tenant por ID. Primero consulta Redis, luego BD.
     *
     * @param id UUID del tenant
     * @return entidad Tenant (sin certificado binario si viene de caché) o null
     */
    public Tenant findById(UUID id) {
        try {
            var tenant = getFromRedis(id);
            if (tenant != null) {
                log.debugf("Tenant cache hit | id=%s", id);
                return tenant;
            }
        } catch (Exception ex) {
            log.warnf("Redis unavailable for tenant cache, falling back to DB: %s", ex.getMessage());
            return tenantRepository.findById(id);
        }

        var tenant = tenantRepository.findById(id);
        if (tenant != null) {
            try {
                putInRedis(tenant);
                log.debugf("Tenant cached | id=%s", id);
            } catch (Exception ex) {
                log.warnf("Failed to cache tenant in Redis: %s", ex.getMessage());
            }
        }
        return tenant;
    }

    /**
     * Invalida la caché de un tenant por ID y nombre de esquema.
     */
    public void invalidate(UUID id, String schemaName) {
        try {
            KeyCommands<String> keys = redisDS.key(String.class);
            keys.del(CACHE_PREFIX + id);
            if (schemaName != null) {
                keys.del(SCHEMA_PREFIX + schemaName);
            }
            log.debugf("Tenant cache invalidated | id=%s schema=%s", id, schemaName);
        } catch (Exception ex) {
            log.warnf("Failed to invalidate tenant cache: %s", ex.getMessage());
        }
    }

    // ── Redis operations ──

    private UUID getSchemaIndex(String schemaName) {
        ValueCommands<String, String> values = redisDS.value(String.class, String.class);
        var tenantId = values.get(SCHEMA_PREFIX + schemaName);
        return tenantId != null ? UUID.fromString(tenantId) : null;
    }

    private Tenant getFromRedis(UUID id) {
        HashCommands<String, String, String> hash = redisDS.hash(String.class, String.class, String.class);
        var data = hash.hgetall(CACHE_PREFIX + id);
        if (data == null || data.isEmpty() || !data.containsKey("tenant_id")) {
            return null;
        }
        return fromRedisHash(data);
    }

    private void putInRedis(Tenant tenant) {
        String tenantKey = CACHE_PREFIX + tenant.id;
        String schemaKey = SCHEMA_PREFIX + tenant.schemaName;
        long ttlMillis = Duration.ofSeconds(ttlSeconds).toMillis();

        HashCommands<String, String, String> hash = redisDS.hash(String.class, String.class, String.class);
        hash.hset(tenantKey, toRedisHash(tenant));

        ValueCommands<String, String> values = redisDS.value(String.class, String.class);
        values.set(schemaKey, tenant.id.toString());

        KeyCommands<String> keys = redisDS.key(String.class);
        keys.pexpire(tenantKey, ttlMillis);
        keys.pexpire(schemaKey, ttlMillis);
    }

    // ── Serialization (sin certificado binario) ──

    static Map<String, String> toRedisHash(Tenant t) {
        var map = new java.util.HashMap<String, String>();
        map.put("tenant_id", t.id.toString());
        map.put("ruc", t.ruc);
        map.put("legal_name", t.legalName);
        putIfNotNull(map, "trade_name", t.tradeName);
        map.put("main_address", t.mainAddress);
        map.put("required_accounting", String.valueOf(t.requiredAccounting));
        putIfNotNull(map, "special_taxpayer", t.specialTaxpayer);
        map.put("micro_enterprise_regime", String.valueOf(t.microEnterpriseRegime));
        putIfNotNull(map, "withholding_agent", t.withholdingAgent);
        map.put("environment", t.environment);
        map.put("emission_type", String.valueOf(t.emissionType));
        putIfNotNull(map, "logo_url", t.logoUrl);
        putIfNotNull(map, "certificate_subject", t.certificateSubject);
        if (t.certificateExpiration != null) {
            map.put("certificate_expiration", t.certificateExpiration.toString());
        }
        putIfNotNull(map, "certificate_serial", t.certificateSerial);
        putIfNotNull(map, "webhook_url", t.webhookUrl);
        putIfNotNull(map, "webhook_secret", t.webhookSecret);
        map.put("rate_limit_rpm", String.valueOf(t.rateLimitRpm));
        putIfNotNull(map, "email_sender_name", t.emailSenderName);
        putIfNotNull(map, "reply_email", t.replyEmail);
        map.put("schema_name", t.schemaName);
        map.put("status", t.status);
        if (t.createdAt != null) {
            map.put("created_at", t.createdAt.toString());
        }
        if (t.updatedAt != null) {
            map.put("updated_at", t.updatedAt.toString());
        }
        return Map.copyOf(map);
    }

    static Tenant fromRedisHash(Map<String, String> data) {
        var t = new Tenant();
        t.id = UUID.fromString(data.get("tenant_id"));
        t.ruc = data.get("ruc");
        t.legalName = data.get("legal_name");
        t.tradeName = data.get("trade_name");
        t.mainAddress = data.get("main_address");
        t.requiredAccounting = Boolean.parseBoolean(data.get("required_accounting"));
        t.specialTaxpayer = data.get("special_taxpayer");
        t.microEnterpriseRegime = Boolean.parseBoolean(data.get("micro_enterprise_regime"));
        t.withholdingAgent = data.get("withholding_agent");
        t.environment = data.get("environment");
        t.emissionType = Short.parseShort(data.get("emission_type"));
        t.logoUrl = data.get("logo_url");
        t.certificateSubject = data.get("certificate_subject");
        var certExp = data.get("certificate_expiration");
        t.certificateExpiration = certExp != null ? Instant.parse(certExp) : null;
        t.certificateSerial = data.get("certificate_serial");
        t.webhookUrl = data.get("webhook_url");
        t.webhookSecret = data.get("webhook_secret");
        t.rateLimitRpm = Integer.parseInt(data.get("rate_limit_rpm"));
        t.emailSenderName = data.get("email_sender_name");
        t.replyEmail = data.get("reply_email");
        t.schemaName = data.get("schema_name");
        t.status = data.get("status");
        var createdAt = data.get("created_at");
        t.createdAt = createdAt != null ? Instant.parse(createdAt) : null;
        var updatedAt = data.get("updated_at");
        t.updatedAt = updatedAt != null ? Instant.parse(updatedAt) : null;
        // Binary fields NOT cached: certificateP12, certificatePasswordEnc
        return t;
    }

    private static void putIfNotNull(Map<String, String> map, String key, String value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
