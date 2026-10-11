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
 * 9.25). Les gardes ont déjà été évaluées sous verrou lors du claim ; entre ce claim et l'appel,
 * le paiement est RELEASED (réservé), état que le remboursement, l'annulation et l'ouverture d'un
 * litige admin refusent déjà (ils exigent ESCROW). Restent hors de notre contrôle : un litige
 * bancaire (webhook Stripe) et le gel d'un voyageur, revérifiés ici au plus près de l'appel.
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
        if (!intent.isGuardsOverridden()) {
            if (payment.isDisputed()) {
                return "litige bancaire ouvert";
            }
            if (intent.getTravelerId() != null && holdPolicy.isHeld(intent.getTravelerId())) {
                return "versements du voyageur gelés";
            }
        }
        return null;
    }
}
