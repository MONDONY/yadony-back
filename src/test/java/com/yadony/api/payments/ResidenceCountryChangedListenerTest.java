package com.yadony.api.payments;

import com.stripe.exception.ApiConnectionException;
import com.yadony.api.settings.events.ResidenceCountryChangedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResidenceCountryChangedListenerTest {

    @Mock PaymentService paymentService;
    @Mock StripeGateway stripeGateway;
    @InjectMocks ResidenceCountryChangedListener listener;

    private final UUID userId = UUID.randomUUID();

    @Test
    @DisplayName("Compte en attente joint : detachement delegue dans la transaction")
    void detach_withPendingAccount_delegates() {
        listener.detachPendingAccount(
                new ResidenceCountryChangedEvent(userId, "FR", "SN", "acct_pending"));

        verify(paymentService).detachPendingConnectAccountForCountryChange(
                userId, "acct_pending", "FR", "SN");
    }

    @Test
    @DisplayName("Sans compte en attente : ni detachement ni fermeture")
    void withoutPendingAccount_doesNothing() {
        ResidenceCountryChangedEvent event = new ResidenceCountryChangedEvent(userId, "FR", "SN", null);

        listener.detachPendingAccount(event);
        listener.closeDetachedAccount(event);

        verifyNoInteractions(paymentService, stripeGateway);
    }

    @Test
    @DisplayName("Apres commit : le compte detache est ferme chez Stripe")
    void close_afterCommit_closesAccount() throws Exception {
        listener.closeDetachedAccount(
                new ResidenceCountryChangedEvent(userId, "FR", "SN", "acct_pending"));

        verify(stripeGateway).closeAccountV2("acct_pending");
    }

    @Test
    @DisplayName("Fermeture Stripe en echec : jamais bloquant")
    void close_stripeFailure_isSwallowed() throws Exception {
        when(stripeGateway.closeAccountV2(any()))
                .thenThrow(new ApiConnectionException("network down"));

        assertThatCode(() -> listener.closeDetachedAccount(
                new ResidenceCountryChangedEvent(userId, "FR", "SN", "acct_pending")))
                .doesNotThrowAnyException();
    }
}
