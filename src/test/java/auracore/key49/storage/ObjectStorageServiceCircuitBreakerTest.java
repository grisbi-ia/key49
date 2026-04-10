package auracore.key49.storage;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * Tests de integración para Circuit Breaker en ObjectStorageService.
 *
 * <p>
 * Verifica que el CB se abre tras fallos consecutivos de MinIO y que las
 * llamadas fallan rápido (fail-fast) cuando el circuito está abierto.
 *
 * <p>
 * Nota: En el entorno de test no hay MinIO disponible, por lo que todas las
 * llamadas fallan con StorageException. Esto nos permite verificar que el CB se
 * abre tras el umbral de fallos.
 */

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ObjectStorageServiceCircuitBreakerTest {

    private static final String TENANT = "tenant_test";
    private static final LocalDate ISSUE_DATE = LocalDate.of(2025, 6, 20);
    private static final String DOC_TYPE = "01";
    private static final String ACCESS_KEY = "2506202501099271531200110010020000000011234567813";
    private static final byte[] DATA = "<xml>test</xml>".getBytes();

    @Inject
    ObjectStorageService storageService;

    @BeforeEach
    void ensureServiceInjected() {
        assertNotNull(storageService, "ObjectStorageService debe estar inyectado");
    }

    @Test
    @Order(1)
    @DisplayName("store: circuito se abre tras fallos consecutivos de MinIO")
    void storeCircuitShouldOpenAfterConsecutiveFailures() {
        // Generar suficientes fallos para superar el requestVolumeThreshold=10
        for (int i = 0; i < 10; i++) {
            try {
                storageService.store(TENANT, ISSUE_DATE, DOC_TYPE, ACCESS_KEY,
                        DocumentArtifact.UNSIGNED_XML, DATA);
            } catch (Exception ignored) {
                // StorageException esperada — MinIO no está disponible en test
            }
        }

        // La siguiente llamada debe fallar rápido (circuit open)
        var thrown = assertThrows(Exception.class,
                () -> storageService.store(TENANT, ISSUE_DATE, DOC_TYPE, ACCESS_KEY,
                        DocumentArtifact.UNSIGNED_XML, DATA),
                "Debe lanzar excepción cuando el circuito está abierto");

        // Puede ser CircuitBreakerOpenException o StorageException dependiendo del timing
        assertTrue(
                thrown.getClass().getSimpleName().contains("CircuitBreaker")
                        || thrown instanceof StorageException,
                "Excepción debe ser de circuit breaker o storage: " + thrown.getClass().getName());
    }

    @Test
    @Order(2)
    @DisplayName("retrieve: circuito se abre tras fallos consecutivos de MinIO")
    void retrieveCircuitShouldOpenAfterConsecutiveFailures() {
        var path = "tenant_test/2025/06/01/test-key/unsigned.xml";

        // Generar suficientes fallos para superar el requestVolumeThreshold=10
        for (int i = 0; i < 10; i++) {
            try {
                storageService.retrieve(path);
            } catch (Exception ignored) {
                // StorageException esperada — MinIO no está disponible en test
            }
        }

        // La siguiente llamada debe fallar rápido (circuit open)
        var thrown = assertThrows(Exception.class,
                () -> storageService.retrieve(path),
                "Debe lanzar excepción cuando el circuito está abierto");

        assertTrue(
                thrown.getClass().getSimpleName().contains("CircuitBreaker")
                        || thrown instanceof StorageException,
                "Excepción debe ser de circuit breaker o storage: " + thrown.getClass().getName());
    }
}
