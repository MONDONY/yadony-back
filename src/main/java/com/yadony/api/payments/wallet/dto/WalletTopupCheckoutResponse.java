package com.yadony.api.payments.wallet.dto;

/** URL de la session Stripe Checkout vers laquelle le portail redirige le voyageur. */
public record WalletTopupCheckoutResponse(String url) {}
