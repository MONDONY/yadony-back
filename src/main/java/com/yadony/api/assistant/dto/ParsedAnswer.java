package com.yadony.api.assistant.dto;

import java.util.List;

/**
 * @param aboutMyParcel le modèle juge que la question porte sur l'état d'un colis de l'utilisateur
 *                      (où il est, son remboursement, son code…) sans qu'un colis soit fourni
 */
public record ParsedAnswer(String answer, boolean answered, List<String> routes, boolean aboutMyParcel) {

    public ParsedAnswer(String answer, boolean answered, List<String> routes) {
        this(answer, answered, routes, false);
    }
}
