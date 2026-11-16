package com.yadony.api.assistant.adminhelp;

import com.yadony.api.assistant.kb.KbRoute;

import java.util.List;

/**
 * Fiche du centre d'aide de la console admin.
 *
 * @param permission permission de la page décrite (nom d'{@code AdminPermission}), ou null si toute la console y a accès
 * @param body       Markdown restreint (titres, listes, gras, encadrés, liens internes), rendu par la console
 */
public record AdminHelpArticle(String id, String title, String category, int order, String summary,
                               String permission, List<String> keywords, List<KbRoute> routes, String body) {
}
