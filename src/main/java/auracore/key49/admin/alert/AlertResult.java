package auracore.key49.admin.alert;

/**
 * Resultado de evaluar una regla de alerta.
 *
 * @param firing  {@code true} si la condición de alerta se cumple
 * @param name    nombre identificador de la alerta (e.g. "sri_health")
 * @param summary resumen legible de la condición actual
 */
public record AlertResult(boolean firing, String name, String summary) {

    public static AlertResult ok(String name, String summary) {
        return new AlertResult(false, name, summary);
    }

    public static AlertResult firing(String name, String summary) {
        return new AlertResult(true, name, summary);
    }
}
