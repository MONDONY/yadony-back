package com.yadony.api.matching.dto;

import com.yadony.api.matching.TransportMode;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * La capacité en kilos ({@code availableKg}) d'un trajet est obligatoire, sauf pour un trajet
 * bateau vendu au m³ ({@code transportMode = BOAT} et {@code pricePerM3} renseigné), qui n'a pas de
 * kilos (V382). Remplace le {@code @NotNull} de {@code availableKg} : même message, même champ
 * dans {@code violations}, donc rien ne change pour un client qui l'omettait par erreur.
 */
@Documented
@Constraint(validatedBy = KgCapacityRequiredUnlessPricedPerM3.Validator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface KgCapacityRequiredUnlessPricedPerM3 {

    String message() default "{validation.trip.available-kg.required}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<KgCapacityRequiredUnlessPricedPerM3, AnnouncementRequest> {
        @Override
        public boolean isValid(AnnouncementRequest request, ConstraintValidatorContext context) {
            if (request == null || request.availableKg() != null) {
                return true;
            }
            if (request.transportMode() == TransportMode.BOAT && request.pricePerM3() != null) {
                return true;
            }
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                    .addPropertyNode("availableKg")
                    .addConstraintViolation();
            return false;
        }
    }
}
