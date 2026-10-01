package com.yadony.api.kyc.provider.didit;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Appels HTTP a l'API Didit (v3).
 *
 * <p>Deux operations seulement : ouvrir une session et relire sa decision. Didit n'expose pas
 * d'annulation de session, il n'y a donc rien a appeler pour abandonner.
 */
@Component
public class DiditClient {

    private static final Logger log = LoggerFactory.getLogger(DiditClient.class);

    private final RestClient restClient;
    private final DiditProperties properties;

    /**
     * Deux constructeurs coexistent (celui-ci et celui des tests) : sans {@code @Autowired},
     * Spring ne sait pas lequel choisir et cherche un constructeur sans argument, qui n'existe
     * pas — le contexte entier refuse alors de demarrer.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public DiditClient(DiditProperties properties) {
        this(withTimeouts(), properties);
    }

    DiditClient(RestClient restClient, DiditProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    /**
     * Timeouts durs obligatoires : {@code RestClient.create()} herite des defauts JDK
     * (connexion et lecture INFINIES). Le 2026-09-02, un connect pendu vers un service externe
     * a gele une synchronisation sans exception, donc sans alerte. Echouer vite, ne jamais
     * pendre — d'autant qu'ici l'appel se fait dans la requete d'un utilisateur qui attend.
     *
     * <p>NE PAS revenir a {@code SimpleClientHttpRequestFactory} pour poser ces timeouts. Elle
     * s'appuie sur {@code HttpURLConnection}, qui suit les redirections en REPOSTANT SANS LE
     * CORPS : Didit repondait alors {@code 400 {"workflow_id":["This field is required."]}},
     * comme si la requete etait vide — diagnostic couteux, car les valeurs envoyees etaient
     * toutes correctes. Constate en staging le 2026-09-05. {@code EcbRateClient} utilise la
     * meme fabrique sans dommage parce qu'il ne fait que des GET, qui n'ont pas de corps.
     *
     * <p>{@code ClientHttpRequestFactoryBuilder.detect()} choisit la meilleure implementation
     * disponible au classpath (Apache HttpClient, sinon le client JDK) : toutes reemettent
     * correctement le corps d'un POST redirige.
     *
     * <p>Corps BUFFERISE obligatoire. {@code httpclient5} est au classpath (via firebase-admin),
     * donc {@code detect()} prend Apache HttpClient, qui envoyait le JSON en flux :
     * {@code Transfer-Encoding: chunked}, sans {@code Content-Length}. Didit lisait alors par
     * moments un corps vide et repondait le meme {@code 400 workflow_id required} : du 28/09
     * au 01/10 en staging, le premier essai de verification echouait et le suivant passait.
     * {@link BufferingClientHttpRequestFactory} serialise d'abord, pose le
     * {@code Content-Length} et rend le corps rejouable sur une redirection.
     */
    private static RestClient withTimeouts() {
        return RestClient.builder()
                .requestFactory(new BufferingClientHttpRequestFactory(
                        ClientHttpRequestFactoryBuilder.detect()
                                .build(ClientHttpRequestFactorySettings.defaults()
                                        .withConnectTimeout(Duration.ofSeconds(5))
                                        .withReadTimeout(Duration.ofSeconds(10)))))
                .build();
    }

    /**
     * Ouvre (ou reprend) la session de verification de cet utilisateur.
     *
     * <p>{@code vendor_data} porte l'UUID yadony : Didit s'en sert pour dedoublonner lui-meme.
     * Une session terminale (Approved, Declined, Expired, Abandoned, Kyc Expired) n'est jamais
     * resservie, une session inachevee l'est telle quelle. C'est ce qui rend inutile le
     * controle manuel que reclame Stripe.
     *
     * <p>Aucune donnee personnelle n'est envoyee : ni {@code contact_details}, ni
     * {@code expected_details}. yadony ne transmet pas un etat civil qu'il n'a pas verifie.
     *
     * @throws RuntimeException si Didit refuse ou ne repond pas — l'appelant en fait un 503.
     */
    public JsonNode createSession(UUID userId, String callbackUrl, String language) {
        return createSession(userId, callbackUrl, userId.toString(), language);
    }

    /**
     * Meme appel avec un {@code vendor_data} explicite. Didit dedoublonnant sur ce champ, un
     * {@code vendor_data} distinct de l'UUID force une session neuve (session refusee par un
     * administrateur et encore inachevee chez Didit). {@code metadata.user_id} porte toujours
     * l'UUID yadony ; les webhooks sont rapproches par {@code session_id}, jamais par
     * {@code vendor_data}.
     *
     * <p>{@code language} : langue de l'interface Didit, celle de l'app de l'utilisateur
     * ({@code fr}, {@code en}). Elle était forcée en français, y compris pour un testeur
     * anglophone (feedback FLUTTER-27).
     */
    public JsonNode createSession(UUID userId, String callbackUrl, String vendorData, String language) {
        Map<String, Object> body = Map.of(
                "workflow_id", properties.workflowId(),
                "vendor_data", vendorData,
                "callback", callbackUrl,
                "language", language,
                "metadata", Map.of("user_id", userId.toString()));

        return restClient.post()
                .uri(properties.baseUrl() + "/v3/session/")
                .header("x-api-key", properties.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
    }

    /**
     * Decision complete d'une session. Best-effort : un echec rend {@code empty}, jamais une
     * exception — les deux appelants (prefill Connect, vue admin) doivent se degrader.
     *
     * <p>Le contenu n'est jamais journalise : il porte l'etat civil de la personne.
     */
    public Optional<JsonNode> retrieveDecision(String sessionId) {
        try {
            return Optional.ofNullable(restClient.get()
                    .uri(properties.baseUrl() + "/v3/session/" + sessionId + "/decision/")
                    .header("x-api-key", properties.apiKey())
                    .retrieve()
                    .body(JsonNode.class));
        } catch (RuntimeException e) {
            log.warn("Décision Didit illisible pour la session {} ({})",
                    sessionId, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
