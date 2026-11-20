package com.yadony.api.matching.ranking;

import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * Calcule le score de confiance (0 à 100) d'une annonce de trajet à partir de la qualité du voyageur.
 * Classe pure, sans accès base : ne lève jamais d'exception.
 */
@Component
public class TripScoreCalculator {

    private static final double PRIOR_RATING = 4.0;
    private static final double PRIOR_COUNT = 5.0;
    private static final double LN_51 = Math.log(51);
    private static final double LN_21 = Math.log(21);
    private static final double FRESHNESS_WINDOW_DAYS = 30.0;

    private final TripRankingProperties props;

    public TripScoreCalculator(TripRankingProperties props) {
        this.props = props;
    }

    /**
     * Qualité du voyageur.
     *
     * @param averageRating note moyenne (peut être null s'il n'y a aucun avis)
     */
    public record TravelerQuality(int cancellations, int noShows, int deliveries,
                                  BigDecimal averageRating, int ratingCount) {
    }

    /**
     * @param daysToDeparture jours avant le départ (négatif ou 0 = départ aujourd'hui ou passé)
     * @param ageDays         ancienneté de l'annonce en jours
     * @return score entre 0 et 100 (0 si tous les poids sont à zéro)
     */
    public double score(TravelerQuality q, boolean onlinePayment, long daysToDeparture, long ageDays) {
        return score(q, onlinePayment, daysToDeparture, ageDays, 0);
    }

    /**
     * @param likeCount nombre de j'aime du trajet (négatif traité comme 0) ; le bonus sature à 20
     * @return score entre 0 et 100 (0 si tous les poids sont à zéro)
     */
    public double score(TravelerQuality q, boolean onlinePayment, long daysToDeparture, long ageDays,
                        long likeCount) {
        double sum = props.weightSum();
        if (sum <= 0) {
            return 0;
        }
        TripRankingProperties.Weights w = props.weights();
        double total = w.reliability() * reliability(q)
                + w.rating() * rating(q)
                + w.deliveries() * deliveries(q)
                + w.freshness() * freshness(daysToDeparture, ageDays)
                + w.onlinePayment() * (onlinePayment ? 1.0 : 0.0)
                + w.likes() * likes(likeCount);
        return clamp(total / sum) * 100;
    }

    /** Fiabilité : (d + 4) / (d + 5 + 4 × (annulations + 2 × absences)). */
    private double reliability(TravelerQuality q) {
        double d = Math.max(0, q.deliveries());
        double incidents = Math.max(0, q.cancellations()) + 2.0 * Math.max(0, q.noShows());
        return clamp((d + 4) / (d + 5 + 4 * incidents));
    }

    /** Note bayésienne ramenée de [3 ; 5] à [0 ; 1]. */
    private double rating(TravelerQuality q) {
        int n = Math.max(0, q.ratingCount());
        double avg = (n == 0 || q.averageRating() == null) ? 0 : q.averageRating().doubleValue();
        double bayes = (avg * n + PRIOR_RATING * 5) / (n + PRIOR_COUNT);
        return clamp((bayes - 3) / 2);
    }

    /** Expérience : min(1, ln(1 + d) / ln(51)). */
    private double deliveries(TravelerQuality q) {
        return clamp(Math.log(1 + Math.max(0, q.deliveries())) / LN_51);
    }

    /** Fraîcheur : moitié proximité du départ, moitié nouveauté de l'annonce, sur 30 jours. */
    private double freshness(long daysToDeparture, long ageDays) {
        double departure = clamp(1 - Math.max(0, daysToDeparture) / FRESHNESS_WINDOW_DAYS);
        double age = clamp(1 - Math.max(0, ageDays) / FRESHNESS_WINDOW_DAYS);
        return 0.5 * departure + 0.5 * age;
    }

    /** J'aime : min(1, ln(1 + n) / ln(21)), saturé à 20 j'aime. */
    private double likes(long likeCount) {
        return clamp(Math.log(1 + Math.max(0, likeCount)) / LN_21);
    }

    private static double clamp(double v) {
        if (Double.isNaN(v)) {
            return 0;
        }
        return Math.max(0, Math.min(1, v));
    }
}
