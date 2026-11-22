package com.yadony.api.payments.fee;

import com.yadony.api.cancellation.fee.SenderRefundInFlightPort;
import com.yadony.api.payments.PaymentRail;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Réponse du paquet {@code payments} au port {@link SenderRefundInFlightPort} : un remboursement pawaPay du paiement du
 * colis est-il vivant ou abouti ({@code findLive}, tout sauf FAILED / SUBMIT_REJECTED) ? Rail carte : jamais, le
 * remboursement Stripe est émis et constaté dans la transaction du règlement. Lectures simples seulement, aucun
 * verrou (voir le contrat du port).
 */
@Component
public class SenderRefundInFlightAdapter implements SenderRefundInFlightPort {

    private final PaymentRepository payments;
    private final PawapayOperationService operations;

    public SenderRefundInFlightAdapter(PaymentRepository payments, PawapayOperationService operations) {
        this.payments = payments;
        this.operations = operations;
    }

    @Override
    public boolean senderRefundInFlight(UUID bidId) {
        return payments.findForBid(bidId)
                .filter(payment -> payment.getRail() == PaymentRail.PAWAPAY)
                .map(payment -> operations.findLive(payment.getId(), PawapayOperationKind.REFUND).isPresent())
                .orElse(false);
    }
}
