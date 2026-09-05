package com.yadony.api.payments.pawapay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yadony.api.admin.AdminAlertEntity;
import com.yadony.api.admin.AdminAlertRepository;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.payments.pawapay.dto.PawapayWalletBalance;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PawapayBalanceMonitorTest {

    @Mock PawapayClient client;
    @Mock AdminAlertService alerts;
    @Mock AdminAlertRepository alertRepository;

    private PawapayBalanceMonitor monitor(BigDecimal minXof, BigDecimal minXaf) {
        return monitor(true, minXof, minXaf);
    }

    private PawapayBalanceMonitor monitor(boolean enabled, BigDecimal minXof, BigDecimal minXaf) {
        return new PawapayBalanceMonitor(client, alerts, alertRepository, new PawapayProperties(enabled, "https://x", "t",
                false, 30, "https://r", "yadony://bids/%s/mobile-money/awaiting",
                new PawapayProperties.BalanceMin(minXof, minXaf)));
    }

    @Test
    void belowThreshold_raisesOnce() {
        when(client.walletBalances()).thenReturn(List.of(
                new PawapayWalletBalance("SEN", "XOF", new BigDecimal("50000")),
                new PawapayWalletBalance("CMR", "XAF", new BigDecimal("900000"))));
        when(alertRepository.findByTypeAndResolved("PAWAPAY_BALANCE_LOW_XOF", false)).thenReturn(List.of());

        monitor(new BigDecimal("100000"), new BigDecimal("100000")).check();

        // Revue ronde 1, point 5 : vérifie la devise précise, pas seulement le nombre
        // d'appels — une comparaison inversée aurait alerté XAF (au-dessus du seuil) au lieu
        // de XOF (en dessous), et serait passée inaperçue avec un simple verify(times(1)).
        verify(alerts).raise(eq("PAWAPAY_BALANCE_LOW_XOF"), contains("XOF"),
                argThat(ctx -> "XOF".equals(ctx.get("currency"))));
        verify(alerts, never()).raise(eq("PAWAPAY_BALANCE_LOW_XAF"), any(), any());

        ArgumentCaptor<AdminAlertEntity> saved = ArgumentCaptor.forClass(AdminAlertEntity.class);
        verify(alertRepository).save(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo("PAWAPAY_BALANCE_LOW_XOF");
    }

    @Test
    void existingUnresolvedAlert_isNotDuplicated() {
        when(client.walletBalances()).thenReturn(List.of(new PawapayWalletBalance("SEN", "XOF", new BigDecimal("10"))));
        AdminAlertEntity existing = new AdminAlertEntity();
        existing.setType("PAWAPAY_BALANCE_LOW_XOF");
        when(alertRepository.findByTypeAndResolved("PAWAPAY_BALANCE_LOW_XOF", false)).thenReturn(List.of(existing));

        monitor(new BigDecimal("100000"), BigDecimal.ZERO).check();

        verify(alerts, never()).raise(any(), any(), any());
        verify(alertRepository, never()).save(any());
    }

    @Test
    void zeroThreshold_disablesTheCheck() {
        monitor(BigDecimal.ZERO, BigDecimal.ZERO).check();
        verify(client, never()).walletBalances();
    }

    // Revue ronde 1, point 9 : symétrique du test du poller — verrouille que rien ne part
    // vers pawaPay quand le rail est fermé (état par défaut, yadony.pawapay.enabled=false).

    @Test
    void disabled_doesNothing() {
        monitor(false, new BigDecimal("100000"), new BigDecimal("100000")).check();
        verify(client, never()).walletBalances();
    }

    // Revue ronde 1, point 2 (CRITIQUE) : PostgreSQL jsonb reformate le payload à la
    // relecture (espace après les deux-points, ordre des clés non garanti). Un marqueur de
    // sous-chaîne compact ("currency":"XOF") ne matcherait alors plus jamais un payload relu
    // depuis la vraie colonne jsonb, et l'alerte se relèverait indéfiniment. Preuve directe :
    // un payload existant déjà DANS cette forme canonique doit être reconnu par la
    // déduplication tout autant qu'un payload compact — ce qui n'est possible que parce que
    // la déduplication ne dépend plus du tout du contenu du payload, seulement du type exact.

    @Test
    void existingUnresolvedAlert_isRecognizedEvenInJsonbCanonicalForm() {
        when(client.walletBalances()).thenReturn(List.of(new PawapayWalletBalance("SEN", "XOF", new BigDecimal("10"))));
        AdminAlertEntity existing = new AdminAlertEntity();
        existing.setType("PAWAPAY_BALANCE_LOW_XOF");
        // Forme exacte que PostgreSQL renvoie après un aller-retour par une colonne jsonb :
        // espace après chaque ':', clés réordonnées — jamais la forme compacte d'origine.
        existing.setPayload("{\"balance\": \"10\", \"currency\": \"XOF\", \"threshold\": \"100000\"}");
        when(alertRepository.findByTypeAndResolved("PAWAPAY_BALANCE_LOW_XOF", false)).thenReturn(List.of(existing));

        monitor(new BigDecimal("100000"), BigDecimal.ZERO).check();

        verify(alerts, never()).raise(any(), any(), any());
        verify(alertRepository, never()).save(any());
    }

    // Revue ronde 1, point 6 (mineur) : Map.of lève une NPE sur une clé nulle, là où un
    // HashMap rend la valeur par défaut. PawapayClient construit la devise avec
    // asText(null) : une ligne de solde sans devise ne doit pas tuer tout le passage, y
    // compris pour les devises parfaitement lisibles du même tableau.

    @Test
    void unknownOrNullCurrency_doesNotStopTheBatch() {
        when(client.walletBalances()).thenReturn(List.of(
                new PawapayWalletBalance("XX", null, new BigDecimal("5")),
                new PawapayWalletBalance("SEN", "XOF", new BigDecimal("50000"))));
        when(alertRepository.findByTypeAndResolved("PAWAPAY_BALANCE_LOW_XOF", false)).thenReturn(List.of());

        monitor(new BigDecimal("100000"), BigDecimal.ZERO).check();

        verify(alerts).raise(eq("PAWAPAY_BALANCE_LOW_XOF"), any(), any());
    }
}
