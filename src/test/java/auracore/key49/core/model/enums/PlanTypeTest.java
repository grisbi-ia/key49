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
    @DisplayName("STARTER tiene code 'starter' y cuota 100")
    void starterDefaults() {
        assertEquals("starter", PlanType.STARTER.code());
        assertEquals(100, PlanType.STARTER.defaultQuota());
    }

    @Test
    @DisplayName("BUSINESS tiene code 'business' y cuota 500")
    void businessDefaults() {
        assertEquals("business", PlanType.BUSINESS.code());
        assertEquals(500, PlanType.BUSINESS.defaultQuota());
    }

    @Test
    @DisplayName("ENTERPRISE tiene code 'enterprise' y cuota 5000")
    void enterpriseDefaults() {
        assertEquals("enterprise", PlanType.ENTERPRISE.code());
        assertEquals(5000, PlanType.ENTERPRISE.defaultQuota());
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
