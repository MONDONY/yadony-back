package com.yadony.api.payments.supplement;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TripTimezones;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Fenêtre d'une demande de supplément : colis accepté, ou remis avant le départ (même fenêtre que
 * le refus du colis), et assez de temps pour répondre. L'échéance vaut
 * {@code min(maintenant + expiry, départ − departure-margin)} : la fenêtre de refus du colis reste
 * ainsi ouverte après l'expiration.
 */
@Component
public class BidSupplementWindow {

    private final BidSupplementProperties properties;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public BidSupplementWindow(BidSupplementProperties properties) {
        this(properties, Clock.systemUTC());
    }

    BidSupplementWindow(BidSupplementProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** Maintenant, en UTC (colonnes {@code TIMESTAMP} de la base). */
    public LocalDateTime now() {
        return LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
    }

    /**
     * Échéance d'une demande créée maintenant.
     *
     * @throws YadonyBusinessException 422 {@code invalid-bid-status} hors ACCEPTED / HANDED_OVER,
     *         409 {@code supplement-locked} si le départ est passé ou trop proche.
     */
    public LocalDateTime requireOpen(BidEntity bid, AnnouncementEntity announcement) {
        if (bid.getStatus() != BidStatus.ACCEPTED && bid.getStatus() != BidStatus.HANDED_OVER) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-bid-status",
                    "Unprocessable", "Un supplément ne se demande que sur un colis accepté ou remis avant le départ");
        }
        return expiresAt(announcement).orElseThrow(() -> new YadonyBusinessException(HttpStatus.CONFLICT,
                "supplement-locked", "Supplement Locked", "Le départ est trop proche pour demander un supplément"));
    }

    /** Même règle que {@link #requireOpen}, sans exception : pour {@code supplementRequestAllowed}. */
    public boolean isOpen(BidEntity bid, AnnouncementEntity announcement) {
        return (bid.getStatus() == BidStatus.ACCEPTED || bid.getStatus() == BidStatus.HANDED_OVER)
                && expiresAt(announcement).isPresent();
    }

    private Optional<LocalDateTime> expiresAt(AnnouncementEntity announcement) {
        LocalDateTime now = now();
        LocalDateTime expiresAt = now.plus(properties.getExpiry());
        Instant departure = departure(announcement);
        if (departure != null) {
            LocalDateTime latest = LocalDateTime.ofInstant(departure.minus(properties.getDepartureMargin()), ZoneOffset.UTC);
            if (latest.isBefore(expiresAt)) {
                expiresAt = latest;
            }
        }
        return expiresAt.isBefore(now.plus(properties.getMinResponseWindow())) ? Optional.empty() : Optional.of(expiresAt);
    }

    /**
     * Départ du trajet : heure réelle si elle est connue, sinon le lendemain de la date de départ
     * dans le fuseau du trajet (même repli que {@code CancellationGuard.hasDeparted}).
     */
    private static Instant departure(AnnouncementEntity announcement) {
        if (announcement == null) {
            return null;
        }
        if (announcement.getDepartureAt() != null) {
            return announcement.getDepartureAt().toInstant();
        }
        if (announcement.getDepartureDate() != null) {
            return announcement.getDepartureDate().plusDays(1)
                    .atStartOfDay(TripTimezones.zoneOf(announcement.getTimezone())).toInstant();
        }
        return null;
    }
}
