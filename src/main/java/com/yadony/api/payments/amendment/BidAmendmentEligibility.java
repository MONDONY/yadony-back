package com.yadony.api.payments.amendment;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementPriceGridItemRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.CapacityUnit;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.currency.CurrencyPaymentRails;
import com.yadony.api.payments.supplement.BidSupplementRail;
import com.yadony.api.payments.supplement.BidSupplementRepository;
import com.yadony.api.payments.supplement.BidSupplementStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Un colis peut-il recevoir une modification ? Source unique de la création et du drapeau
 * {@code amendmentAllowed} de l'app : un refus rend toujours le même code.
 *
 * <p>Lecture pure, sans verrou : la création ({@code bids.lockForUpdate}) refait ce contrôle sous le verrou
 * du colis, c'est elle qui garantit l'unicité d'une modification ouverte.
 */
@Component
public class BidAmendmentEligibility {

    /** Rails qu'une modification sait encaisser. WAVE et ORANGE_MONEY (hérités) n'ont aucun moyen de le faire. */
    private static final Set<PaymentMethod> SUPPORTED = EnumSet.of(
            PaymentMethod.STRIPE, PaymentMethod.MOBILE_MONEY, PaymentMethod.CASH);

    private final BidAmendmentProperties properties;
    private final BidAmendmentWindow window;
    private final BidAmendmentRepository amendments;
    private final BidSupplementRepository supplements;
    private final AnnouncementPriceGridItemRepository gridItems;

    public BidAmendmentEligibility(BidAmendmentProperties properties, BidAmendmentWindow window,
                                   BidAmendmentRepository amendments, BidSupplementRepository supplements,
                                   AnnouncementPriceGridItemRepository gridItems) {
        this.properties = properties;
        this.window = window;
        this.amendments = amendments;
        this.supplements = supplements;
        this.gridItems = gridItems;
    }

    /**
     * Moyens de paiement que l'expéditeur peut choisir : ceux de l'annonce, que la devise du colis autorise
     * et que le voyageur peut recevoir ({@link BidSupplementRail#payableBy}). Carte : compte Connect complet ;
     * mobile money : compte de versement dans la devise ; espèces : toujours.
     */
    public Set<PaymentMethod> offerableRails(BidEntity bid, AnnouncementEntity announcement, UserEntity traveler) {
        EnumSet<PaymentMethod> offered = EnumSet.noneOf(PaymentMethod.class);
        if (traveler == null || announcement.getAcceptedPaymentMethods() == null) {
            return offered;
        }
        for (PaymentMethod method : announcement.getAcceptedPaymentMethods()) {
            if (SUPPORTED.contains(method)
                    && CurrencyPaymentRails.allowsCode(bid.getCurrency(), method)
                    && BidSupplementRail.from(method).payableBy(traveler, bid.getCurrency())) {
                offered.add(method);
            }
        }
        return offered;
    }

    /**
     * Code d'erreur du premier refus, ou vide si le colis est éligible. Un refus de fond rend
     * {@code amendment-not-eligible} (négocié, trajet dédié, pas l'expéditeur, fenêtre fermée, rien à ajouter).
     */
    public Optional<String> blockingReason(BidEntity bid, AnnouncementEntity announcement, UserEntity traveler,
                                           UUID callerId) {
        return blockingReason(bid, announcement, traveler, callerId, false);
    }

    /**
     * {@link #blockingReason(BidEntity, AnnouncementEntity, UserEntity, UUID)}, sans la règle « rien à ajouter »
     * quand {@code ignoreNothingToAdd} est vrai. La création et le devis s'en servent quand les kilos demandés
     * dépassent les kilos libres : l'expéditeur apprend alors que la capacité manque (409
     * {@code amendment-capacity-unavailable}), et non que son colis n'est pas modifiable. C'est le cas du
     * perdant d'une course aux derniers kilos sur un trajet sans grille.
     */
    public Optional<String> blockingReason(BidEntity bid, AnnouncementEntity announcement, UserEntity traveler,
                                           UUID callerId, boolean ignoreNothingToAdd) {
        if (!properties.isEnabled()) {
            return Optional.of("amendment-disabled");
        }
        if (bid.getStatus() != BidStatus.ACCEPTED) {
            return Optional.of("invalid-bid-status");
        }
        if (callerId == null || !callerId.equals(bid.getSenderId())
                || bid.getLinkedNegotiationThreadId() != null
                || announcement.getLinkedPackageRequestId() != null) {
            return Optional.of("amendment-not-eligible");
        }
        if (amendments.findFirstByBidIdAndStatusIn(bid.getId(), BidAmendmentStatus.OPEN).isPresent()
                || supplements.existsByBidIdAndStatusIn(bid.getId(), BidSupplementStatus.OPEN)) {
            return Optional.of("amendment-already-open");
        }
        if (amendments.countByBidIdAndAcceptedAtNotNull(bid.getId()) >= properties.getMaxAcceptedPerBid()) {
            return Optional.of("amendment-limit-reached");
        }
        if (window.deadline(bid, announcement).isEmpty()
                || (!ignoreNothingToAdd && !hasSomethingToAdd(announcement))) {
            return Optional.of("amendment-not-eligible");
        }
        if (offerableRails(bid, announcement, traveler).isEmpty()) {
            return Optional.of("amendment-rail-unavailable");
        }
        return Optional.empty();
    }

    public boolean allowed(BidEntity bid, AnnouncementEntity announcement, UserEntity traveler, UUID callerId) {
        return blockingReason(bid, announcement, traveler, callerId).isEmpty();
    }

    /** Erreur HTTP d'un code de {@link #blockingReason} : 409 si une transaction est déjà ouverte, sinon 422. */
    public static YadonyBusinessException exceptionFor(String code) {
        HttpStatus status = "amendment-already-open".equals(code) ? HttpStatus.CONFLICT : HttpStatus.UNPROCESSABLE_ENTITY;
        return new YadonyBusinessException(status, code, "Amendment Unavailable",
                "Ce colis ne peut pas être modifié (" + code + ")");
    }

    /** Des kilos libres vendus au kilo (ou une capacité libre), ou des articles de grille. */
    private boolean hasSomethingToAdd(AnnouncementEntity announcement) {
        boolean freeKg = announcement.getPricePerKg() != null
                && (announcement.getCapacityUnit() == CapacityUnit.KG_FREE
                || (announcement.getAvailableKg() != null && announcement.getAvailableKg().signum() > 0));
        return freeKg || gridItems.existsByAnnouncementId(announcement.getId());
    }
}
