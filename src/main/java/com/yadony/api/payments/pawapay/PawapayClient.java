package com.yadony.api.payments.pawapay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.yadony.api.payments.pawapay.dto.PawapayDepositRequest;
import com.yadony.api.payments.pawapay.dto.PawapayInitiationResult;
import com.yadony.api.payments.pawapay.dto.PawapayOperationSnapshot;
import com.yadony.api.payments.pawapay.dto.PawapayPayoutRequest;
import com.yadony.api.payments.pawapay.dto.PawapayProviderConfig;
import com.yadony.api.payments.pawapay.dto.PawapayProviderPrediction;
import com.yadony.api.payments.pawapay.dto.PawapayPublicKey;
import com.yadony.api.payments.pawapay.dto.PawapayRefundRequest;
import com.yadony.api.payments.pawapay.dto.PawapayWalletBalance;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Client HTTP de l'API pawaPay v2. Aucune logique métier : formate, envoie, parse.
 * Les 4xx porteurs d'un {@code status: REJECTED} sont des réponses normales (parsées) ;
 * les 5xx et les erreurs réseau remontent en {@link RestClientException}.
 */
@Component
public class PawapayClient {

    private static final Logger log = LoggerFactory.getLogger(PawapayClient.class);
    private static final String CONF_KEY = "conf";
    private static final String KEYS_KEY = "keys";

    private final RestClient rest;
    private final ObjectMapper mapper;
    private final Cache<String, Map<String, PawapayProviderConfig>> confCache =
            Caffeine.newBuilder().expireAfterWrite(10, TimeUnit.MINUTES).maximumSize(1).build();
    private final Cache<String, List<PawapayPublicKey>> keysCache =
            Caffeine.newBuilder().expireAfterWrite(1, TimeUnit.HOURS).maximumSize(1).build();

    public PawapayClient(RestClient pawapayRestClient, ObjectMapper mapper) {
        this.rest = pawapayRestClient;
        this.mapper = mapper;
    }

    public PawapayInitiationResult initiateDeposit(PawapayDepositRequest r) {
        ObjectNode body = mapper.createObjectNode();
        body.put("depositId", r.depositId().toString());
        body.put("amount", PawapayAmounts.format(r.amount(), r.currency()));
        body.put("currency", r.currency());
        body.set("payer", party(r.phoneNumber(), r.provider()));
        putIfNotNull(body, "customerMessage", r.customerMessage());
        putIfNotNull(body, "clientReferenceId", r.clientReferenceId());
        putIfNotNull(body, "successfulUrl", r.successfulUrl());
        putIfNotNull(body, "failedUrl", r.failedUrl());
        return initiation(post(PawapayOperationKind.DEPOSIT.path(), body));
    }

    public PawapayInitiationResult initiatePayout(PawapayPayoutRequest r) {
        ObjectNode body = mapper.createObjectNode();
        body.put("payoutId", r.payoutId().toString());
        body.put("amount", PawapayAmounts.format(r.amount(), r.currency()));
        body.put("currency", r.currency());
        body.set("recipient", party(r.phoneNumber(), r.provider()));
        putIfNotNull(body, "customerMessage", r.customerMessage());
        putIfNotNull(body, "clientReferenceId", r.clientReferenceId());
        return initiation(post(PawapayOperationKind.PAYOUT.path(), body));
    }

    public PawapayInitiationResult initiateRefund(PawapayRefundRequest r) {
        ObjectNode body = mapper.createObjectNode();
        body.put("refundId", r.refundId().toString());
        body.put("depositId", r.depositId().toString());
        body.put("amount", PawapayAmounts.format(r.amount(), r.currency()));
        body.put("currency", r.currency());
        return initiation(post(PawapayOperationKind.REFUND.path(), body));
    }

    public Optional<PawapayOperationSnapshot> getStatus(PawapayOperationKind kind, UUID id) {
        JsonNode json = get(kind.path() + "/" + id);
        if (!"FOUND".equalsIgnoreCase(json.path("status").asText())) {
            return Optional.empty();
        }
        JsonNode data = json.path("data");
        Optional<PawapayOperationStatus> status = PawapayOperationStatus.fromApi(data.path("status").asText(null));
        return status.map(s -> new PawapayOperationSnapshot(s,
                data.path("failureReason").path("failureCode").asText(null),
                data.path("failureReason").path("failureMessage").asText(null),
                data.path("providerTransactionId").asText(null),
                data.path("authorizationUrl").asText(null),
                data.toString()));
    }

    public Optional<PawapayProviderPrediction> predictProvider(String phoneNumber) {
        ObjectNode body = mapper.createObjectNode();
        body.put("phoneNumber", phoneNumber);
        JsonNode json = post("/v2/predict-provider", body);
        if (json.hasNonNull("provider") && json.hasNonNull("country")) {
            return Optional.of(new PawapayProviderPrediction(json.get("country").asText(),
                    json.get("provider").asText(), json.path("phoneNumber").asText(null)));
        }
        return Optional.empty();
    }

