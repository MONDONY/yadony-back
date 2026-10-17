package com.yadony.api.assistant.formdraft.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * {@code missing} et {@code done} sont calculés par le serveur, jamais repris du modèle. Toutes les clés sortent,
 * nulles comprises (contrat stable pour l'app), malgré {@code default-property-inclusion: NON_NULL} global.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record FormDraftResponse(@JsonInclude(content = JsonInclude.Include.ALWAYS) Map<String, Object> draft,
                                List<String> understood, List<String> missing,
                                String question, List<String> quickReplies, boolean done, boolean degraded,
                                int remainingToday) {
}
