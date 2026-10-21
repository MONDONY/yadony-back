package com.yadony.api.auth.dto;

import java.math.BigDecimal;
import java.util.List;

public record ProfilePublicResponse(
        String userId,
        String displayName,
        String avatarUrl,
        boolean kycVerified,
        boolean isProAccount,
        /** Déprécié : apps antérieures à GP Pro, à retirer. Toujours {@code false}. */
        boolean isKiloPro,
        boolean gpPro,
        int completedBidsCount,
        BigDecimal averageRating,
        int ratingCount,
        String memberSince,
        List<String> badges,
        String contactMode,
        Integer responseDelayHours,
        String bio,
        List<String> languages,
        /** Numéro de téléphone rattaché au compte (jamais le numéro lui-même). */
        boolean phoneVerified,
        /** E-mail rattaché au compte, toujours vérifié par code ou fournisseur. */
        boolean emailVerified,
        /** Code ISO2, seulement si l'utilisateur a choisi de l'afficher ; sinon null. */
        String residenceCountry,
        /** Temps de réponse mesuré (médiane, minutes) sur 90 jours ; null sous 3 décisions. */
        Integer measuredResponseMinutes,
        /** Jours depuis la dernière ouverture de l'app (0 = aujourd'hui) ; null si masquée ou inconnue. */
        Integer lastSeenDaysAgo,
        /**
         * Taux de fiabilité, en % entier (FLUTTER-NV) : envois menés à bien sur envois engagés
         * tranchés, en tant qu'expéditeur et voyageur. {@code null} (absent du JSON) sous
         * {@link com.yadony.api.auth.ReliabilityScore#MIN_ENGAGED_SHIPMENTS} envois engagés.
         * Définition complète : {@link com.yadony.api.auth.ReliabilityScore}.
         */
        Integer reliabilityPercent
) {
    /** Arité précédente, sans {@code reliabilityPercent}. */
    public ProfilePublicResponse(String userId, String displayName, String avatarUrl,
                                 boolean kycVerified, boolean isProAccount, boolean isKiloPro, boolean gpPro,
                                 int completedBidsCount, BigDecimal averageRating, int ratingCount,
                                 String memberSince, List<String> badges, String contactMode,
                                 Integer responseDelayHours, String bio, List<String> languages,
                                 boolean phoneVerified, boolean emailVerified, String residenceCountry,
                                 Integer measuredResponseMinutes, Integer lastSeenDaysAgo) {
        this(userId, displayName, avatarUrl, kycVerified, isProAccount, isKiloPro, gpPro,
                completedBidsCount, averageRating, ratingCount, memberSince, badges, contactMode,
                responseDelayHours, bio, languages, phoneVerified, emailVerified, residenceCountry,
                measuredResponseMinutes, lastSeenDaysAgo, null);
    }

    public ProfilePublicResponse(String userId, String displayName, String avatarUrl,
                                 boolean kycVerified, boolean isProAccount, boolean isKiloPro, boolean gpPro,
                                 int completedBidsCount, BigDecimal averageRating, int ratingCount,
                                 String memberSince, List<String> badges, String contactMode,
                                 Integer responseDelayHours, String bio, List<String> languages) {
        this(userId, displayName, avatarUrl, kycVerified, isProAccount, isKiloPro, gpPro,
                completedBidsCount, averageRating, ratingCount, memberSince, badges, contactMode,
                responseDelayHours, bio, languages, false, false, null, null, null, null);
    }
}
