package auracore.key49.storage;

/**
 * Excepción lanzada cuando falla una operación de almacenamiento en MinIO/S3.
 */
public class StorageException extends RuntimeException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
