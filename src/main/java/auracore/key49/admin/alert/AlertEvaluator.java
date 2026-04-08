package auracore.key49.admin.alert;

import auracore.key49.admin.alert.rules.CertificateExpiryAlertRule;
import auracore.key49.admin.alert.rules.DlqAlertRule;
import auracore.key49.admin.alert.rules.ErrorRateAlertRule;
import auracore.key49.admin.alert.rules.QueueDepthAlertRule;
import auracore.key49.admin.alert.rules.SriHealthAlertRule;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Orquestador de evaluación de alertas.
 *
 * <p>Ejecuta todas las reglas de alerta periódicamente. Compara el resultado
 * con el estado anterior en Redis y envía notificaciones en transiciones
 * de estado (OK→FIRING, FIRING→OK) y como reminder cada
 * {@code key49.alerts.reminder-interval}.</p>
 */
@ApplicationScoped
public class AlertEvaluator {

    private static final Logger log = Logger.getLogger(AlertEvaluator.class);

    @Inject
    SriHealthAlertRule sriHealthRule;

    @Inject
    DlqAlertRule dlqRule;

    @Inject
    ErrorRateAlertRule errorRateRule;

    @Inject
    QueueDepthAlertRule queueDepthRule;

    @Inject
    AlertStateRepository stateRepository;

    @Inject
    AlertNotifier notifier;

    @ConfigProperty(name = "key49.alerts.enabled", defaultValue = "true")
    boolean alertsEnabled;

    @ConfigProperty(name = "key49.alerts.reminder-interval", defaultValue = "4h")
    Duration reminderInterval;

    /**
     * Evaluación periódica de reglas de infraestructura (cada 60 segundos).
     * Incluye: SRI health, DLQ, error rate, queue depth.
     */
    @Scheduled(every = "60s", identity = "alert-evaluator-infra",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void evaluateInfraRules() {
        if (!alertsEnabled) return;

        var rules = List.<AlertRule>of(sriHealthRule, dlqRule, errorRateRule, queueDepthRule);
        for (var rule : rules) {
            evaluateRule(rule);
        }
    }

    /**
     * Evaluación de certificados (cada 6 horas).
     * Menos frecuente porque los certificados no cambian rápidamente.
     */
    @Inject
    CertificateExpiryAlertRule certExpiryRule;

    @Scheduled(every = "6h", identity = "alert-evaluator-certs",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void evaluateCertRule() {
        if (!alertsEnabled) return;
        evaluateRule(certExpiryRule);
    }

    void evaluateRule(AlertRule rule) {
        try {
            var result = rule.evaluate();
            var previousState = stateRepository.get(result.name());
            var now = Instant.now();

            if (result.firing()) {
                handleFiring(result, previousState, now);
            } else {
                handleOk(result, previousState, now);
            }
        } catch (Exception e) {
            log.errorf(e, "Unexpected error evaluating alert rule");
        }
    }

    private void handleFiring(AlertResult result, AlertState previousState, Instant now) {
        if (previousState == null || !previousState.isFiring()) {
            // Transición OK → FIRING
            notifier.notifyFiring(result);
            stateRepository.save(result.name(), AlertState.firing(now).withNotified(now));
        } else {
            // Ya estaba FIRING — verificar si toca reminder
            var lastNotified = previousState.lastNotified();
            if (lastNotified != null && Duration.between(lastNotified, now).compareTo(reminderInterval) > 0) {
                var activeSince = Duration.between(previousState.since(), now);
                notifier.notifyReminder(result, activeSince);
                stateRepository.save(result.name(),
                        new AlertState(AlertState.FIRING, previousState.since(), now));
            }
            // Si no toca reminder, no hacer nada
        }
    }

    private void handleOk(AlertResult result, AlertState previousState, Instant now) {
        if (previousState != null && previousState.isFiring()) {
            // Transición FIRING → OK
            notifier.notifyResolved(result);
            stateRepository.save(result.name(), AlertState.ok(now));
        }
        // Si ya estaba OK, no hacer nada
    }
}
