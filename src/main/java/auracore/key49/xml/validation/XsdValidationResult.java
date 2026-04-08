package auracore.key49.xml.validation;

import java.util.List;

/**
 * Resultado de la validación de un XML contra su esquema XSD.
 *
 * @param valid true si el XML es conforme al XSD
 * @param errors lista de errores encontrados (vacía si valid=true)
 */
public record XsdValidationResult(
        boolean valid,
        List<ValidationError> errors
) {

    /**
     * Resultado exitoso sin errores.
     */
    public static XsdValidationResult success() {
        return new XsdValidationResult(true, List.of());
    }

    /**
     * Resultado fallido con errores.
     */
    public static XsdValidationResult failure(List<ValidationError> errors) {
        return new XsdValidationResult(false, List.copyOf(errors));
    }

    /**
     * Error individual de validación XSD.
     *
     * @param line    línea del error en el XML (-1 si no disponible)
     * @param column  columna del error en el XML (-1 si no disponible)
     * @param message mensaje de error legible
     */
    public record ValidationError(
            int line,
            int column,
            String message
    ) {
        @Override
        public String toString() {
            if (line > 0 && column > 0) {
                return "Line %d, Column %d: %s".formatted(line, column, message);
            }
            return message;
        }
    }
}
