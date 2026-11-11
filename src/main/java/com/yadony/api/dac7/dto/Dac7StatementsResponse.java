package com.yadony.api.dac7.dto;

/** Résultat de l'envoi des récapitulatifs : envoyés, et ignorés (déjà envoyés ou non envoyables). */
public record Dac7StatementsResponse(int sent, int skipped) {
}
