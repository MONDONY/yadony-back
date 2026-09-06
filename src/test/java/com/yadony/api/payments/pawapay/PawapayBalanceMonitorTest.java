package com.yadony.api.payments.pawapay;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.payments.pawapay.dto.PawapayWalletBalance;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * La déduplication par devise (une alerte non résolue {@code PAWAPAY_BALANCE_LOW_XOF} bloque la
 * suivante) appartient à {@link AdminAlertEscalator}, testée à part : ici on vérifie le seuil, la
 * devise précise et la robustesse du passage.
 */
@ExtendWith(MockitoExtension.class)
class PawapayBalanceMonitorTest {

    @Mock PawapayClient client;
    @Mock AdminAlertEscalator alerts;

    private PawapayBalanceMonitor monitor(BigDecimal minXof, BigDecimal minXaf) {
        return monitor(true, minXof, minXaf);
    }

    private PawapayBalanceMonitor monitor(boolean enabled, BigDecimal minXof, BigDecimal minXaf) {
        return new PawapayBalanceMonitor(client, alerts, new PawapayProperties(enabled, "https://x", "t",
                false, 30, "https://r", "yadony://bids/%s/mobile-money/awaiting",
                new PawapayProperties.BalanceMin(minXof, minXaf)));
    }

    @Test
    void belowThreshold_raisesForThatCurrencyOnly() {
        when(client.walletBalances()).thenReturn(List.of(
                new PawapayWalletBalance("SEN", "XOF", new BigDecimal("50000")),
                new PawapayWalletBalance("CMR", "XAF", new BigDecimal("900000"))));

        monitor(new BigDecimal("100000"), new BigDecimal("100000")).check();

        // Vérifie la devise précise, pas seulement le nombre d'appels — une comparaison inversée
        // aurait alerté XAF (au-dessus du seuil) au lieu de XOF (en dessous).
        verify(alerts).raiseOnce(eq("PAWAPAY_BALANCE_LOW_XOF"), contains("XOF"),
                argThat(ctx -> "XOF".equals(ctx.get("currency"))));
        verify(alerts, never()).raiseOnce(eq("PAWAPAY_BALANCE_LOW_XAF"), any(), any());
    }

    @Test
    void zeroThreshold_disablesTheCheck() {
        monitor(BigDecimal.ZERO, BigDecimal.ZERO).check();
        verify(client, never()).walletBalances();
    }

    // Symétrique du test du poller — verrouille que rien ne part vers pawaPay quand le rail est
    // fermé (état par défaut, yadony.pawapay.enabled=false).

    @Test
    void disabled_doesNothing() {
        monitor(false, new BigDecimal("100000"), new BigDecimal("100000")).check();
        verify(client, never()).walletBalances();
    }

    // Map.of lève une NPE sur une clé nulle, là où un HashMap rend la valeur par défaut.
    // PawapayClient construit la devise avec asText(null) : une ligne de solde sans devise ne
    // doit pas tuer tout le passage, y compris pour les devises parfaitement lisibles du même
    // tableau.

    @Test
    void unknownOrNullCurrency_doesNotStopTheBatch() {
        when(client.walletBalances()).thenReturn(List.of(
                new PawapayWalletBalance("XX", null, new BigDecimal("5")),
                new PawapayWalletBalance("SEN", "XOF", new BigDecimal("50000"))));

        monitor(new BigDecimal("100000"), BigDecimal.ZERO).check();

        verify(alerts).raiseOnce(eq("PAWAPAY_BALANCE_LOW_XOF"), any(), any());
    }
}
