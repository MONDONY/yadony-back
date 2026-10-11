package com.yadony.api.payments;

import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.disputes.DisputeTypes;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.hold.PayoutHoldPolicy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Revérification des gardes de versement juste avant l'appel Stripe (PROD_CHECKLIST 9.23), dans
 * une transaction courte, colis verrouillé AVANT la relecture du paiement (ordre colis → paiement,
 * 9.25). Les gardes ont déjà été évaluées sous verrou lors du claim ; entre ce claim et l'appel, le
 * paiement est RELEASED (réservé), état que le remboursement, l'annulation, la retenue et
 * l'ouverture d'un litige admin refusent (ils exigent ESCROW).
 *
 * <p><b>Fenêtre résiduelle, assumée et documentée</b> : le verrou est rendu au commit de cette
 * vérification, AVANT l'appel Stripe (le tenir pendant l'appel est précisément ce que 9.23
 * supprime). Un litige bancaire (webhook {@code charge.dispute.created}, que nous ne pouvons pas
 * refuser) ou le gel d'un voyageur qui tombe entre ce commit et la réponse de Stripe (quelques
 * centaines de millisecondes, au pire la durée de l'appel) ne bloque donc pas ce Transfer-là. Le
 * paiement est alors traité comme tout litige bancaire survenu après versement (procédure
 * chargeback existante, décision admin). Avant ce lot, la même fenêtre existait sous une autre forme (gel
 * et litige bancaire étaient lus avant le claim, sans verrou).
 */
@Component
public class StripeReleaseGuard implements StripeReleaseExecutor.ReleaseGuard {

    /** Fins de colis où l'argent revient à l'expéditeur : jamais de versement au voyageur. */
    static final Set<BidStatus> NO_PAYOUT_BID_STATUSES = EnumSet.of(BidStatus.CANCELLED, BidStatus.REJECTED,
            BidStatus.EXPIRED, BidStatus.NO_SHOW, BidStatus.PARCEL_REFUSED);

    private final PaymentRepository paymentRepository;
    private final StripeReleaseIntentRepository intentRepository;
    private final BidRepository bidRepository;
    private final DisputeRepository disputeRepository;
    private final PayoutHoldPolicy holdPolicy;

    public StripeReleaseGuard(PaymentRepository paymentRepository, StripeReleaseIntentRepository intentRepository,
                              BidRepository bidRepository, DisputeRepository disputeRepository,
                              PayoutHoldPolicy holdPolicy) {
        this.paymentRepository = paymentRepository;
        this.intentRepository = intentRepository;
        this.bidRepository = bidRepository;
        this.disputeRepository = disputeRepository;
        this.holdPolicy = holdPolicy;
    }

    /** Dans la transaction de l'appelant (aucune propagation propre) : verrou du colis, pris en premier. */
    @Override
    public void lockParcel(UUID bidId) {
        if (bidId != null) {
            bidRepository.lockForUpdate(bidId);
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String blockingReason(StripeReleaseIntentEntity intent) {
        UUID bidId = intent.getBidId();
        BidEntity bid = null;
        if (bidId != null && bidRepository.lockForUpdate(bidId) > 0) {
            bid = bidRepository.findById(bidId).orElse(null);
        }
        StripeReleaseIntentEntity.Status intentStatus = intentRepository.findById(intent.getId())
                .map(StripeReleaseIntentEntity::getStatus).orElse(null);
        if (intentStatus != StripeReleaseIntentEntity.Status.PENDING) {
            return "réservation " + intentStatus;
        }
        PaymentEntity payment = paymentRepository.findById(intent.getPaymentId()).orElse(null);
        if (payment == null || payment.getStatus() != PaymentStatus.RELEASED) {
            return "paiement plus réservé (" + (payment != null ? payment.getStatus() : null) + ")";
        }
        if (bid != null && NO_PAYOUT_BID_STATUSES.contains(bid.getStatus())) {
            return "colis " + bid.getStatus();
        }
        if (payment.getRefundedAmount() != null && payment.getRefundedAmount().signum() > 0) {
            return "remboursement partiel passé";
        }
        if (bidId != null && disputeRepository.existsByBidIdAndStatusAndTypeStartingWith(
                bidId, DisputeTypes.STATUS_OPEN, DisputeTypes.ADMIN_PREFIX)) {
            return "litige admin ouvert";
        }
        // Dérogation admin : garde par garde, jamais globale.
        if (payment.isDisputed() && !intent.overrides(StripeReleaseIntentEntity.GUARD_DISPUTED)) {
            return "litige bancaire ouvert";
        }
        if (intent.getTravelerId() != null && !intent.overrides(StripeReleaseIntentEntity.GUARD_BENEFICIARY_HELD)
                && holdPolicy.isHeld(intent.getTravelerId())) {
            return "versements du voyageur gelés";
        }
        return null;
    }
}
