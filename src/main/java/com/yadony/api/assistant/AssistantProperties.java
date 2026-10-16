package com.yadony.api.assistant;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Assistant IA. Éteint par défaut (ASSISTANT_ENABLED) ; les fournisseurs sont en base, pas ici. */
@ConfigurationProperties("yadony.assistant")
public record AssistantProperties(boolean enabled, int dailyQuota, @DefaultValue("6.5") double minScore) {
}
