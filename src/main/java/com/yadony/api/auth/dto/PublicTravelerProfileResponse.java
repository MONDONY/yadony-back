package com.yadony.api.auth.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Minimal, publicly shareable traveler profile (no auth required).
 * Excludes contact preferences and any private/business data.
 */
public record PublicTravelerProfileResponse(
        String displayName,
        boolean kycVerified,
        /** Déprécié : apps antérieures à GP Pro, à retirer. Toujours {@code false}. */
        boolean isKiloPro,
        boolean gpPro,
        int completedBidsCount,
        BigDecimal averageRating,
        int ratingCount,
        String memberSince,
        List<String> badges,
        /** Statut légal déclaré (INDIVIDUAL ou PROFESSIONAL) ; null si non déclaré. */
        String travelerStatus,
        /** Raison sociale d'un professionnel. Jamais le numéro d'immatriculation sur cette page anonyme. */
        String businessName
) {
    /** Forme d'avant le statut légal du voyageur (lot 2) : aucun statut. */
    public PublicTravelerProfileResponse(String displayName, boolean kycVerified, boolean isKiloPro,
                                         boolean gpPro, int completedBidsCount, BigDecimal averageRating,
                                         int ratingCount, String memberSince, List<String> badges) {
        this(displayName, kycVerified, isKiloPro, gpPro, completedBidsCount, averageRating, ratingCount,
                memberSince, badges, null, null);
    }
}
