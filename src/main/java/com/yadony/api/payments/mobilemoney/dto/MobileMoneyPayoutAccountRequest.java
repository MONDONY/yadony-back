package com.yadony.api.payments.mobilemoney.dto;

/**
 * Corps de POST /payments/mobile-money/accounts ({@code phoneNumber} + {@code provider}, code
 * opérateur pawaPay) et de PUT /payments/mobile-money/accounts/{id}/number ({@code phoneNumber}
 * seul : l'opérateur d'un compte ne change pas). Numéro brut, normalisé par le service.
 */
public record MobileMoneyPayoutAccountRequest(String phoneNumber, String provider) {}
