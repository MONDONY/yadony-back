package com.yadony.api.assistant;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Assistant IA. Éteint par défaut (ASSISTANT_ENABLED) ; les fournisseurs sont en base, pas ici. */
@ConfigurationProperties("yadony.assistant")
public record AssistantProperties(boolean enabled, int dailyQuota, double minScore) {
}
