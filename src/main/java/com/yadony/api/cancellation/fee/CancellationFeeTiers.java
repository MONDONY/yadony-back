package com.yadony.api.cancellation.fee;

import com.yadony.api.matching.AnnouncementEntity;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/** Règle pure du taux de frais d'annulation selon le temps restant avant le départ. */
public final class CancellationFeeTiers {

    private CancellationFeeTiers() {}

    /**
     * Taux applicable (0 à 1). Départ = {@code departureAt}, à défaut {@code departureDate}
     * à 00:00 UTC (comme {@code CancellationGuard.hasDeparted}) ; jamais la date limite de
     * remise. Barème : voir {@link CancellationFeeProperties}.
     */
    public static BigDecimal rateFor(AnnouncementEntity trip, Instant now, CancellationFeeProperties props) {
        return props.tiers().get(tierIndex(trip, now, props.tiers())).rate();
    }

    /**
     * Instant de la prochaine HAUSSE du taux, lu dans le barème (jamais en dur) : le taux actuel vaut jusqu'à cet instant
     * INCLUS, une annulation après lui coûte le palier plus cher suivant. Un palier suivant au même taux (ou plus bas) est
     * sauté. {@code null} au palier le plus cher (aucune hausse à venir). Même mesure exacte que {@link #rateFor} : un
     * palier « au moins N jours » tient jusqu'au départ moins N jours tout juste ; la fenêtre gratuite ({@code 5 -> 0},
     * strictement plus de 5 jours) jusqu'à la dernière seconde avant le départ moins 5 jours.
     */
    public static Instant nextIncreaseAt(AnnouncementEntity trip, Instant now, CancellationFeeProperties props) {
        List<CancellationFeeProperties.Tier> tiers = props.tiers();
        int current = tierIndex(trip, now, tiers);
        BigDecimal rate = tiers.get(current).rate();
        Instant departure = departureInstant(trip);
        for (int next = current + 1; next < tiers.size(); next++) {
            if (tiers.get(next).rate().compareTo(rate) > 0) {
                return lastInstantOf(departure, tiers, next - 1);
            }
        }
        return null;
    }

    /**
     * Palier applicable : le premier, du plus lointain au plus proche, dont le seuil est atteint ; sinon le dernier. Temps
     * restant comparé EXACTEMENT au seuil, sans arrondi à l'heure : à 5 jours et 30 minutes du départ, il reste plus de
     * 5 jours (fenêtre gratuite) ; à 3 jours moins une seconde, moins de 3 jours.
     */
    private static int tierIndex(AnnouncementEntity trip, Instant now, List<CancellationFeeProperties.Tier> tiers) {
        Duration remaining = Duration.between(now, departureInstant(trip));
        for (int i = 0; i < tiers.size(); i++) {
            int comparison = remaining.compareTo(Duration.ofDays(tiers.get(i).minDaysBefore()));
            boolean applies = i == 0 ? comparison > 0 : comparison >= 0;
            if (applies) {
                return i;
            }
        }
        return tiers.size() - 1;
    }

    /**
     * Dernier instant (inclus) où le palier {@code i} s'applique : il reste exactement son seuil ; pour le palier le plus
     * lointain, qui exige strictement plus que son seuil, la dernière seconde avant.
     */
    private static Instant lastInstantOf(Instant departure, List<CancellationFeeProperties.Tier> tiers, int i) {
        Instant threshold = departure.minus(Duration.ofDays(tiers.get(i).minDaysBefore()));
        return i == 0 ? threshold.minusSeconds(1) : threshold;
    }

    private static Instant departureInstant(AnnouncementEntity trip) {
        if (trip.getDepartureAt() != null) {
            return trip.getDepartureAt().toInstant();
        }
        return trip.getDepartureDate().atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
