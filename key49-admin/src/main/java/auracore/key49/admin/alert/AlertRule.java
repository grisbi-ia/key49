package auracore.key49.admin.alert;

/**
 * Interfaz para reglas de alerta evaluadas periódicamente.
 *
 * <p>Cada implementación verifica una condición operativa específica
 * (SRI disponible, DLQ vacía, certificados vigentes, etc.) y retorna
 * un {@link AlertResult} indicando si la condición de alerta se cumple.</p>
 */
public interface AlertRule {

    /**
     * Evalúa la condición de alerta.
     *
     * @return resultado con estado firing/ok y descripción
     */
    AlertResult evaluate();
}
