package auracore.key49.core.model.enums;

import java.util.Map;
import java.util.Set;

/**
 * Estado del documento en el pipeline de procesamiento. Las transiciones
 * válidas están definidas en la máquina de estados (ARCHITECTURE.md).
 */
public enum DocumentStatus {

    CREATED,
    SIGNED,
    SENT,
    RECEIVED,
    AUTHORIZED,
    NOTIFIED,
    REJECTED,
    FAILED,
    RETRY,
    VOIDED;

    private static final Map<DocumentStatus, Set<DocumentStatus>> TRANSITIONS = Map.of(
            CREATED, Set.of(SIGNED, FAILED),
            SIGNED, Set.of(SENT, RETRY, REJECTED),
            SENT, Set.of(RECEIVED),
            RECEIVED, Set.of(AUTHORIZED, REJECTED, RETRY),
            AUTHORIZED, Set.of(NOTIFIED, VOIDED),
            NOTIFIED, Set.of(VOIDED),
            RETRY, Set.of(SIGNED, SENT, AUTHORIZED, FAILED),
            REJECTED, Set.of(CREATED),
            FAILED, Set.of(CREATED),
            VOIDED, Set.of()
    );

    /**
     * Verifica si la transición desde este estado al estado destino es válida.
     */
    public boolean canTransitionTo(DocumentStatus target) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }

    /**
     * Indica si este es un estado terminal de error (el documento puede ser
     * reenviado).
     */
    public boolean isRetryableTerminal() {
        return this == REJECTED || this == FAILED;
    }

    /**
     * Indica si este es un estado terminal (sin transiciones de salida excepto
     * reenvío).
     */
    public boolean isTerminal() {
        return TRANSITIONS.getOrDefault(this, Set.of()).isEmpty()
                || isRetryableTerminal();
    }
}
