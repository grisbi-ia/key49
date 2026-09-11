package auracore.key49.api.dto;

import java.util.Map;

/**
 * Resultado de un reproceso en lote.
 *
 * @param matched  documentos que coincidieron con los filtros
 * @param queued   documentos reencolados para reprocesar
 * @param skipped  documentos omitidos (estado no reprocesable)
 * @param byStatus desglose de los reencolados por estado original
 */
public record ReprocessResult(
        int matched,
        int queued,
        int skipped,
        Map<String, Integer> byStatus
) {
}
