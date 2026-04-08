package auracore.key49.api.filter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests unitarios para RateLimitResult.
 */
class RateLimiterTest {

    @Test
    void allowedResultShouldHaveCorrectFields() {
        var result = new RateLimiter.RateLimitResult(true, 100, 95, 1700000060000L);

        assertTrue(result.allowed());
        assertEquals(100, result.limit());
        assertEquals(95, result.remaining());
        assertEquals(1700000060000L, result.resetEpochMs());
    }

    @Test
    void deniedResultShouldHaveCorrectFields() {
        var result = new RateLimiter.RateLimitResult(false, 100, 0, 1700000060000L);

        assertFalse(result.allowed());
        assertEquals(0, result.remaining());
    }

    @Test
    void resetEpochSecondsShouldConvertFromMillis() {
        var result = new RateLimiter.RateLimitResult(true, 100, 50, 1700000060000L);

        assertEquals(1700000060L, result.resetEpochSeconds());
    }

    @Test
    void retryAfterSecondsShouldBeAtLeastOne() {
        // Reset time in the past — should still return at least 1
        var result = new RateLimiter.RateLimitResult(false, 100, 0, 0L);

        assertTrue(result.retryAfterSeconds() >= 1);
    }

    @Test
    void retryAfterSecondsShouldCalculateFromNow() {
        long futureMs = System.currentTimeMillis() + 30_000L;
        var result = new RateLimiter.RateLimitResult(false, 100, 0, futureMs);

        long retryAfter = result.retryAfterSeconds();
        assertTrue(retryAfter >= 28 && retryAfter <= 31,
                "retryAfter should be ~30 seconds, got: " + retryAfter);
    }

    @Test
    void remainingShouldNeverBeNegative() {
        // When over limit, remaining is computed as max(0, limit - count)
        var result = new RateLimiter.RateLimitResult(false, 100, 0, 1700000060000L);

        assertEquals(0, result.remaining());
    }
}
