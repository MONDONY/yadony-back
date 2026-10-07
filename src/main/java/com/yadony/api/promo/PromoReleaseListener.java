package com.yadony.api.promo;

import com.yadony.api.cancellation.CancellationReason;
import com.yadony.api.cancellation.events.CancellationConfirmedEvent;
import com.yadony.api.cancellation.events.TripCancelledEvent;
import com.yadony.api.matching.events.BidAwaitingPaymentAbandonedEvent;
import com.yadony.api.matching.events.BidExpiredOnDepartureEvent;
import com.yadony.api.matching.events.BidRejectedEvent;
import com.yadony.api.matching.events.ParcelRefusedEvent;
import com.yadony.api.matching.events.VoyageurNoShowEvent;
import com.yadony.api.payments.events.AdminPaymentRefundedEvent;
import com.yadony.api.payments.events.MobileMoneyPaymentExpiredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.UUID;

/**
 * Rend le code promo quand l'envoi se termine SANS livraison et que Yadony ne conserve pas
 * la commission remisée (remboursée en entier ou jamais prélevée) — décision du propriétaire :
 * l'utilisateur n'a pas profité de la remise.
 *
 * <table>
 *   <caption>Événements écoutés</caption>
 *   <tr><th>Événement</th><th>Argent</th><th>Libère</th></tr>
 *   <tr><td>{@link BidRejectedEvent} (annulation expéditeur/voyageur, refus voyageur,
 *       délai de réponse, trajet supprimé ou retiré, compte supprimé)</td>
 *       <td>carte/mobile money : remboursement intégral ({@code RefundProcessor}) ; espèces :
 *       commission recréditée ({@code BidCancelledCommissionRefundListener})</td><td>oui</td></tr>
 *   <tr><td>{@link TripCancelledEvent} (annulation de trajet, report refusé, annulation après
 *       remise)</td><td>remboursement intégral sur tous les rails</td><td>oui</td></tr>
 *   <tr><td>{@link BidExpiredOnDepartureEvent}</td><td>remboursement intégral (bids PENDING,
 *       PAYMENT_ESCROWED, NEGOTIATING : commission espèces jamais prélevée)</td><td>oui</td></tr>
 *   <tr><td>{@link MobileMoneyPaymentExpiredEvent}</td><td>dépôt jamais reçu</td><td>oui</td></tr>
 *   <tr><td>{@link VoyageurNoShowEvent}</td><td>carte/mobile money remboursés ; espèces :
 *       commission du voyageur conservée</td><td>oui hors espèces</td></tr>
 *   <tr><td>{@link CancellationConfirmedEvent} SENDER_NO_SHOW (confirmation, délai de
 *       contestation, arbitrage admin)</td><td>remboursement intégral sur tous les rails</td>
 *       <td>oui (décision du propriétaire)</td></tr>
 *   <tr><td>{@link ParcelRefusedEvent}</td><td>carte/mobile money remboursés ; espèces :
 *       commission conservée</td><td>oui sur tous les rails (décision explicite du
 *       propriétaire)</td></tr>
 *   <tr><td>{@link AdminPaymentRefundedEvent}</td><td>remboursement manuel par un admin</td>
 *       <td>oui ; paiement porté par un fil : bid(s) matérialisé(s) de ce fil</td></tr>
 *   <tr><td>{@link BidAwaitingPaymentAbandonedEvent}</td><td>PaymentIntent jamais autorisé,
 *       annulé</td><td>oui</td></tr>
 * </table>
 *
 * <p>Bids issus d'un fil de négociation : même traitement que les bids classiques. Leur
 * paiement et leur commission espèces sont résolus via le fil par les écouteurs de
 * remboursement ({@code PaymentRepository#findForBid}, {@code CashCommissionService}), et leur
 * rachat promo est porté par le bid matérialisé ({@code ThreadAcceptedBidListener}).
 *
 * <p>Non écoutés (le code reste consommé) : litiges tranchés sans mouvement d'argent, no-show
 * à la livraison (issue financière décidée au cas par cas, puis éventuellement remboursement
 * admin, qui lui libère).
 *
 * <p>{@code AFTER_COMMIT} : rien n'est rendu si l'annulation est annulée (rollback). Chaque
 * libération ouvre sa propre transaction ({@link PromoService#releaseForBid},
 * {@code REQUIRES_NEW}). Aucune erreur ne remonte : la transaction d'origine est déjà validée.
 * Le paquet {@code promo} ne dépend que des événements des autres paquets ; le rail du bid est
 * lu par {@link PromoRedemptionRepository#findBidPaymentMethod}.
 */
@Component
public class PromoReleaseListener {

    private static final Logger log = LoggerFactory.getLogger(PromoReleaseListener.class);

