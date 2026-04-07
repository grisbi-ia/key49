package auracore.key49.admin.alert.rules;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitarios para ErrorRateAlertRule.
 */
class ErrorRateAlertRuleTest {

    private ErrorRateAlertRule rule;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        rule = new ErrorRateAlertRule();
        rule.registry = registry;
        rule.errorRatePercent = 5;
    }

    @Test
    void shouldReturnOkWhenNoDocuments() {
        var result = rule.evaluate();
        assertFalse(result.firing());
        assertEquals("error_rate", result.name());
        assertTrue(result.summary().contains("Sin documentos"));
    }

    @Test
    void shouldReturnOkWhenErrorRateBelowThreshold() {
        // 100 processed, 2 rejected, 1 failed → 2.9% < 5%
        incrementCounter("key49.documents.processed", 100);
        incrementCounter("key49.documents.rejected", 2);
        incrementCounter("key49.documents.failed", 1);

        var result = rule.evaluate();
        assertFalse(result.firing());
    }

    @Test
    void shouldFireWhenErrorRateAboveThreshold() {
        // 90 processed, 5 rejected, 5 failed → 10% > 5%
        incrementCounter("key49.documents.processed", 90);
        incrementCounter("key49.documents.rejected", 5);
        incrementCounter("key49.documents.failed", 5);

        var result = rule.evaluate();
        assertTrue(result.firing());
        assertTrue(result.summary().contains("10"));
    }

    @Test
    void shouldReturnOkWhenErrorRateExactlyAtThreshold() {
        // 95 processed, 3 rejected, 2 failed → 5.0% = 5% (not > 5%, so OK)
        incrementCounter("key49.documents.processed", 95);
        incrementCounter("key49.documents.rejected", 3);
        incrementCounter("key49.documents.failed", 2);

        var result = rule.evaluate();
        assertFalse(result.firing());
    }

    @Test
    void shouldFireWithAllErrors() {
        // 0 processed, 0 rejected, 10 failed → 100% > 5%
        incrementCounter("key49.documents.failed", 10);

        var result = rule.evaluate();
        assertTrue(result.firing());
    }

    private void incrementCounter(String name, int times) {
        var counter = Counter.builder(name).register(registry);
        for (int i = 0; i < times; i++) {
            counter.increment();
        }
    }
}
