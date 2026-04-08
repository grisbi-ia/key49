package auracore.key49.queue.retry;

import java.time.Duration;
import java.time.Instant;

/**
 * Calcula delays de backoff exponencial para reintentos del pipeline.
 *
 * <p>Política: 5s, 15s, 45s, 135s, 405s (factor ×3).
 * Si el retryCount excede la tabla de delays, se usa el último valor (405s).
 */
public final class RetryDelayCalculator {

    private static final long[] DELAYS_SECONDS = {5, 15, 45, 135, 405};

    private RetryDelayCalculator() {
    }

    /**
     * Calcula el delay para el intento de reintento dado.
     *
     * @param retryCount número de reintento (1-based: primer reintento = 1)
     * @return duración del delay
     */
    public static Duration calculateDelay(int retryCount) {
        if (retryCount <= 0) {
            return Duration.ZERO;
        }
        int index = Math.min(retryCount - 1, DELAYS_SECONDS.length - 1);
        return Duration.ofSeconds(DELAYS_SECONDS[index]);
    }

    /**
     * Calcula el instante para el próximo reintento.
     *
     * @param retryCount número de reintento (1-based)
     * @return instante de próximo reintento
     */
    public static Instant calculateNextRetryAt(int retryCount) {
        return Instant.now().plus(calculateDelay(retryCount));
    }

    /**
     * Determina si los reintentos se han agotado.
     *
     * @param retryCount número de reintentos realizados
     * @param maxRetries máximo de reintentos permitidos
     * @return true si se agotaron
     */
    public static boolean isExhausted(int retryCount, int maxRetries) {
        return retryCount >= maxRetries;
    }
}
