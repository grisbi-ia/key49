package auracore.key49.admin.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Métricas custom de Micrometer para documentos electrónicos.
 * Registra contadores y timers que se exponen en /q/metrics (Prometheus).
 *
 * <p>Los consumers del pipeline (sign, send, authorize, notify) invocan
 * estos métodos para incrementar contadores y registrar latencias.</p>
 */
@ApplicationScoped
public class DocumentMetrics {

    private final Counter processedCounter;
    private final Counter rejectedCounter;
    private final Counter failedCounter;
    private final Timer sriReceptionTimer;
    private final Timer sriAuthorizationTimer;

    @Inject
    public DocumentMetrics(MeterRegistry registry) {
        this.processedCounter = Counter.builder("key49.documents.processed")
                .description("Total documents processed successfully (AUTHORIZED)")
                .register(registry);

        this.rejectedCounter = Counter.builder("key49.documents.rejected")
                .description("Total documents rejected by SRI (business errors)")
                .register(registry);

        this.failedCounter = Counter.builder("key49.documents.failed")
                .description("Total documents that failed after retries exhausted")
                .register(registry);

        this.sriReceptionTimer = Timer.builder("key49.sri.request.duration")
                .tag("operation", "reception")
                .description("Duration of SOAP requests to SRI Recepción")
                .register(registry);

        this.sriAuthorizationTimer = Timer.builder("key49.sri.request.duration")
                .tag("operation", "authorization")
                .description("Duration of SOAP requests to SRI Autorización")
                .register(registry);
    }

    public void incrementProcessed() {
        processedCounter.increment();
    }

    public void incrementRejected() {
        rejectedCounter.increment();
    }

    public void incrementFailed() {
        failedCounter.increment();
    }

    public Timer sriReceptionTimer() {
        return sriReceptionTimer;
    }

    public Timer sriAuthorizationTimer() {
        return sriAuthorizationTimer;
    }
}
