package com.yadony.api.payments.amendment.dto;

import com.yadony.api.matching.dto.BidAmendmentResponse;

import java.util.List;

/**
 * Réponse à la création, vue expéditeur. Rail carte : {@code clientSecret}, {@code paymentMethodTypes} et
 * {@code publishableKey} de la PaymentSheet qui autorise le montant (renseignés après le commit). Autres
 * rails : nuls.
 */
public record AmendmentCreateResponse(BidAmendmentResponse amendment, String clientSecret,
                                      List<String> paymentMethodTypes, String publishableKey) {

    /** Sans PaymentSheet (espèces, mobile money, ou avant la création du PaymentIntent). */
    public static AmendmentCreateResponse of(BidAmendmentResponse amendment) {
        return new AmendmentCreateResponse(amendment, null, null, null);
    }
}
