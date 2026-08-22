package com.yadony.api.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Adresse de résidence du voyageur, transmise ensuite à Stripe Connect.
 *
 * <p>Le pays n'y figure pas volontairement : il vient de {@code users.country},
 * figé à l'étape « pays » de l'onboarding, et Stripe verrouille le pays du
 * compte connecté. L'accepter ici permettrait de le contredire.
 *
 * @param street     requis — numéro et voie
 * @param line2      optionnel — appartement, étage, bâtiment
 * @param postalCode requis
 * @param city       requis — écrase {@code users.city}, même donnée
 */
public record ResidenceAddressRequest(
        @NotBlank @Size(max = 255) String street,
        @Size(max = 100) String line2,
        @NotBlank @Size(max = 20) String postalCode,
        @NotBlank @Size(max = 100) String city) {}
