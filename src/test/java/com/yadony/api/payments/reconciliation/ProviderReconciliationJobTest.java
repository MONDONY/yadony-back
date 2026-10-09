package com.yadony.api.payments.reconciliation;

import com.yadony.api.admin.AdminAlertEscalator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProviderReconciliationJobTest {

    private final StripeReconciler stripe = mock(StripeReconciler.class);
    private final PawapayReconciler pawapay = mock(PawapayReconciler.class);
    private final AdminAlertEscalator alerts = mock(AdminAlertEscalator.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private ProviderReconciliationJob job(boolean enabled) {
        return new ProviderReconciliationJob(stripe, pawapay, alerts, registry, enabled);
    }

    private double gauge(String name, String provider) {
        return registry.get(name).tag("provider", provider).gauge().value();
    }

    @Test
    void chaqueEcartLeveUneAlerteEtLesJaugesRefletentLePassage() {
        ReconciliationMismatch lost = new ReconciliationMismatch(ReconciliationMismatch.Provider.STRIPE,
                "pi_topup_lost", "RECHARGE_NON_CREDITEE", "20.00 eur payés, aucun crédit");
        when(stripe.reconcile(any())).thenReturn(new ReconciliationResult(List.of(lost), 12, 1));
        when(pawapay.reconcile(any())).thenReturn(new ReconciliationResult(List.of(), 4, 0));

        job(true).run();

        verify(alerts).raiseOnce(eq("RECON_STRIPE_pi_topup_lost"), anyString(), anyMap());
        assertThat(gauge("yadony.reconciliation.mismatches", "STRIPE")).isEqualTo(1.0);
        assertThat(gauge("yadony.reconciliation.mismatches", "PAWAPAY")).isZero();
        assertThat(gauge("yadony.reconciliation.errors", "STRIPE")).isEqualTo(1.0);
        assertThat(registry.get("yadony.reconciliation.last_run_seconds").gauge().value()).isPositive();
    }

    @Test
    void unRapprochementQuiPlanteNEmpechePasLAutre() {
        when(stripe.reconcile(any())).thenThrow(new IllegalStateException("bug"));
        when(pawapay.reconcile(any())).thenReturn(new ReconciliationResult(List.of(), 3, 0));

        job(true).run();

        verify(pawapay).reconcile(any(Instant.class));
        assertThat(gauge("yadony.reconciliation.errors", "STRIPE")).isEqualTo(-1.0);
        verify(alerts, never()).raiseOnce(anyString(), anyString(), anyMap());
    }

    @Test
    void desactive_lePassagePlanifieNInterrogeRien() {
        job(false).scheduledRun();

        verifyNoInteractions(stripe, pawapay);
    }
}
