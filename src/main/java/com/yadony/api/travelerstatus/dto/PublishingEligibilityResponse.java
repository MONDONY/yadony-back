package com.yadony.api.travelerstatus.dto;

import com.yadony.api.travelerstatus.PublishingEligibilityReason;
import com.yadony.api.travelerstatus.TravelerStatus;

import java.time.LocalDate;

/**
 * Éligibilité du voyageur à publier un trajet maintenant.
 *
 * @param allowed vrai si et seulement si {@code reason} vaut {@code OK}
 * @param limit limite mensuelle de l'utilisateur ; {@code null} quand il n'est jamais limité (aucune limite
 *        saisie, ou GP Pro validé)
 * @param used trajets publiés (hors brouillons) créés dans le mois civil courant
 * @param resetsOn premier jour du mois suivant, quand le compteur repart de zéro
 * @param travelerStatus statut déclaré, {@code null} s'il ne l'a jamais été
 */
public record PublishingEligibilityResponse(
        boolean allowed,
        PublishingEligibilityReason reason,
        Integer limit,
        int used,
        LocalDate resetsOn,
        TravelerStatus travelerStatus
) {
}
