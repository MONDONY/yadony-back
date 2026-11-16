package com.yadony.api.assistant.adminhelp.dto;

import java.util.List;

/**
 * @param accessible faux quand la page décrite exige une permission que l'admin n'a pas : la fiche reste lisible
 * @param routes     seulement les pages que l'admin peut ouvrir
 */
public record AdminHelpArticleSummary(String id, String title, String category, String summary, String permission,
                                      boolean accessible, List<AdminHelpRoute> routes) {
}
