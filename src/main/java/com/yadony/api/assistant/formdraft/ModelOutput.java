package com.yadony.api.assistant.formdraft;

import java.util.List;
import java.util.Map;

/** Sortie du modèle, lue mais pas encore validée : chaque champ du brouillon passe par le schéma. */
public record ModelOutput(Map<String, Object> draft, boolean needsConfirmation, String question,
                          List<String> quickReplies) {
}
