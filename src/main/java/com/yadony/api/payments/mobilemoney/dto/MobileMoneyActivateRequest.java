package com.yadony.api.payments.mobilemoney.dto;

import java.util.List;

/**
 * Corps optionnel de POST /payments/mobile-money/account : numéro de versement (E.164 ou chiffres,
 * prioritaire sur le numéro Firebase) et réseaux acceptés (codes pawaPay du catalogue du numéro).
 * Sans {@code providers}, l'opérateur prédit est seul accepté (ancien contrat).
 */
public record MobileMoneyActivateRequest(String phoneNumber, List<String> providers) {}
