package com.yadony.api.assistant.adminhelp.dto;

import java.util.List;

/** Sommaire du centre d'aide ; {@code assistantEnabled} dit à la console si elle affiche la bulle de l'assistant. */
public record AdminHelpIndexResponse(List<AdminHelpCategoryResponse> categories,
                                     List<AdminHelpArticleSummary> articles, boolean assistantEnabled) {
}
