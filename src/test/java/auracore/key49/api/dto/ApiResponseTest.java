package auracore.key49.api.dto;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ApiResponseTest {

    @Test
    void of_createsWithRequestIdAndTimestamp() {
        var response = ApiResponse.of("test-data", "req_abc123");

        assertEquals("test-data", response.data());
        assertNotNull(response.meta());
        assertEquals("req_abc123", response.meta().requestId());
        assertNotNull(response.meta().timestamp());
    }

    @Test
    void pagedResponse_calculatesCorrectPages() {
        var response = PagedResponse.of(List.of("a", "b"), 50, 1, 20);

        assertEquals(2, response.data().size());
        assertEquals(50, response.meta().total());
        assertEquals(1, response.meta().page());
        assertEquals(20, response.meta().perPage());
        assertEquals(3, response.meta().totalPages()); // ceil(50/20) = 3
    }

    @Test
    void pagedResponse_singlePage() {
        var response = PagedResponse.of(List.of("a"), 1, 1, 20);

        assertEquals(1, response.meta().totalPages());
    }

    @Test
    void pagedResponse_exactPages() {
        var response = PagedResponse.of(List.of(), 40, 3, 20);

        assertEquals(2, response.meta().totalPages()); // 40/20 = 2 exactly
    }

    @Test
    void pagedResponse_zeroPerPage_handledSafely() {
        var response = PagedResponse.of(List.of(), 10, 1, 0);

        assertEquals(0, response.meta().totalPages());
    }
}
