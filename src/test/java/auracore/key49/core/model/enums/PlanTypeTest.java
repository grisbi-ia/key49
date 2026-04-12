package auracore.key49.core.model.enums;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests unitarios para el enum PlanType.
 */
@DisplayName("PlanType enum")
class PlanTypeTest {

    @Test
    @DisplayName("DEMO tiene code 'demo' y cuota 25")
    void demoDefaults() {
        assertEquals("demo", PlanType.DEMO.code());
        assertEquals(25, PlanType.DEMO.defaultQuota());
    }

    @Test
    @DisplayName("DEMO rate limits: write=10, read=30")
    void demoRateLimits() {
        assertEquals(10, PlanType.DEMO.writeRpm());
        assertEquals(30, PlanType.DEMO.readRpm());
        assertEquals(40, PlanType.DEMO.totalRpm());
    }

    @Test
    @DisplayName("STARTER tiene code 'starter' y cuota 100")
    void starterDefaults() {
        assertEquals("starter", PlanType.STARTER.code());
        assertEquals(100, PlanType.STARTER.defaultQuota());
    }

    @Test
    @DisplayName("STARTER rate limits: write=30, read=100")
    void starterRateLimits() {
        assertEquals(30, PlanType.STARTER.writeRpm());
        assertEquals(100, PlanType.STARTER.readRpm());
        assertEquals(130, PlanType.STARTER.totalRpm());
    }

    @Test
    @DisplayName("BUSINESS tiene code 'business' y cuota 500")
    void businessDefaults() {
        assertEquals("business", PlanType.BUSINESS.code());
        assertEquals(500, PlanType.BUSINESS.defaultQuota());
    }

    @Test
    @DisplayName("BUSINESS rate limits: write=60, read=200")
    void businessRateLimits() {
        assertEquals(60, PlanType.BUSINESS.writeRpm());
        assertEquals(200, PlanType.BUSINESS.readRpm());
        assertEquals(260, PlanType.BUSINESS.totalRpm());
    }

    @Test
    @DisplayName("ENTERPRISE tiene code 'enterprise' y cuota 5000")
    void enterpriseDefaults() {
        assertEquals("enterprise", PlanType.ENTERPRISE.code());
        assertEquals(5000, PlanType.ENTERPRISE.defaultQuota());
    }

    @Test
    @DisplayName("ENTERPRISE rate limits: write=200, read=600")
    void enterpriseRateLimits() {
        assertEquals(200, PlanType.ENTERPRISE.writeRpm());
        assertEquals(600, PlanType.ENTERPRISE.readRpm());
        assertEquals(800, PlanType.ENTERPRISE.totalRpm());
    }

    @Test
    @DisplayName("fromCode resuelve cada tipo correctamente")
    void fromCodeResolvesAll() {
        assertEquals(PlanType.DEMO, PlanType.fromCode("demo"));
        assertEquals(PlanType.STARTER, PlanType.fromCode("starter"));
        assertEquals(PlanType.BUSINESS, PlanType.fromCode("business"));
        assertEquals(PlanType.ENTERPRISE, PlanType.fromCode("enterprise"));
    }

    @Test
    @DisplayName("fromCode con código inválido lanza IllegalArgumentException")
    void fromCodeInvalidThrows() {
        assertThrows(IllegalArgumentException.class, () -> PlanType.fromCode("premium"));
    }

    @Test
    @DisplayName("Existen exactamente 4 tipos de plan")
    void exactlyFourPlans() {
        assertEquals(4, PlanType.values().length);
    }
}
