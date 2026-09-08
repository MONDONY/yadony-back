package com.yadony.api.payments.mobilemoney.dto;

/** Corps optionnel de POST /bids/{id}/mobile-money/initiate : numéro payeur de remplacement (E.164 ou chiffres). */
public record MobileMoneyInitiateRequest(String phoneNumber) {}
