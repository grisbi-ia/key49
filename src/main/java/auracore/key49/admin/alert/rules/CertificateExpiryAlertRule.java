package auracore.key49.admin.alert.rules;

import auracore.key49.admin.alert.AlertResult;
import auracore.key49.admin.alert.AlertRule;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;

/**
 * Regla de alerta: Certificados próximos a vencer.
 *
 * <p>Consulta la tabla {@code public.tenants} para detectar certificados
 * que expiran dentro de {@code key49.alerts.cert-expiry-days} días.
 * Incluye la lista de tenants afectados en el resumen.</p>
 */
@ApplicationScoped
public class CertificateExpiryAlertRule implements AlertRule {

    static final String NAME = "cert_expiry";
    private static final Logger log = Logger.getLogger(CertificateExpiryAlertRule.class);

    @Inject
    DataSource dataSource;

    @ConfigProperty(name = "key49.alerts.cert-expiry-days", defaultValue = "30")
    int certExpiryDays;

    @Override
    public AlertResult evaluate() {
        try {
            var threshold = Instant.now().plus(certExpiryDays, ChronoUnit.DAYS);

            var expiring = new ArrayList<String>();
            try (var conn = dataSource.getConnection();
                 var stmt = conn.prepareStatement(
                         "SELECT legal_name, certificate_expiration FROM public.tenants " +
                                 "WHERE status = 'active' AND certificate_expiration IS NOT NULL " +
                                 "AND certificate_expiration < ? " +
                                 "ORDER BY certificate_expiration ASC")) {

                stmt.setTimestamp(1, Timestamp.from(threshold));
                try (var rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        var name = rs.getString("legal_name");
                        var exp = rs.getTimestamp("certificate_expiration").toInstant();
                        var daysLeft = ChronoUnit.DAYS.between(Instant.now(), exp);
                        expiring.add("%s (%d días)".formatted(name, daysLeft));
                    }
                }
            }

            if (!expiring.isEmpty()) {
                return AlertResult.firing(NAME,
                        "%d certificado(s) por vencer: %s".formatted(
                                expiring.size(), String.join(", ", expiring)));
            }

            return AlertResult.ok(NAME,
                    "Todos los certificados vigentes (umbral: %d días)".formatted(certExpiryDays));
        } catch (Exception e) {
            log.warnf("Certificate expiry alert eval failed: %s", e.getMessage());
            return AlertResult.ok(NAME, "No se pudo evaluar: " + e.getMessage());
        }
    }
}
