package com.yadony.api.payments.wallet;

import com.yadony.api.referral.events.ReferralRewardGrantedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReferralRewardWalletListener — unit tests")
class ReferralRewardWalletListenerTest {

    @Mock private WalletService walletService;

    private ReferralRewardWalletListener listener;

    private static final UUID REFERRER_ID = UUID.randomUUID();
    private static final UUID INVITATION_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new ReferralRewardWalletListener(walletService);
    }

    @Test
    @DisplayName("credits the referrer wallet with the euro amount and a stable idempotency key")
    void creditsWalletInEuros() {
        listener.onReferralRewardGranted(
                new ReferralRewardGrantedEvent(REFERRER_ID, 500, INVITATION_ID));

        ArgumentCaptor<BigDecimal> amountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);

        verify(walletService).credit(
                org.mockito.ArgumentMatchers.eq(REFERRER_ID),
                org.mockito.ArgumentMatchers.eq("EUR"),
                amountCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(WalletTransactionType.REFERRAL_REWARD),
                org.mockito.ArgumentMatchers.eq(INVITATION_ID.toString()),
                keyCaptor.capture());

        // 500 cents → 5.00 €
        assertThat(amountCaptor.getValue()).isEqualByComparingTo(new BigDecimal("5.00"));
        // Idempotency key is derived from the invitation id (one reward per invitation)
        assertThat(keyCaptor.getValue()).isEqualTo("referral-reward-" + INVITATION_ID);
    }

    @Test
    @DisplayName("converts arbitrary cent amounts to euros correctly")
    void convertsCentsToEuros() {
        listener.onReferralRewardGranted(
                new ReferralRewardGrantedEvent(REFERRER_ID, 1234, INVITATION_ID));

        ArgumentCaptor<BigDecimal> amountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(walletService).credit(
                org.mockito.ArgumentMatchers.eq(REFERRER_ID),
                org.mockito.ArgumentMatchers.eq("EUR"),
                amountCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(WalletTransactionType.REFERRAL_REWARD),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());

        assertThat(amountCaptor.getValue()).isEqualByComparingTo(new BigDecimal("12.34"));
    }
}
