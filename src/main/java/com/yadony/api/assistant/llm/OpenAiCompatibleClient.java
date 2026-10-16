package com.yadony.api.assistant.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client unique pour tous les fournisseurs compatibles OpenAI (Groq, Gemini, Mistral, OpenRouter…).
 *
 * <p>Les URL de base sont saisies par un administrateur : ce client est la frontière anti-SSRF.
 * <ul>
 *   <li>{@link ProviderAddressGuard#check} refuse tôt les hôtes non publics (erreur claire).</li>
 *   <li>La connexion elle-même passe par le résolveur gardé : elle n'utilise que des adresses vérifiées,
 *       ce qui ferme le rebinding DNS (réponse publique pour la vérification, 127.0.0.1 pour la connexion).</li>
 *   <li>Les redirections ne sont jamais suivies.</li>
 *   <li>La réponse est lue au plus {@value #MAX_RESPONSE_BYTES} octets, et l'appel entier (envoi compris)
 *       dure au plus {@link #TOTAL_TIMEOUT}. Le délai socket est par lecture : seul le délai total arrête
 *       un fournisseur qui envoie un octet à la fois.</li>
 *   <li>Tout abandon (corps trop gros, statut hors 2xx, délai) ferme la connexion SANS lire le reste du
 *       corps : la fermeture standard d'Apache le viderait entièrement, donc sans fin pour un corps infini.</li>
 * </ul>
 * Aucun texte venant du fournisseur n'entre dans un message d'exception : il pourrait contenir un extrait
 * de la requête ou une clé partiellement masquée.
 */
@Component
public class OpenAiCompatibleClient {

    static final int MAX_RESPONSE_BYTES = 64 * 1024;
    static final Duration TOTAL_TIMEOUT = Duration.ofSeconds(20);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CloseableHttpClient http;
    private final ScheduledThreadPoolExecutor timeouts;
    private final ProviderAddressGuard guard;
    private final Duration totalTimeout;

    /** Seul constructeur annoté : Spring en voit un second (tests) et ne saurait pas lequel choisir. */
    @Autowired
    public OpenAiCompatibleClient(ProviderAddressGuard guard) {
        this(guard, guard.asDnsResolver(), TOTAL_TIMEOUT);
    }

    /** Tests : résolveur et délai total injectables (un serveur local exige un résolveur sans garde). */
    OpenAiCompatibleClient(ProviderAddressGuard guard, DnsResolver dnsResolver, Duration totalTimeout) {
        this.guard = guard;
        this.totalTimeout = totalTimeout;
        this.http = buildHttpClient(dnsResolver);
        this.timeouts = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "llm-call-timeout");
            thread.setDaemon(true);
            return thread;
        });
        this.timeouts.setRemoveOnCancelPolicy(true);
    }

    @PreDestroy
    void close() {
        timeouts.shutdownNow();
        http.close(CloseMode.IMMEDIATE);
    }

    public String complete(LlmProviderConfig provider, String system, String user) {
        return complete(provider, system, user, totalTimeout);
    }

    /**
     * Comme {@link #complete(LlmProviderConfig, String, String)} avec un délai total propre à l'appel. Le
     * délai court dès l'entrée : la résolution DNS de la garde est comptée dedans, le routeur de l'assistant
     * s'en sert pour tenir son budget de question sous le délai de nginx.
     */
    String complete(LlmProviderConfig provider, String system, String user, Duration timeout) {
        long startedAt = System.nanoTime();
        // Jamais plus que le délai total du client : un appelant ne peut pas désarmer la borne.
        long budgetMillis = Math.min(Objects.requireNonNull(timeout, "timeout").toMillis(), totalTimeout.toMillis());
        guard.check(provider.baseUrl());
        long remainingMillis = budgetMillis - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        if (remainingMillis <= 0) {
            throw new LlmCallException(LlmCallException.NETWORK, "Délai dépassé");
        }
        byte[] payload;
        try {
            payload = MAPPER.writeValueAsBytes(requestBody(provider, system, user));
        } catch (JsonProcessingException e) {
            throw new LlmCallException(LlmCallException.INVALID_OUTPUT, "Requête illisible");
        }
        HttpPost request;
        try {
            request = new HttpPost(provider.baseUrl() + "/chat/completions");
        } catch (IllegalArgumentException e) {
            throw new LlmCallException(LlmCallException.NETWORK, "URL du fournisseur invalide");
        }
        request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + provider.apiKey());
        request.setEntity(new ByteArrayEntity(payload, ContentType.APPLICATION_JSON));

        // Le délai couvre l'appel entier, de l'envoi à la fin de la lecture bornée : cancel() coupe la connexion.
        AtomicBoolean timedOut = new AtomicBoolean();
        ScheduledFuture<?> timer = null;
        JsonNode response;
        try {
            timer = timeouts.schedule(() -> {
                timedOut.set(true);
                request.cancel();
            }, remainingMillis, TimeUnit.MILLISECONDS);
            response = execute(request);
        } catch (IOException e) {
            if (timedOut.get()) {
                throw new LlmCallException(LlmCallException.NETWORK, "Délai dépassé");
            }
            if (isBlocked(e)) {
                throw new LlmCallException(LlmCallException.BLOCKED_ADDRESS, "Adresse du fournisseur refusée");
            }
            throw new LlmCallException(LlmCallException.NETWORK, "Réseau indisponible ou délai dépassé");
        } catch (LlmCallException e) {
            throw e;
        } catch (RuntimeException e) {
            // Exécuteur fermé, course d'annulation côté Apache… : jamais d'exception brute hors de complete().
            throw new LlmCallException(LlmCallException.NETWORK, timedOut.get() ? "Délai dépassé" : "Appel impossible");
        } finally {
            if (timer != null) {
                timer.cancel(false);
            }
        }
        JsonNode choice = response == null ? null : response.path("choices").path(0);
        JsonNode content = choice == null ? null : choice.path("message").path("content");
        if (content == null || !content.isTextual() || content.asText().isBlank()) {
            // finish_reason "length" sans contenu : le modèle a épuisé max_tokens, souvent en réflexion.
            boolean truncated = choice != null && "length".equals(choice.path("finish_reason").asText());
            throw new LlmCallException(LlmCallException.INVALID_OUTPUT,
                    truncated ? "Réponse coupée par la limite de tokens" : "Réponse sans contenu");
        }
        return content.asText();
    }

    /**
     * Corps de la requête chat/completions. Les modèles « à réflexion » d'OpenRouter (Nemotron, Gemma…)
     * réfléchissent par défaut, et cette réflexion est décomptée de {@code max_tokens} : avec 400 tokens,
     * la réponse revenait vide ({@code finish_reason: length}) ou dépassait le délai. On la coupe pour
     * OpenRouter seulement ; ce paramètre n'est pas standard et d'autres fournisseurs pourraient le
     * refuser. Un modèle à réflexion obligatoire répond alors en erreur et le routeur bascule.
     */
    static Map<String, Object> requestBody(LlmProviderConfig provider, String system, String user) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", provider.model());
        body.put("messages", List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user)));
        body.put("temperature", 0.2);
        body.put("max_tokens", 400);
        if (provider.supportsJsonMode()) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        if (isOpenRouter(provider.baseUrl())) {
            body.put("reasoning", Map.of("effort", "none"));
        }
        return body;
    }

    /** Hôte openrouter.ai ou l'un de ses sous-domaines, jamais un nom qui le contient seulement. */
    static boolean isOpenRouter(String baseUrl) {
        if (baseUrl == null) {
            return false;
        }
        try {
            String host = URI.create(baseUrl).getHost();
            if (host == null) {
                return false;
            }
            host = host.toLowerCase(Locale.ROOT);
            return host.equals("openrouter.ai") || host.endsWith(".openrouter.ai");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Lit la réponse sans jamais vider le reste du corps. Succès (2xx, corps entièrement lu) : fermeture
     * normale, la connexion retourne au pool. Tout le reste : {@link CloseMode#IMMEDIATE}, connexion jetée.
     */
    private JsonNode execute(HttpPost request) throws IOException {
        // Pas de try-with-resources : close() standard consommerait le corps restant.
        CloseableHttpResponse response = null;
        boolean abandon = true;
        try {
            response = http.execute(request);
            int code = response.getCode();
            if (code >= 300 && code < 400) {
                throw new LlmCallException(code, "Redirection refusée");
            }
            if (code < 200 || code >= 300) {
                throw new LlmCallException(code, "HTTP " + code);
            }
            HttpEntity entity = response.getEntity();
            if (entity == null) {
                throw new LlmCallException(LlmCallException.INVALID_OUTPUT, "Réponse sans contenu");
            }
            // Le flux n'est pas fermé ici non plus : son close() viderait lui aussi le reste du corps.
            InputStream in = entity.getContent();
            byte[] bytes = in.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) {
                throw new LlmCallException(LlmCallException.INVALID_OUTPUT, "Réponse trop volumineuse");
            }
            try {
                JsonNode json = MAPPER.readTree(bytes);
                abandon = false;
                return json;
            } catch (IOException e) {
                throw new LlmCallException(LlmCallException.INVALID_OUTPUT, "Réponse illisible");
            }
        } finally {
            if (response != null) {
                if (abandon) {
                    response.close(CloseMode.IMMEDIATE);
                } else {
                    response.close();
                }
            }
        }
    }

    private static boolean isBlocked(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof ProviderAddressGuard.BlockedAddressException) {
                return true;
            }
            if (t.getCause() == t) break;
        }
        return false;
    }

    /**
     * Apache HttpClient 5 explicite : seul moyen de lui imposer le résolveur DNS gardé. Pas de redirections,
     * pas de cookies. Délais durs : connexion 5 s, lecture 15 s (par lecture ; le total est géré à part).
     */
    private static CloseableHttpClient buildHttpClient(DnsResolver dnsResolver) {
        PoolingHttpClientConnectionManager connections = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(dnsResolver)
                // Pool explicite (valeurs par défaut Apache : 5 par route, 25 au total) : 20 questions
                // simultanées vers un même fournisseur ne doivent pas attendre une connexion libre.
                .setMaxConnPerRoute(20)
                .setMaxConnTotal(50)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofSeconds(5))
                        .setSocketTimeout(Timeout.ofSeconds(15))
                        .build())
                .build();
        return HttpClients.custom()
                .setConnectionManager(connections)
                .disableRedirectHandling()
                .disableCookieManagement()
                // Les relances automatiques (429/503) drainent le corps et dorment Retry-After sans pouvoir
                // être annulées : le routeur de l'assistant bascule lui-même de fournisseur.
                .disableAutomaticRetries()
                // Un flux gzip suivi d'octets sans fin échapperait au plafond et ferait drainer jusqu'au délai.
                .disableContentCompression()
                .build();
    }
}
