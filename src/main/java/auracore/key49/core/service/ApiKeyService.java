package auracore.key49.core.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Servicio para generación y validación de API keys.
 * Formato: fec_test_XXXXXXXXXXXXXXXXXXXXXXXX o fec_live_XXXXXXXXXXXXXXXXXXXXXXXX
 */
public final class ApiKeyService {

    public static final String PREFIX_TEST = "fec_test_";
    public static final String PREFIX_LIVE = "fec_live_";
    private static final int RAW_KEY_LENGTH = 24;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    private ApiKeyService() {
    }

    /**
     * Genera una API key con el prefijo dado y retorna el par (rawKey, hash).
     */
    public static GeneratedKey generate(String prefix) {
        if (!PREFIX_TEST.equals(prefix) && !PREFIX_LIVE.equals(prefix)) {
            throw new IllegalArgumentException("Invalid API key prefix: " + prefix);
        }
        var rawSuffix = generateRandomString(RAW_KEY_LENGTH);
        var rawKey = prefix + rawSuffix;
        var hash = sha256(rawKey);
        return new GeneratedKey(rawKey, hash, prefix.substring(0, prefix.length() - 1));
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
     * Extrae el prefijo de una API key raw (ej: "fec_test" de "fec_test_XXXX").
     */
    public static String extractPrefix(String rawKey) {
        if (rawKey == null || rawKey.length() < PREFIX_TEST.length()) {
            return null;
        }
        if (rawKey.startsWith(PREFIX_TEST)) {
            return PREFIX_TEST.substring(0, PREFIX_TEST.length() - 1);
        }
        if (rawKey.startsWith(PREFIX_LIVE)) {
            return PREFIX_LIVE.substring(0, PREFIX_LIVE.length() - 1);
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
     * @param rawKey    la API key completa (solo se muestra una vez al usuario)
     * @param hash      el hash SHA-256 para almacenar en BD
     * @param keyPrefix el prefijo sin trailing underscore (ej: "fec_test")
     */
    public record GeneratedKey(String rawKey, String hash, String keyPrefix) {
    }
}
