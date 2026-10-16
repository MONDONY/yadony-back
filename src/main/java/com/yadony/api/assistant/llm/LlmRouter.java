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
    static final String INVALID_OUTPUT_MESSAGE = "Réponse inexploitable";

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
            if (!health.tryAcquire(provider)) continue;
            attempts++;
            String content;
            try {
                content = client.complete(provider, system, user);
            } catch (LlmCallException e) {
                health.recordFailure(provider.id(), e.status(), e.getMessage());
                log.warn("Assistant : fournisseur {} en échec (statut {}), bascule", provider.slug(), e.status());
                continue;
            }
            T value;
            try {
                value = parser.apply(content);
            } catch (RuntimeException e) {
                // Le message du parser peut contenir la sortie du modèle : ni stocké, ni journalisé.
                health.recordFailure(provider.id(), LlmCallException.INVALID_OUTPUT, INVALID_OUTPUT_MESSAGE);
                log.warn("Assistant : fournisseur {} a renvoyé une réponse inexploitable, bascule", provider.slug());
                continue;
            }
            health.recordSuccess(provider.id());
            return Optional.of(new LlmResult<>(provider.slug(), value));
        }
        if (attempts == 0) {
            log.warn("Assistant : aucun fournisseur disponible (mis de côté ou limite du jour)");
        } else {
            log.error("Assistant : aucun fournisseur n'a répondu après {} essai(s)", attempts);
        }
        return Optional.empty();
    }
}
