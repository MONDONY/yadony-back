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
        List<String> badges
) {}
