package com.yadony.api.payments.wallet;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletDisputeServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock private WalletTransactionRepository walletTransactionRepository;
    @Mock private WalletDisputeHoldRepository holdRepository;
    @Mock private WalletService walletService;
    @Mock private AuditService auditService;
    @Mock private AdminAlertService adminAlertService;

    private WalletDisputeService service;

    @BeforeEach
    void setUp() {
        service = new WalletDisputeService(walletTransactionRepository, holdRepository, walletService,
                auditService, adminAlertService);
    }

    private void stubTopup(String currency, String amount) {
        WalletTransactionEntity topup = new WalletTransactionEntity();
        topup.setUserId(USER_ID);
        topup.setCurrency(currency);
        topup.setType(WalletTransactionType.TOP_UP);
        topup.setAmount(new BigDecimal(amount));
        topup.setPaymentRef("pi_topup");
        when(walletTransactionRepository.findByPaymentRefAndType("pi_topup", WalletTransactionType.TOP_UP))
                .thenReturn(List.of(topup));
    }

    @Test
    void ouverture_horsRecharge_rienNEstTouche() {
        when(walletTransactionRepository.findByPaymentRefAndType("pi_bid", WalletTransactionType.TOP_UP))
                .thenReturn(List.of());

        assertThat(service.onDisputeOpened("dp_1", "pi_bid", 5000)).isFalse();

        verifyNoInteractions(walletService, holdRepository, adminAlertService);
    }

    @Test
    void ouverture_sansPaymentIntent_rien() {
        assertThat(service.onDisputeOpened("dp_1", null, 5000)).isFalse();
        verifyNoInteractions(walletTransactionRepository, walletService);
    }

    /**
     * Recharge de 50 € contestée, solde réduit à 30 € : 30 € repris, gel posé, l'admin voit les
     * 20 € à récupérer.
     */
    @Test
    void ouverture_surUneRecharge_reprendDansLaLimiteDuSoldeEtGele() {
        stubTopup("EUR", "50.00");
        when(holdRepository.findByStripeDisputeId("dp_1")).thenReturn(Optional.empty());
        when(walletService.debitDisputedTopup(USER_ID, "EUR", new BigDecimal("50.00"), "pi_topup", "dispute-dp_1"))
                .thenReturn(new BigDecimal("30.00"));

        assertThat(service.onDisputeOpened("dp_1", "pi_topup", 5000)).isTrue();

        ArgumentCaptor<WalletDisputeHoldEntity> hold = ArgumentCaptor.forClass(WalletDisputeHoldEntity.class);
        verify(holdRepository).save(hold.capture());
        assertThat(hold.getValue().getStatus()).isEqualTo(WalletDisputeHoldStatus.OPEN);
        assertThat(hold.getValue().getDisputedAmount()).isEqualByComparingTo("50.00");
        assertThat(hold.getValue().getDebitedAmount()).isEqualByComparingTo("30.00");
        assertThat(hold.getValue().getUserId()).isEqualTo(USER_ID);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> context = ArgumentCaptor.forClass(Map.class);
        verify(adminAlertService).raise(eq("wallet-topup-disputed"), anyString(), context.capture());
        assertThat(context.getValue()).containsEntry("remainingDue", "20.00");
    }

    /** XOF sans décimales : le montant Stripe est déjà en francs ; jamais plus que la recharge. */
    @Test
    void ouverture_montantConverti_selonLUniteMineure_etBorneParLaRecharge() {
        stubTopup("XOF", "10000");
        when(holdRepository.findByStripeDisputeId("dp_x")).thenReturn(Optional.empty());
        when(walletService.debitDisputedTopup(any(), any(), any(), any(), any())).thenReturn(BigDecimal.ZERO);

        service.onDisputeOpened("dp_x", "pi_topup", 25000);

        verify(walletService).debitDisputedTopup(USER_ID, "XOF", new BigDecimal("10000"), "pi_topup", "dispute-dp_x");
    }

    @Test
    void ouverture_montantNul_reprendToutLaRecharge() {
        stubTopup("EUR", "50.00");
        when(holdRepository.findByStripeDisputeId("dp_0")).thenReturn(Optional.empty());
        when(walletService.debitDisputedTopup(any(), any(), any(), any(), any())).thenReturn(BigDecimal.ZERO);

        service.onDisputeOpened("dp_0", "pi_topup", 0);

        verify(walletService).debitDisputedTopup(USER_ID, "EUR", new BigDecimal("50.00"), "pi_topup", "dispute-dp_0");
    }

    @Test
    void ouverture_rejouee_neReprendPasDeuxFois() {
        stubTopup("EUR", "50.00");
        when(holdRepository.findByStripeDisputeId("dp_1")).thenReturn(Optional.of(new WalletDisputeHoldEntity()));

        assertThat(service.onDisputeOpened("dp_1", "pi_topup", 5000)).isTrue();

        verifyNoInteractions(walletService, adminAlertService);
        verify(holdRepository, never()).save(any());
    }

    private WalletDisputeHoldEntity openHold(String debited) {
        WalletDisputeHoldEntity hold = new WalletDisputeHoldEntity();
        hold.setUserId(USER_ID);
        hold.setCurrency("EUR");
        hold.setStripeDisputeId("dp_1");
        hold.setPaymentIntentId("pi_topup");
        hold.setDisputedAmount(new BigDecimal("50.00"));
        hold.setDebitedAmount(new BigDecimal(debited));
        when(holdRepository.findByStripeDisputeIdForUpdate("dp_1")).thenReturn(Optional.of(hold));
        return hold;
    }

    @Test
    void cloture_gagnee_rendLaRepriseEtLeveLeGel() {
        WalletDisputeHoldEntity hold = openHold("30.00");

        service.onDisputeClosed("dp_1", true);

        assertThat(hold.getStatus()).isEqualTo(WalletDisputeHoldStatus.WON);
        assertThat(hold.getClosedAt()).isNotNull();
        verify(walletService).credit(USER_ID, "EUR", new BigDecimal("30.00"),
                WalletTransactionType.TOPUP_DISPUTE_REVERSED, "pi_topup", "dispute-won-dp_1");
        verify(adminAlertService).raise(eq("wallet-topup-dispute-closed"), anyString(), any());
    }

    @Test
    void cloture_perdue_leveLeGelSansRien_rendre() {
        WalletDisputeHoldEntity hold = openHold("30.00");

        service.onDisputeClosed("dp_1", false);

        assertThat(hold.getStatus()).isEqualTo(WalletDisputeHoldStatus.LOST);
        verifyNoInteractions(walletService);
    }

    @Test
    void cloture_gagneeSansReprise_aucunCredit() {
        openHold("0.00");

        service.onDisputeClosed("dp_1", true);

        verifyNoInteractions(walletService);
    }

    @Test
    void cloture_rejouee_ouInconnue_neFaitRien() {
        WalletDisputeHoldEntity hold = openHold("30.00");
        hold.setStatus(WalletDisputeHoldStatus.WON);

        service.onDisputeClosed("dp_1", true);
        service.onDisputeClosed("dp_unknown", true);

        verifyNoInteractions(walletService, adminAlertService);
    }
}
