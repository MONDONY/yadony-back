package com.yadony.api.assistant.llm;

import java.util.UUID;

/** Fournisseur prêt à appeler (clé déchiffrée). Ne jamais journaliser l'instance. */
public record LlmProviderConfig(UUID id, String slug, String baseUrl, String model, String apiKey,
                                boolean supportsJsonMode, Integer dailyRequestLimit) {

    @Override
    public String toString() {
        return "LlmProviderConfig[slug=" + slug + "]";
    }
}
