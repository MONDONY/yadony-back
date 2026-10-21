package com.yadony.api.matching.ranking;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Réglages du classement des annonces de trajet. Détecté par {@code @ConfigurationPropertiesScan}.
 *
 * @param weights         poids (sur 100) de chaque critère du score de confiance du voyageur.
 * @param exactThreshold  seuil de la correspondance exacte (7 par défaut).
 * @param similarMax      nombre maximal d'annonces « similaires » renvoyées (20 par défaut).
 * @param maxCandidates   plafond de candidats chargés avant classement (1000 par défaut).
 * @param maxCandidatesWithoutCity plafond de candidats d'une recherche sans ville de départ ni
 *                        d'arrivée, triée par score (300 par défaut) : le fil entier n'est pas
 *                        borné par un trajet, on en lit moins.
 */
@ConfigurationProperties(prefix = "yadony.ranking")
public record TripRankingProperties(@DefaultValue Weights weights, int exactThreshold, int similarMax, int maxCandidates,
                                    int maxCandidatesWithoutCity) {

    public TripRankingProperties {
        if (weights == null) weights = new Weights(30, 25, 20, 15, 10);
        if (exactThreshold <= 0) exactThreshold = 7;
        if (similarMax <= 0) similarMax = 20;
        if (maxCandidates <= 0) maxCandidates = 1000;
        if (maxCandidatesWithoutCity <= 0) maxCandidatesWithoutCity = 300;
    }

    /**
     * Poids des critères : seule une valeur absente prend le défaut (30/25/20/15/10), un 0 explicite
     * est conservé (critère désactivé) et une valeur négative est refusée.
     */
    public record Weights(@DefaultValue("30") int reliability, @DefaultValue("25") int rating,
                          @DefaultValue("20") int deliveries, @DefaultValue("15") int freshness,
                          @DefaultValue("10") int onlinePayment) {

        public Weights {
            if (reliability < 0 || rating < 0 || deliveries < 0 || freshness < 0 || onlinePayment < 0) {
                throw new IllegalArgumentException("Les poids du classement ne peuvent pas être négatifs");
            }
        }
    }

    /** Somme des poids, utilisée pour normaliser le score. */
    public double weightSum() {
        return (double) weights.reliability() + weights.rating() + weights.deliveries()
                + weights.freshness() + weights.onlinePayment();
    }
}
