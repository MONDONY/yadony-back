package com.yadony.api.payments.mobilemoney.dto;

/** Corps optionnel des catalogues : numéro à examiner (E.164 ou chiffres) ; absent, le numéro déjà connu s'applique. */
public record MobileMoneyProvidersRequest(String phoneNumber) {}
