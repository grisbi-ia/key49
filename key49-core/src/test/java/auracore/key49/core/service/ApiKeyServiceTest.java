package auracore.key49.core.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

class ApiKeyServiceTest {

    @Test
    void shouldGenerateTestKey() {
        var result = ApiKeyService.generate(ApiKeyService.PREFIX_TEST);

        assertTrue(result.rawKey().startsWith("fec_test_"));
        assertEquals("fec_test", result.keyPrefix());
        assertNotNull(result.hash());
        assertEquals(64, result.hash().length()); // SHA-256 hex = 64 chars
    }

    @Test
    void shouldGenerateLiveKey() {
        var result = ApiKeyService.generate(ApiKeyService.PREFIX_LIVE);

        assertTrue(result.rawKey().startsWith("fec_live_"));
        assertEquals("fec_live", result.keyPrefix());
        assertNotNull(result.hash());
    }

    @Test
    void shouldRejectInvalidPrefix() {
        assertThrows(IllegalArgumentException.class,
                () -> ApiKeyService.generate("invalid_"));
    }

    @Test
    void shouldGenerateUniqueKeys() {
        var key1 = ApiKeyService.generate(ApiKeyService.PREFIX_TEST);
        var key2 = ApiKeyService.generate(ApiKeyService.PREFIX_TEST);

        assertNotEquals(key1.rawKey(), key2.rawKey());
        assertNotEquals(key1.hash(), key2.hash());
    }

    @Test
    void shouldProduceDeterministicHash() {
        var rawKey = "fec_test_abcdefghijklmnopqrstuvwx";
        var hash1 = ApiKeyService.sha256(rawKey);
        var hash2 = ApiKeyService.sha256(rawKey);

        assertEquals(hash1, hash2);
    }

    @Test
    void shouldHashMatchGenerated() {
        var generated = ApiKeyService.generate(ApiKeyService.PREFIX_TEST);
        var recomputed = ApiKeyService.sha256(generated.rawKey());

        assertEquals(generated.hash(), recomputed);
    }

    @ParameterizedTest
    @ValueSource(strings = {"fec_test_abc123", "fec_test_XXXXX"})
    void shouldExtractTestPrefix(String key) {
        assertEquals("fec_test", ApiKeyService.extractPrefix(key));
    }

    @ParameterizedTest
    @ValueSource(strings = {"fec_live_abc123", "fec_live_XXXXX"})
    void shouldExtractLivePrefix(String key) {
        assertEquals("fec_live", ApiKeyService.extractPrefix(key));
    }

    @Test
    void shouldReturnNullForInvalidPrefix() {
        assertNull(ApiKeyService.extractPrefix("invalid_key"));
        assertNull(ApiKeyService.extractPrefix(null));
        assertNull(ApiKeyService.extractPrefix("short"));
    }

    @Test
    void shouldDetectExpiredKey() {
        var expired = Instant.now().minus(1, ChronoUnit.HOURS);
        assertTrue(ApiKeyService.isExpired(expired));
    }

    @Test
    void shouldNotDetectFutureExpiration() {
        var future = Instant.now().plus(1, ChronoUnit.HOURS);
        assertFalse(ApiKeyService.isExpired(future));
    }

    @Test
    void shouldNotExpireWhenNull() {
        assertFalse(ApiKeyService.isExpired(null));
    }

    @Test
    void shouldGenerateKeyWithCorrectLength() {
        var result = ApiKeyService.generate(ApiKeyService.PREFIX_TEST);
        // Prefix (9) + random (24) = 33 chars
        assertEquals(33, result.rawKey().length());
    }
}
