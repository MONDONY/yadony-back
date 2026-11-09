package com.yadony.api.admin.broadcast.preset;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Audience prête à l'emploi de l'écran Communications : une règle déjà construite, une explication
 * et un modèle de message par langue. La règle est un arbre ET/OU ordinaire, comme celui que
 * l'administrateur construit à la main : elle passe par les mêmes validations et le même
 * compilateur, et reste modifiable dans l'écran avant l'envoi.
 *
 * @param key         identifiant stable (jamais affiché)
 * @param label       nom court affiché dans la liste
 * @param description ce que l'audience contient, en une phrase
 * @param rule        règle d'audience ({@code {"op":"AND","children":[…]}})
 * @param messages    modèle de message par code de langue ({@code fr}, {@code en})
 */
public record AudiencePreset(String key, String label, String description, JsonNode rule,
                             Map<String, Message> messages) {

    /** Titre et texte d'un modèle, dans les limites de {@code BroadcastRequest}. */
    public record Message(String title, String body) {}

    public AudiencePreset {
        messages = Map.copyOf(messages);
    }
}
