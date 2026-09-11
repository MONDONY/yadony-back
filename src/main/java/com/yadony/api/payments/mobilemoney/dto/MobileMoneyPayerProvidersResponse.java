package com.yadony.api.payments.mobilemoney.dto;

import java.util.List;

/**
 * Réseaux avec lesquels l'expéditeur peut payer ce colis : ceux de son numéro (pays, dépôt ouvert,
 * devise du colis) restreints aux marques acceptées par le voyageur. Liste vide = aucun réseau
 * commun, l'app l'explique avec {@code travelerAccepts} et {@code travelerFirstName}.
 */
public record MobileMoneyPayerProvidersResponse(String country, String currency, String msisdnMasked, String detected,
                                                List<MobileMoneyProvidersResponse.ProviderOption> providers,
                                                List<String> travelerAccepts, String travelerFirstName) {}
