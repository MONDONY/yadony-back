package com.yadony.api.assistant.adminhelp.dto;

/** @param snippet phrase de la fiche qui contient le plus de mots cherchés (texte brut), sinon son résumé */
public record AdminHelpSearchResult(String id, String title, String category, String summary, String snippet,
                                    boolean accessible) {
}
