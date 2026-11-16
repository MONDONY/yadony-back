package com.yadony.api.assistant.adminhelp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Centre d'aide de la console admin. L'interrupteur de l'assistant et son quota sont des réglages plateforme
 * (page Paramètres) ; seul le seuil de pertinence, calibré sur les fiches, reste ici.
 *
 * @param minScore score BM25 brut sous lequel une fiche n'est pas transmise à l'assistant
 */
@ConfigurationProperties("yadony.assistant.admin-help")
public record AdminHelpProperties(@DefaultValue("6.0") double minScore) {
}
