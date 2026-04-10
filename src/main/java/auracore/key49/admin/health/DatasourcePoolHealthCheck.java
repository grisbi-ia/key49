package auracore.key49.admin.health;

import javax.sql.DataSource;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Health check de readiness para el pool de conexiones PostgreSQL (Agroal).
 *
 * <p>
 * Verifica que el pool puede obtener una conexión y reporta métricas clave:
 * activas, disponibles, tamaño máximo y awaiting.</p>
 */
@Readiness
@ApplicationScoped
public class DatasourcePoolHealthCheck implements HealthCheck {

    @Inject
    DataSource dataSource;

    @Override
    public HealthCheckResponse call() {
        try {
            // Verificar conectividad real
            try (var conn = dataSource.getConnection(); var stmt = conn.prepareStatement("SELECT 1"); var rs = stmt.executeQuery()) {
                rs.next();
            }

            var builder = HealthCheckResponse.named("Datasource pool").up();

            if (dataSource instanceof AgroalDataSource agroal) {
                var metrics = agroal.getMetrics();
                var config = agroal.getConfiguration().connectionPoolConfiguration();
                builder.withData("max_size", config.maxSize())
                        .withData("min_size", config.minSize())
                        .withData("active_count", metrics.activeCount())
                        .withData("available_count", metrics.availableCount())
                        .withData("awaiting_count", metrics.awaitingCount());
            }

            return builder.build();
        } catch (Exception e) {
            return HealthCheckResponse.named("Datasource pool")
                    .down()
                    .withData("error", e.getMessage())
                    .build();
        }
    }
}
