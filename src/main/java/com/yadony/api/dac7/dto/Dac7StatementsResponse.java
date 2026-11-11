package com.yadony.api.dac7.dto;

import java.util.List;
import java.util.UUID;

/**
 * Résultat de l'envoi des récapitulatifs : envoyés ; ignorés (déjà envoyés ou voyageur non déclaré) ; et sans e-mail
 * (inscrits par téléphone) : ni envoyés ni réservés, ils pourront recevoir leur récapitulatif plus tard (lot 3f).
 */
public record Dac7StatementsResponse(int sent, int skipped, int skippedNoEmail, List<UUID> skippedNoEmailTravelerIds) {

    public Dac7StatementsResponse(int sent, int skipped) {
        this(sent, skipped, 0, List.of());
    }
}
