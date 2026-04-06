package auracore.key49.queue.retry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class RetryDelayCalculatorTest {

    @Test
    void shouldReturnZeroDelayForRetryCountZero() {
        assertEquals(Duration.ZERO, RetryDelayCalculator.calculateDelay(0));
    }

    @Test
    void shouldReturnZeroDelayForNegativeRetryCount() {
        assertEquals(Duration.ZERO, RetryDelayCalculator.calculateDelay(-1));
    }

    @Test
    void shouldReturn5SecondsForFirstRetry() {
        assertEquals(Duration.ofSeconds(5), RetryDelayCalculator.calculateDelay(1));
    }

    @Test
    void shouldReturn15SecondsForSecondRetry() {
        assertEquals(Duration.ofSeconds(15), RetryDelayCalculator.calculateDelay(2));
    }

    @Test
    void shouldReturn45SecondsForThirdRetry() {
        assertEquals(Duration.ofSeconds(45), RetryDelayCalculator.calculateDelay(3));
    }

    @Test
    void shouldReturn135SecondsForFourthRetry() {
        assertEquals(Duration.ofSeconds(135), RetryDelayCalculator.calculateDelay(4));
    }

    @Test
    void shouldReturn405SecondsForFifthRetry() {
        assertEquals(Duration.ofSeconds(405), RetryDelayCalculator.calculateDelay(5));
    }

    @Test
    void shouldClampToMaxDelayForHighRetryCount() {
        assertEquals(Duration.ofSeconds(405), RetryDelayCalculator.calculateDelay(6));
        assertEquals(Duration.ofSeconds(405), RetryDelayCalculator.calculateDelay(10));
        assertEquals(Duration.ofSeconds(405), RetryDelayCalculator.calculateDelay(100));
    }

    @Test
    void shouldCalculateNextRetryAtInTheFuture() {
        Instant before = Instant.now();
        Instant nextRetryAt = RetryDelayCalculator.calculateNextRetryAt(1);
        Instant after = Instant.now();

        assertTrue(nextRetryAt.isAfter(before.plusSeconds(4)));
        assertTrue(nextRetryAt.isBefore(after.plusSeconds(6)));
    }

    @Test
    void shouldNotBeExhaustedWhenUnderLimit() {
        assertFalse(RetryDelayCalculator.isExhausted(0, 6));
        assertFalse(RetryDelayCalculator.isExhausted(3, 6));
        assertFalse(RetryDelayCalculator.isExhausted(5, 6));
    }

    @Test
    void shouldBeExhaustedWhenAtLimit() {
        assertTrue(RetryDelayCalculator.isExhausted(6, 6));
    }

    @Test
    void shouldBeExhaustedWhenOverLimit() {
        assertTrue(RetryDelayCalculator.isExhausted(7, 6));
        assertTrue(RetryDelayCalculator.isExhausted(100, 6));
    }

    @Test
    void shouldFollowExponentialBackoffPattern() {
        long d1 = RetryDelayCalculator.calculateDelay(1).toSeconds();
        long d2 = RetryDelayCalculator.calculateDelay(2).toSeconds();
        long d3 = RetryDelayCalculator.calculateDelay(3).toSeconds();
        long d4 = RetryDelayCalculator.calculateDelay(4).toSeconds();
        long d5 = RetryDelayCalculator.calculateDelay(5).toSeconds();

        // Factor ×3
        assertEquals(d1 * 3, d2);
        assertEquals(d2 * 3, d3);
        assertEquals(d3 * 3, d4);
        assertEquals(d4 * 3, d5);
    }
}
