package auracore.key49.api.portal;

import auracore.key49.core.service.ApiKeyService;
import auracore.key49.core.service.PasswordHasher;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.hash.HashCommands;
import io.quarkus.redis.datasource.keys.KeyCommands;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.SQLException;
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
    RedisDataSource redisDS;

    @Inject
    DataSource dataSource;

    @Inject
    PasswordHasher passwordHasher;

    /**
     * Autentica con API key y crea una sesión en Redis.
     *
     * @return sessionId si el login es exitoso, null si falla
     */
    public String login(String rawApiKey) {
        if (rawApiKey == null || rawApiKey.isBlank()) {
            return null;
        }

        var keyHash = ApiKeyService.sha256(rawApiKey);

        try (var conn = dataSource.getConnection(); var stmt = conn.prepareStatement("""
                     SELECT a.tenant_id, a.status AS key_status, a.expires_at,
                            t.schema_name, t.status AS tenant_status, t.legal_name
                     FROM api_keys a JOIN tenants t ON a.tenant_id = t.tenant_id
                     WHERE a.key_hash = ?""")) {

            stmt.setString(1, keyHash);
            try (var rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    log.debug("Portal login: invalid API key");
                    return null;
                }

                if (!"active".equals(rs.getString("key_status"))) {
                    return null;
                }
                if (!"active".equals(rs.getString("tenant_status"))) {
                    return null;
                }

                var tenantId = rs.getObject("tenant_id", UUID.class).toString();
                var schemaName = rs.getString("schema_name");
                var legalName = rs.getString("legal_name");

                return createSession(tenantId, schemaName, legalName);
            }
        } catch (SQLException e) {
            log.errorf(e, "Portal login: database error");
            return null;
        }
    }

    /**
     * Autentica con email + contraseña y crea una sesión en Redis.
     *
     * @return sessionId si el login es exitoso, null si falla
     */
    public String loginWithPassword(String email, String password) {
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            return null;
        }

        try (var conn = dataSource.getConnection(); var stmt = conn.prepareStatement("""
                     SELECT tenant_id, schema_name, legal_name, status,
                            portal_password_hash
                     FROM tenants
                     WHERE email = ? AND status = 'active'""")) {

            stmt.setString(1, email.strip().toLowerCase());
            try (var rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    log.debug("Portal login: email not found");
                    return null;
                }

                var hash = rs.getString("portal_password_hash");
                if (hash == null || !passwordHasher.verify(password, hash)) {
                    log.debug("Portal login: invalid password");
                    return null;
                }

                var tenantId = rs.getObject("tenant_id", UUID.class).toString();
                var schemaName = rs.getString("schema_name");
                var legalName = rs.getString("legal_name");

                return createSession(tenantId, schemaName, legalName);
            }
        } catch (SQLException e) {
            log.errorf(e, "Portal login: database error");
            return null;
        }
    }

    /**
     * Crea una sesión en Redis y retorna el sessionId.
     */
    private String createSession(String tenantId, String schemaName, String legalName) {
        var sessionId = UUID.randomUUID().toString();
        var key = SESSION_PREFIX + sessionId;

        HashCommands<String, String, String> hash = redisDS.hash(String.class, String.class, String.class);
        hash.hset(key, Map.of(
                "tenant_id", tenantId,
                "schema_name", schemaName,
                "legal_name", legalName != null ? legalName : ""
        ));

        KeyCommands<String> keys = redisDS.key(String.class);
        keys.pexpire(key, SESSION_TTL.toMillis());

        log.infof("Portal session created | tenant=%s", tenantId);
        return sessionId;
    }

    /**
     * Valida una sesión y devuelve los datos del tenant. Renueva el TTL en cada
     * acceso.
     */
    public PortalSession validate(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }

        var key = SESSION_PREFIX + sessionId;
        HashCommands<String, String, String> hash = redisDS.hash(String.class, String.class, String.class);
        var data = hash.hgetall(key);

        if (data == null || data.isEmpty() || !data.containsKey("tenant_id")) {
            return null;
        }

        // Renew TTL
        KeyCommands<String> keys = redisDS.key(String.class);
        keys.pexpire(key, SESSION_TTL.toMillis());

        return new PortalSession(
                UUID.fromString(data.get("tenant_id")),
                data.get("schema_name"),
                data.getOrDefault("legal_name", "")
        );
    }

    /**
     * Cierra la sesión eliminando los datos de Redis.
     */
    public void logout(String sessionId) {
        if (sessionId == null) {
            return;
        }
        var key = SESSION_PREFIX + sessionId;
        KeyCommands<String> keys = redisDS.key(String.class);
        keys.del(key);
        log.infof("Portal session destroyed | sessionId=%s...", sessionId.substring(0, Math.min(8, sessionId.length())));
    }

    /**
     * Datos de sesión del portal.
     */
    public record PortalSession(UUID tenantId, String schemaName, String legalName) {

    }
}
