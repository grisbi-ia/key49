package auracore.key49.notify.webhook;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class WebhookUrlValidatorTest {

    @Nested
    @DisplayName("URLs inválidas")
    class InvalidUrls {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"  ", "\t"})
        @DisplayName("rechaza URL nula o vacía")
        void rejectsNullOrBlank(String url) {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> WebhookUrlValidator.validate(url));
            assertTrue(ex.getMessage().contains("required"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"not-a-url", "://missing-scheme", "ftp://example.com/hook"})
        @DisplayName("rechaza esquemas no HTTP/HTTPS")
        void rejectsInvalidSchemes(String url) {
            assertThrows(IllegalArgumentException.class,
                    () -> WebhookUrlValidator.validate(url));
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "http://localhost/hook",
            "https://localhost:8443/hook",
            "http://myhost.local/hook",
            "http://internal.service.internal/hook",
            "http://[::1]/hook"
        })
        @DisplayName("bloquea hosts locales/internos")
        void blocksLocalHosts(String url) {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> WebhookUrlValidator.validate(url));
            assertTrue(ex.getMessage().contains("blocked"));
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "http://127.0.0.1/hook",
            "http://127.0.0.1:8080/hook"
        })
        @DisplayName("bloquea direcciones loopback")
        void blocksLoopback(String url) {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> WebhookUrlValidator.validate(url));
            assertTrue(ex.getMessage().contains("blocked"));
        }

        @Test
        @DisplayName("rechaza host que no resuelve DNS")
        void rejectsUnresolvableHost() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> WebhookUrlValidator.validate("https://this-host-does-not-exist-xyz123.example/hook"));
            assertTrue(ex.getMessage().contains("cannot be resolved"));
        }
    }

    @Nested
    @DisplayName("URLs válidas")
    class ValidUrls {

        @ParameterizedTest
        @ValueSource(strings = {
            "https://webhook.site/abc-123",
            "https://example.com/webhook",
            "http://example.com:8080/webhook/path"
        })
        @DisplayName("permite URLs públicas")
        void allowsPublicUrls(String url) {
            assertDoesNotThrow(() -> WebhookUrlValidator.validate(url));
        }
    }
}
