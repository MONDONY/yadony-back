package com.yadony.api.payments.supplement;

import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.model.RefundCollection;
import com.stripe.model.Transfer;
import com.stripe.model.TransferCollection;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.RefundListParams;
import com.stripe.param.TransferCreateParams;
import com.stripe.param.TransferListParams;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** Passerelle Stripe du supplément : paramètres et clés d'idempotence transmis au SDK. */
class StripeSupplementGatewayImplTest {

    private final StripeSupplementGatewayImpl gateway = new StripeSupplementGatewayImpl();

    private static PaymentIntent pi(String status) {
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getId()).thenReturn("pi_1");
        when(pi.getStatus()).thenReturn(status);
        when(pi.getAmount()).thenReturn(2110L);
        when(pi.getCurrency()).thenReturn("eur");
        when(pi.getClientSecret()).thenReturn("pi_1_secret");
        when(pi.getLatestCharge()).thenReturn("ch_1");
        when(pi.getPaymentMethodTypes()).thenReturn(List.of("card", "paypal"));
        return pi;
    }

    @Test
    void creationLectureEtAnnulation() throws Exception {
        PaymentIntent created = pi("requires_payment_method");
        PaymentIntent canceled = pi("canceled");
        ArgumentCaptor<RequestOptions> opts = ArgumentCaptor.forClass(RequestOptions.class);
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder().setAmount(2110L).setCurrency("eur").build();
        when(created.cancel(any(PaymentIntentCancelParams.class))).thenReturn(canceled);
        try (MockedStatic<PaymentIntent> intents = mockStatic(PaymentIntent.class)) {
            intents.when(() -> PaymentIntent.create(org.mockito.ArgumentMatchers.eq(params), opts.capture()))
                    .thenReturn(created);
            intents.when(() -> PaymentIntent.retrieve("pi_1")).thenReturn(created);

            StripeSupplementGateway.PaymentIntentView view = gateway.createPaymentIntent(params, "supplement-pi-x-v1");
            assertThat(opts.getValue().getIdempotencyKey()).isEqualTo("supplement-pi-x-v1");
            assertThat(view.clientSecret()).isEqualTo("pi_1_secret");
            assertThat(view.paymentMethodTypes()).containsExactly("card", "paypal");
            assertThat(view.payable()).isTrue();
            assertThat(gateway.retrievePaymentIntent("pi_1").latestCharge()).isEqualTo("ch_1");
            StripeSupplementGateway.PaymentIntentView after = gateway.cancelPaymentIntent("pi_1");
            assertThat(after.canceled()).isTrue();
            assertThat(after.succeeded()).isFalse();
            assertThat(after.payable()).isFalse();
        }
    }

    @Test
    void transfer_groupeSourceEtCle_etRechercheParGroupe() throws Exception {
        Transfer reversed = mock(Transfer.class);
        when(reversed.getReversed()).thenReturn(true);
        Transfer live = mock(Transfer.class);
        when(live.getReversed()).thenReturn(false);
        when(live.getId()).thenReturn("tr_live");
        TransferCollection page = mock(TransferCollection.class);
        when(page.getData()).thenReturn(List.of(reversed, live));
        try (MockedStatic<Transfer> transfers = mockStatic(Transfer.class)) {
            Transfer created = mock(Transfer.class);
            when(created.getId()).thenReturn("tr_1");
            ArgumentCaptor<TransferCreateParams> params = ArgumentCaptor.forClass(TransferCreateParams.class);
            ArgumentCaptor<RequestOptions> opts = ArgumentCaptor.forClass(RequestOptions.class);
            transfers.when(() -> Transfer.create(params.capture(), opts.capture())).thenReturn(created);
            transfers.when(() -> Transfer.list(any(TransferListParams.class))).thenReturn(page);

            assertThat(gateway.createTransfer(2000L, "eur", "acct_t", "ch_1", "supplement_s1",
                    Map.of("bid_supplement_id", "s1"), "supplement-transfer-s1")).isEqualTo("tr_1");
            assertThat(params.getValue().getSourceTransaction()).isEqualTo("ch_1");
            assertThat(params.getValue().getTransferGroup()).isEqualTo("supplement_s1");
            assertThat(params.getValue().getDestination()).isEqualTo("acct_t");
            assertThat(opts.getValue().getIdempotencyKey()).isEqualTo("supplement-transfer-s1");

            gateway.createTransfer(1L, "eur", "acct_t", " ", "supplement_s1", Map.of(), "k");
            assertThat(params.getValue().getSourceTransaction()).isNull();
            assertThat(gateway.findTransferByGroup("supplement_s1")).contains("tr_live");
        }
    }

    @Test
    void refund_integralAvecCle_etRechercheParMetadonnee() throws Exception {
        Refund other = mock(Refund.class);
        when(other.getMetadata()).thenReturn(Map.of("bid_supplement_id", "autre"));
        Refund failed = mock(Refund.class);
        when(failed.getMetadata()).thenReturn(Map.of("bid_supplement_id", "s1"));
        when(failed.getStatus()).thenReturn("failed");
        Refund ok = mock(Refund.class);
        when(ok.getMetadata()).thenReturn(Map.of("bid_supplement_id", "s1"));
        when(ok.getStatus()).thenReturn("succeeded");
        when(ok.getId()).thenReturn("re_ok");
        Refund noMeta = mock(Refund.class);
        RefundCollection page = mock(RefundCollection.class);
        when(page.getData()).thenReturn(List.of(noMeta, other, failed, ok));
        try (MockedStatic<Refund> refunds = mockStatic(Refund.class)) {
            Refund created = mock(Refund.class);
            when(created.getId()).thenReturn("re_1");
            ArgumentCaptor<RefundCreateParams> params = ArgumentCaptor.forClass(RefundCreateParams.class);
            ArgumentCaptor<RequestOptions> opts = ArgumentCaptor.forClass(RequestOptions.class);
            refunds.when(() -> Refund.create(params.capture(), opts.capture())).thenReturn(created);
            refunds.when(() -> Refund.list(any(RefundListParams.class))).thenReturn(page);

            assertThat(gateway.createRefund("pi_1", Map.of("bid_supplement_id", "s1"), "supplement-refund-s1"))
                    .isEqualTo("re_1");
            assertThat(params.getValue().getPaymentIntent()).isEqualTo("pi_1");
            assertThat(params.getValue().getAmount()).as("remboursement intégral").isNull();
            assertThat(opts.getValue().getIdempotencyKey()).isEqualTo("supplement-refund-s1");
            assertThat(gateway.findRefund("pi_1", "s1")).contains("re_ok");
            assertThat(gateway.findRefund("pi_1", "absent")).isEmpty();
        }
    }

    @Test
    void dernierRefundNonEchoue_etEtatDeLaCharge() throws Exception {
        Refund failed = mock(Refund.class);
        when(failed.getStatus()).thenReturn("failed");
        Refund ok = mock(Refund.class);
        when(ok.getStatus()).thenReturn("succeeded");
        when(ok.getId()).thenReturn("re_dashboard");
        RefundCollection page = mock(RefundCollection.class);
        when(page.getData()).thenReturn(List.of(failed, ok));
        com.stripe.model.Charge disputed = mock(com.stripe.model.Charge.class);
        when(disputed.getDisputed()).thenReturn(true);
        when(disputed.getAmountRefunded()).thenReturn(300L);
        com.stripe.model.Charge clean = mock(com.stripe.model.Charge.class);
        try (MockedStatic<Refund> refunds = mockStatic(Refund.class);
             MockedStatic<com.stripe.model.Charge> charges = mockStatic(com.stripe.model.Charge.class)) {
            refunds.when(() -> Refund.list(any(RefundListParams.class))).thenReturn(page);
            charges.when(() -> com.stripe.model.Charge.retrieve("ch_1")).thenReturn(disputed);
            charges.when(() -> com.stripe.model.Charge.retrieve("ch_2")).thenReturn(clean);

            assertThat(gateway.latestRefund("pi_1")).contains("re_dashboard");
            assertThat(gateway.retrieveCharge("ch_1")).isEqualTo(new StripeSupplementGateway.ChargeView(true, 300L));
            assertThat(gateway.retrieveCharge("ch_2")).isEqualTo(new StripeSupplementGateway.ChargeView(false, 0L));
        }
    }
}
