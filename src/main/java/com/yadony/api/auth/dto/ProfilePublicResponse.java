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
        /** Fiabilité en tant qu'expéditeur (FLUTTER-E0/E6) : annulations après acceptation
         *  d'un voyageur et absences au rendez-vous de remise confirmées. */
        int senderIncidentCount
) {
    public ProfilePublicResponse(String userId, String displayName, String avatarUrl,
                                 boolean kycVerified, boolean isProAccount, boolean isKiloPro,
                                 int completedBidsCount, BigDecimal averageRating, int ratingCount,
                                 String memberSince, List<String> badges, String contactMode,
                                 Integer responseDelayHours, String bio, List<String> languages) {
        this(userId, displayName, avatarUrl, kycVerified, isProAccount, isKiloPro,
                completedBidsCount, averageRating, ratingCount, memberSince, badges, contactMode,
                responseDelayHours, bio, languages, false, false, null, null, null, 0);
    }
}
