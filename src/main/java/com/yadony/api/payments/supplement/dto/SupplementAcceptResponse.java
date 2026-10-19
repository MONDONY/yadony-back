package com.yadony.api.payments.supplement.dto;

import com.yadony.api.matching.dto.BidSupplementResponse;

import java.math.BigDecimal;
import java.util.List;

/**
 * Réponse à l'acceptation par l'expéditeur. Carte : {@code clientSecret} et
 * {@code paymentMethodTypes} pour le PaymentSheet ; mobile money : l'app ouvre l'écran d'attente ;
 * espèces : le supplément est directement payé.
 */
public record SupplementAcceptResponse(BidSupplementResponse supplement, String rail, String clientSecret,
                                       List<String> paymentMethodTypes, BigDecimal amount, String currency) {}
