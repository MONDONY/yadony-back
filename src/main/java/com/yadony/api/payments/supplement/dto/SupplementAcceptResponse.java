package com.yadony.api.payments.supplement.dto;

import com.yadony.api.matching.dto.BidSupplementResponse;

import java.math.BigDecimal;
import java.util.List;

/**
 * Réponse à l'acceptation par l'expéditeur. Carte : {@code clientSecret}, {@code publishableKey}
 * et {@code paymentMethodTypes} pour la PaymentSheet (même contrat que {@code BidCheckoutResponse} ;
 * la clé éphémère des cartes enregistrées reste demandée à {@code POST /payments/me/ephemeral-key}).
 * {@code clientSecret} est nul quand il n'y a plus rien à payer (supplément déjà payé). Mobile
 * money : l'app ouvre l'écran d'attente ; espèces : le supplément est directement payé.
 * {@code amount} est le brut, dans {@code currency}.
 */
public record SupplementAcceptResponse(BidSupplementResponse supplement, String rail, String clientSecret,
                                       List<String> paymentMethodTypes, BigDecimal amount, String currency,
                                       String publishableKey) {

    /** Rails sans PaymentSheet (mobile money, espèces). */
    public SupplementAcceptResponse(BidSupplementResponse supplement, String rail, String clientSecret,
                                    List<String> paymentMethodTypes, BigDecimal amount, String currency) {
        this(supplement, rail, clientSecret, paymentMethodTypes, amount, currency, null);
    }
}
