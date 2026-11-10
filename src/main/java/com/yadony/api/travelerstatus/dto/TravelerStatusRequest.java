package com.yadony.api.travelerstatus.dto;

import com.yadony.api.travelerstatus.TravelerStatus;
import jakarta.validation.constraints.NotNull;

/**
 * Déclaration du statut légal du voyageur. Seul « particulier » se déclare ici : le statut
 * professionnel passe par la demande GP Pro ({@code POST /gp-pro/applications}), validée par l'équipe.
 * Les anciens champs entreprise d'un vieux client sont ignorés à la désérialisation.
 *
 * @param personalUseConfirmed obligatoire (vrai) pour un particulier : le voyageur certifie ne pas
 *        faire du transport de colis une activité professionnelle
 */
public record TravelerStatusRequest(
        @NotNull TravelerStatus status,
        Boolean personalUseConfirmed
) {
}
