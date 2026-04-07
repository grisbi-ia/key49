package auracore.key49.admin.alert.rules;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import auracore.key49.admin.alert.AlertResult;
import auracore.key49.admin.alert.AlertRule;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Regla de alerta: Tasa de error alta.
 *
 * <p>Lee los contadores Micrometer {@code key49.documents.processed},
 * {@code key49.documents.rejected} y {@code key49.documents.failed}.
 * Dispara alerta si {@code (rejected + failed) / total > umbral%}.</p>
 */
@ApplicationScoped
public class ErrorRateAlertRule implements AlertRule {

    static final String NAME = "error_rate";
    private static final Logger log = Logger.getLogger(ErrorRateAlertRule.class);

    @Inject
    MeterRegistry registry;

    @ConfigProperty(name = "key49.alerts.error-rate-percent", defaultValue = "5")
    int errorRatePercent;

    @Override
    public AlertResult evaluate() {
        try {
            var processed = getCounterValue("key49.documents.processed");
            var rejected = getCounterValue("key49.documents.rejected");
            var failed = getCounterValue("key49.documents.failed");

            var total = processed + rejected + failed;
            if (total == 0) {
                return AlertResult.ok(NAME, "Sin documentos procesados aún");
            }

            var errors = rejected + failed;
            var ratePercent = (errors * 100.0) / total;

            if (ratePercent > errorRatePercent) {
                return AlertResult.firing(NAME,
                        "Tasa de error %.1f%% (%.0f errores de %.0f total, umbral: %d%%)"
                                .formatted(ratePercent, errors, total, errorRatePercent));
            }

            return AlertResult.ok(NAME,
                    "Tasa de error %.1f%% (%.0f errores de %.0f total, umbral: %d%%)"
                            .formatted(ratePercent, errors, total, errorRatePercent));
        } catch (Exception e) {
            log.warnf("Error rate alert eval failed: %s", e.getMessage());
            return AlertResult.ok(NAME, "No se pudo evaluar: " + e.getMessage());
        }
    }

    private double getCounterValue(String name) {
        var counter = registry.find(name).counter();
        return counter != null ? counter.count() : 0.0;
    }
}
