package com.yadony.api.payments.reconciliation;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.payments.reconciliation.ReconciliationMismatch.Provider;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Rapprochement quotidien de la base avec Stripe et pawaPay (04:30 UTC, hors de la fenêtre de
 * lenteur disque du soir sur les VPS).
 *
 * <p>Chaque écart lève l'alerte {@code RECON_<PRESTATAIRE>_<référence>} (persistée dans
 * {@code admin_alerts}, envoyée sur Telegram, une fois tant qu'elle n'est pas résolue). Jauges
 * Prometheus par prestataire : {@code yadony_reconciliation_mismatches} et
 * {@code yadony_reconciliation_errors} (objets non confirmés par le prestataire ; {@code -1}
 * quand le rapprochement entier a planté), plus {@code yadony_reconciliation_last_run_seconds}.
 */
@Component
public class ProviderReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ProviderReconciliationJob.class);
    private static final int ALERT_TYPE_MAX_LENGTH = AdminAlertEscalator.TYPE_MAX_LENGTH;

    private final StripeReconciler stripe;
    private final PawapayReconciler pawapay;
    private final AdminAlertEscalator alerts;
    private final boolean enabled;
    private final Map<Provider, AtomicLong> mismatchGauges = new EnumMap<>(Provider.class);
    private final Map<Provider, AtomicLong> errorGauges = new EnumMap<>(Provider.class);
    private final AtomicLong lastRun = new AtomicLong();

    public ProviderReconciliationJob(StripeReconciler stripe, PawapayReconciler pawapay,
                                     AdminAlertEscalator alerts, MeterRegistry meterRegistry,
                                     @Value("${yadony.reconciliation.enabled:true}") boolean enabled) {
        this.stripe = stripe;
        this.pawapay = pawapay;
        this.alerts = alerts;
        this.enabled = enabled;
        for (Provider provider : Provider.values()) {
            mismatchGauges.put(provider, register(meterRegistry, "yadony.reconciliation.mismatches",
                    "Écarts trouvés au dernier rapprochement", provider));
            errorGauges.put(provider, register(meterRegistry, "yadony.reconciliation.errors",
                    "Objets que le prestataire n'a pas pu confirmer (-1 : rapprochement en échec)", provider));
        }
        Gauge.builder("yadony.reconciliation.last_run_seconds", lastRun, AtomicLong::get)
                .description("Fin du dernier rapprochement (secondes epoch)")
                .register(meterRegistry);
    }

    private static AtomicLong register(MeterRegistry registry, String name, String description, Provider provider) {
        AtomicLong value = new AtomicLong();
        Gauge.builder(name, value, AtomicLong::get)
                .description(description)
                .tag("provider", provider.name())
                .register(registry);
        return value;
    }

    @Scheduled(cron = "${yadony.reconciliation.cron:0 30 4 * * *}", zone = "UTC")
    public void scheduledRun() {
        if (enabled) {
            run();
        }
    }

    public void run() {
        Instant now = Instant.now();
        runOne(Provider.STRIPE, stripe::reconcile, now);
        runOne(Provider.PAWAPAY, pawapay::reconcile, now);
        lastRun.set(Instant.now().getEpochSecond());
    }

    private void runOne(Provider provider, Function<Instant, ReconciliationResult> reconciler, Instant now) {
        ReconciliationResult result;
        try {
            result = reconciler.apply(now);
        } catch (RuntimeException e) {
            log.error("Rapprochement {} en échec", provider, e);
            errorGauges.get(provider).set(-1);
            return;
        }
        mismatchGauges.get(provider).set(result.mismatches().size());
        errorGauges.get(provider).set(result.errors());
        log.info("Rapprochement {} : {} objets comparés, {} écart(s), {} non confirmé(s)",
                provider, result.checked(), result.mismatches().size(), result.errors());
        for (ReconciliationMismatch mismatch : result.mismatches()) {
            raise(mismatch);
        }
    }

    private void raise(ReconciliationMismatch mismatch) {
        String type = "RECON_" + mismatch.provider() + "_" + mismatch.reference();
        if (type.length() > ALERT_TYPE_MAX_LENGTH) {
            type = type.substring(0, ALERT_TYPE_MAX_LENGTH);
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("prestataire", mismatch.provider().name());
        context.put("reference", mismatch.reference());
        context.put("ecart", mismatch.code());
        context.put("detail", mismatch.detail());
        alerts.raiseOnce(type, "Rapprochement " + mismatch.provider() + " : " + mismatch.code()
                + " sur " + mismatch.reference() + " — " + mismatch.detail(), context);
    }
}
