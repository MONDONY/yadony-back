package com.yadony.api.payments;

import com.stripe.StripeClient;
import com.stripe.model.Account;
import com.stripe.model.Balance;
import com.stripe.model.Payout;
import com.stripe.model.StripeCollection;
import com.stripe.net.RequestOptions;
import com.stripe.param.AccountRejectParams;
import com.stripe.param.PayoutListParams;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Answers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Fermeture d'un compte Connect : chaque appel vise bien le compte connecté. */
class StripeGatewayImplConnectClosureTest {

    private final StripeClient stripeClient = mock(StripeClient.class, Answers.RETURNS_DEEP_STUBS);
    private final StripeGatewayImpl gateway = new StripeGatewayImpl(stripeClient);

    @Test
    void retrieveConnectedBalance_surLeCompteConnecte() throws Exception {
        Balance balance = new Balance();
        when(stripeClient.v1().balance().retrieve(any(RequestOptions.class))).thenReturn(balance);

        assertThat(gateway.retrieveConnectedBalance("acct_1")).isSameAs(balance);

        ArgumentCaptor<RequestOptions> options = ArgumentCaptor.forClass(RequestOptions.class);
        verify(stripeClient.v1().balance()).retrieve(options.capture());
        assertThat(options.getValue().getStripeAccount()).isEqualTo("acct_1");
    }

    @Test
    void listConnectedPayouts_filtreParStatut() throws Exception {
        StripeCollection<Payout> payouts = new StripeCollection<>();
        when(stripeClient.v1().payouts().list(any(PayoutListParams.class), any(RequestOptions.class)))
                .thenReturn(payouts);

        assertThat(gateway.listConnectedPayouts("acct_1", "in_transit")).isSameAs(payouts);

        ArgumentCaptor<PayoutListParams> params = ArgumentCaptor.forClass(PayoutListParams.class);
        ArgumentCaptor<RequestOptions> options = ArgumentCaptor.forClass(RequestOptions.class);
        verify(stripeClient.v1().payouts()).list(params.capture(), options.capture());
        assertThat(params.getValue().getStatus()).isEqualTo("in_transit");
        assertThat(options.getValue().getStripeAccount()).isEqualTo("acct_1");
    }

    @Test
    void deleteAccount_etRejectAccount() throws Exception {
        Account deleted = new Account();
        Account rejected = new Account();
        when(stripeClient.v1().accounts().delete("acct_1")).thenReturn(deleted);
        when(stripeClient.v1().accounts().reject(eq("acct_1"), any(AccountRejectParams.class))).thenReturn(rejected);

        assertThat(gateway.deleteAccount("acct_1")).isSameAs(deleted);
        assertThat(gateway.rejectAccount("acct_1", "other")).isSameAs(rejected);

        ArgumentCaptor<AccountRejectParams> params = ArgumentCaptor.forClass(AccountRejectParams.class);
        verify(stripeClient.v1().accounts()).reject(eq("acct_1"), params.capture());
        assertThat(params.getValue().getReason()).isEqualTo("other");
    }
}
