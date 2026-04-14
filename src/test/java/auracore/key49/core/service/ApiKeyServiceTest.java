package auracore.key49.core.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

class ApiKeyServiceTest {

    @Test
    void shouldGenerateKey() {
        var result = ApiKeyService.generate();

        assertTrue(result.rawKey().startsWith("k49_"));
        assertEquals("k49", result.keyPrefix());
        assertNotNull(result.hash());
        assertEquals(64, result.hash().length()); // SHA-256 hex = 64 chars
    }

    @Test
    void shouldRejectInvalidPrefix() {
        // generate() no longer takes a prefix, so just check extractPrefix
        assertNull(ApiKeyService.extractPrefix("invalid_key"));
    }

    @Test
    void shouldGenerateUniqueKeys() {
        var key1 = ApiKeyService.generate();
        var key2 = ApiKeyService.generate();

        assertNotEquals(key1.rawKey(), key2.rawKey());
        assertNotEquals(key1.hash(), key2.hash());
    }

    @Test
    void shouldProduceDeterministicHash() {
        var rawKey = "k49_abcdefghijklmnopqrstuvwx";
        var hash1 = ApiKeyService.sha256(rawKey);
        var hash2 = ApiKeyService.sha256(rawKey);

        assertEquals(hash1, hash2);
    }

    @Test
    void shouldHashMatchGenerated() {
        var generated = ApiKeyService.generate();
        var recomputed = ApiKeyService.sha256(generated.rawKey());

        assertEquals(generated.hash(), recomputed);
    }

    @ParameterizedTest
    @ValueSource(strings = {"k49_abc123", "k49_XXXXX"})
    void shouldExtractPrefix(String key) {
        assertEquals("k49", ApiKeyService.extractPrefix(key));
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
        var result = ApiKeyService.generate();
        // Prefix (4) + random (24) = 28 chars
        assertEquals(28, result.rawKey().length());
    }
}
