package auracore.key49.core.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;

/**
 * Valida que un código SRI exista en un enum específico.
 * Uso: {@code @ValidSriCode(enumClass = PaymentMethod.class)}
 */
@Documented
@Constraint(validatedBy = ValidSriCode.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidSriCode {

    String message() default "Código SRI no reconocido";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /**
     * Enum que implementa el método {@code sriCode()} contra el que se valida.
     */
    Class<? extends Enum<?>> enumClass();

    class Validator implements ConstraintValidator<ValidSriCode, String> {

        private Class<? extends Enum<?>> enumClass;

        @Override
        public void initialize(ValidSriCode annotation) {
            this.enumClass = annotation.enumClass();
        }

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            if (value == null || value.isBlank()) {
                return true;
            }
            return Arrays.stream(enumClass.getEnumConstants())
                    .anyMatch(e -> {
                        try {
                            var method = e.getClass().getMethod("sriCode");
                            return value.equals(method.invoke(e));
                        } catch (ReflectiveOperationException ex) {
                            return false;
                        }
                    });
        }
    }
}
