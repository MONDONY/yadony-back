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
        listener = new ReferralRewardWalletListener(walletService, resolverReturning("EUR"));
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

    // Mockito renvoie null pour un retour String non stubé (et non Optional.empty()) :
    // sans ce stub la devise résolue serait nulle et le repli ne serait pas testé.
    private static com.yadony.api.payments.currency.ActiveCurrencyResolver resolverReturning(String code) {
        var resolver = org.mockito.Mockito.mock(
                com.yadony.api.payments.currency.ActiveCurrencyResolver.class);
        org.mockito.Mockito.lenient()
                .when(resolver.resolve(org.mockito.ArgumentMatchers.any()))
                .thenReturn(code);
        return resolver;
    }


    @Test
    @DisplayName("la récompense est versée dans la devise active du parrain")
    void creditsTheRewardInTheReferrerActiveCurrency() {
        var listenerInUsd = new ReferralRewardWalletListener(walletService, resolverReturning("USD"));

        listenerInUsd.onReferralRewardGranted(
                new com.yadony.api.referral.events.ReferralRewardGrantedEvent(
                        REFERRER_ID, 500, INVITATION_ID));

        // Le barème de 5 € est converti pour garder sa valeur : 5,40 USD. Le
        // portefeuille crédité est bien celui de la devise du parrain.
        org.mockito.Mockito.verify(walletService).credit(
                org.mockito.ArgumentMatchers.eq(REFERRER_ID),
                org.mockito.ArgumentMatchers.eq("USD"),
                org.mockito.ArgumentMatchers.argThat(
                        a -> a.compareTo(new java.math.BigDecimal("5.40")) == 0),
                org.mockito.ArgumentMatchers.eq(WalletTransactionType.REFERRAL_REWARD),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("le barème est converti et arrondi à la précision de la devise")
    void roundsTheRewardToTheCurrencyPrecision() {
        var listenerInXof = new ReferralRewardWalletListener(walletService, resolverReturning("XOF"));

        listenerInXof.onReferralRewardGranted(
                new com.yadony.api.referral.events.ReferralRewardGrantedEvent(
                        REFERRER_ID, 500, INVITATION_ID));

        // Le barème de 5 € garde sa valeur : environ 3 280 F CFA. Verser 5 XOF,
        // soit moins d'un centime d'euro, aurait vidé la récompense de tout
        // contenu. Le franc CFA n'ayant pas de sous-unité, le montant est entier.
        org.mockito.Mockito.verify(walletService).credit(
                org.mockito.ArgumentMatchers.eq(REFERRER_ID),
                org.mockito.ArgumentMatchers.eq("XOF"),
                org.mockito.ArgumentMatchers.argThat(
                        a -> a.scale() == 0
                                && a.compareTo(new java.math.BigDecimal("3280")) == 0),
                org.mockito.ArgumentMatchers.eq(WalletTransactionType.REFERRAL_REWARD),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

}
