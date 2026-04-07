package auracore.key49.admin.alert;

import java.time.Instant;

/**
 * Estado persistido de una alerta en Redis.
 *
 * @param status       {@code "FIRING"} o {@code "OK"}
 * @param since        instante del último cambio de estado
 * @param lastNotified instante de la última notificación enviada (o {@code null})
 */
public record AlertState(String status, Instant since, Instant lastNotified) {

    public static final String FIRING = "FIRING";
    public static final String OK = "OK";

    public static AlertState firing(Instant since) {
        return new AlertState(FIRING, since, null);
    }

    public static AlertState ok(Instant since) {
        return new AlertState(OK, since, null);
    }

    public boolean isFiring() {
        return FIRING.equals(status);
    }

    public AlertState withNotified(Instant at) {
        return new AlertState(status, since, at);
    }
}
