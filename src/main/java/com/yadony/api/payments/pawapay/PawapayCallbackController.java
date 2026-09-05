package com.yadony.api.payments.pawapay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Réception des callbacks pawaPay. Public (pas de token Firebase) : la sécurité est la
 * signature RFC 9421, obligatoire en prod ({@code PawapayConfig}), vérifiée dès qu'elle
 * est requise OU présente. On répond 200 à tout ce qui est compris, même inconnu ou
 * déjà final : pawaPay retente pendant 15 min sinon, et le poller rattrape de toute façon.
 *
 * <p>Le corps est reçu en {@code byte[]} brut, jamais désérialisé puis re-sérialisé avant
 * vérification : {@link PawapaySignatureVerifier#verify} recalcule le {@code Content-Digest}
 * sur ces octets exacts. Un aller-retour par un objet Jackson (même pour reformater en
 * JSON canonique) romprait l'égalité byte-à-byte attendue par la signature pawaPay et ferait
 * échouer 100 % des callbacks avec un 401 d'apparence parfaitement légitime.
 */
@RestController
@RequestMapping("/pawapay/callbacks")
public class PawapayCallbackController {

    private static final Logger log = LoggerFactory.getLogger(PawapayCallbackController.class);

    private final PawapayOperationService operations;
    private final PawapaySignatureVerifier verifier;
    private final PawapayProperties props;
    private final ObjectMapper mapper;

    public PawapayCallbackController(PawapayOperationService operations, PawapaySignatureVerifier verifier,
                                     PawapayProperties props, ObjectMapper mapper) {
        this.operations = operations;
        this.verifier = verifier;
        this.props = props;
        this.mapper = mapper;
    }

    @PostMapping("/deposits")
    public ResponseEntity<Void> deposits(HttpServletRequest request, @RequestBody byte[] body) {
        return handle(PawapayOperationKind.DEPOSIT, request, body);
    }

    @PostMapping("/payouts")
    public ResponseEntity<Void> payouts(HttpServletRequest request, @RequestBody byte[] body) {
        return handle(PawapayOperationKind.PAYOUT, request, body);
    }

    @PostMapping("/refunds")
    public ResponseEntity<Void> refunds(HttpServletRequest request, @RequestBody byte[] body) {
        return handle(PawapayOperationKind.REFUND, request, body);
    }

    private ResponseEntity<Void> handle(PawapayOperationKind kind, HttpServletRequest request, byte[] body) {
        Map<String, String> headers = lowerCaseHeaders(request);
        // Vérification dès que la signature est requise (prod) OU simplement présente (permet
        // de tester la vérification en environnement où callback-signatures-required=false,
        // cf. PawapayCallbackControllerIT#invalidSignature_is401_whenVerifierRejects) : `body`
        // est passé tel que reçu par le convertisseur byte[], sans aucune étape de
        // (dé)sérialisation intermédiaire.
        if (props.callbackSignaturesRequired() || headers.containsKey("signature")) {
            String authority = Optional.ofNullable(request.getHeader("Host")).orElse(request.getServerName());
            verifier.verify(request.getMethod(), authority, request.getRequestURI(), headers, body);
        }

        JsonNode json;
        try {
            json = mapper.readTree(body);
        } catch (Exception e) {
            log.warn("pawaPay callback {} : corps illisible ({} octets)", kind, body.length);
            return ResponseEntity.ok().build();
        }
        String rawId = json.path(kind.idField()).asText(null);
        UUID id;
        try {
            id = UUID.fromString(rawId);
        } catch (Exception e) {
            log.warn("pawaPay callback {} : {} absent ou invalide", kind, kind.idField());
            return ResponseEntity.ok().build();
        }
        Optional<PawapayOperationStatus> status = PawapayOperationStatus.fromApi(json.path("status").asText(null));
        if (status.isEmpty()) {
            log.warn("pawaPay callback {} {} : statut inconnu {}", kind, id, truncate(json.path("status").asText()));
            return ResponseEntity.ok().build();
        }
        operations.apply(id, status.get(),
                json.path("failureReason").path("failureCode").asText(null),
                json.path("failureReason").path("failureMessage").asText(null),
                json.path("providerTransactionId").asText(null),
                json.path("authorizationUrl").asText(null),
                new String(body, StandardCharsets.UTF_8),
                PawapayOperationService.Source.CALLBACK);
        return ResponseEntity.ok().build();
    }

    private static Map<String, String> lowerCaseHeaders(HttpServletRequest request) {
        Map<String, String> out = new HashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            out.put(name.toLowerCase(Locale.ROOT), request.getHeader(name));
        }
        return out;
    }

    /**
     * Borne à 64 caractères une valeur non authentifiée avant de la journaliser — même
     * convention que {@code PawapaySignatureVerifier#truncate} : un {@code status} n'est
     * jamais vérifié par signature quand {@code callbackSignaturesRequired=false} (staging),
     * un appelant anonyme pourrait donc y glisser une charge arbitrairement longue (avec
     * retours à la ligne, pour forger de fausses entrées de journal).
     */
    private static String truncate(String value) {
        return value.length() > 64 ? value.substring(0, 64) : value;
    }
}
