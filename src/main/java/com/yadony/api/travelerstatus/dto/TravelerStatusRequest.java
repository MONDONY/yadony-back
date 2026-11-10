package com.yadony.api.travelerstatus.dto;

import com.yadony.api.travelerstatus.TravelerStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Déclaration du statut légal du voyageur.
 *
 * @param personalUseConfirmed obligatoire (vrai) pour un particulier : le voyageur certifie ne pas
 *        faire du transport de colis une activité professionnelle
 * @param businessName raison sociale, obligatoire pour un professionnel
 * @param registrationNumber numéro d'immatriculation de l'entreprise (SIRET, RCCM, NINEA…),
 *        obligatoire pour un professionnel ; chiffré au repos, jamais journalisé
 * @param country code ISO 3166-1 alpha-2 du pays d'immatriculation, en majuscules
 */
public record TravelerStatusRequest(
        @NotNull TravelerStatus status,
        Boolean personalUseConfirmed,
        @Size(min = 2, max = 120) String businessName,
        @Size(min = 4, max = 40) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9 ./-]*$") String registrationNumber,
        @Pattern(regexp = "^[A-Z]{2}$") String country
) {
}
