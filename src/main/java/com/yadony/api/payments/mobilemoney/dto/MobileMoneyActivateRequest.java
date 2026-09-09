package com.yadony.api.payments.mobilemoney.dto;

/** Corps optionnel de POST /payments/mobile-money/account : numéro de versement, utilisé seulement si le compte Firebase n'en a pas (E.164 ou chiffres). */
public record MobileMoneyActivateRequest(String phoneNumber) {}
