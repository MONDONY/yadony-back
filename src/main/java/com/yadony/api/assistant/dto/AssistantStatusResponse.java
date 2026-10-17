package com.yadony.api.assistant.dto;

/** {@code formDraftEnabled} : bouton « Créer avec l'IA » des formulaires (champ ajouté, absent des anciens back). */
public record AssistantStatusResponse(boolean enabled, boolean formDraftEnabled) {
}
