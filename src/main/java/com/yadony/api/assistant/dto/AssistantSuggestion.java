package com.yadony.api.assistant.dto;

/**
 * Question que l'utilisateur pourrait poser ensuite, proposée sous la réponse de l'assistant.
 *
 * @param text question courte, dans la langue de la conversation (au plus 80 caractères)
 * @param kind {@value #FOLLOW_UP} : suite de la question posée ; {@value #DISCOVERY} : une autre
 *             fonctionnalité de Yadony, prise dans les fiches d'aide
 */
public record AssistantSuggestion(String text, String kind) {

    public static final String FOLLOW_UP = "FOLLOW_UP";
    public static final String DISCOVERY = "DISCOVERY";
}
