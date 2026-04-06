package auracore.key49.api.dto;

import java.util.List;

/**
 * Wrapper para respuestas paginadas.
 *
 * <pre>{@code
 * {
 *   "data": [ ... ],
 *   "meta": { "total": 150, "page": 1, "per_page": 20, "total_pages": 8 }
 * }
 * }</pre>
 */
public record PagedResponse<T>(List<T> data, PageMeta meta) {

    public record PageMeta(long total, int page, int perPage, int totalPages) {
    }

    public static <T> PagedResponse<T> of(List<T> data, long total, int page, int perPage) {
        int totalPages = perPage > 0 ? (int) Math.ceil((double) total / perPage) : 0;
        return new PagedResponse<>(data, new PageMeta(total, page, perPage, totalPages));
    }
}
