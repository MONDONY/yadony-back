package com.yadony.api.assistant.adminhelp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Centre d'aide de la console admin. Indépendant de {@code yadony.assistant.enabled} (l'assistant de l'app) : la
 * doc et sa recherche marchent toujours ; l'assistant passe par les fournisseurs de la page « Assistant IA » et,
 * sans fournisseur, propose les fiches les plus proches.
 *
 * @param dailyQuota questions à l'assistant par administrateur et par jour UTC
 * @param minScore   score BM25 brut sous lequel une fiche n'est pas transmise à l'assistant
 */
@ConfigurationProperties("yadony.assistant.admin-help")
public record AdminHelpProperties(@DefaultValue("true") boolean assistantEnabled,
                                  @DefaultValue("60") int dailyQuota,
                                  @DefaultValue("6.0") double minScore) {
}
