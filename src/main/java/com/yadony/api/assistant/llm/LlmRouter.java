package com.yadony.api.assistant.llm;

import com.yadony.api.assistant.provider.ProviderCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Essaie les fournisseurs actifs par priorité, au plus 3 par question (spec §3.5), dans un budget de temps
 * de 25 s par question : nginx coupe {@code /api/v1/} à 30 s, et un 504 HTML vaut moins
 * que le mode dégradé (réponse 200 avec liens). La garde et la résolution DNS comptent dans ce budget.
 */
@Component
public class LlmRouter {

    private static final Logger log = LoggerFactory.getLogger(LlmRouter.class);
    static final int MAX_ATTEMPTS = 3;
    static final String INVALID_OUTPUT_MESSAGE = "Réponse inexploitable";
    static final Duration QUESTION_BUDGET = Duration.ofSeconds(25);
    /** En dessous, un essai n'a aucune chance d'aboutir : on passe directement au mode dégradé. */
    static final Duration MIN_ATTEMPT = Duration.ofSeconds(3);
    static final Duration ERROR_LOG_INTERVAL = Duration.ofMinutes(1);

    private final ProviderCatalog catalog;
    private final OpenAiCompatibleClient client;
    private final ProviderHealthRegistry health;
    private final Clock clock;
    private final AtomicReference<Instant> lastErrorLog = new AtomicReference<>();

    /** Seul constructeur annoté : Spring en voit un second (tests) et ne saurait pas lequel choisir. */
    @Autowired
    public LlmRouter(ProviderCatalog catalog, OpenAiCompatibleClient client, ProviderHealthRegistry health) {
        this(catalog, client, health, Clock.systemUTC());
    }

    LlmRouter(ProviderCatalog catalog, OpenAiCompatibleClient client, ProviderHealthRegistry health, Clock clock) {
        this.catalog = catalog;
        this.client = client;
        this.health = health;
        this.clock = clock;
    }

    public <T> Optional<LlmResult<T>> complete(String system, String user, Function<String, T> parser) {
        Instant deadline = clock.instant().plus(QUESTION_BUDGET);
        int attempts = 0;
        for (LlmProviderConfig provider : catalog.activeProviders()) {
            if (attempts >= MAX_ATTEMPTS) break;
            Duration remaining = Duration.between(clock.instant(), deadline);
            if (remaining.compareTo(MIN_ATTEMPT) < 0) {
                log.warn("Assistant : budget de temps de la question épuisé, plus d'essai");
                break;
            }
            if (!health.tryAcquire(provider)) continue;
            attempts++;
            String content;
            try {
                content = client.complete(provider, system, user, remaining.compareTo(OpenAiCompatibleClient.TOTAL_TIMEOUT) < 0
                        ? remaining : OpenAiCompatibleClient.TOTAL_TIMEOUT);
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
        } else if (mayLogError()) {
            log.error("Assistant : aucun fournisseur n'a répondu après {} essai(s)", attempts);
        } else {
            log.warn("Assistant : aucun fournisseur n'a répondu après {} essai(s)", attempts);
        }
        return Optional.empty();
    }

    /** Un seul ERROR par minute (alerte Sentry) : les échecs suivants restent visibles en WARN. */
    private boolean mayLogError() {
        Instant now = clock.instant();
        Instant last = lastErrorLog.get();
        return (last == null || !now.isBefore(last.plus(ERROR_LOG_INTERVAL))) && lastErrorLog.compareAndSet(last, now);
    }
}
