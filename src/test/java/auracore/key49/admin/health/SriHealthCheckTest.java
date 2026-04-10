package auracore.key49.admin.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import auracore.key49.core.model.enums.SriEnvironment;
import auracore.key49.sri.config.SriEndpoints;

/**
 * Tests unitarios para SriReceptionHealthCheck y SriAuthorizationHealthCheck.
 *
 * <p>
 * Verifica que los health checks manejan correctamente errores de conexión. No
 * se prueban conexiones reales al SRI (eso es E2E).</p>
 */
class SriHealthCheckTest {

    private SriEndpoints sriEndpoints;

    @BeforeEach
    void setup() {
        sriEndpoints = new SriEndpoints() {
            @Override
            public String receptionUrl(SriEnvironment env) {
                return switch (env) {
                    case TEST ->
                        "https://celcer.sri.gob.ec/comprobantes-electronicos-ws/RecepcionComprobantesOffline?wsdl";
                    case PRODUCTION ->
                        "https://cel.sri.gob.ec/comprobantes-electronicos-ws/RecepcionComprobantesOffline?wsdl";
                };
            }

            @Override
            public String authorizationUrl(SriEnvironment env) {
                return switch (env) {
                    case TEST ->
                        "https://celcer.sri.gob.ec/comprobantes-electronicos-ws/AutorizacionComprobantesOffline?wsdl";
                    case PRODUCTION ->
                        "https://cel.sri.gob.ec/comprobantes-electronicos-ws/AutorizacionComprobantesOffline?wsdl";
                };
            }
        };
    }

    @Test
    void receptionHealthCheckShouldReturnDownOnConnectionError() {
        var healthCheck = new SriReceptionHealthCheck();
        healthCheck.environment = "test";
        healthCheck.sriEndpoints = sriEndpoints;

        var response = healthCheck.call();

        assertNotNull(response);
        assertEquals("SRI Recepción", response.getName());
        assertTrue(response.getData().isPresent());
    }

    @Test
    void authorizationHealthCheckShouldReturnDownOnConnectionError() {
        var healthCheck = new SriAuthorizationHealthCheck();
        healthCheck.environment = "test";
        healthCheck.sriEndpoints = sriEndpoints;

        var response = healthCheck.call();

        assertNotNull(response);
        assertEquals("SRI Autorización", response.getName());
        assertTrue(response.getData().isPresent());
    }

    @Test
    void receptionHealthCheckShouldIncludeUrl() {
        var healthCheck = new SriReceptionHealthCheck();
        healthCheck.environment = "test";
        healthCheck.sriEndpoints = sriEndpoints;

        var response = healthCheck.call();

        assertTrue(response.getData().get().containsKey("url"));
        var url = response.getData().get().get("url").toString();
        assertTrue(url.contains("RecepcionComprobantesOffline"));
    }

    @Test
    void authorizationHealthCheckShouldIncludeUrl() {
        var healthCheck = new SriAuthorizationHealthCheck();
        healthCheck.environment = "test";
        healthCheck.sriEndpoints = sriEndpoints;

        var response = healthCheck.call();

        assertTrue(response.getData().get().containsKey("url"));
        var url = response.getData().get().get("url").toString();
        assertTrue(url.contains("AutorizacionComprobantesOffline"));
    }
}
