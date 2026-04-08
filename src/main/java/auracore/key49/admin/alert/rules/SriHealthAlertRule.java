package auracore.key49.admin.alert.rules;

import java.time.Duration;
import java.time.Instant;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.Liveness;
import org.jboss.logging.Logger;

import auracore.key49.admin.alert.AlertResult;
import auracore.key49.admin.alert.AlertRule;
import auracore.key49.admin.health.SriAuthorizationHealthCheck;
import auracore.key49.admin.health.SriReceptionHealthCheck;
import io.vertx.mutiny.redis.client.Command;
import io.vertx.mutiny.redis.client.Redis;
import io.vertx.mutiny.redis.client.Request;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Regla de alerta: SRI no responde.
 *
 * <p>Reutiliza los health checks existentes de SRI Recepción y SRI Autorización.
 * Registra en Redis el timestamp del primer fallo y dispara la alerta si el
 * SRI lleva más de {@code key49.alerts.sri-timeout} sin responder.</p>
 */
@ApplicationScoped
public class SriHealthAlertRule implements AlertRule {

    static final String NAME = "sri_health";
    private static final Logger log = Logger.getLogger(SriHealthAlertRule.class);
    private static final String FIRST_FAILURE_KEY = "alert:sri_health_first_failure";

    @Inject
    @Liveness
    SriReceptionHealthCheck receptionCheck;

    @Inject
    @Liveness
    SriAuthorizationHealthCheck authorizationCheck;

    @Inject
    Redis redis;

    @ConfigProperty(name = "key49.alerts.sri-timeout", defaultValue = "5m")
    Duration sriTimeout;

    @Override
    public AlertResult evaluate() {
        try {
            var receptionUp = receptionCheck.call().getStatus() ==
                    org.eclipse.microprofile.health.HealthCheckResponse.Status.UP;
            var authorizationUp = authorizationCheck.call().getStatus() ==
                    org.eclipse.microprofile.health.HealthCheckResponse.Status.UP;

            if (receptionUp && authorizationUp) {
                clearFirstFailure();
                return AlertResult.ok(NAME, "SRI Recepción y Autorización operativos");
            }

            var downServices = (!receptionUp ? "Recepción" : "") +
                    (!receptionUp && !authorizationUp ? " y " : "") +
                    (!authorizationUp ? "Autorización" : "");

            var firstFailure = getOrSetFirstFailure();
            var downDuration = Duration.between(firstFailure, Instant.now());

            if (downDuration.compareTo(sriTimeout) > 0) {
                return AlertResult.firing(NAME,
                        "SRI %s no responde desde hace %d minutos".formatted(
                                downServices, downDuration.toMinutes()));
            }

            return AlertResult.ok(NAME,
                    "SRI %s caído hace %d segundos (umbral: %d minutos)".formatted(
                            downServices, downDuration.toSeconds(), sriTimeout.toMinutes()));
        } catch (Exception e) {
            log.warnf("SRI health alert eval failed: %s", e.getMessage());
            return AlertResult.ok(NAME, "No se pudo evaluar: " + e.getMessage());
        }
    }

    private Instant getOrSetFirstFailure() {
        try {
            var existing = redis.send(Request.cmd(Command.GET).arg(FIRST_FAILURE_KEY))
                    .await().atMost(Duration.ofSeconds(2));

            if (existing != null && existing.toString() != null && !existing.toString().isBlank()) {
                return Instant.parse(existing.toString());
            }

            var now = Instant.now();
            redis.send(Request.cmd(Command.SET).arg(FIRST_FAILURE_KEY).arg(now.toString())
                            .arg("EX").arg(Duration.ofHours(24).toSeconds()))
                    .await().atMost(Duration.ofSeconds(2));
            return now;
        } catch (Exception e) {
            log.warnf("Redis error in SRI alert rule: %s", e.getMessage());
            return Instant.now();
        }
    }

    private void clearFirstFailure() {
        try {
            redis.send(Request.cmd(Command.DEL).arg(FIRST_FAILURE_KEY))
                    .await().atMost(Duration.ofSeconds(2));
        } catch (Exception e) {
            log.warnf("Redis error clearing SRI first failure: %s", e.getMessage());
        }
    }
}
