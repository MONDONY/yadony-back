package com.yadony.api.payments;

import com.yadony.api.matching.TravelerParcelsNetPort;
import com.yadony.api.payments.overview.TravelerParcelNet;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Implémentation du port côté {@code payments/} : le net de chaque colis vient de
 * {@link TravelerParcelNet}, donc du même calcul que « Mon argent » (hors bon de parrainage, connu
 * seulement au versement). Un colis en espèces n'a pas de paiement et compte pour zéro. Deux
 * requêtes au plus, quel que soit le nombre de colis.
 */
@Component
public class TravelerParcelsNetAdapter implements TravelerParcelsNetPort {

    private final PaymentRepository paymentRepository;

    public TravelerParcelsNetAdapter(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal estimatedNet(Collection<UUID> bidIds, String currency) {
        if (bidIds == null || bidIds.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = BigDecimal.ZERO;
        Set<UUID> withPayment = new HashSet<>();
        for (PaymentEntity payment : paymentRepository.findByBidIdIn(bidIds)) {
            withPayment.add(payment.getBidId());
            if (sameCurrency(payment, currency)) {
                total = total.add(net(payment));
            }
        }
        // Colis issus d'une négociation : paiement rattaché au fil (bid_id NULL), lu par lot.
        Set<UUID> withoutDirect = new HashSet<>(bidIds);
        withoutDirect.removeAll(withPayment);
        if (!withoutDirect.isEmpty()) {
            for (Object[] pair : paymentRepository.findLinkedNegotiationPaymentsOfBids(withoutDirect)) {
                PaymentEntity linked = (PaymentEntity) pair[1];
                if (sameCurrency(linked, currency)) {
                    total = total.add(net(linked));
                }
            }
        }
        return total;
    }

    /** Le total est étiqueté dans la devise du trajet, comme « Mon argent » qui groupe par devise. */
    private static boolean sameCurrency(PaymentEntity payment, String currency) {
        return payment.getCurrency() != null && payment.getCurrency().equalsIgnoreCase(currency);
    }

    private static BigDecimal net(PaymentEntity payment) {
        return TravelerParcelNet.of(payment.getStatus() == null ? null : payment.getStatus().name(),
                payment.getAmount(), payment.getCommissionAmount(), payment.getRefundedAmount());
    }
}
