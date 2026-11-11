package com.yadony.api.dac7;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Identité d'un vendeur pour l'export DAC7, résolue par {@link Dac7IdentityResolver} à la demande et jamais
 * persistée. {@link #toString()} ne montre aucune valeur (ni nom, ni date de naissance, ni adresse).
 *
 * @param missing codes de ce qui manque ({@code ACCOUNT_DELETED}, {@code NAME}, {@code BIRTH_DATE},
 *                {@code ADDRESS}, {@code BUSINESS_NAME}, {@code REGISTRATION_NUMBER}, {@code BUSINESS_ADDRESS}),
 *                vide si l'identité est complète ; jamais une valeur
 */
public record Dac7SellerIdentity(UUID userId, SellerType type, Dac7IdentityStatus status, List<String> missing,
                                 String firstName, String lastName, LocalDate birthDate, Dac7Address address,
                                 String businessName, String registrationNumber) {

    /** Personne physique (bloc {@code IndividualSeller}) ou entité ({@code EntitySeller}). */
    public enum SellerType {
        INDIVIDUAL,
        ENTITY
    }

    @Override
    public String toString() {
        return "Dac7SellerIdentity[" + userId + ", " + type + ", " + status + ", manque " + missing + "]";
    }
}
