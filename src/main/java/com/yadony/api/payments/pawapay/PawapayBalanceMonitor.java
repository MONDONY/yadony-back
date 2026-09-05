package com.yadony.api.payments.pawapay;

import com.yadony.api.admin.AdminAlertEntity;
import com.yadony.api.admin.AdminAlertRepository;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.payments.pawapay.dto.PawapayWalletBalance;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

/**
 * Le wallet pawaPay est préfinancé à la main (virement yadony). Cette alerte prévient
 * avant qu'un payout ne soit refusé pour solde insuffisant. Idempotente : pas de doublon
 * tant qu'une alerte non résolue existe pour la devise, identifiée par un type d'alerte
 * exact suffixé par la devise (ex. {@code PAWAPAY_BALANCE_LOW_XOF}) — jamais par le
 * contenu du payload JSON. PostgreSQL reformate un {@code jsonb} à la relecture (espace
 * après chaque « : », ordre des clés non garanti) : un marqueur de sous-chaîne compact ne
 * matcherait alors plus jamais un payload relu depuis la vraie colonne, et l'alerte se
 * relèverait à chaque passage horaire, indéfiniment (revue ronde 1, point 2).
 *
 * <p>Ce contrôle tourne toutes les heures : sans la déduplication par alerte non résolue,
 * un solde durablement bas relèverait une alerte à chaque passage et noierait le canal.
 */
@Component
public class PawapayBalanceMonitor {

    static final String ALERT_TYPE_PREFIX = "PAWAPAY_BALANCE_LOW_";
    private static final Logger log = LoggerFactory.getLogger(PawapayBalanceMonitor.class);

    private final PawapayClient client;
    private final AdminAlertService alerts;
    private final AdminAlertRepository alertRepository;
    private final PawapayProperties props;

    public PawapayBalanceMonitor(PawapayClient client, AdminAlertService alerts,
                                 AdminAlertRepository alertRepository, PawapayProperties props) {
        this.client = client;
        this.alerts = alerts;
        this.alertRepository = alertRepository;
        this.props = props;
    }

    @Scheduled(cron = "${yadony.pawapay.balance-cron}")
    public void check() {
        // HashMap (jamais Map.of) : PawapayClient construit la devise avec asText(null) —
        // une ligne de solde sans devise ne doit pas faire lever de NPE sur getOrDefault et
        // tuer tout le passage, y compris pour les devises parfaitement lisibles du même
        // tableau (revue ronde 1, point 6). Map.of interdit purement et simplement toute
        // clé de recherche nulle, HashMap répond simplement le défaut.
        Map<String, BigDecimal> thresholds = new HashMap<>();
        thresholds.put("XOF", nz(props.balanceMin().xof()));
        thresholds.put("XAF", nz(props.balanceMin().xaf()));
        if (!props.enabled() || thresholds.values().stream().allMatch(t -> t.signum() <= 0)) {
            return;
        }
        List<PawapayWalletBalance> balances;
        try {
            balances = client.walletBalances();
        } catch (RestClientException e) {
            // Anticipé : pawaPay indisponible. Message seul, la pile n'apporterait rien.
            log.error("pawaPay : soldes indisponibles ({})", e.getMessage());
            return;
        } catch (Exception e) {
            // Inattendu : mérite sa pile, sinon un bug déterministe ne produirait qu'une
            // ligne ERROR sans trace à chaque passage horaire.
            log.error("pawaPay : soldes indisponibles, erreur inattendue", e);
            return;
        }
        for (PawapayWalletBalance b : balances) {
            BigDecimal min = thresholds.getOrDefault(b.currency(), BigDecimal.ZERO);
            if (min.signum() <= 0 || b.balance().compareTo(min) >= 0) {
                continue;
            }
            String type = ALERT_TYPE_PREFIX + b.currency();
            if (!alertRepository.findByTypeAndResolved(type, false).isEmpty()) {
                continue;
            }
            AdminAlertEntity alert = new AdminAlertEntity();
            alert.setType(type);
            alert.setPayload("{\"currency\":\"" + b.currency() + "\",\"balance\":\"" + b.balance().toPlainString()
                    + "\",\"threshold\":\"" + min.toPlainString() + "\"}");
            alert.setResolved(false);
            alertRepository.save(alert);
            alerts.raise(type, "Solde pawaPay " + b.currency() + " sous le seuil : " + b.balance().toPlainString(),
                    Map.of("currency", b.currency(), "balance", b.balance().toPlainString(), "threshold", min.toPlainString()));
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
