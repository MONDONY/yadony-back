package com.yadony.api.assistant.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.RequestOptions;
import com.anthropic.core.Timeout;
import com.anthropic.errors.AnthropicInvalidDataException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.ThinkingConfigDisabled;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Client natif Claude (API Messages d'Anthropic) via le SDK officiel {@code com.anthropic:anthropic-java}.
 *
 * <p>Même contrat que {@link OpenAiCompatibleClient} : texte de la réponse, ou {@link LlmCallException} avec
 * le statut HTTP réel (429, 401/403 déclenchent la mise de côté dans {@link ProviderHealthRegistry}),
 * {@link LlmCallException#NETWORK} pour réseau et délai, {@link LlmCallException#INVALID_OUTPUT} pour une
 * réponse vide ou refusée.
 *
 * <p><b>Adresse figée.</b> Le SDK appelle toujours {@value #API_BASE_URL}, jamais l'adresse saisie dans
 * l'admin (qui sert seulement à reconnaître le fournisseur, voir {@link LlmClients#isAnthropic}). Aucune
 * adresse choisie par un administrateur n'est donc appelée sur ce chemin : la garde anti-SSRF
 * ({@link ProviderAddressGuard}) est sans objet ici, l'hôte est un nom public fixe.
 *
 * <p><b>Modèle libre.</b> Le nom de modèle saisi dans l'admin est envoyé tel quel, sans liste blanche : un
 * modèle Claude sorti après ce code fonctionne sans modifier le back. Les paramètres de vitesse (réflexion
 * coupée, effort {@code low}) ne sont pas acceptés par tous les modèles : sur un 400 qui porte sur
 * {@code thinking}, {@code effort} ou {@code temperature}, l'appel est refait UNE fois sans ce paramètre,
 * dans le délai restant, et la variante acceptée est retenue en mémoire par nom de modèle.
 *
 * <p>Relances du SDK à 0 : le routeur de l'assistant bascule lui-même de fournisseur. Ni la clé ni le corps
 * de la réponse n'entrent dans un message d'exception ou un journal.
 */
@Component
public class AnthropicMessagesClient {

    static final String API_BASE_URL = "https://api.anthropic.com";
    /** Réponse JSON courte (réponse + routes) : large marge sans réflexion. */
    static final long FAST_MAX_TOKENS = 1024;
    /** Réflexion possiblement active (adaptative par défaut) : de quoi réfléchir ET répondre. */
    static final long FALLBACK_MAX_TOKENS = 2048;
    static final int MAX_REMEMBERED_MODELS = 64;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Jeux de paramètres, du plus rapide au plus permissif.
     * <ul>
     *   <li>{@code FAST} : réflexion désactivée + effort {@code low} (Claude Haiku 5.5, Claude Opus 5…).</li>
     *   <li>{@code LOW_EFFORT} : réflexion omise (valeur par défaut du modèle) + effort {@code low} : modèles qui
     *       refusent la réflexion désactivée (Claude Opus 5.5, Claude Sonnet 5.5).</li>
     *   <li>{@code PLAIN} : ni réflexion ni effort : modèles qui ne connaissent pas l'effort (Claude Haiku 4.5…).</li>
     * </ul>
     */
    enum Variant { FAST, LOW_EFFORT, PLAIN }

    private final String apiBaseUrl;
    private final Duration maxTimeout;
    private final Map<Object, KeyedClient> clients = new ConcurrentHashMap<>();
    private final Map<String, Variant> variants = new ConcurrentHashMap<>();

    /** Une instance du SDK par fournisseur (sa clé), remplacée quand la clé change. */
    private record KeyedClient(String keyFingerprint, AnthropicClient client) {
        @Override
        public String toString() {
            return "KeyedClient";
        }
    }

    /** Seul constructeur annoté : Spring en voit un second (tests) et ne saurait pas lequel choisir. */
    @Autowired
    public AnthropicMessagesClient() {
        this(API_BASE_URL, OpenAiCompatibleClient.TOTAL_TIMEOUT);
    }

    /** Tests : adresse d'un serveur local. La prod reste figée sur {@value #API_BASE_URL}. */
    AnthropicMessagesClient(String apiBaseUrl, Duration maxTimeout) {
        this.apiBaseUrl = Objects.requireNonNull(apiBaseUrl, "apiBaseUrl");
        this.maxTimeout = Objects.requireNonNull(maxTimeout, "maxTimeout");
    }

    @PreDestroy
    void close() {
        clients.values().forEach(c -> c.client().close());
        clients.clear();
    }

    /**
     * Appel unique (au plus un second essai sans paramètre refusé, dans le même délai). Le délai est borné
     * par {@code timeout} et par le délai maximal du client (20 s).
     */
    String complete(LlmProviderConfig provider, String system, String user, Duration timeout) {
        long deadline = System.nanoTime()
                + Math.min(Objects.requireNonNull(timeout, "timeout").toNanos(), maxTimeout.toNanos());
        if (provider.apiKey() == null || provider.apiKey().isBlank()) {
            // Jamais de repli sur une clé d'environnement : statut 401, le fournisseur est mis de côté.
            throw new LlmCallException(401, "Clé d'API absente");
        }
        String model = provider.model();
        AnthropicClient client;
        try {
            client = clientFor(provider);
        } catch (RuntimeException e) {
            throw new LlmCallException(LlmCallException.NETWORK, "Appel impossible");
        }
        Variant variant = variants.getOrDefault(model, Variant.FAST);
        boolean retried = false;
        while (true) {
            Duration remaining = Duration.ofNanos(deadline - System.nanoTime());
            if (remaining.toMillis() <= 0) {
                throw new LlmCallException(LlmCallException.NETWORK, "Délai dépassé");
            }
            try {
                Message message = client.messages().create(params(variant, model, system, user), options(remaining));
                if (variant != Variant.FAST) remember(model, variant);
                return textOf(message);
            } catch (AnthropicServiceException e) {
                Optional<Variant> next = retried || e.statusCode() != 400
                        ? Optional.empty() : fallbackFor(variant, errorText(e));
                if (next.isEmpty()) {
                    throw new LlmCallException(e.statusCode(), "HTTP " + e.statusCode());
                }
                variant = next.get();
                retried = true;
            } catch (AnthropicIoException e) {
                throw new LlmCallException(LlmCallException.NETWORK,
                        System.nanoTime() >= deadline ? "Délai dépassé" : "Réseau indisponible ou délai dépassé");
            } catch (AnthropicInvalidDataException e) {
                throw new LlmCallException(LlmCallException.INVALID_OUTPUT, "Réponse illisible");
            } catch (LlmCallException e) {
                throw e;
            } catch (RuntimeException e) {
                // Jamais d'exception brute hors de complete() : son message pourrait citer la requête.
                throw new LlmCallException(LlmCallException.NETWORK, "Appel impossible");
            }
        }
    }

    static MessageCreateParams params(Variant variant, String model, String system, String user) {
        MessageCreateParams.Builder b = MessageCreateParams.builder()
                .model(model)
                .system(system)
                .addUserMessage(user)
                .maxTokens(variant == Variant.FAST ? FAST_MAX_TOKENS : FALLBACK_MAX_TOKENS);
        if (variant == Variant.FAST) {
            b.thinking(ThinkingConfigDisabled.builder().build());
        }
        if (variant != Variant.PLAIN) {
            b.outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build());
        }
        // Pas de temperature : valeurs non par défaut refusées (400) par les modèles Claude récents.
        return b.build();
    }

    /**
     * Variante suivante après un 400 portant sur un paramètre de vitesse, ou rien (échec normal : clé,
     * requête mal formée…). Le texte d'erreur sert uniquement à ce choix : jamais journalisé ni renvoyé.
     */
    static Optional<Variant> fallbackFor(Variant current, String errorText) {
        String t = errorText == null ? "" : errorText.toLowerCase(Locale.ROOT);
        boolean thinking = t.contains("thinking");
        boolean effort = t.contains("effort") || t.contains("output_config");
        boolean temperature = t.contains("temperature");
        if (!thinking && !effort && !temperature) return Optional.empty();
        return switch (current) {
            case FAST -> Optional.of(effort || temperature ? Variant.PLAIN : Variant.LOW_EFFORT);
            case LOW_EFFORT -> Optional.of(Variant.PLAIN);
            case PLAIN -> Optional.empty();
        };
    }

    private static String errorText(AnthropicServiceException e) {
        StringBuilder sb = new StringBuilder();
        if (e.getMessage() != null) sb.append(e.getMessage());
        try {
            sb.append(' ').append(e.body());
        } catch (RuntimeException ignored) {
            // Corps illisible : le message seul suffit.
        }
        return sb.toString();
    }

    private static String textOf(Message message) {
        if (message.stopReason().filter(StopReason.REFUSAL::equals).isPresent()) {
            throw new LlmCallException(LlmCallException.INVALID_OUTPUT, "Réponse refusée par le modèle");
        }
        String text = message.content().stream()
                .map(ContentBlock::text)
                .flatMap(Optional::stream)
                .map(block -> block.text())
                .collect(Collectors.joining());
        if (text.isBlank()) {
            // Couvre aussi max_tokens atteint pendant la réflexion, sans texte produit.
            throw new LlmCallException(LlmCallException.INVALID_OUTPUT, "Réponse sans contenu");
        }
        return text;
    }

    private static RequestOptions options(Duration remaining) {
        Duration connect = remaining.compareTo(CONNECT_TIMEOUT) < 0 ? remaining : CONNECT_TIMEOUT;
        return RequestOptions.builder()
                .timeout(Timeout.builder().connect(connect).read(remaining).write(remaining).request(remaining).build())
                .build();
    }

    private void remember(String model, Variant variant) {
        if (variants.size() >= MAX_REMEMBERED_MODELS && !variants.containsKey(model)) {
            variants.clear();
        }
        variants.put(model, variant);
    }

    /** Pour les tests : variante retenue pour ce modèle. */
    Optional<Variant> rememberedVariant(String model) {
        return Optional.ofNullable(variants.get(model));
    }

    /**
     * Une instance du SDK par fournisseur. Quand la clé change, l'ancienne instance n'est pas fermée (un appel
     * peut encore l'utiliser) : ses connexions inactives expirent d'elles-mêmes.
     */
    private AnthropicClient clientFor(LlmProviderConfig provider) {
        Object id = provider.id() != null ? provider.id() : provider.slug();
        String fingerprint = fingerprint(provider.apiKey());
        return clients.compute(id, (k, existing) -> existing != null && existing.keyFingerprint().equals(fingerprint)
                ? existing
                : new KeyedClient(fingerprint, AnthropicOkHttpClient.builder()
                        .baseUrl(apiBaseUrl)
                        .apiKey(provider.apiKey())
                        .maxRetries(0)
                        .timeout(maxTimeout)
                        .build())).client();
    }

    private static String fingerprint(String apiKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(apiKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    /** Pour les tests : nombre d'instances du SDK ouvertes. */
    int openClients() {
        return clients.size();
    }
}
