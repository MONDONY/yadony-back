package com.yadony.api.payments.pawapay;

import com.yadony.api.admin.AdminAlertEntity;
import com.yadony.api.admin.AdminAlertRepository;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.payments.pawapay.dto.PawapayWalletBalance;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Le wallet pawaPay est préfinancé à la main (virement yadony). Cette alerte prévient
 * avant qu'un payout ne soit refusé pour solde insuffisant. Idempotente : pas de doublon
 * tant qu'une alerte non résolue existe pour la devise (même patron qu'EscrowScheduler).
 *
 * <p>Ce contrôle tourne toutes les heures : sans la déduplication par alerte non résolue,
 * un solde durablement bas relèverait une alerte à chaque passage et noierait le canal.
 */
@Component
public class PawapayBalanceMonitor {

    static final String ALERT_TYPE = "PAWAPAY_BALANCE_LOW";
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
    @Transactional
    public void check() {
        Map<String, BigDecimal> thresholds = Map.of("XOF", nz(props.balanceMin().xof()), "XAF", nz(props.balanceMin().xaf()));
        if (!props.enabled() || thresholds.values().stream().allMatch(t -> t.signum() <= 0)) {
            return;
        }
        List<PawapayWalletBalance> balances;
        try {
            balances = client.walletBalances();
        } catch (Exception e) {
            log.error("pawaPay : soldes indisponibles ({})", e.toString());
            return;
        }
        List<AdminAlertEntity> unresolved = alertRepository.findByTypeAndResolved(ALERT_TYPE, false);
        for (PawapayWalletBalance b : balances) {
            BigDecimal min = thresholds.getOrDefault(b.currency(), BigDecimal.ZERO);
            if (min.signum() <= 0 || b.balance().compareTo(min) >= 0) {
                continue;
            }
            String marker = "\"currency\":\"" + b.currency() + "\"";
            if (unresolved.stream().anyMatch(a -> a.getPayload() != null && a.getPayload().contains(marker))) {
                continue;
            }
            AdminAlertEntity alert = new AdminAlertEntity();
            alert.setType(ALERT_TYPE);
            alert.setPayload("{" + marker + ",\"balance\":\"" + b.balance().toPlainString()
                    + "\",\"threshold\":\"" + min.toPlainString() + "\"}");
            alert.setResolved(false);
            alertRepository.save(alert);
            alerts.raise(ALERT_TYPE, "Solde pawaPay " + b.currency() + " sous le seuil : " + b.balance().toPlainString(),
                    Map.of("currency", b.currency(), "balance", b.balance().toPlainString(), "threshold", min.toPlainString()));
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
