package com.yadony.api.assistant.dto;

import java.util.List;

/**
 * Explication de l'écran depuis lequel l'assistant est ouvert, lue dans une fiche : sans fournisseur
 * d'IA ni quota.
 *
 * @param questions questions proposées sous l'explication
 * @param actions   autres écrans de la fiche (jamais l'écran expliqué lui-même)
 */
public record AssistantScreenResponse(String id, String title, String explanation, List<String> questions,
                                      List<AssistantAction> actions) {
}
