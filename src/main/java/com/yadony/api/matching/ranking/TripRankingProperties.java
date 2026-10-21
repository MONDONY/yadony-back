package com.yadony.api.matching.ranking;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Réglages du classement des annonces de trajet. Détecté par {@code @ConfigurationPropertiesScan}.
 *
 * @param weights         poids (sur 100) de chaque critère du score de confiance du voyageur.
 * @param exactThreshold  seuil de la correspondance exacte (7 par défaut).
 * @param similarMax      nombre maximal d'annonces « similaires » renvoyées (20 par défaut).
 * @param maxCandidates   plafond de candidats chargés avant classement (1000 par défaut).
 */
@ConfigurationProperties(prefix = "yadony.ranking")
public record TripRankingProperties(Weights weights, int exactThreshold, int similarMax, int maxCandidates) {

    public TripRankingProperties {
        if (weights == null) weights = new Weights(0, 0, 0, 0, 0);
        if (exactThreshold <= 0) exactThreshold = 7;
        if (similarMax <= 0) similarMax = 20;
        if (maxCandidates <= 0) maxCandidates = 1000;
    }

    /** Poids des critères ; chaque valeur absente ou nulle retombe sur le défaut (30/25/20/15/10). */
    public record Weights(int reliability, int rating, int deliveries, int freshness, int onlinePayment) {

        public Weights {
            if (reliability <= 0) reliability = 30;
            if (rating <= 0) rating = 25;
            if (deliveries <= 0) deliveries = 20;
            if (freshness <= 0) freshness = 15;
            if (onlinePayment <= 0) onlinePayment = 10;
        }
    }

    /** Somme des poids, utilisée pour normaliser le score. */
    public double weightSum() {
        return (double) weights.reliability() + weights.rating() + weights.deliveries()
                + weights.freshness() + weights.onlinePayment();
    }
}
