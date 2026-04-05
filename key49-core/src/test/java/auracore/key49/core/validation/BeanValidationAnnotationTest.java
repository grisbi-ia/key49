package auracore.key49.core.validation;

import auracore.key49.core.model.enums.IdentificationType;
import auracore.key49.core.model.enums.PaymentMethod;
import auracore.key49.core.model.enums.TaxType;
import auracore.key49.core.model.enums.VatRate;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BeanValidationAnnotationTest {

    private Validator validator;

    @BeforeAll
    void setup() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    // --- @ValidRuc ---

    record RucHolder(@ValidRuc String ruc) {
    }

    @Test
    void validRucAnnotationShouldPass() {
        var violations = validator.validate(new RucHolder("1790016919001"));
        assertTrue(violations.isEmpty());
    }

    @Test
    void validRucAnnotationShouldFailForInvalid() {
        var violations = validator.validate(new RucHolder("1234567890123"));
        assertFalse(violations.isEmpty());
    }

    @Test
    void validRucAnnotationShouldPassForNull() {
        var violations = validator.validate(new RucHolder(null));
        assertTrue(violations.isEmpty()); // null checked by @NotBlank
    }

    // --- @ValidCedula ---

    record CedulaHolder(@ValidCedula String cedula) {
    }

    @Test
    void validCedulaAnnotationShouldPass() {
        var violations = validator.validate(new CedulaHolder("1710034065"));
        assertTrue(violations.isEmpty());
    }

    @Test
    void validCedulaAnnotationShouldFailForInvalid() {
        var violations = validator.validate(new CedulaHolder("0000000000"));
        assertFalse(violations.isEmpty());
    }

    // --- @ValidSriCode ---

    record TaxCodeHolder(@ValidSriCode(enumClass = TaxType.class) String code) {
    }

    record PaymentHolder(@ValidSriCode(enumClass = PaymentMethod.class) String code) {
    }

    record IdTypeHolder(@ValidSriCode(enumClass = IdentificationType.class) String code) {
    }

    record VatRateHolder(@ValidSriCode(enumClass = VatRate.class) String code) {
    }

    @Test
    void validSriCodeShouldPassForKnownTaxType() {
        var violations = validator.validate(new TaxCodeHolder("2")); // IVA
        assertTrue(violations.isEmpty());
    }

    @Test
    void validSriCodeShouldFailForUnknownTaxType() {
        var violations = validator.validate(new TaxCodeHolder("99"));
        assertFalse(violations.isEmpty());
    }

    @Test
    void validSriCodeShouldPassForKnownPaymentMethod() {
        var violations = validator.validate(new PaymentHolder("01")); // SIN UTILIZACION DEL SISTEMA FINANCIERO
        assertTrue(violations.isEmpty());
    }

    @Test
    void validSriCodeShouldFailForUnknownPaymentMethod() {
        var violations = validator.validate(new PaymentHolder("99"));
        assertFalse(violations.isEmpty());
    }

    @Test
    void validSriCodeShouldPassForKnownIdType() {
        var violations = validator.validate(new IdTypeHolder("04")); // RUC
        assertTrue(violations.isEmpty());
    }

    @Test
    void validSriCodeShouldFailForUnknownIdType() {
        var violations = validator.validate(new IdTypeHolder("01"));
        assertFalse(violations.isEmpty());
    }

    @Test
    void validSriCodeShouldPassForKnownVatRate() {
        var violations = validator.validate(new VatRateHolder("2")); // IVA 12%
        assertTrue(violations.isEmpty());
    }

    @Test
    void validSriCodeShouldPassForNull() {
        var violations = validator.validate(new TaxCodeHolder(null));
        assertTrue(violations.isEmpty()); // null checked by @NotBlank
    }
}
