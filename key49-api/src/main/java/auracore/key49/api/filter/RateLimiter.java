package auracore.key49.api.filter;

import java.time.Instant;
import java.util.List;

import io.quarkus.logging.Log;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.redis.client.Command;
import io.vertx.mutiny.redis.client.Redis;
import io.vertx.mutiny.redis.client.Request;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Rate limiter con ventana deslizante (sliding window) usando Redis sorted sets.
 *
 * <p>Usa un script Lua para atomicidad: elimina entries viejas, agrega el request
 * actual, cuenta entries en la ventana, y aplica TTL — todo en una sola operación.</p>
 *
 * <p>Si Redis no está disponible, el rate limiting es permisivo (permite el request).</p>
 */
@ApplicationScoped
public class RateLimiter {

    private static final long WINDOW_MS = 60_000L;
    private static final String KEY_PREFIX = "ratelimit:";

    /**
     * Script Lua para sliding window atómico.
     * KEYS[1] = sorted set key
     * ARGV[1] = window start (millis)
     * ARGV[2] = now (millis)
     * ARGV[3] = member (unique id)
     * ARGV[4] = ttl millis
     * Returns: count of entries in window after add
     */
    private static final String LUA_SCRIPT = """
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
            redis.call('ZADD', KEYS[1], ARGV[2], ARGV[3])
            local count = redis.call('ZCARD', KEYS[1])
            redis.call('PEXPIRE', KEYS[1], ARGV[4])
            return count
            """;

    @Inject
    Redis redis;

    /**
     * Verifica si el request debe ser permitido bajo el rate limit.
     *
     * @param apiKeyPrefix prefijo de la API key (identificador para el rate limit)
     * @param maxRequests  máximo de requests permitidos en la ventana de 60 segundos
     * @return resultado con allow/deny y conteos
     */
    public Uni<RateLimitResult> checkLimit(String apiKeyPrefix, int maxRequests) {
        var now = Instant.now().toEpochMilli();
        var windowStart = now - WINDOW_MS;
        var key = KEY_PREFIX + apiKeyPrefix;
        var member = now + ":" + Thread.currentThread().threadId();

        var request = Request.cmd(Command.EVAL)
                .arg(LUA_SCRIPT)
                .arg(1)           // number of keys
                .arg(key)         // KEYS[1]
                .arg(windowStart) // ARGV[1]
                .arg(now)         // ARGV[2]
                .arg(member)      // ARGV[3]
                .arg(WINDOW_MS);  // ARGV[4]

        return redis.send(request)
                .map(response -> {
                    long currentCount = response.toLong();
                    long remaining = Math.max(0, maxRequests - currentCount);
                    boolean allowed = currentCount <= maxRequests;
                    long resetAt = now + WINDOW_MS;

                    return new RateLimitResult(allowed, maxRequests, remaining, resetAt);
                })
                .onFailure().recoverWithItem(e -> {
                    Log.warnf("Redis rate limit check failed, allowing request: %s", e.getMessage());
                    return new RateLimitResult(true, maxRequests, maxRequests, now + WINDOW_MS);
                });
    }

    /**
     * Resultado de la verificación de rate limit.
     */
    public record RateLimitResult(
            boolean allowed,
            int limit,
            long remaining,
            long resetEpochMs) {

        /**
         * Epoch en segundos para el header X-RateLimit-Reset.
         */
        public long resetEpochSeconds() {
            return resetEpochMs / 1000;
        }

        /**
         * Segundos hasta el reset para el header Retry-After.
         */
        public long retryAfterSeconds() {
            long seconds = (resetEpochMs - Instant.now().toEpochMilli()) / 1000;
            return Math.max(1, seconds);
        }
    }
}
