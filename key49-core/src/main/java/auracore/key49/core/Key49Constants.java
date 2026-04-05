package auracore.key49.core;

import java.time.ZoneId;

/**
 * Constantes globales del sistema Key49.
 */
public final class Key49Constants {

    private Key49Constants() {
    }

    /**
     * Zona horaria de Ecuador (UTC-5). Toda lógica de "fecha actual"
     * debe usar esta zona: {@code LocalDate.now(EC_ZONE)}.
     */
    public static final ZoneId EC_ZONE = ZoneId.of("America/Guayaquil");
}
