package com.yadony.api.matching.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Voyageur tel que vu par un visiteur anonyme : liste blanche stricte.
 *
 * <p>Ni identifiant, ni photo, ni téléphone, ni e-mail, ni réglages. {@code identityVerified}
 * reprend {@code kycVerified} du profil complet.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicTravelerCard(
        String displayName,
        Double averageRating,
        Integer totalTrips,
        boolean identityVerified
) {

    static PublicTravelerCard from(TravelerProfileDto t) {
        if (t == null) {
            return null;
        }
        return new PublicTravelerCard(
                t.displayName(), t.averageRating(), t.totalTrips(), t.kycVerified());
    }
}
