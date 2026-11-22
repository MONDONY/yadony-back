package com.yadony.api.payments.fee;

import com.yadony.api.cancellation.fee.CancellationFeeQuotePort;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRail;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Réponse du paquet {@code payments} au port {@link CancellationFeeQuotePort} : le paiement en séquestre du colis (fil
 * de négociation compris) et le brut que son remboursement retiendrait maintenant, par le calcul du règlement
 * ({@link CancellationFeeSettlement#preview}). Rail mobile money : le montant du dépôt abouti, que le règlement compare
 * au paiement. Lectures simples seulement, aucun verrou, aucun appel au prestataire.
 */
@Component
public class CancellationFeeQuoteAdapter implements CancellationFeeQuotePort {

    private final PaymentRepository payments;
    private final PawapayOperationService operations;
    private final CancellationFeeSettlement settlement;

    public CancellationFeeQuoteAdapter(PaymentRepository payments, PawapayOperationService operations,
                                       CancellationFeeSettlement settlement) {
        this.payments = payments;
        this.operations = operations;
        this.settlement = settlement;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Quote> quote(UUID bidId, BigDecimal rate) {
        return payments.findForBid(bidId)
                .filter(payment -> payment.getStatus() == PaymentStatus.ESCROW)
                .map(payment -> {
                    CancellationFeeSettlement.Preview preview =
                            settlement.preview(payment, rate, completedDepositAmount(payment));
                    return new Quote(payment.getAmount(), payment.getCurrency(), preview.retainable(),
                            preview.grossFee(), preview.senderRefund());
                });
    }

    /** Montant du dernier dépôt pawaPay abouti, celui que le remboursement rendrait ; {@code null} sur la carte. */
    private BigDecimal completedDepositAmount(PaymentEntity payment) {
        if (payment.getRail() != PaymentRail.PAWAPAY) {
            return null;
        }
        return operations.findLatest(payment.getId(), PawapayOperationKind.DEPOSIT)
                .filter(deposit -> deposit.getStatus() == PawapayOperationStatus.COMPLETED)
                .map(PawapayOperationEntity::getAmount)
                .orElse(null);
    }
}
