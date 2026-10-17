package com.yadony.api.cancellation;

import com.yadony.api.common.assistant.ParcelFacts;
import com.yadony.api.common.assistant.ParcelFactsContributor;
import com.yadony.api.common.assistant.ParcelLink;
import com.yadony.api.common.assistant.ParcelRef;
import com.yadony.api.common.assistant.ParcelRole;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Annulation encore possible ou non, retour du colis et absences déclarées, lus avec les règles
 * de ce package ({@link CancellationGuard}, {@link ParcelReturn}, {@link NoShowReasons}). Le code
 * de retour n'est jamais donné : seulement s'il est utilisable.
 */
@Component
@Order(30)
public class CancellationParcelFacts implements ParcelFactsContributor {

    private static final Set<BidStatus> BEFORE_HANDOVER = EnumSet.of(BidStatus.AWAITING_PAYMENT,
            BidStatus.PENDING, BidStatus.PAYMENT_ESCROWED, BidStatus.ACCEPTED);

    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final CancellationRepository cancellationRepository;
    private final Clock clock;

    @Autowired
    public CancellationParcelFacts(BidRepository bidRepository, AnnouncementRepository announcementRepository,
                                   CancellationRepository cancellationRepository) {
        this(bidRepository, announcementRepository, cancellationRepository, Clock.systemDefaultZone());
    }

    CancellationParcelFacts(BidRepository bidRepository, AnnouncementRepository announcementRepository,
                            CancellationRepository cancellationRepository, Clock clock) {
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.cancellationRepository = cancellationRepository;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public ParcelFacts facts(ParcelRef parcel) {
        BidEntity bid = bidRepository.findById(parcel.bidId()).orElse(null);
        if (bid == null) return ParcelFacts.NONE;
        List<String> lines = new ArrayList<>();
        Set<ParcelLink> links = EnumSet.noneOf(ParcelLink.class);
        ParcelRole role = parcel.role();

        cancellability(bid, role, lines);
        parcelReturn(bid, role, lines, links);
        noShows(bid, role, lines);
        return new ParcelFacts(lines, links);
    }

    private void cancellability(BidEntity bid, ParcelRole role, List<String> lines) {
        boolean beforeHandover = BEFORE_HANDOVER.contains(bid.getStatus());
        boolean inHands = bid.getStatus() == BidStatus.HANDED_OVER
                || bid.getStatus() == BidStatus.IN_TRANSIT || bid.getStatus() == BidStatus.ARRIVED;
        if (!beforeHandover && !inHands) return;
        if (role == ParcelRole.RECIPIENT) {
            lines.add("Only the sender or the traveler can cancel this parcel.");
            return;
        }
        if (beforeHandover) {
            lines.add("Cancellation is still possible from the parcel page.");
            return;
        }
        AnnouncementEntity announcement = bid.getAnnouncementId() == null ? null
                : announcementRepository.findById(bid.getAnnouncementId()).orElse(null);
        if (bid.getStatus() == BidStatus.HANDED_OVER && !CancellationGuard.hasDeparted(announcement)) {
            lines.add("Cancellation is still possible until departure; because the traveler already has the "
                    + "parcel, cancelling opens a return: the traveler must give it back to the sender within "
                    + ParcelReturn.RETURN_DAYS + " days.");
        } else {
            lines.add("Cancellation is no longer possible: the parcel is in transit or the trip has departed. "
                    + "If something goes wrong at delivery, the team can help.");
        }
    }

    private void parcelReturn(BidEntity bid, ParcelRole role, List<String> lines, Set<ParcelLink> links) {
        if (bid.getReturnDeadline() == null) return;
        if (bid.getReturnedAt() != null) {
            lines.add("The parcel was returned to the sender on " + bid.getReturnedAt().toLocalDate() + ".");
            return;
        }
        if (role == ParcelRole.RECIPIENT) {
            lines.add("The parcel is being returned to the sender.");
            return;
        }
        String deadline = bid.getReturnDeadline().toLocalDate().toString();
        lines.add(role == ParcelRole.SENDER
                ? "A return is in progress: the traveler must give the parcel back before " + deadline
                + ". The sender holds the return code, shown in the parcel page, and gives it to the traveler "
                + "only when getting the parcel back."
                : "A return is in progress: the traveler must give the parcel back to the sender before " + deadline
                + " and enter the sender's return code in the parcel page.");
        LocalDateTime now = LocalDateTime.now(clock);
        if (bid.getReturnCodeExpiry() != null && now.isAfter(bid.getReturnCodeExpiry())) {
            lines.add("The return code has expired: the team must be contacted.");
            links.add(ParcelLink.SUPPORT);
        } else if (bid.getReturnCodeAttempts() >= CancellationService.MAX_RETURN_CODE_ATTEMPTS) {
            lines.add("The return code is blocked after " + CancellationService.MAX_RETURN_CODE_ATTEMPTS
                    + " wrong entries: the team must be contacted.");
            links.add(ParcelLink.SUPPORT);
        }
    }

    private void noShows(BidEntity bid, ParcelRole role, List<String> lines) {
        for (CancellationEntity c : cancellationRepository.findAllByBidId(bid.getId())) {
            if (!NoShowReasons.isNoShow(c.getReason())) continue;
            String accused = NoShowReasons.accusedRole(c.getReason());
            // Le destinataire n'apprend que l'absence qui le concerne.
            if (role == ParcelRole.RECIPIENT && !"RECIPIENT".equals(accused)) continue;
            String who = "The " + accused.toLowerCase(Locale.ROOT) + " was declared absent by the "
                    + NoShowReasons.declarantRole(c.getReason()).toLowerCase(Locale.ROOT)
                    + (c.getScope() == CancellationScope.DELIVERY ? " at delivery" : " at handover");
            lines.add(who + switch (c.getNoShowStatus()) {
                case PENDING_CONFIRMATION -> "; it can be contested"
                        + (c.getContestationDeadline() != null
                        ? " until " + c.getContestationDeadline().toLocalDate() : "") + " from the parcel page.";
                case CONTESTED -> "; it was contested, so a dispute is open and the team decides.";
                case CONFIRMED -> "; the absence is confirmed.";
                case RESOLVED -> "; the team has made its decision.";
            });
        }
    }
}
