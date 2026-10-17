package com.yadony.api.assistant.formdraft.dto;

import java.util.List;
import java.util.Map;

/** {@code missing} et {@code done} sont calculés par le serveur, jamais repris du modèle. */
public record FormDraftResponse(Map<String, Object> draft, List<String> understood, List<String> missing,
                                String question, List<String> quickReplies, boolean done, boolean degraded,
                                int remainingToday) {
}
