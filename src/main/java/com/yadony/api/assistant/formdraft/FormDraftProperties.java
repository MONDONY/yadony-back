package com.yadony.api.assistant.formdraft;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Formulaires remplis par l'IA (trajet, demande d'envoi). Éteint par défaut (ASSISTANT_FORM_DRAFT_ENABLED) ;
 * exige aussi l'assistant allumé. Quota en tours par uid et par jour UTC, commun aux deux formulaires.
 */
@ConfigurationProperties("yadony.assistant.form-draft")
public record FormDraftProperties(boolean enabled, @DefaultValue("30") int dailyQuota, @DefaultValue("8") int maxTurns) {
}
