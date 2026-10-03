package com.yadony.api.auth.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Validation de UpdateProfileRequest — langues parlées (FLUTTER-9Z)")
class UpdateProfileRequestValidationTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    private UpdateProfileRequest withLanguages(Set<String> languages) {
        return new UpdateProfileRequest(null, null, null, null, null, null, languages);
    }

    @Test
    @DisplayName("des langues de la liste et une saisie libre courte passent")
    void listAndShortCustomLanguagePass() {
        assertThat(validator.validate(withLanguages(Set.of("Français", "Peul", "Kabyle")))).isEmpty();
        assertThat(validator.validate(withLanguages(null))).isEmpty();
    }

    @Test
    @DisplayName("une langue de plus de 32 caractères est refusée (colonne VARCHAR 32)")
    void tooLongLanguageRejected() {
        assertThat(validator.validate(withLanguages(Set.of("x".repeat(33))))).isNotEmpty();
        assertThat(validator.validate(withLanguages(Set.of("x".repeat(32))))).isEmpty();
    }

    @Test
    @DisplayName("une langue vide est refusée")
    void blankLanguageRejected() {
        assertThat(validator.validate(withLanguages(Set.of("  ")))).isNotEmpty();
    }

    @Test
    @DisplayName("plus de 30 langues sont refusées")
    void tooManyLanguagesRejected() {
        Set<String> many = new HashSet<>();
        for (int i = 0; i < 31; i++) {
            many.add("Langue " + i);
        }
        assertThat(validator.validate(withLanguages(many))).isNotEmpty();
    }
}
