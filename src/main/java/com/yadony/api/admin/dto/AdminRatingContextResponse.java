package com.yadony.api.admin.dto;

import com.yadony.api.admin.dto.AdminReportContextResponse.HistoryEntry;
import com.yadony.api.admin.dto.AdminReportContextResponse.RelatedReport;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ce qu'il faut à un modérateur pour décider du sort d'un avis : qui note, qui est noté, ce que
 * l'avis pèse dans la moyenne, ce qui a été signalé et fait. Lu à l'ouverture du dossier, pas dans
 * la liste (décomptes et lectures d'audit par avis).
 *
 * @param rater            le notant ; {@code null} pour un avis anonyme (destinataire d'un colis) ou un compte supprimé
 * @param rated            la personne notée ; {@code null} si le compte n'existe plus
 * @param sameRelationCount notes de ce notant à cette personne (celle-ci comprise) ; 0 sans notant ou sans noté
 * @param reports          signalements visant cet avis, du plus récent au plus ancien (10 au plus)
 * @param openReports      dont encore ouverts
 * @param history          gestes tracés sur cet avis, du plus récent au plus ancien
 */
public record AdminRatingContextResponse(
        Rater rater,
        Rated rated,
        long sameRelationCount,
        List<RelatedReport> reports,
        long openReports,
        List<HistoryEntry> history
) {

    /**
     * @param ratingsGiven    notes émises par ce compte
     * @param averageGiven    leur moyenne (une décimale) ; {@code null} sans note
     * @param lowRatingsGiven dont de deux étoiles ou moins
     */
    public record Rater(UUID userId, String displayName, LocalDateTime memberSince,
                        long ratingsGiven, BigDecimal averageGiven, long lowRatingsGiven) {}

    /**
     * @param averageRating moyenne stockée du compte (avis exclus et supprimés écartés) ; {@code null} sans avis
     * @param ratingCount   nombre d'avis comptés dans cette moyenne
     * @param excludedCount avis sortis de la moyenne par l'équipe
     * @param distribution  nombre d'avis comptés par note (1 à 5), seulement les valeurs présentes
     */
    public record Rated(UUID userId, String displayName, LocalDateTime memberSince, BigDecimal averageRating,
                        int ratingCount, long excludedCount, Map<Integer, Long> distribution) {}
}
