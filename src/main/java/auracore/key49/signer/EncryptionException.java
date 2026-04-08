package auracore.key49.signer;

/**
 * Excepción lanzada cuando falla el cifrado o descifrado de certificados .p12.
 *
 * <p>Encapsula errores de AES-256-GCM: clave maestra inválida, datos corruptos,
 * o fallo en la autenticación del tag GCM.
 */
public class EncryptionException extends RuntimeException {

    public EncryptionException(String message) {
        super(message);
    }

    public EncryptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
