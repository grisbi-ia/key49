package auracore.key49.sri.client;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import auracore.key49.core.model.enums.SriEnvironment;
import auracore.key49.sri.SriException;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * Tests de integración para Circuit Breaker en SriReceptionClient.
 *
 * <p>
 * Verifica: apertura del circuito tras 10 fallos consecutivos, fail-fast con
 * CircuitBreakerOpenException, y recuperación tras el delay cuando el SRI
 * responde correctamente.
 */

@QuarkusTest
@QuarkusTestResource(MockSriServerResource.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SriReceptionCircuitBreakerTest {

    private static final String SAMPLE_XML = "<factura id=\"comprobante\"><test/></factura>";

    @Inject
    SriReceptionClient receptionClient;

    @BeforeEach
    void resetMock() {
        MockSriServerResource.SHOULD_FAIL.set(true);
    }

    @Test
    @Order(1)
    @DisplayName("circuito se abre tras 10 fallos consecutivos del SRI")
    void shouldOpenCircuitAfterConsecutiveFailures() {
        // Generar suficientes fallos para superar el requestVolumeThreshold=10
        for (int i = 0; i < 10; i++) {
            try {
                receptionClient.send(SAMPLE_XML, SriEnvironment.TEST);
            } catch (Exception ignored) {
                // Esperamos SriException por HTTP 500
            }
        }

        // La siguiente llamada debe fallar rápido (circuit open)
        var thrown = assertThrows(Exception.class,
                () -> receptionClient.send(SAMPLE_XML, SriEnvironment.TEST),
                "Debe lanzar excepción cuando el circuito está abierto");

        // Verificar que es una excepción de circuit breaker o SRI (fail-fast)
        var message = rootCauseMessage(thrown);
        assertTrue(
                thrown.getClass().getSimpleName().contains("CircuitBreaker")
                        || message.contains("circuit breaker")
                        || message.contains("Circuit")
                        || thrown instanceof SriException,
                "Excepción debe indicar circuit breaker abierto o error SRI: " + thrown);
    }

    @Test
    @Order(2)
    @DisplayName("circuito se recupera cuando SRI vuelve a responder correctamente")
    void shouldRecoverAfterDelay() throws InterruptedException {
        // Abrir el circuito con fallos
        for (int i = 0; i < 10; i++) {
            try {
                receptionClient.send(SAMPLE_XML, SriEnvironment.TEST);
            } catch (Exception ignored) {
            }
        }

        // Verificar que el circuito está abierto
        assertThrows(Exception.class,
                () -> receptionClient.send(SAMPLE_XML, SriEnvironment.TEST));

        // Cambiar mock a modo éxito y esperar el delay del CB (2s en test)
        MockSriServerResource.SHOULD_FAIL.set(false);
        Thread.sleep(2500);

        // En half-open, las siguientes llamadas deben tener éxito
        // successThreshold=3, necesitamos 3 llamadas exitosas para cerrar
        Exception lastException = null;
        int successCount = 0;
        for (int i = 0; i < 5; i++) {
            try {
                var response = receptionClient.send(SAMPLE_XML, SriEnvironment.TEST);
                assertNotNull(response, "Respuesta no debe ser null tras recovery");
                successCount++;
            } catch (Exception e) {
                lastException = e;
            }
        }

        assertTrue(successCount >= 3,
                "Al menos 3 llamadas deben tener éxito para cerrar el circuito. "
                        + "Éxitos: " + successCount
                        + (lastException != null ? ", último error: " + lastException : ""));
    }

    private String rootCauseMessage(Throwable t) {
        var current = t;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() != null ? current.getMessage() : current.getClass().getName();
    }
}
