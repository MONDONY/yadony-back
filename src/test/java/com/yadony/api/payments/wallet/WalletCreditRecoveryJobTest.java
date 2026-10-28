package com.yadony.api.payments.wallet;

import com.yadony.api.common.stripe.AdminAlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletCreditRecoveryJobTest {

    private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");

    @Mock private WalletTransactionRepository walletTransactionRepository;
    @Mock private WalletService walletService;
    @Mock private AdminAlertService adminAlertService;

    private WalletCreditRecoveryJob job;

    @BeforeEach
    void setUp() {
        job = new WalletCreditRecoveryJob(walletTransactionRepository, walletService, adminAlertService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static WalletTransactionRepository.MissedCredit missed(UUID id, UUID userId, String currency, String amount) {
        return new WalletTransactionRepository.MissedCredit() {
            @Override public UUID getId() { return id; }
            @Override public UUID getUserId() { return userId; }
            @Override public String getCurrency() { return currency; }
            @Override public BigDecimal getAmount() { return new BigDecimal(amount); }
        };
    }

    @Test
    void rejoueLesCreditsAvecLaCleDOrigine_etAlerte() {
        UUID op = UUID.randomUUID();
        UUID invitation = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID referrer = UUID.randomUUID();
        when(walletTransactionRepository.findCompletedWalletTopupsWithoutCredit(any(), any()))
                .thenReturn(List.of(missed(op, user, "XOF", "10000")));
        when(walletTransactionRepository.findChosenReferralRewardsWithoutCredit(any(), any()))
                .thenReturn(List.of(missed(invitation, referrer, "EUR", "5.00")));

        job.recoverMissedCredits();

        verify(walletService).credit(user, "XOF", new BigDecimal("10000"), WalletTransactionType.TOP_UP,
                "pawapay:" + op, "pawapay-topup-" + op);
        verify(walletService).credit(referrer, "EUR", new BigDecimal("5.00"), WalletTransactionType.REFERRAL_REWARD,
                invitation.toString(), "referral-reward:" + invitation);
        verify(adminAlertService).raise(eq("wallet-topup-credit-recovered"), anyString(), any());
        verify(adminAlertService).raise(eq("referral-reward-recovered"), anyString(), any());
    }

    @Test
    void fenetres_entreLaGraceEtLaLimite() {
        when(walletTransactionRepository.findCompletedWalletTopupsWithoutCredit(any(), any())).thenReturn(List.of());
        when(walletTransactionRepository.findChosenReferralRewardsWithoutCredit(any(), any())).thenReturn(List.of());

        job.recoverMissedCredits();

        verify(walletTransactionRepository).findCompletedWalletTopupsWithoutCredit(
                OffsetDateTime.parse("2026-10-28T11:45:00Z"), OffsetDateTime.parse("2026-10-21T12:00:00Z"));
        verify(walletTransactionRepository).findChosenReferralRewardsWithoutCredit(
                LocalDateTime.of(2026, 10, 28, 11, 45), LocalDateTime.of(2026, 9, 28, 12, 0));
        verifyNoInteractions(walletService, adminAlertService);
    }

    @Test
    void unEchec_nArretePasLesAutres_etNeCompteQueCeuxRattrapes() {
        UUID failing = UUID.randomUUID();
        UUID ok = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        when(walletTransactionRepository.findCompletedWalletTopupsWithoutCredit(any(), any()))
                .thenReturn(List.of(missed(failing, user, "XOF", "1000"), missed(ok, user, "XOF", "2000")));
        when(walletTransactionRepository.findChosenReferralRewardsWithoutCredit(any(), any())).thenReturn(List.of());
        doThrow(new IllegalStateException("verrou")).when(walletService).credit(user, "XOF", new BigDecimal("1000"),
                WalletTransactionType.TOP_UP, "pawapay:" + failing, "pawapay-topup-" + failing);

        job.recoverMissedCredits();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> context = ArgumentCaptor.forClass(Map.class);
        verify(adminAlertService).raise(eq("wallet-topup-credit-recovered"), anyString(), context.capture());
        assertThat(context.getValue()).containsEntry("count", 1).containsEntry("ids", ok.toString());
    }

    @Test
    void planifie_etSansTransactionEnglobante() throws Exception {
        var method = WalletCreditRecoveryJob.class.getMethod("recoverMissedCredits");
        assertThat(method.isAnnotationPresent(Scheduled.class)).isTrue();
        assertThat(method.isAnnotationPresent(Transactional.class)).isFalse();
    }
}
