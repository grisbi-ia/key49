package auracore.key49.api.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Respuesta del dashboard de métricas del tenant. Incluye conteos de documentos
 * para hoy y el mes actual, estado del certificado y última factura procesada.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MetricsSummaryResponse(
        PeriodSnapshot today,
        PeriodSnapshot month,
        long certificateExpiresInDays,
        Instant lastInvoiceAt) {

    public record PeriodSnapshot(
            long total,
            long authorized,
            long rejected,
            long pending,
            long failed) {

    }
}
