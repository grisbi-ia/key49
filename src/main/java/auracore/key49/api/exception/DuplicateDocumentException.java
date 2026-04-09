package auracore.key49.api.exception;

import java.time.Instant;
import java.util.UUID;

/**
 * Excepción lanzada cuando se intenta crear un documento con secuencial ya
 * existente, pero el documento existente está en un estado activo (en progreso
 * o completado).
 *
 * A diferencia de un error genérico, esta excepción incluye la información del
 * documento existente para que el cliente pueda tomar acción (consultar estado,
 * esperar, etc.).
 */
public class DuplicateDocumentException extends BusinessException {

    private final UUID existingDocumentId;
    private final String existingStatus;
    private final String accessKey;
    private final Instant authorizationDate;

    public DuplicateDocumentException(String code, String message,
            UUID existingDocumentId, String existingStatus,
            String accessKey, Instant authorizationDate) {
        super(code, message, 409);
        this.existingDocumentId = existingDocumentId;
        this.existingStatus = existingStatus;
        this.accessKey = accessKey;
        this.authorizationDate = authorizationDate;
    }

    public UUID existingDocumentId() {
        return existingDocumentId;
    }

    public String existingStatus() {
        return existingStatus;
    }

    public String accessKey() {
        return accessKey;
    }

    public Instant authorizationDate() {
        return authorizationDate;
    }
}
