package auracore.key49.admin.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;

/**
 * Test de integración para verificar la configuración de virtual threads,
 * thread pool y event loops en Quarkus.
 */
@QuarkusTest
class ThreadPoolConfigTest {

    @ConfigProperty(name = "quarkus.virtual-threads.enabled")
    boolean virtualThreadsEnabled;

    @ConfigProperty(name = "quarkus.thread-pool.max-threads")
    int maxThreads;

    @Inject
    javax.sql.DataSource dataSource;

    @Test
    void shouldHaveVirtualThreadsEnabled() {
        assertTrue(virtualThreadsEnabled, "Virtual threads should be enabled");
    }

    @Test
    void shouldHaveThreadPoolMaxConfigured() {
        assertTrue(maxThreads > 0, "thread-pool.max-threads should be positive");
    }

    @Test
    void shouldHandleConcurrentRequests() throws InterruptedException {
        int concurrency = 20;
        var latch = new CountDownLatch(concurrency);
        List<Integer> statusCodes = new CopyOnWriteArrayList<>();

        for (int i = 0; i < concurrency; i++) {
            Thread.startVirtualThread(() -> {
                try {
                    int status = RestAssured.given()
                            .get("/q/health/ready")
                            .statusCode();
                    statusCodes.add(status);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS),
                "All concurrent requests should complete within 30s");
        assertEquals(concurrency, statusCodes.size(),
                "All requests should have responded");
        assertTrue(statusCodes.stream().allMatch(s -> s == 200 || s == 503),
                "All responses should be valid health check responses");
    }

    @Test
    void shouldHandleConcurrentDatabaseAccess() throws InterruptedException {
        int concurrency = 20;
        var latch = new CountDownLatch(concurrency);
        List<Boolean> results = new CopyOnWriteArrayList<>();

        for (int i = 0; i < concurrency; i++) {
            Thread.startVirtualThread(() -> {
                try (var conn = dataSource.getConnection(); var stmt = conn.prepareStatement("SELECT 1"); var rs = stmt.executeQuery()) {
                    results.add(rs.next());
                } catch (Exception e) {
                    results.add(false);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS),
                "All concurrent DB requests should complete within 30s");
        assertEquals(concurrency, results.size(),
                "All DB requests should have completed");
        assertTrue(results.stream().allMatch(r -> r),
                "All DB requests should succeed (no thread starvation)");
    }
}
