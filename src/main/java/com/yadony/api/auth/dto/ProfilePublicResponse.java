package com.yadony.api.auth.dto;

import java.math.BigDecimal;
import java.util.List;

public record ProfilePublicResponse(
        String userId,
        String displayName,
        String avatarUrl,
        boolean kycVerified,
        boolean isProAccount,
        boolean isKiloPro,
        int completedBidsCount,
        BigDecimal averageRating,
        int ratingCount,
        String memberSince,
        List<String> badges,
        String contactMode,
        Integer responseDelayHours,
        String bio,
        List<String> languages
) {}
