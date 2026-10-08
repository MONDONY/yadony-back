package com.yadony.api.payments.split;

import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.model.RefundCollection;
import com.stripe.model.Transfer;
import com.stripe.model.TransferCollection;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCaptureParams;
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
import static org.mockito.Mockito.*;

/** Passerelle Stripe du partage : paramètres et clés d'idempotence transmis au SDK. */
class StripeSplitGatewayImplTest {

    private final StripeSplitGatewayImpl gateway = new StripeSplitGatewayImpl();

    private static PaymentIntent pi(String status) {
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getStatus()).thenReturn(status);
        when(pi.getAmount()).thenReturn(10500L);
        when(pi.getAmountReceived()).thenReturn(6500L);
        when(pi.getLatestCharge()).thenReturn("ch_1");
        return pi;
    }

    @Test
    void refund_partielAvecMetadonneesEtCle() throws Exception {
        try (MockedStatic<Refund> refund = mockStatic(Refund.class)) {
            Refund created = mock(Refund.class);
            when(created.getId()).thenReturn("re_1");
            ArgumentCaptor<RefundCreateParams> params = ArgumentCaptor.forClass(RefundCreateParams.class);
            ArgumentCaptor<RequestOptions> opts = ArgumentCaptor.forClass(RequestOptions.class);
            refund.when(() -> Refund.create(params.capture(), opts.capture())).thenReturn(created);

            assertThat(gateway.createRefund("pi_1", 4000L, Map.of("split_id", "s1"), "split-refund-s1")).isEqualTo("re_1");
            assertThat(params.getValue().getAmount()).isEqualTo(4000L);
            assertThat(params.getValue().getPaymentIntent()).isEqualTo("pi_1");
            assertThat(params.getValue().getMetadata().toString()).contains("split_id");
            assertThat(opts.getValue().getIdempotencyKey()).isEqualTo("split-refund-s1");
        }
    }

    @Test
    void findRefund_neRetientQueLeRefundDuPartageNonEchoue() throws Exception {
        Refund other = mock(Refund.class);
        when(other.getMetadata()).thenReturn(Map.of("split_id", "autre"));
        Refund failed = mock(Refund.class);
        when(failed.getMetadata()).thenReturn(Map.of("split_id", "s1"));
        when(failed.getStatus()).thenReturn("failed");
        Refund ok = mock(Refund.class);
        when(ok.getMetadata()).thenReturn(Map.of("split_id", "s1"));
        when(ok.getStatus()).thenReturn("succeeded");
        when(ok.getId()).thenReturn("re_ok");
        RefundCollection page = mock(RefundCollection.class);
        when(page.getData()).thenReturn(List.of(other, failed, ok));
        try (MockedStatic<Refund> refund = mockStatic(Refund.class)) {
            refund.when(() -> Refund.list(any(RefundListParams.class))).thenReturn(page);
            assertThat(gateway.findRefund("pi_1", "s1")).contains("re_ok");
            assertThat(gateway.findRefund("pi_1", "absent")).isEmpty();
        }
    }

    @Test
    void capturePartielle_etLecture() throws Exception {
        PaymentIntent authorized = pi("requires_capture");
        PaymentIntent captured = pi("succeeded");
        ArgumentCaptor<PaymentIntentCaptureParams> params = ArgumentCaptor.forClass(PaymentIntentCaptureParams.class);
        ArgumentCaptor<RequestOptions> opts = ArgumentCaptor.forClass(RequestOptions.class);
        when(authorized.capture(params.capture(), opts.capture())).thenReturn(captured);
        try (MockedStatic<PaymentIntent> intents = mockStatic(PaymentIntent.class)) {
            intents.when(() -> PaymentIntent.retrieve("pi_1")).thenReturn(authorized);

            assertThat(gateway.retrievePaymentIntent("pi_1").status()).isEqualTo("requires_capture");
            var state = gateway.capture("pi_1", 6500L, "split-capture-s1");

            assertThat(state.status()).isEqualTo("succeeded");
            assertThat(state.amountReceived()).isEqualTo(6500L);
            assertThat(state.latestCharge()).isEqualTo("ch_1");
            assertThat(params.getValue().getAmountToCapture()).isEqualTo(6500L);
            assertThat(opts.getValue().getIdempotencyKey()).isEqualTo("split-capture-s1");
        }
    }

    @Test
    void transfertPartiel_groupeSourceEtCle() throws Exception {
        try (MockedStatic<Transfer> transfers = mockStatic(Transfer.class)) {
            Transfer created = mock(Transfer.class);
            when(created.getId()).thenReturn("tr_1");
            ArgumentCaptor<TransferCreateParams> params = ArgumentCaptor.forClass(TransferCreateParams.class);
            ArgumentCaptor<RequestOptions> opts = ArgumentCaptor.forClass(RequestOptions.class);
            transfers.when(() -> Transfer.create(params.capture(), opts.capture())).thenReturn(created);

            assertThat(gateway.createTransfer(6000L, "eur", "acct_t", "ch_1", "split-s1",
                    Map.of("split_id", "s1"), "split-transfer-s1")).isEqualTo("tr_1");
            assertThat(params.getValue().getAmount()).isEqualTo(6000L);
            assertThat(params.getValue().getDestination()).isEqualTo("acct_t");
            assertThat(params.getValue().getSourceTransaction()).isEqualTo("ch_1");
            assertThat(params.getValue().getTransferGroup()).isEqualTo("split-s1");
            assertThat(opts.getValue().getIdempotencyKey()).isEqualTo("split-transfer-s1");

            gateway.createTransfer(1L, "eur", "acct_t", null, "split-s1", Map.of(), "k");
            assertThat(params.getValue().getSourceTransaction()).isNull();
        }
    }

    @Test
    void findTransfer_ignoreUnTransfertAnnule() throws Exception {
        Transfer reversed = mock(Transfer.class);
        when(reversed.getReversed()).thenReturn(true);
        Transfer live = mock(Transfer.class);
        when(live.getReversed()).thenReturn(false);
        when(live.getId()).thenReturn("tr_live");
        TransferCollection page = mock(TransferCollection.class);
        when(page.getData()).thenReturn(List.of(reversed, live));
        try (MockedStatic<Transfer> transfers = mockStatic(Transfer.class)) {
            transfers.when(() -> Transfer.list(any(TransferListParams.class))).thenReturn(page);
            assertThat(gateway.findTransfer("split-s1")).contains("tr_live");
        }
    }
}
