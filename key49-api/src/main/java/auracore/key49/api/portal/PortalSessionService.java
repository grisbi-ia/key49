package auracore.key49.api.portal;

import auracore.key49.core.service.ApiKeyService;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Servicio de sesiones del portal web respaldado por Redis.
 *
 * <p>
 * Gestiona login vía API key, creación/validación de sesiones en Redis, y
 * renovación de TTL. Las sesiones almacenan tenantId, schemaName y
 * legalName.</p>
 */
@ApplicationScoped
public class PortalSessionService {

    private static final String SESSION_PREFIX = "portal:session:";
    private static final Duration SESSION_TTL = Duration.ofMinutes(30);

    @Inject
    Logger log;

    @Inject
    ReactiveRedisDataSource redis;

    @Inject
    PgPool pgPool;

    /**
     * Autentica con API key y crea una sesión en Redis.
     *
     * @return sessionId si el login es exitoso, null si falla
     */
    public Uni<String> login(String rawApiKey) {
        if (rawApiKey == null || rawApiKey.isBlank()) {
            return Uni.createFrom().nullItem();
        }

        var keyHash = ApiKeyService.sha256(rawApiKey);

        return pgPool.preparedQuery("""
                        SELECT a.tenant_id, a.status AS key_status, a.expires_at,
                               t.schema_name, t.status AS tenant_status, t.legal_name
                        FROM api_keys a JOIN tenants t ON a.tenant_id = t.tenant_id
                        WHERE a.key_hash = $1""")
                .execute(Tuple.of(keyHash))
                .chain(rows -> {
                    var it = rows.iterator();
                    if (!it.hasNext()) {
                        log.debug("Portal login: invalid API key");
                        return Uni.createFrom().nullItem();
                    }

                    var row = it.next();
                    if (!"active".equals(row.getString("key_status"))) {
                        return Uni.createFrom().nullItem();
                    }
                    if (!"active".equals(row.getString("tenant_status"))) {
                        return Uni.createFrom().nullItem();
                    }

                    var tenantId = row.getUUID("tenant_id").toString();
                    var schemaName = row.getString("schema_name");
                    var legalName = row.getString("legal_name");

                    var sessionId = UUID.randomUUID().toString();
                    var key = SESSION_PREFIX + sessionId;
                    var hash = redis.hash(String.class);

                    return hash.hset(key, Map.of(
                            "tenant_id", tenantId,
                            "schema_name", schemaName,
                            "legal_name", legalName != null ? legalName : ""
                    ))
                            .chain(() -> redis.key().pexpire(key, SESSION_TTL.toMillis()))
                            .replaceWith(sessionId)
                            .invoke(() -> log.infof("Portal session created | tenant=%s", tenantId));
                });
    }

    /**
     * Valida una sesión y devuelve los datos del tenant. Renueva el TTL en cada
     * acceso.
     */
    public Uni<PortalSession> validate(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Uni.createFrom().nullItem();
        }

        var key = SESSION_PREFIX + sessionId;
        var hash = redis.hash(String.class);

        return hash.hgetall(key)
                .chain(data -> {
                    if (data == null || data.isEmpty() || !data.containsKey("tenant_id")) {
                        return Uni.createFrom().nullItem();
                    }

                    // Renew TTL
                    return redis.key().pexpire(key, SESSION_TTL.toMillis())
                            .replaceWith(new PortalSession(
                                    UUID.fromString(data.get("tenant_id")),
                                    data.get("schema_name"),
                                    data.getOrDefault("legal_name", "")
                            ));
                });
    }

    /**
     * Cierra la sesión eliminando los datos de Redis.
     */
    public Uni<Void> logout(String sessionId) {
        if (sessionId == null) {
            return Uni.createFrom().voidItem();
        }
        var key = SESSION_PREFIX + sessionId;
        return redis.key().del(key).replaceWithVoid()
                .invoke(() -> log.infof("Portal session destroyed | sessionId=%s", sessionId));
    }

    /**
     * Datos de sesión del portal.
     */
    public record PortalSession(UUID tenantId, String schemaName, String legalName) {

    }
}
