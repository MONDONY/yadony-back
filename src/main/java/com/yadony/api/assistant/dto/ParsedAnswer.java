package com.yadony.api.assistant.dto;

import java.util.List;

/**
 * @param aboutMyParcel     le modèle juge que la question porte sur l'état d'un colis de l'utilisateur
 *                          (où il est, son remboursement, son code…) sans qu'un colis soit fourni
 * @param followUps         questions de suite proposées par le modèle, brutes (filtrées par le service)
 * @param discoveryId       identifiant de la fiche « découverte » choisie parmi celles proposées, ou null
 * @param discoveryQuestion question que le modèle propose pour cette fiche, ou null
 */
public record ParsedAnswer(String answer, boolean answered, List<String> routes, boolean aboutMyParcel,
                           List<String> followUps, String discoveryId, String discoveryQuestion) {

    public ParsedAnswer {
        followUps = followUps == null ? List.of() : List.copyOf(followUps);
    }

    public ParsedAnswer(String answer, boolean answered, List<String> routes, boolean aboutMyParcel) {
        this(answer, answered, routes, aboutMyParcel, List.of(), null, null);
    }

    public ParsedAnswer(String answer, boolean answered, List<String> routes) {
        this(answer, answered, routes, false);
    }
}
