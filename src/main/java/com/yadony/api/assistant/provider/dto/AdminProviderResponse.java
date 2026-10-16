package com.yadony.api.assistant.provider.dto;

import java.util.UUID;

/** Jamais de clé d'API ici : seulement ses 4 derniers caractères. */
public record AdminProviderResponse(UUID id, String name, String slug, String baseUrl, String model,
                                    String apiKeyLast4, boolean supportsJsonMode, Integer dailyRequestLimit,
                                    int priority, boolean enabled, AdminProviderStats stats) {
}
