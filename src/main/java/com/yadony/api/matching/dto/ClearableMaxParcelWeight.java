package com.yadony.api.matching.dto;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.math.BigDecimal;

/**
 * Poids maximal par colis d'un trajet : absent ({@code null}), {@code 0} (sentinelle d'effacement)
 * ou au moins 0,5 kg (le minimum d'une demande de colis). Entre 0 exclu et 0,5 exclu : refusé.
 */
@Documented
@Constraint(validatedBy = ClearableMaxParcelWeight.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ClearableMaxParcelWeight {

    BigDecimal MIN_KG = new BigDecimal("0.5");

    String message() default "{validation.trip.max-parcel-weight.min}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<ClearableMaxParcelWeight, BigDecimal> {
        @Override
        public boolean isValid(BigDecimal value, ConstraintValidatorContext context) {
            return value == null || value.signum() == 0 || value.compareTo(MIN_KG) >= 0;
        }
    }
}
