package auracore.key49.core.model;

import auracore.key49.core.model.enums.DocumentStatus;

/**
 * Excepción lanzada cuando se intenta una transición de estado no permitida
 * según la máquina de estados de documentos.
 */
public class InvalidStateTransitionException extends RuntimeException {

    private final DocumentStatus from;
    private final DocumentStatus to;

    public InvalidStateTransitionException(DocumentStatus from, DocumentStatus to) {
        super("Invalid state transition from %s to %s".formatted(from, to));
        this.from = from;
        this.to = to;
    }

    public DocumentStatus from() {
        return from;
    }

    public DocumentStatus to() {
        return to;
    }
}
