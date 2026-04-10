package auracore.key49.core.service;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests unitarios para AuditService.resolveIp().
 */




class AuditServiceTest {

    @Nested
    @DisplayName("resolveIp")
    class ResolveIp {

        @Test
        @DisplayName("devuelve 'unknown' si request es null")
        void returnsUnknownForNullRequest() {
            assertEquals("unknown", AuditService.resolveIp(null));
        }

        @Test
        @DisplayName("extrae primera IP de X-Forwarded-For")
        void extractsFirstIpFromXForwardedFor() {
            var request = mock(HttpServerRequest.class);
            when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.50, 70.41.3.18, 150.172.238.178");
            assertEquals("203.0.113.50", AuditService.resolveIp(request));
        }

        @Test
        @DisplayName("usa IP única de X-Forwarded-For")
        void extractsSingleIpFromXForwardedFor() {
            var request = mock(HttpServerRequest.class);
            when(request.getHeader("X-Forwarded-For")).thenReturn("192.168.1.100");
            assertEquals("192.168.1.100", AuditService.resolveIp(request));
        }

        @Test
        @DisplayName("ignora X-Forwarded-For vacío y usa remoteAddress")
        void fallsBackToRemoteAddressWhenHeaderBlank() {
            var request = mock(HttpServerRequest.class);
            when(request.getHeader("X-Forwarded-For")).thenReturn("   ");
            var addr = mock(SocketAddress.class);
            when(addr.host()).thenReturn("10.0.0.1");
            when(request.remoteAddress()).thenReturn(addr);
            assertEquals("10.0.0.1", AuditService.resolveIp(request));
        }

        @Test
        @DisplayName("usa remoteAddress cuando no hay X-Forwarded-For")
        void usesRemoteAddressWhenNoHeader() {
            var request = mock(HttpServerRequest.class);
            when(request.getHeader("X-Forwarded-For")).thenReturn(null);
            var addr = mock(SocketAddress.class);
            when(addr.host()).thenReturn("127.0.0.1");
            when(request.remoteAddress()).thenReturn(addr);
            assertEquals("127.0.0.1", AuditService.resolveIp(request));
        }

        @Test
        @DisplayName("devuelve 'unknown' si no hay header ni remoteAddress")
        void returnsUnknownWhenNoAddressAvailable() {
            var request = mock(HttpServerRequest.class);
            when(request.getHeader("X-Forwarded-For")).thenReturn(null);
            when(request.remoteAddress()).thenReturn(null);
            assertEquals("unknown", AuditService.resolveIp(request));
        }

        @Test
        @DisplayName("trim de espacios en X-Forwarded-For")
        void trimsWhitespaceFromForwardedHeader() {
            var request = mock(HttpServerRequest.class);
            when(request.getHeader("X-Forwarded-For")).thenReturn("  203.0.113.50 , 10.0.0.1 ");
            assertEquals("203.0.113.50", AuditService.resolveIp(request));
        }
    }
}
