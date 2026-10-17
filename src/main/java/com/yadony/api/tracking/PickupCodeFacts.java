package com.yadony.api.tracking;

import com.yadony.api.common.assistant.ParcelFacts;
import com.yadony.api.common.assistant.ParcelFactsContributor;
import com.yadony.api.common.assistant.ParcelLink;
import com.yadony.api.common.assistant.ParcelRef;
import com.yadony.api.common.assistant.ParcelRole;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.PickupCodes;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * État du code de retrait, jamais sa valeur : l'assistant renvoie vers l'écran qui l'affiche.
 * Mêmes règles que {@link TrackingService#refreshConfirmationCode} et
 * {@link PickupCodeRequestService#requestNewCode}.
 */
@Component
@Order(20)
public class PickupCodeFacts implements ParcelFactsContributor {

    private final BidRepository bidRepository;
    private final Clock clock;

    @Autowired
    public PickupCodeFacts(BidRepository bidRepository) {
        this(bidRepository, Clock.systemUTC());
    }

    PickupCodeFacts(BidRepository bidRepository, Clock clock) {
        this.bidRepository = bidRepository;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public ParcelFacts facts(ParcelRef parcel) {
        BidEntity bid = bidRepository.findById(parcel.bidId()).orElse(null);
        if (bid == null) return ParcelFacts.NONE;
        LocalDateTime now = LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
        List<String> lines = new ArrayList<>();
        Set<ParcelLink> links = EnumSet.noneOf(ParcelLink.class);
        ParcelRole role = parcel.role();

        if (bid.getStatus() == BidStatus.ACCEPTED) {
            lines.add(switch (role) {
                case SENDER -> "The pickup code does not exist yet: it is created when the traveler scans the "
                        + "parcel at handover. The sender then sees it in the parcel page.";
                case TRAVELER -> "At delivery, the recipient gives the traveler the pickup code; the traveler never "
                        + "receives it in advance.";
                case RECIPIENT -> "The pickup code will appear in the recipient's Receptions page once the traveler "
                        + "has the parcel.";
            });
        } else if (PickupCodes.IN_TRAVELER_HANDS.contains(bid.getStatus())) {
            if (PickupCodes.renewalNeeded(bid, now)) {
                lines.add(switch (role) {
                    case SENDER -> "The pickup code is no longer usable (blocked after "
                            + TrackingService.MAX_CODE_ATTEMPTS + " wrong entries, or expired). Only the sender can "
                            + "generate a new one, from the parcel page (Regenerate the code).";
                    case TRAVELER -> "The pickup code is no longer usable (blocked or expired). Only the sender can "
                            + "generate a new one; the traveler can ask the sender for one from the parcel page, at most "
                            + "once every " + PickupCodeRequestService.COOLDOWN.toMinutes() + " minutes.";
                    case RECIPIENT -> "The pickup code is no longer usable (blocked or expired): the sender must "
                            + "generate a new one.";
                });
            } else {
                lines.add(switch (role) {
                    case SENDER -> "The pickup code is valid. The sender sees it in the parcel page and gives it only "
                            + "to the recipient, never to the traveler.";
                    case TRAVELER -> "The pickup code is valid. The recipient gives it to the traveler at delivery; "
                            + "wrong entries so far: " + bid.getConfirmationCodeAttempts() + " of "
                            + TrackingService.MAX_CODE_ATTEMPTS + ".";
                    case RECIPIENT -> "The pickup code is valid and shown in the recipient's Receptions page for this "
                            + "parcel. The recipient gives it to the traveler only when receiving the parcel.";
                });
            }
            if (role == ParcelRole.SENDER) {
                int left = refreshesLeft(bid, now);
                lines.add(left > 0
                        ? "The sender can still regenerate the pickup code " + left + " time(s) in the next 24 hours."
                        : "The sender has reached the limit of " + TrackingService.MAX_CODE_REFRESHES_PER_DAY
                        + " regenerations in 24 hours; the button comes back after that delay.");
                if (left > 0) links.add(ParcelLink.RENEW_PICKUP_CODE);
            }
        }
        return new ParcelFacts(lines, links);
    }

    /** Régénérations encore possibles dans la fenêtre glissante de 24 h. */
    static int refreshesLeft(BidEntity bid, LocalDateTime now) {
        LocalDateTime windowStart = bid.getConfirmationCodeRefreshWindowStart();
        if (windowStart == null || !windowStart.isAfter(now.minusHours(24))) {
            return TrackingService.MAX_CODE_REFRESHES_PER_DAY;
        }
        return Math.max(0, TrackingService.MAX_CODE_REFRESHES_PER_DAY - bid.getConfirmationCodeRefreshCount());
    }
}
