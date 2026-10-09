package com.yadony.api.payments.reconciliation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.payments.pawapay.PawapayClient;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationRepository;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import com.yadony.api.payments.pawapay.dto.PawapayOperationSnapshot;
import com.yadony.api.payments.reconciliation.ReconciliationMismatch.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Rapprochement quotidien avec pawaPay : relit chez pawaPay chaque opération terminée chez nous
 * (COMPLETED ou FAILED) ces derniers jours, et compare statut, montant et devise.
 *
 * <p>Complète {@code PawapayReconciliationPoller}, qui ne s'occupe que des opérations encore
 * ouvertes : une fois une opération finale chez nous, plus rien ne la relisait. Les opérations
 * SUBMIT_REJECTED ne sont jamais parties chez pawaPay et ne sont pas relues.
 */
@Component
public class PawapayReconciler {

    private static final Logger log = LoggerFactory.getLogger(PawapayReconciler.class);

    /** Opérations relues : le passage est quotidien, trois jours laissent deux rattrapages. */
    static final Duration WINDOW = Duration.ofDays(3);

    private final PawapayOperationRepository repository;
    private final PawapayClient client;
    private final ObjectMapper mapper;

    public PawapayReconciler(PawapayOperationRepository repository, PawapayClient client, ObjectMapper mapper) {
        this.repository = repository;
        this.client = client;
        this.mapper = mapper;
    }

    public ReconciliationResult reconcile(Instant now) {
        List<ReconciliationMismatch> mismatches = new ArrayList<>();
        int checked = 0;
        int errors = 0;
        LocalDateTime since = LocalDateTime.ofInstant(now.minus(WINDOW), ZoneOffset.UTC);
        for (PawapayOperationEntity op : repository.findByStatusInAndFinalizedAtAfter(
                List.of(PawapayOperationStatus.COMPLETED, PawapayOperationStatus.FAILED), since)) {
            Optional<PawapayOperationSnapshot> remote;
            try {
                remote = client.getStatus(op.getKind(), op.getId());
            } catch (RestClientException e) {
                errors++;
                log.warn("Rapprochement pawaPay : {} {} non relue, à revoir au prochain passage ({})",
                        op.getKind(), op.getId(), e.getMessage());
                continue;
            }
            checked++;
            List<String> codes = new ArrayList<>();
            String remoteView;
            if (remote.isEmpty()) {
                if (op.getStatus() == PawapayOperationStatus.COMPLETED) {
                    codes.add("INCONNUE_CHEZ_PAWAPAY");
                }
                remoteView = "inconnue";
            } else {
                PawapayOperationSnapshot snapshot = remote.get();
                if (snapshot.status() != op.getStatus()) {
                    codes.add("STATUT_DIFFERENT");
                }
                JsonNode data = readRaw(snapshot.raw());
                String amount = data.path("amount").asText(null);
                String currency = data.path("currency").asText(null);
                if (amount != null && new BigDecimal(amount).compareTo(op.getAmount()) != 0) {
                    codes.add("MONTANT_DIFFERENT");
                }
                if (currency != null && !currency.equalsIgnoreCase(op.getCurrency())) {
                    codes.add("DEVISE_DIFFERENTE");
                }
                remoteView = snapshot.status() + " " + amount + " " + currency;
            }
            if (!codes.isEmpty()) {
                mismatches.add(new ReconciliationMismatch(Provider.PAWAPAY, op.getId().toString(),
                        String.join(",", codes),
                        "base : " + op.getKind() + " " + op.getStatus() + " " + op.getAmount() + " " + op.getCurrency()
                                + " ; pawaPay : " + remoteView));
            }
        }
        return new ReconciliationResult(List.copyOf(mismatches), checked, errors);
    }

    /** Le JSON {@code data} renvoyé par pawaPay ; objet vide s'il est illisible (montant non comparé). */
    private JsonNode readRaw(String raw) {
        if (raw == null) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(raw);
        } catch (JsonProcessingException e) {
            return mapper.createObjectNode();
        }
    }
}
