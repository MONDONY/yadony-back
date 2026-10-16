package com.yadony.api.assistant.llm;

import com.yadony.api.assistant.provider.ProviderCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.function.Function;

/** Essaie les fournisseurs actifs par priorité, au plus 3 par question (spec §3.5). */
@Component
public class LlmRouter {

    private static final Logger log = LoggerFactory.getLogger(LlmRouter.class);
    static final int MAX_ATTEMPTS = 3;

    private final ProviderCatalog catalog;
    private final OpenAiCompatibleClient client;
    private final ProviderHealthRegistry health;

    public LlmRouter(ProviderCatalog catalog, OpenAiCompatibleClient client, ProviderHealthRegistry health) {
        this.catalog = catalog;
        this.client = client;
        this.health = health;
    }

    public <T> Optional<LlmResult<T>> complete(String system, String user, Function<String, T> parser) {
        int attempts = 0;
        for (LlmProviderConfig provider : catalog.activeProviders()) {
            if (attempts >= MAX_ATTEMPTS) break;
            if (!health.isAvailable(provider)) continue;
            attempts++;
            health.recordAttempt(provider.id());
            try {
                T value = parser.apply(client.complete(provider, system, user));
                health.recordSuccess(provider.id());
                return Optional.of(new LlmResult<>(provider.slug(), value));
            } catch (LlmCallException e) {
                health.recordFailure(provider.id(), e.status(), e.getMessage());
                log.warn("Assistant : fournisseur {} en échec (statut {}), bascule", provider.slug(), e.status());
            }
        }
        if (attempts > 0) {
            log.error("Assistant : aucun fournisseur n'a répondu après {} essai(s)", attempts);
        }
        return Optional.empty();
    }
}
