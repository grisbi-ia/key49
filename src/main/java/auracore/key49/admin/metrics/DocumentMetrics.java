package auracore.key49.admin.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Métricas custom de Micrometer para documentos electrónicos, dimensionadas por
 * tenant.
 *
 * <p>
 * Registra contadores y timers que se exponen en /q/metrics (Prometheus). Cada
 * métrica incluye el tag {@code tenant} con el schema name del tenant para
 * permitir desglose por cliente.</p>
 *
 * <p>
 * Los consumers del pipeline (sign, send, authorize, notify) invocan estos
 * métodos para incrementar contadores y registrar latencias.</p>
 */
@ApplicationScoped
public class DocumentMetrics {

    private final MeterRegistry registry;

    // Counters globales (sin tenant) para compatibilidad con dashboards existentes
    private final Counter processedCounter;
    private final Counter rejectedCounter;
    private final Counter failedCounter;

    @Inject
    public DocumentMetrics(MeterRegistry registry) {
        this.registry = registry;

        this.processedCounter = Counter.builder("key49.documents.processed")
                .description("Total documents processed successfully (AUTHORIZED)")
                .register(registry);

        this.rejectedCounter = Counter.builder("key49.documents.rejected")
                .description("Total documents rejected by SRI (business errors)")
                .register(registry);

        this.failedCounter = Counter.builder("key49.documents.failed")
                .description("Total documents that failed after retries exhausted")
                .register(registry);
    }

    // ── Counters globales (backward-compatible) ──
    public void incrementProcessed() {
        processedCounter.increment();
    }

    public void incrementRejected() {
        rejectedCounter.increment();
    }

    public void incrementFailed() {
        failedCounter.increment();
    }

    // ── Counters dimensionados por tenant ──
    /**
     * Incrementa contador de documentos creados, dimensionado por tenant y
     * tipo.
     */
    public void recordCreated(String tenant, String documentType) {
        registry.counter("key49.documents.created",
                "tenant", tenant, "type", documentType).increment();
    }

    /**
     * Incrementa contador de documentos autorizados, dimensionado por tenant.
     */
    public void recordAuthorized(String tenant) {
        registry.counter("key49.documents.authorized", "tenant", tenant).increment();
        processedCounter.increment();
    }

    /**
     * Incrementa contador de documentos rechazados por SRI, dimensionado por
     * tenant.
     */
    public void recordRejected(String tenant, String reason) {
        registry.counter("key49.documents.failed",
                "tenant", tenant, "reason", reason != null ? reason : "UNKNOWN").increment();
        rejectedCounter.increment();
    }

    /**
     * Incrementa contador de documentos fallidos (reintentos agotados),
     * dimensionado por tenant.
     */
    public void recordFailed(String tenant) {
        registry.counter("key49.documents.failed",
                "tenant", tenant, "reason", "RETRIES_EXHAUSTED").increment();
        failedCounter.increment();
    }

    // ── Timers SRI dimensionados por tenant ──
    /**
     * Retorna timer para medir latencia SOAP Recepción por tenant.
     */
    public Timer sriReceptionTimer(String tenant) {
        return Timer.builder("key49.sri.latency")
                .tag("tenant", tenant)
                .tag("operation", "reception")
                .description("Duration of SOAP requests to SRI Recepción")
                .register(registry);
    }

    /**
     * Retorna timer para medir latencia SOAP Autorización por tenant.
     */
    public Timer sriAuthorizationTimer(String tenant) {
        return Timer.builder("key49.sri.latency")
                .tag("tenant", tenant)
                .tag("operation", "authorization")
                .description("Duration of SOAP requests to SRI Autorización")
                .register(registry);
    }

    // ── Counters de notificación ──
    /**
     * Incrementa contador de emails enviados, dimensionado por tenant.
     */
    public void recordEmailSent(String tenant) {
        registry.counter("key49.email.sent", "tenant", tenant).increment();
    }

    /**
     * Incrementa contador de emails fallidos, dimensionado por tenant.
     */
    public void recordEmailFailed(String tenant) {
        registry.counter("key49.email.failed", "tenant", tenant).increment();
    }

    /**
     * Incrementa contador de webhooks despachados, dimensionado por tenant.
     */
    public void recordWebhookDispatched(String tenant) {
        registry.counter("key49.webhook.dispatched", "tenant", tenant).increment();
    }

    // ── Backward-compatible timers (sin tenant, deprecated) ──
    /**
     * @deprecated Usar {@link #sriReceptionTimer(String)} con tenant.
     */
    @Deprecated(forRemoval = true)
    public Timer sriReceptionTimer() {
        return Timer.builder("key49.sri.request.duration")
                .tag("operation", "reception")
                .register(registry);
    }

    /**
     * @deprecated Usar {@link #sriAuthorizationTimer(String)} con tenant.
     */
    @Deprecated(forRemoval = true)
    public Timer sriAuthorizationTimer() {
        return Timer.builder("key49.sri.request.duration")
                .tag("operation", "authorization")
                .register(registry);
    }
}
