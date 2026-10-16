package com.yadony.api.assistant.provider;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.yadony.api.assistant.llm.LlmProviderConfig;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/** Fournisseurs actifs, par priorité, gardés 60 s ; vidé à chaque écriture admin. */
@Component
public class ProviderCatalog {

    private static final String KEY = "active";

    private final AssistantLlmProviderRepository repository;
    private final Cache<String, List<LlmProviderConfig>> cache =
            Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(60)).maximumSize(1).build();

    public ProviderCatalog(AssistantLlmProviderRepository repository) {
        this.repository = repository;
    }

    public List<LlmProviderConfig> activeProviders() {
        return cache.get(KEY, k -> repository.findByEnabledTrueOrderByPriorityAsc().stream()
                .map(ProviderCatalog::toConfig).toList());
    }

    public void evict() {
        cache.invalidateAll();
    }

    static LlmProviderConfig toConfig(AssistantLlmProviderEntity e) {
        return new LlmProviderConfig(e.getId(), e.getSlug(), e.getBaseUrl(), e.getModel(), e.getApiKey(),
                e.isSupportsJsonMode(), e.getDailyRequestLimit());
    }
}
