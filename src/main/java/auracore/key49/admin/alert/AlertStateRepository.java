package auracore.key49.admin.alert;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.hash.HashCommands;
import io.quarkus.redis.datasource.keys.KeyCommands;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Repositorio de estado de alertas en Redis.
 *
 * <p>Cada alerta tiene un hash en Redis con campos {@code status}, {@code since}
 * y {@code last_notified}. TTL de 7 días para limpieza automática.</p>
 */
@ApplicationScoped
public class AlertStateRepository {

    private static final Logger log = Logger.getLogger(AlertStateRepository.class);
    private static final String KEY_PREFIX = "alert:";
    private static final long TTL_SECONDS = Duration.ofDays(7).toSeconds();

    @Inject
    RedisDataSource redisDS;

    /**
     * Obtiene el estado actual de una alerta.
     *
     * @param alertName nombre de la alerta (e.g. "sri_health")
     * @return estado actual o {@code null} si no existe
     */
    public AlertState get(String alertName) {
        try {
            var key = KEY_PREFIX + alertName;
            HashCommands<String, String, String> hash = redisDS.hash(String.class, String.class, String.class);
            Map<String, String> data = hash.hgetall(key);

            if (data == null || data.isEmpty() || !data.containsKey("status")) {
                return null;
            }

            var status = data.get("status");
            var since = data.get("since");
            var lastNotified = data.get("last_notified");

            if (status == null || since == null) {
                return null;
            }

            return new AlertState(
                    status,
                    Instant.parse(since),
                    lastNotified != null && !lastNotified.isBlank() ? Instant.parse(lastNotified) : null
            );
        } catch (Exception e) {
            log.warnf("Failed to read alert state from Redis: alert=%s error=%s", alertName, e.getMessage());
            return null;
        }
    }

    /**
     * Guarda el estado de una alerta en Redis con TTL de 7 días.
     *
     * @param alertName nombre de la alerta
     * @param state     estado a persistir
     */
    public void save(String alertName, AlertState state) {
        try {
            var key = KEY_PREFIX + alertName;
            var lastNotified = state.lastNotified() != null ? state.lastNotified().toString() : "";

            HashCommands<String, String, String> hash = redisDS.hash(String.class, String.class, String.class);
            hash.hset(key, Map.of(
                    "status", state.status(),
                    "since", state.since().toString(),
                    "last_notified", lastNotified
            ));

            KeyCommands<String> keys = redisDS.key(String.class);
            keys.pexpire(key, Duration.ofDays(7).toMillis());
        } catch (Exception e) {
            log.warnf("Failed to save alert state to Redis: alert=%s error=%s", alertName, e.getMessage());
        }
    }
}
