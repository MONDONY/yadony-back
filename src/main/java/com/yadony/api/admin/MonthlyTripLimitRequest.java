package com.yadony.api.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Réglage admin de la limite mensuelle de trajets publiés d'un utilisateur.
 * {@code limit} null = aucune limite ; sinon de 1 à 100. Un GP Pro validé n'est jamais limité, quelle que
 * soit la valeur saisie.
 */
public record MonthlyTripLimitRequest(
        @Min(value = 1, message = "La limite doit être d'au moins 1 trajet")
        @Max(value = 100, message = "La limite ne peut pas dépasser 100 trajets")
        Integer limit
) {}
