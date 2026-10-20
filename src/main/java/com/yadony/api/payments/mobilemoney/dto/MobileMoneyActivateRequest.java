package com.yadony.api.payments.mobilemoney.dto;

import java.util.List;

/**
 * Corps optionnel de l'ancien POST /payments/mobile-money/account : numéro de versement (E.164 ou
 * chiffres, prioritaire sur le numéro Firebase) et réseaux cochés (codes pawaPay du catalogue du
 * numéro). Un seul compte est créé ou mis à jour : celui du détecté s'il est coché, sinon du premier
 * coché ; sans {@code providers}, celui de l'opérateur prédit.
 */
public record MobileMoneyActivateRequest(String phoneNumber, List<String> providers) {}
