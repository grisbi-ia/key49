package auracore.key49.admin.alert;

import io.vertx.mutiny.redis.client.Command;
import io.vertx.mutiny.redis.client.Redis;
import io.vertx.mutiny.redis.client.Request;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;

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
    Redis redis;

    /**
     * Obtiene el estado actual de una alerta.
     *
     * @param alertName nombre de la alerta (e.g. "sri_health")
     * @return estado actual o {@code null} si no existe
     */
    public AlertState get(String alertName) {
        try {
            var key = KEY_PREFIX + alertName;
            var response = redis.send(Request.cmd(Command.HGETALL).arg(key))
                    .await().atMost(Duration.ofSeconds(3));

            if (response == null || response.size() == 0) {
                return null;
            }

            String status = null;
            String since = null;
            String lastNotified = null;

            for (int i = 0; i < response.size(); i += 2) {
                var field = response.get(i).toString();
                var value = response.get(i + 1).toString();
                switch (field) {
                    case "status" -> status = value;
                    case "since" -> since = value;
                    case "last_notified" -> lastNotified = value;
                }
            }

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

            redis.send(Request.cmd(Command.HSET)
                            .arg(key)
                            .arg("status").arg(state.status())
                            .arg("since").arg(state.since().toString())
                            .arg("last_notified").arg(lastNotified))
                    .await().atMost(Duration.ofSeconds(3));

            redis.send(Request.cmd(Command.EXPIRE).arg(key).arg(TTL_SECONDS))
                    .await().atMost(Duration.ofSeconds(3));
        } catch (Exception e) {
            log.warnf("Failed to save alert state to Redis: alert=%s error=%s", alertName, e.getMessage());
        }
    }
}
