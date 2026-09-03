package com.yadony.api.toolkit;

import java.util.UUID;

/**
 * Compteurs de lignes actives (deleted_at IS NULL) par outil. Requêtes natives
 * dans ce package : le contrat interdit d'injecter les services des packages
 * addressbook/, alerts/, triptemplate/ et matching/.
 */
public interface ToolsCompletionQueryRepository {
    long countAddresses(UUID userId);
    long countRecipients(UUID userId);
    long countAlerts(UUID userId);
    long countTripTemplates(UUID userId);
    long countPriceGridItems(UUID userId);
}
