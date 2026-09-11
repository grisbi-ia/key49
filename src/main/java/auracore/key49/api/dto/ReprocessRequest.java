package auracore.key49.api.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Filtros para el reproceso en lote de documentos.
 *
 * @param statuses      estados a reprocesar (por defecto: FAILED, RECEIVED, RETRY)
 * @param documentTypes tipos de documento a incluir (opcional)
 * @param dateFrom      fecha de emisión desde (opcional)
 * @param dateTo        fecha de emisión hasta (opcional)
 * @param limit         máximo de documentos a reprocesar (por defecto 500, máx 2000)
 */
public record ReprocessRequest(
        List<String> statuses,
        List<String> documentTypes,
        LocalDate dateFrom,
        LocalDate dateTo,
        Integer limit
) {
}
