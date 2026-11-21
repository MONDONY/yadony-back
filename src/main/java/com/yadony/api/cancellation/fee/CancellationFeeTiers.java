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
     * sauté. {@code null} au palier le plus cher (aucune hausse à venir). Même mesure en heures entières que
     * {@link #rateFor} : la fenêtre gratuite ({@code 5 -> 0}, strictement plus de 120 h) tient jusqu'à J-121 h, les
     * autres paliers jusqu'à J-{@code minDaysBefore * 24} h.
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

    /** Palier applicable : le premier, du plus lointain au plus proche, dont le seuil est atteint ; sinon le dernier. */
    private static int tierIndex(AnnouncementEntity trip, Instant now, List<CancellationFeeProperties.Tier> tiers) {
        long hours = Duration.between(now, departureInstant(trip)).toHours();
        for (int i = 0; i < tiers.size(); i++) {
            long thresholdHours = tiers.get(i).minDaysBefore() * 24L;
            boolean applies = i == 0 ? hours > thresholdHours : hours >= thresholdHours;
            if (applies) {
                return i;
            }
        }
        return tiers.size() - 1;
    }

    /**
     * Dernier instant (inclus) où le palier {@code i} s'applique : il reste exactement son seuil en heures entières, une
     * heure de plus pour le palier le plus lointain, qui exige strictement plus que son seuil.
     */
    private static Instant lastInstantOf(Instant departure, List<CancellationFeeProperties.Tier> tiers, int i) {
        long hours = tiers.get(i).minDaysBefore() * 24L + (i == 0 ? 1 : 0);
        return departure.minus(Duration.ofHours(hours));
    }

    private static Instant departureInstant(AnnouncementEntity trip) {
        if (trip.getDepartureAt() != null) {
            return trip.getDepartureAt().toInstant();
        }
        return trip.getDepartureDate().atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
