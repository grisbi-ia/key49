package auracore.key49.signer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cifra y descifra certificados .p12 y contraseñas usando AES-256-GCM.
 *
 * <p>Conforme a ADR-004: los certificados se almacenan cifrados en PostgreSQL (columna bytea).
 * La clave maestra (256 bits) se obtiene de la variable de entorno {@code KEY49_MASTER_KEY}
 * (codificada en Base64).
 *
 * <p>El formato del bloque cifrado es: {@code [IV (12 bytes)] [ciphertext + GCM auth tag]}.
 * Se genera un IV aleatorio único para cada operación de cifrado.
 */
public final class CertificateEncryptor {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int AES_KEY_LENGTH = 32;

    private CertificateEncryptor() {
    }

    /**
     * Cifra datos binarios (por ejemplo, un archivo .p12) con AES-256-GCM.
     *
     * @param data      datos a cifrar (no nulo, no vacío)
     * @param masterKey clave maestra de 256 bits (32 bytes, no nula)
     * @return bloque cifrado: IV (12 bytes) + ciphertext + GCM auth tag
     * @throws EncryptionException si la clave es inválida o el cifrado falla
     */
    public static byte[] encrypt(byte[] data, byte[] masterKey) {
        validateInputs(data, "data");
        validateMasterKey(masterKey);

        try {
            byte[] iv = generateIv();
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(masterKey, ALGORITHM),
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));

            byte[] ciphertext = cipher.doFinal(data);

            ByteBuffer result = ByteBuffer.allocate(GCM_IV_LENGTH + ciphertext.length);
            result.put(iv);
            result.put(ciphertext);
            return result.array();
        } catch (GeneralSecurityException e) {
            throw new EncryptionException("Failed to encrypt data", e);
        }
    }

    /**
     * Descifra datos previamente cifrados con {@link #encrypt(byte[], byte[])}.
     *
     * @param encrypted bloque cifrado (IV + ciphertext + auth tag)
     * @param masterKey clave maestra de 256 bits (32 bytes)
     * @return datos originales descifrados
     * @throws EncryptionException si la clave es incorrecta, los datos están corruptos,
     *                             o la autenticación GCM falla
     */
    public static byte[] decrypt(byte[] encrypted, byte[] masterKey) {
        validateInputs(encrypted, "encrypted data");
        validateMasterKey(masterKey);

        if (encrypted.length <= GCM_IV_LENGTH) {
            throw new EncryptionException("Encrypted data is too short");
        }

        try {
            ByteBuffer buffer = ByteBuffer.wrap(encrypted);
            byte[] iv = new byte[GCM_IV_LENGTH];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(masterKey, ALGORITHM),
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));

            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new EncryptionException("Failed to decrypt data — wrong key or corrupted data", e);
        }
    }

    /**
     * Cifra una contraseña (char[]) con AES-256-GCM.
     *
     * <p>La contraseña se convierte a bytes UTF-8 para el cifrado. El array de chars intermedio
     * se limpia (zeroed) después de la conversión.
     *
     * @param password  contraseña a cifrar (no nula, no vacía)
     * @param masterKey clave maestra de 256 bits (32 bytes)
     * @return bloque cifrado de la contraseña
     * @throws EncryptionException si el cifrado falla
     */
    public static byte[] encryptPassword(char[] password, byte[] masterKey) {
        if (password == null || password.length == 0) {
            throw new EncryptionException("Password must not be null or empty");
        }
        byte[] passwordBytes = new String(password).getBytes(StandardCharsets.UTF_8);
        try {
            return encrypt(passwordBytes, masterKey);
        } finally {
            Arrays.fill(passwordBytes, (byte) 0);
        }
    }

    /**
     * Descifra una contraseña previamente cifrada con {@link #encryptPassword(char[], byte[])}.
     *
     * @param encrypted bloque cifrado de la contraseña
     * @param masterKey clave maestra de 256 bits (32 bytes)
     * @return contraseña descifrada como char[]
     * @throws EncryptionException si el descifrado falla
     */
    public static char[] decryptPassword(byte[] encrypted, byte[] masterKey) {
        byte[] decrypted = decrypt(encrypted, masterKey);
        try {
            return new String(decrypted, StandardCharsets.UTF_8).toCharArray();
        } finally {
            Arrays.fill(decrypted, (byte) 0);
        }
    }

    /**
     * Genera una clave maestra AES-256 (32 bytes) aleatoria.
     *
     * @return clave maestra de 256 bits
     */
    public static byte[] generateMasterKey() {
        byte[] key = new byte[AES_KEY_LENGTH];
        new SecureRandom().nextBytes(key);
        return key;
    }

    /**
     * Decodifica una clave maestra desde su representación Base64.
     *
     * @param base64Key clave codificada en Base64
     * @return clave maestra de 256 bits (32 bytes)
     * @throws EncryptionException si la clave decodificada no tiene 32 bytes
     */
    public static byte[] decodeMasterKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new EncryptionException("Master key (Base64) must not be null or blank");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException e) {
            throw new EncryptionException("Invalid Base64 encoding for master key", e);
        }
        if (decoded.length != AES_KEY_LENGTH) {
            throw new EncryptionException(
                    "Master key must be 256 bits (32 bytes), got " + decoded.length + " bytes");
        }
        return decoded;
    }

    private static byte[] generateIv() {
        byte[] iv = new byte[GCM_IV_LENGTH];
        new SecureRandom().nextBytes(iv);
        return iv;
    }

    private static void validateInputs(byte[] data, String name) {
        if (data == null || data.length == 0) {
            throw new EncryptionException(name + " must not be null or empty");
        }
    }

    private static void validateMasterKey(byte[] masterKey) {
        if (masterKey == null || masterKey.length != AES_KEY_LENGTH) {
            throw new EncryptionException(
                    "Master key must be exactly 32 bytes (256 bits), got "
                            + (masterKey == null ? "null" : masterKey.length + " bytes"));
        }
    }
}