    static final String REASON_BID_CANCELLED = "BID_CANCELLED";
    static final String REASON_TRIP_CANCELLED = "TRIP_CANCELLED";
    static final String REASON_BID_EXPIRED = "BID_EXPIRED_ON_DEPARTURE";
    static final String REASON_MM_PAYMENT_EXPIRED = "MM_PAYMENT_EXPIRED";
    static final String REASON_TRAVELER_NO_SHOW = "TRAVELER_NO_SHOW";
    static final String REASON_SENDER_NO_SHOW = "SENDER_NO_SHOW";
    static final String REASON_PARCEL_REFUSED = "PARCEL_REFUSED";
    static final String REASON_ADMIN_REFUND = "ADMIN_REFUND";
    static final String REASON_PAYMENT_ABANDONED = "PAYMENT_ABANDONED";

    private static final String CASH = "CASH";

    private final PromoService promoService;
    private final PromoRedemptionRepository redemptionRepository;

    public PromoReleaseListener(PromoService promoService, PromoRedemptionRepository redemptionRepository) {
        this.promoService = promoService;
        this.redemptionRepository = redemptionRepository;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBidRejected(BidRejectedEvent event) {
        release(event.getBidId(), REASON_BID_CANCELLED);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBidExpiredOnDeparture(BidExpiredOnDepartureEvent event) {
        release(event.getBidId(), REASON_BID_EXPIRED);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMobileMoneyPaymentExpired(MobileMoneyPaymentExpiredEvent event) {
        // Le dépôt n'a jamais été reçu : aucune commission prélevée.
        release(event.bidId(), REASON_MM_PAYMENT_EXPIRED);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTravelerNoShow(VoyageurNoShowEvent event) {
        UUID bidId = event.getBidId();
        if (!hasActiveRedemption(bidId)) return;
        String paymentMethod = paymentMethod(bidId);
        if (paymentMethod == null || CASH.equals(paymentMethod)) {
            // Espèces : la commission prélevée au voyageur n'est remboursée par aucun flux.
            log.info("No-show voyageur bid {} : code promo conservé (rail={})", bidId, paymentMethod);
            return;
        }
        releaseNow(bidId, REASON_TRAVELER_NO_SHOW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTripCancelled(TripCancelledEvent event) {
        List<UUID> bidIds = event.getAffectedBidIds();
        if (bidIds == null) return;
        for (UUID bidId : bidIds) {
            release(bidId, REASON_TRIP_CANCELLED);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCancellationConfirmed(CancellationConfirmedEvent event) {
        if (event.reason() != CancellationReason.SENDER_NO_SHOW) return;
        release(event.bidId(), REASON_SENDER_NO_SHOW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onParcelRefused(ParcelRefusedEvent event) {
        release(event.getBidId(), REASON_PARCEL_REFUSED);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBidAwaitingPaymentAbandoned(BidAwaitingPaymentAbandonedEvent event) {
        release(event.bidId(), REASON_PAYMENT_ABANDONED);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAdminPaymentRefunded(AdminPaymentRefundedEvent event) {
        // Le paiement entier est rendu à l'expéditeur : la commission remisée aussi.
        if (event.bidId() != null) {
            release(event.bidId(), REASON_ADMIN_REFUND);
            return;
        }
        if (event.negotiationThreadId() == null) return;
        // Paiement porté par un fil : le rachat est sur le bid matérialisé depuis ce fil.
        List<UUID> bids;
        try {
            bids = redemptionRepository.findBidIdsByNegotiationThreadId(event.negotiationThreadId());
        } catch (RuntimeException e) {
            log.error("Bids du fil {} illisibles : code promo non rendu ({})",
                    event.negotiationThreadId(), e.getMessage(), e);
            return;
        }
        if (bids.isEmpty()) {
            // Remboursé avant matérialisation : aucun rachat n'a été écrit (il naît avec le bid).
            log.info("Remboursement admin du fil {} sans bid matérialisé : aucun code à rendre",
                    event.negotiationThreadId());
        }
        for (UUID bidId : bids) {
            release(bidId, REASON_ADMIN_REFUND);
        }
    }

    /** Libère si le bid porte un rachat actif. */
    private void release(UUID bidId, String reason) {
        if (bidId == null || !hasActiveRedemption(bidId)) return;
        releaseNow(bidId, reason);
    }

    private boolean hasActiveRedemption(UUID bidId) {
        try {
            return bidId != null && !redemptionRepository.findByBidIdAndReleasedAtIsNull(bidId).isEmpty();
        } catch (RuntimeException e) {
            log.error("Lecture des rachats promo du bid {} impossible : {}", bidId, e.getMessage(), e);
            return false;
        }
    }

    private String paymentMethod(UUID bidId) {
        try {
            return redemptionRepository.findBidPaymentMethod(bidId).orElse(null);
        } catch (RuntimeException e) {
            log.error("Lecture du rail du bid {} impossible : {}", bidId, e.getMessage(), e);
            return null;
        }
    }

    private void releaseNow(UUID bidId, String reason) {
        try {
            promoService.releaseForBid(bidId, reason);
        } catch (RuntimeException e) {
            // La transaction d'origine est validée : ne jamais faire échouer l'appelant.
            log.error("Libération du code promo du bid {} ({}) échouée : {}", bidId, reason, e.getMessage(), e);
        }
    }
}
