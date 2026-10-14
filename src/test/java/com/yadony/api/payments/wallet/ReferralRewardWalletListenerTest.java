package com.yadony.api.payments.wallet;

import com.yadony.api.referral.events.ReferralWalletRewardChosenEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ReferralRewardWalletListenerTest {

    @Mock private WalletService walletService;

    @InjectMocks private ReferralRewardWalletListener listener;

    private final UUID referrerId = UUID.randomUUID();
    private final UUID invitationId = UUID.randomUUID();

    @Test
    void creditsTheReferrerWithAnIdempotencyKeyPerInvitation() {
        listener.onWalletRewardChosen(
                new ReferralWalletRewardChosenEvent(invitationId, referrerId, new BigDecimal("5.00"), "EUR"));

        verify(walletService).credit(referrerId, "EUR", new BigDecimal("5.00"),
                WalletTransactionType.REFERRAL_REWARD, invitationId.toString(),
                "referral-reward:" + invitationId);
    }

    @Test
    void creditFailure_isRethrown() {
        doThrow(new IllegalStateException("db down")).when(walletService)
                .credit(any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> listener.onWalletRewardChosen(
                new ReferralWalletRewardChosenEvent(invitationId, referrerId, new BigDecimal("5.00"), "EUR")))
                .isInstanceOf(IllegalStateException.class);
    }
}
