package com.yadony.api.payments;

import com.yadony.api.common.assistant.ParcelFacts;
import com.yadony.api.common.assistant.ParcelFactsContributor;
import com.yadony.api.common.assistant.ParcelLink;
import com.yadony.api.common.assistant.ParcelRef;
import com.yadony.api.common.assistant.ParcelRole;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationRepository;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Paiement et remboursement d'un colis, du point de vue du demandeur : l'expéditeur apprend où en
 * est son argent, le voyageur où en est son versement ou sa commission, le destinataire rien.
 * Lu comme {@link RefundProcessor} : le paiement d'un bid se résout par {@link PaymentRepository#findForBid}.
 */
@Component
@Order(40)
public class PaymentParcelFacts implements ParcelFactsContributor {

    private final BidRepository bidRepository;
    private final PaymentRepository paymentRepository;
    private final PawapayOperationRepository pawapayOperations;

    public PaymentParcelFacts(BidRepository bidRepository, PaymentRepository paymentRepository,
                              PawapayOperationRepository pawapayOperations) {
        this.bidRepository = bidRepository;
        this.paymentRepository = paymentRepository;
        this.pawapayOperations = pawapayOperations;
    }

    @Override
    @Transactional(readOnly = true)
    public ParcelFacts facts(ParcelRef parcel) {
        if (parcel.role() == ParcelRole.RECIPIENT) {
            return ParcelFacts.of(List.of("Payment details are visible only to the sender and the traveler."));
        }
        BidEntity bid = bidRepository.findById(parcel.bidId()).orElse(null);
        if (bid == null) return ParcelFacts.NONE;
        List<String> lines = new ArrayList<>();
        Set<ParcelLink> links = EnumSet.noneOf(ParcelLink.class);
        if (bid.getPaymentMethod() == PaymentMethod.CASH) {
            cash(bid, parcel.role(), lines, links);
        } else {
            Optional<PaymentEntity> payment = paymentRepository.findForBid(bid.getId());
            String method = bid.getPaymentMethod() == PaymentMethod.STRIPE ? "card" : "mobile money";
            lines.add("Payment method: " + method + ".");
            if (payment.isEmpty()) {
                lines.add("No payment has been made for this parcel yet.");
            } else if (parcel.role() == ParcelRole.SENDER) {
                sender(payment.get(), lines, links);
            } else {
                traveler(payment.get(), lines, links);
            }
        }
        return new ParcelFacts(lines, links);
    }

    private void sender(PaymentEntity p, List<String> lines, Set<ParcelLink> links) {
        switch (p.getStatus()) {
            case PENDING -> lines.add("The payment is not completed yet: nothing has been charged.");
            case ESCROW -> lines.add("The sender has paid; Yadony holds the money until delivery is confirmed, "
                    + "and refunds it in full automatically if the parcel is cancelled before transit.");
            case RELEASED -> lines.add("Delivery was confirmed and the money was released to the traveler: "
                    + "no automatic refund is possible any more. If there is a problem, the team can help.");
            case FAILED -> lines.add("The payment failed: nothing was charged.");
            case CANCELLED -> lines.add("The payment was cancelled: nothing was charged.");
            case REFUNDED -> refunded(p, lines, links);
        }
    }

    private void refunded(PaymentEntity p, List<String> lines, Set<ParcelLink> links) {
        BigDecimal amount = p.getRefundedAmount() != null && p.getRefundedAmount().signum() > 0
                ? p.getRefundedAmount() : p.getAmount();
        String refund = "The sender was refunded" + (amount != null ? " " + amount.toPlainString() + " "
                + p.getCurrency() : "");
        if (p.getRail() != PaymentRail.PAWAPAY) {
            lines.add(refund + " to the card used; the bank credits it after processing, the delay depends on the bank.");
            return;
        }
        Optional<PawapayOperationStatus> status = pawapayOperations
                .findFirstByPaymentIdAndKindOrderByCreatedAtDesc(p.getId(), PawapayOperationKind.REFUND)
                .map(PawapayOperationEntity::getStatus);
        if (status.isPresent() && status.get() == PawapayOperationStatus.COMPLETED) {
            lines.add(refund + " to the mobile money account used.");
        } else if (status.isPresent() && status.get().isFinal()) {
            lines.add("The mobile money refund failed: the team must be contacted.");
            links.add(ParcelLink.SUPPORT);
        } else {
            lines.add("The mobile money refund is in progress; the delay depends on the operator.");
        }
    }

    private void traveler(PaymentEntity p, List<String> lines, Set<ParcelLink> links) {
        switch (p.getStatus()) {
            case PENDING, FAILED -> lines.add("The sender's payment is not completed.");
            case ESCROW -> lines.add("The sender's payment is held by Yadony; the traveler is paid once delivery "
                    + "is confirmed with the pickup code.");
            case RELEASED -> {
                lines.add("Delivery was confirmed and the payment was released to the traveler"
                        + (p.getEscrowReleasedAt() != null ? " on " + p.getEscrowReleasedAt().toLocalDate() : "")
                        + "; the payout usually arrives within 24 hours, up to 48 hours depending on the bank.");
                if (p.getPayoutHeldAt() != null) {
                    lines.add("The payout is on hold: the team must be contacted.");
                    links.add(ParcelLink.SUPPORT);
                }
            }
            case REFUNDED, CANCELLED -> lines.add("The sender was refunded: there is no payment for this parcel.");
        }
    }

    private static void cash(BidEntity bid, ParcelRole role, List<String> lines, Set<ParcelLink> links) {
        if (role == ParcelRole.SENDER) {
            lines.add("Payment method: cash, paid to the traveler at handover. Yadony holds no money for this "
                    + "parcel, so there is nothing to refund online.");
            return;
        }
        lines.add("Payment method: cash, received at handover; the traveler pays the Yadony commission online.");
        if (bid.getCommissionStatus() == null) return;
        switch (bid.getCommissionStatus()) {
            case PENDING, REQUIRES_3DS, FAILED -> lines.add("The commission is not paid yet.");
            case CHARGED -> lines.add("The commission is paid; it is refunded automatically if the parcel is cancelled.");
            case REFUNDED -> lines.add("The commission was refunded"
                    + (bid.getCommissionChargedVia() != null
                    ? " to the " + (bid.getCommissionChargedVia().name().equals("WALLET") ? "wallet balance" : "card")
                    : "") + ".");
            case REFUND_FAILED -> {
                lines.add("The commission refund failed: the team must be contacted.");
                links.add(ParcelLink.SUPPORT);
            }
        }
    }
}
