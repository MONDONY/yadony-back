package com.yadony.api.assistant.adminhelp.dto;

import java.util.List;

/**
 * @param answer   Markdown restreint (paragraphes, puces, étapes numérotées, gras), sans lien ni HTML
 * @param actions  pages à ouvrir, prises dans les fiches transmises et permises à l'admin
 * @param sources  fiches à lire pour aller plus loin
 * @param provider fournisseur qui a répondu, {@code none} sans appel au modèle
 */
public record AdminHelpAskResponse(String answer, boolean answered, List<AdminHelpRoute> actions,
                                   List<AdminHelpSource> sources, String provider, int remaining) {
}
