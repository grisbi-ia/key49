package auracore.key49.core.tenant;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Tests unitarios para TenantContext.
 */
class TenantContextTest {

    @Test
    void shouldDefaultRateLimitTo100() {
        var ctx = new TenantContext();
        assertEquals(100, ctx.getRateLimitRpm());
    }

    @Test
    void shouldDefaultWriteRateLimitTo30() {
        var ctx = new TenantContext();
        assertEquals(30, ctx.getRateLimitWriteRpm());
    }

    @Test
    void shouldDefaultReadRateLimitTo200() {
        var ctx = new TenantContext();
        assertEquals(200, ctx.getRateLimitReadRpm());
    }

    @Test
    void shouldStoreRateLimitRpm() {
        var ctx = new TenantContext();
        ctx.setRateLimitRpm(50);
        assertEquals(50, ctx.getRateLimitRpm());
    }

    @Test
    void shouldStoreGranularRateLimits() {
        var ctx = new TenantContext();
        ctx.setRateLimitWriteRpm(10);
        ctx.setRateLimitReadRpm(500);
        assertEquals(10, ctx.getRateLimitWriteRpm());
        assertEquals(500, ctx.getRateLimitReadRpm());
    }

    @Test
    void shouldStoreApiKeyPrefix() {
        var ctx = new TenantContext();
        assertNull(ctx.getApiKeyPrefix());

        ctx.setApiKeyPrefix("k49_abc123");
        assertEquals("k49_abc123", ctx.getApiKeyPrefix());
    }

    @Test
    void isSetShouldBeFalseWithoutTenant() {
        var ctx = new TenantContext();
        assertFalse(ctx.isSet());
    }

    @Test
    void isSetShouldBeTrueAfterSetTenant() {
        var ctx = new TenantContext();
        ctx.setTenant(UUID.randomUUID(), "tenant_abc123");
        assertTrue(ctx.isSet());
    }
}
