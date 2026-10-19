package com.yadony.api.payments.mobilemoney.dto;

import java.util.List;

/**
 * Opérateurs mobile money avec lesquels l'expéditeur peut payer un voyageur : codes pawaPay des
 * opérateurs ouverts en dépôt, dans la devise du colis ou du fil, dont la marque est acceptée par
 * le voyageur, dans l'ordre du catalogue. Liste vide = aucun réseau commun (200, l'app l'explique).
 * {@code travelerFirstName} sert à le dire (« Aminata accepte… »).
 */
public record MobileMoneyAcceptedOperatorsResponse(String currency, String travelerFirstName, List<String> providers) {}
