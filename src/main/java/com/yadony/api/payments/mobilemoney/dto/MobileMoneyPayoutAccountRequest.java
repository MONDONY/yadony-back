package com.yadony.api.payments.mobilemoney.dto;

import jakarta.validation.constraints.Size;

/**
 * Corps de POST /payments/mobile-money/accounts ({@code phoneNumber} + {@code provider}, code
 * opérateur pawaPay) et de PUT /payments/mobile-money/accounts/{id}/number ({@code phoneNumber}
 * seul : l'opérateur d'un compte ne change pas). Numéro brut, normalisé par le service ; les bornes
 * ne font que refuser une saisie démesurée avant tout traitement (colonne {@code provider} : 30).
 */
public record MobileMoneyPayoutAccountRequest(@Size(max = 32) String phoneNumber, @Size(max = 30) String provider) {}
