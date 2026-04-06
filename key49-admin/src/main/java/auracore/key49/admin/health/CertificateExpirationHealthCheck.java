package auracore.key49.admin.health;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;
import org.jboss.logging.Logger;

import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Health check de readiness que verifica la expiración de certificados de
 * tenants.
 *
 * <p>
 * Reporta warning si algún tenant activo tiene un certificado que expira en
 * menos de 30 días. No marca DOWN el servicio, pero incluye datos de los
 * certificados próximos a vencer.</p>
 */
@Readiness
@ApplicationScoped
public class CertificateExpirationHealthCheck implements HealthCheck {

    private static final Logger log = Logger.getLogger(CertificateExpirationHealthCheck.class);
    private static final int WARNING_DAYS = 30;

    @Inject
    PgPool pgPool;

    @Override
    public HealthCheckResponse call() {
        try {
            var threshold = Instant.now().plus(WARNING_DAYS, ChronoUnit.DAYS);
            var rows = pgPool.preparedQuery(
                    "SELECT legal_name, certificate_expiration FROM public.tenants "
                    + "WHERE status = 'active' AND certificate_expiration IS NOT NULL "
                    + "AND certificate_expiration < $1 "
                    + "ORDER BY certificate_expiration ASC")
                    .execute(Tuple.of(threshold.atOffset(java.time.ZoneOffset.UTC)))
                    .await().atMost(java.time.Duration.ofSeconds(5));

            HealthCheckResponseBuilder builder = HealthCheckResponse.named("Certificate expiration");
            int expiringCount = 0;

            for (Row row : rows) {
                expiringCount++;
                var legalName = row.getString("legal_name");
                var expiration = row.getOffsetDateTime("certificate_expiration");
                var daysLeft = ChronoUnit.DAYS.between(Instant.now(), expiration.toInstant());
                builder.withData(legalName, daysLeft + " days remaining");
            }

            if (expiringCount > 0) {
                builder.withData("warning", expiringCount + " certificate(s) expiring within " + WARNING_DAYS + " days");
                log.warnf("Certificate expiration warning: %d certificates expiring within %d days", expiringCount, WARNING_DAYS);
            }

            builder.withData("expiring_count", expiringCount);
            builder.up();
            return builder.build();
        } catch (Exception e) {
            log.errorf(e, "Certificate expiration health check failed");
            return HealthCheckResponse.named("Certificate expiration")
                    .up()
                    .withData("error", e.getMessage())
                    .build();
        }
    }
}
