package com.yadony.api.payments.supplement;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.PayoutTrigger;
import com.yadony.api.matching.TripTimezones;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

/**
 * Fenêtre d'une demande de supplément : colis accepté, ou remis avant le départ (même fenêtre que
 * le refus du colis), et assez de temps pour répondre. L'échéance vaut
 * {@code min(maintenant + expiry, départ − departure-margin)} : la fenêtre de refus du colis reste
 * ainsi ouverte après l'expiration.
 *
 * <p>Colis payé au dépôt ({@link PayoutTrigger#DEPOSIT}) : fermée dès le dépôt (scan DEPART). Le transporteur a été
 * payé à cet instant ; un supplément ne serait plus versé avec le principal, et son prix ne change plus (décision du
 * propriétaire du 11/11).
 */
@Component
public class BidSupplementWindow {

    /** Statuts d'un colis déposé : {@link PayoutTrigger#DEPOSITED_STATUSES} (définition unique). */
    private static final Set<BidStatus> DEPOSITED = PayoutTrigger.DEPOSITED_STATUSES;

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

    /** Délai laissé au voyageur pour confirmer la 3DS de la commission d'un supplément espèces. */
    public java.time.Duration commissionConfirmWindow() {
        return properties.getCommissionConfirmWindow();
    }

    /** Maintenant, en UTC (colonnes {@code TIMESTAMP} de la base). */
    public LocalDateTime now() {
        return LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
    }

    /**
     * Échéance d'une demande créée maintenant.
     *
     * @throws YadonyBusinessException 422 {@code supplement-after-deposit} pour un colis payé au dépôt déjà déposé,
     *         422 {@code invalid-bid-status} hors ACCEPTED / HANDED_OVER,
     *         409 {@code supplement-locked} si le départ est passé ou trop proche.
     */
    public LocalDateTime requireOpen(BidEntity bid, AnnouncementEntity announcement) {
        if (depositedAndPaidAtDeposit(bid)) {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "supplement-after-deposit",
                    "problem.supplement-after-deposit");
        }
        if (bid.getStatus() != BidStatus.ACCEPTED && bid.getStatus() != BidStatus.HANDED_OVER) {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-bid-status", "problem.invalid-bid-status.supplement-demande-que-sur");
        }
        return expiresAt(announcement).orElseThrow(() -> YadonyBusinessException.localized(HttpStatus.CONFLICT, "supplement-locked", "problem.supplement-locked"));
    }

    /** Même règle que {@link #requireOpen}, sans exception : pour {@code supplementRequestAllowed}. */
    public boolean isOpen(BidEntity bid, AnnouncementEntity announcement) {
        return (bid.getStatus() == BidStatus.ACCEPTED || bid.getStatus() == BidStatus.HANDED_OVER)
                && !depositedAndPaidAtDeposit(bid)
                && expiresAt(announcement).isPresent();
    }

    /** Colis payé au dépôt et déjà déposé : aucun supplément. */
    static boolean depositedAndPaidAtDeposit(BidEntity bid) {
        return bid.getPayoutTrigger() == PayoutTrigger.DEPOSIT && DEPOSITED.contains(bid.getStatus());
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
     * dans le fuseau du trajet (même repli que {@code CancellationGuard.hasDeparted}). Partagé avec la
     * fenêtre d'une modification de colis ({@code BidAmendmentWindow}).
     */
    public static Instant departure(AnnouncementEntity announcement) {
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
