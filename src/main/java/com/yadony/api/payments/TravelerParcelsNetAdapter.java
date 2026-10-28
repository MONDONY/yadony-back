package com.yadony.api.payments;

import com.yadony.api.matching.TravelerParcelsNetPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Implémentation du port côté {@code payments/} : net estimé = montant payé − commission, la formule
 * de « Mon argent » ({@code MoneyStateResolver#amountFor}), hors bon de parrainage (connu seulement
 * au versement, et {@link ReleaseNetCalculator} le consomme). Les paiements remboursés sont exclus ;
 * un colis en espèces n'a pas de paiement et compte pour zéro.
 */
@Component
public class TravelerParcelsNetAdapter implements TravelerParcelsNetPort {

    private final PaymentRepository paymentRepository;

    public TravelerParcelsNetAdapter(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal estimatedNet(Collection<UUID> bidIds) {
        if (bidIds == null || bidIds.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = BigDecimal.ZERO;
        Set<UUID> withPayment = new HashSet<>();
        for (PaymentEntity payment : paymentRepository.findByBidIdIn(bidIds)) {
            withPayment.add(payment.getBidId());
            total = total.add(net(payment));
        }
        // Colis issus d'une négociation : paiement rattaché au fil (bid_id NULL), un bid à la fois.
        for (UUID bidId : bidIds) {
            if (!withPayment.contains(bidId)) {
                total = total.add(paymentRepository.findLinkedNegotiationPaymentOfBid(bidId)
                        .map(TravelerParcelsNetAdapter::net).orElse(BigDecimal.ZERO));
            }
        }
        return total;
    }

    private static BigDecimal net(PaymentEntity payment) {
        if (payment.getStatus() == PaymentStatus.REFUNDED || payment.getAmount() == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal commission = payment.getCommissionAmount() == null ? BigDecimal.ZERO : payment.getCommissionAmount();
        return payment.getAmount().subtract(commission).max(BigDecimal.ZERO);
    }
}
