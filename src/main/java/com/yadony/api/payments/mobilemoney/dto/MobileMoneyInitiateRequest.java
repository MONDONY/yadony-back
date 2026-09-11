package com.yadony.api.payments.mobilemoney.dto;

/**
 * Corps optionnel de POST /bids/{id}/mobile-money/initiate : numéro payeur de remplacement (E.164 ou
 * chiffres) et opérateur choisi (code pawaPay du catalogue payeur). Sans {@code provider}, l'opérateur
 * prédit s'applique ; dans les deux cas sa marque doit être acceptée par le voyageur.
 */
public record MobileMoneyInitiateRequest(String phoneNumber, String provider) {}
