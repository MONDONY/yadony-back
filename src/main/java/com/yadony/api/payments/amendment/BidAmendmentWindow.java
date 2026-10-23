package com.yadony.api.payments.amendment;

import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.payments.supplement.BidSupplementWindow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Fenêtre de réponse du voyageur à une modification de colis. L'échéance vaut
 * {@code min(maintenant + expiry, départ − departure-margin, date limite de remise du colis)} ; elle est
 * absente (modification indisponible) quand elle laisse moins de {@code min-response-window}.
 * Le départ se calcule comme pour le supplément ({@link BidSupplementWindow#departure}).
 */
@Component
public class BidAmendmentWindow {

    private final BidAmendmentProperties properties;
    private final Clock clock;

    @Autowired
    public BidAmendmentWindow(BidAmendmentProperties properties) {
        this(properties, Clock.systemUTC());
    }

    BidAmendmentWindow(BidAmendmentProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** Maintenant, en UTC (colonnes {@code TIMESTAMP} de la base). */
    public LocalDateTime now() {
        return LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
    }

    /**
     * Échéance d'une modification créée maintenant, ou vide si la fenêtre est fermée ou trop courte pour
     * être utile. {@code bid.handoverDeadline} peut être nul : il n'entre alors pas dans le minimum.
     */
    public Optional<LocalDateTime> deadline(BidEntity bid, AnnouncementEntity announcement) {
        LocalDateTime now = now();
        LocalDateTime deadline = now.plus(properties.getExpiry());
        Instant departure = BidSupplementWindow.departure(announcement);
        if (departure != null) {
            LocalDateTime latest = LocalDateTime.ofInstant(departure.minus(properties.getDepartureMargin()), ZoneOffset.UTC);
            if (latest.isBefore(deadline)) {
                deadline = latest;
            }
        }
        LocalDateTime handover = bid.getHandoverDeadline();
        if (handover != null && handover.isBefore(deadline)) {
            deadline = handover;
        }
        return deadline.isBefore(now.plus(properties.getMinResponseWindow())) ? Optional.empty() : Optional.of(deadline);
    }
}
