package auracore.key49.core.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Servicio para generación y validación de API keys. Formato:
 * k49_XXXXXXXXXXXXXXXXXXXXXXXX (prefijo único, environment del tenant es la
 * fuente de verdad).
 */
public final class ApiKeyService {

    public static final String PREFIX = "k49_";
    public static final String KEY_PREFIX_STORED = "k49";
    private static final int RAW_KEY_LENGTH = 24;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    private ApiKeyService() {
    }

    /**
     * Genera una API key con prefijo k49_ y retorna el par (rawKey, hash).
     */
    public static GeneratedKey generate() {
        var rawSuffix = generateRandomString(RAW_KEY_LENGTH);
        var rawKey = PREFIX + rawSuffix;
        var hash = sha256(rawKey);
        return new GeneratedKey(rawKey, hash, KEY_PREFIX_STORED);
    }

    /**
     * Calcula el hash SHA-256 de una API key raw.
     */
    public static String sha256(String rawKey) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hashBytes = digest.digest(rawKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Extrae el prefijo de una API key raw (ej: "k49" de "k49_XXXX").
     */
    public static String extractPrefix(String rawKey) {
        if (rawKey == null || rawKey.length() < PREFIX.length()) {
            return null;
        }
        if (rawKey.startsWith(PREFIX)) {
            return KEY_PREFIX_STORED;
        }
        return null;
    }

    /**
     * Valida que una API key no haya expirado.
     */
    public static boolean isExpired(Instant expiresAt) {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    private static String generateRandomString(int length) {
        var sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET[SECURE_RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }

    /**
     * Resultado de la generación de una API key.
     *
     * @param rawKey la API key completa (solo se muestra una vez al usuario)
     * @param hash el hash SHA-256 para almacenar en BD
     * @param keyPrefix el prefijo sin trailing underscore (ej: "k49")
     */
    public record GeneratedKey(String rawKey, String hash, String keyPrefix) {

    }
}
