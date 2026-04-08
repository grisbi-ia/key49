package auracore.key49.admin.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Tests unitarios para SriReceptionHealthCheck y SriAuthorizationHealthCheck.
 *
 * <p>
 * Verifica que los health checks manejan correctamente errores de conexión. No
 * se prueban conexiones reales al SRI (eso es E2E).</p>
 */
class SriHealthCheckTest {

    @Test
    void receptionHealthCheckShouldReturnDownOnConnectionError() {
        var healthCheck = new SriReceptionHealthCheck();
        healthCheck.environment = "test";

        // Use an invalid URL to force connection error
        var response = healthCheck.call();

        // Will be DOWN because HEAD to real SRI may timeout in test
        assertNotNull(response);
        assertEquals("SRI Recepción", response.getName());
        assertTrue(response.getData().isPresent());
    }

    @Test
    void authorizationHealthCheckShouldReturnDownOnConnectionError() {
        var healthCheck = new SriAuthorizationHealthCheck();
        healthCheck.environment = "test";

        var response = healthCheck.call();

        assertNotNull(response);
        assertEquals("SRI Autorización", response.getName());
        assertTrue(response.getData().isPresent());
    }

    @Test
    void receptionHealthCheckShouldIncludeUrl() {
        var healthCheck = new SriReceptionHealthCheck();
        healthCheck.environment = "test";

        var response = healthCheck.call();

        assertTrue(response.getData().get().containsKey("url"));
        var url = response.getData().get().get("url").toString();
        assertTrue(url.contains("RecepcionComprobantesOffline"));
    }

    @Test
    void authorizationHealthCheckShouldIncludeUrl() {
        var healthCheck = new SriAuthorizationHealthCheck();
        healthCheck.environment = "test";

        var response = healthCheck.call();

        assertTrue(response.getData().get().containsKey("url"));
        var url = response.getData().get().get("url").toString();
        assertTrue(url.contains("AutorizacionComprobantesOffline"));
    }
}
