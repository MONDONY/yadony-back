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
        long hours = Duration.between(now, departureInstant(trip)).toHours();
        List<CancellationFeeProperties.Tier> tiers = props.tiers();
        for (int i = 0; i < tiers.size(); i++) {
            long thresholdHours = tiers.get(i).minDaysBefore() * 24L;
            boolean applies = i == 0 ? hours > thresholdHours : hours >= thresholdHours;
            if (applies) {
                return tiers.get(i).rate();
            }
        }
        return tiers.get(tiers.size() - 1).rate();
    }

    private static Instant departureInstant(AnnouncementEntity trip) {
        if (trip.getDepartureAt() != null) {
            return trip.getDepartureAt().toInstant();
        }
        return trip.getDepartureDate().atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
