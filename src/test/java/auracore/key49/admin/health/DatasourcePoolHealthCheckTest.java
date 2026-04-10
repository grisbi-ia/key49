package auracore.key49.admin.health;

import org.eclipse.microprofile.health.HealthCheckResponse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * Test de integración para DatasourcePoolHealthCheck.
 *
 * <p>
 * Verifica que el health check reporta UP con las métricas del pool Agroal y
 * que la configuración de pool se aplica correctamente.</p>
 */
@QuarkusTest
class DatasourcePoolHealthCheckTest {

    @Inject
    javax.sql.DataSource dataSource;

    private DatasourcePoolHealthCheck newHealthCheck() {
        var hc = new DatasourcePoolHealthCheck();
        hc.dataSource = dataSource;
        return hc;
    }

    @Test
    void shouldReturnUpWithPoolMetrics() {
        var response = newHealthCheck().call();

        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
        assertEquals("Datasource pool", response.getName());

        var data = response.getData().orElseThrow();
        assertTrue(data.containsKey("max_size"), "Should report max_size");
        assertTrue(data.containsKey("min_size"), "Should report min_size");
        assertTrue(data.containsKey("active_count"), "Should report active_count");
        assertTrue(data.containsKey("available_count"), "Should report available_count");
        assertTrue(data.containsKey("awaiting_count"), "Should report awaiting_count");
    }

    @Test
    void shouldHaveConfiguredPoolSize() {
        assertTrue(dataSource instanceof AgroalDataSource,
                "DataSource should be Agroal instance");

        var agroal = (AgroalDataSource) dataSource;
        var config = agroal.getConfiguration().connectionPoolConfiguration();
        assertTrue(config.maxSize() > 0, "max-size should be positive");
        assertTrue(config.minSize() >= 0, "min-size should be non-negative");
    }

    @Test
    void shouldReportZeroAwaitingWhenIdle() {
        var response = newHealthCheck().call();
        var data = response.getData().orElseThrow();

        assertEquals(0L, data.get("awaiting_count"),
                "No connections should be awaiting when pool is idle");
    }

    @Test
    void shouldValidateConnectionWithSelectOne() {
        var response = newHealthCheck().call();
        assertEquals(HealthCheckResponse.Status.UP, response.getStatus(),
                "Pool should be UP when database is reachable");
    }

    @Test
    void shouldHaveAcquisitionTimeoutConfigured() {
        var agroal = (AgroalDataSource) dataSource;
        var config = agroal.getConfiguration().connectionPoolConfiguration();
        assertTrue(config.acquisitionTimeout().toSeconds() > 0,
                "acquisition-timeout should be configured");
    }
}