    /** Configuration active aplatie par code opérateur. Cache 10 min. */
    public Map<String, PawapayProviderConfig> activeConfiguration() {
        return confCache.get(CONF_KEY, k -> fetchActiveConfiguration());
    }

    public List<PawapayWalletBalance> walletBalances() {
        List<PawapayWalletBalance> out = new ArrayList<>();
        for (JsonNode b : get("/v2/wallet-balances").path("balances")) {
            out.add(new PawapayWalletBalance(b.path("country").asText(null), b.path("currency").asText(null),
                    new BigDecimal(b.path("balance").asText("0"))));
        }
        return out;
    }

    /** Clés publiques de signature des callbacks. Cache 1 h ({@link #evictCaches()} sur keyid inconnu). */
    public List<PawapayPublicKey> publicKeys() {
        return keysCache.get(KEYS_KEY, k -> {
            List<PawapayPublicKey> out = new ArrayList<>();
            for (JsonNode n : get("/v2/public-key/http")) {
                out.add(new PawapayPublicKey(n.path("id").asText(null), n.path("key").asText(null)));
            }
            return out;
        });
    }

    public void evictCaches() {
        confCache.invalidateAll();
        keysCache.invalidateAll();
    }

    // ── interne ──────────────────────────────────────────────────────────────

    private Map<String, PawapayProviderConfig> fetchActiveConfiguration() {
        Map<String, PawapayProviderConfig> out = new LinkedHashMap<>();
        for (JsonNode country : get("/v2/active-conf").path("countries")) {
            String alpha3 = country.path("country").asText(null);
            for (JsonNode provider : country.path("providers")) {
                String code = provider.path("provider").asText(null);
                for (JsonNode currency : provider.path("currencies")) {
                    JsonNode ops = currency.path("operationTypes");
                    out.put(code, new PawapayProviderConfig(code, alpha3, currency.path("currency").asText(null),
                            limits(ops.get("DEPOSIT")), limits(ops.get("PAYOUT")), limits(ops.get("REFUND"))));
                }
            }
        }
        return Map.copyOf(out);
    }

    private static PawapayProviderConfig.Limits limits(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        return new PawapayProviderConfig.Limits(
                n.hasNonNull("minAmount") ? new BigDecimal(n.get("minAmount").asText()) : null,
                n.hasNonNull("maxAmount") ? new BigDecimal(n.get("maxAmount").asText()) : null,
                n.path("decimalsInAmount").asText(null), n.path("authType").asText(null), n.path("status").asText(null));
    }

    private ObjectNode party(String phoneNumber, String provider) {
        ObjectNode details = mapper.createObjectNode();
        details.put("phoneNumber", phoneNumber);
        details.put("provider", provider);
        ObjectNode party = mapper.createObjectNode();
        party.put("type", "MMO");
        party.set("accountDetails", details);
        return party;
    }

    private static void putIfNotNull(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) node.put(field, value);
    }

    private PawapayInitiationResult initiation(JsonNode json) {
        String status = json.path("status").asText("");
        return switch (status) {
            case "ACCEPTED" -> PawapayInitiationResult.accepted();
            case "DUPLICATE_IGNORED" -> new PawapayInitiationResult(PawapayInitiationResult.Outcome.DUPLICATE_IGNORED, null, null);
            default -> new PawapayInitiationResult(PawapayInitiationResult.Outcome.REJECTED,
                    json.path("failureReason").path("failureCode").asText("REJECTED"),
                    json.path("failureReason").path("failureMessage").asText(null));
        };
    }

    /** POST qui lit le corps quel que soit le 4xx (REJECTED arrive en 200 ou 400) ; 5xx → exception. */
    private JsonNode post(String path, ObjectNode body) {
        return rest.post().uri(path).body(body.toString()).exchange((req, res) -> readBody(path, res));
    }

    private JsonNode get(String path) {
        return rest.get().uri(path).exchange((req, res) -> readBody(path, res));
    }

    private JsonNode readBody(String path, org.springframework.http.client.ClientHttpResponse res) throws IOException {
        int code = res.getStatusCode().value();
        byte[] bytes = res.getBody().readAllBytes();
        if (code >= 500) {
            throw new RestClientException("pawaPay " + path + " HTTP " + code);
        }
        if (bytes.length == 0) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(bytes);
        } catch (IOException e) {
            log.warn("pawaPay {} : réponse non JSON (HTTP {})", path, code);
            throw new RestClientException("pawaPay " + path + " réponse illisible", e);
        }
    }
}
