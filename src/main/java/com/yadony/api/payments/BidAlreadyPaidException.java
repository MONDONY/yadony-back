package com.yadony.api.payments;

import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;

/**
 * 409 {@code bid-already-paid} : l'escrow Stripe de ce bid est déjà actif, le bid
 * vient d'être promu en {@code PAYMENT_ESCROWED} par auto-réparation, et le client
 * doit recharger pour afficher l'état à jour.
 *
 * <p>Classe dédiée, et non un simple code sur {@link YadonyBusinessException} :
 * les chemins de paiement transactionnels ({@code BidCheckoutService.checkout},
 * {@code negotiationCheckout}) doivent la déclarer en {@code noRollbackFor}.
 * Sans cela, la promotion du bid effectuée juste avant de lever ce 409 est
 * annulée par le rollback de la transaction : l'expéditeur lit « déjà payé,
 * actualisez » et retrouve son colis toujours « à payer » après actualisation.
 */
public class BidAlreadyPaidException extends YadonyBusinessException {

    public BidAlreadyPaidException() {
        super(HttpStatus.CONFLICT, "bid-already-paid", "Bid Already Paid",
                "Ce colis est déjà payé. Actualisez pour voir son état à jour.");
    }
}
