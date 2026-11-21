package com.yadony.api.payments.fee;

import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.ApiException;
import com.stripe.model.Transfer;
import com.stripe.model.TransferCollection;
import com.stripe.net.RequestOptions;
import com.stripe.param.TransferCreateParams;
import com.stripe.param.TransferListParams;
import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.disputes.DisputeTypes;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRail;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.hold.PayoutHoldPolicy;
import com.yadony.api.payments.hold.PayoutHoldReason;
import com.yadony.api.payments.hold.PayoutHoldStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Versement du dédommagement d'annulation au voyageur (rail carte) : Transfer Stripe Connect du net retenu,
 * mêmes gardes que le versement d'un colis livré, ligne toujours {@code PAID_OUT} ou {@code PAYOUT_PENDING}
 * après un essai, jamais un second Transfer.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CancellationFeePayoutServiceTest {

    private static final UUID FEE_ID = UUID.randomUUID();
    private static final UUID BID_ID = UUID.randomUUID();
    private static final UUID TRAVELER_ID = UUID.randomUUID();
    private static final UUID PAYMENT_ID = UUID.randomUUID();
    private static final String ALERT_TYPE = CancellationFeePayoutService.ALERT_PREFIX + FEE_ID;
    private static final String KEY = "cancel-fee-transfer-" + FEE_ID;

    @Mock CancellationFeePayoutPort port;
    @Mock PaymentRepository payments;
    @Mock UserRepository users;
    @Mock PayoutHoldPolicy holdPolicy;
    @Mock DisputeRepository disputes;
    @Mock AdminAlertEscalator alerts;

    private CancellationFeePayoutService service;
    private MockedStatic<Transfer> transfers;
    private PaymentEntity payment;
    private UserEntity traveler;

    @BeforeEach
    void setUp() {
        service = new CancellationFeePayoutService(port, payments, users, holdPolicy, disputes, alerts,
                TransactionOperations.withoutTransaction());
        transfers = mockStatic(Transfer.class);

        payment = new PaymentEntity();
        ReflectionTestUtils.setField(payment, "id", PAYMENT_ID);
        payment.setBidId(BID_ID);
        payment.setStatus(PaymentStatus.REFUNDED);
        payment.setCurrency("EUR");
        payment.setAmount(new BigDecimal("100.00"));
        payment.setRefundedAmount(new BigDecimal("70.00"));
        payment.setStripeChargeId("ch_1");

        traveler = new UserEntity();
        ReflectionTestUtils.setField(traveler, "id", TRAVELER_ID);
        traveler.setStripeAccountId("acct_1");
        traveler.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);

        when(port.findPayable(FEE_ID)).thenReturn(Optional.of(fee("26.84", "EUR", PayableCancellationFee.Status.RETAINED)));
        when(payments.findForBid(BID_ID)).thenReturn(Optional.of(payment));
        when(users.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
        when(holdPolicy.statusOf(TRAVELER_ID)).thenReturn(PayoutHoldStatus.NONE);
        when(port.markPaidOut(eq(FEE_ID), any())).thenReturn(true);
        when(port.markPayoutPending(eq(FEE_ID), anyString())).thenReturn(true);
        stubNoExistingTransfer();
    }

    @AfterEach
    void tearDown() {
        transfers.close();
    }

    @Test
    void payOut_success_marksPaidOut() {
        ArgumentCaptor<TransferCreateParams> params = ArgumentCaptor.forClass(TransferCreateParams.class);
        ArgumentCaptor<RequestOptions> options = ArgumentCaptor.forClass(RequestOptions.class);
        transfers.when(() -> Transfer.create(params.capture(), options.capture())).thenReturn(transfer("tr_1"));

        assertThat(service.payOut(FEE_ID)).isTrue();

        TransferCreateParams sent = params.getValue();
        assertThat(sent.getAmount()).isEqualTo(2684L);
        assertThat(sent.getCurrency()).isEqualTo("eur");
        assertThat(sent.getDestination()).isEqualTo("acct_1");
        assertThat(sent.getSourceTransaction()).isEqualTo("ch_1");
        assertThat(sent.getTransferGroup()).isEqualTo("cancel_fee_" + FEE_ID);
        assertThat(sent.getMetadata())
                .containsEntry("bid_id", BID_ID.toString())
                .containsEntry("fee_id", FEE_ID.toString())
                .containsEntry("payment_id", PAYMENT_ID.toString());
        assertThat(options.getValue().getIdempotencyKey()).isEqualTo(KEY);
        verify(port).markPaidOut(FEE_ID, "tr_1");
        verify(port, never()).markPayoutPending(any(), any());
        verify(alerts, never()).raiseOnce(anyString(), anyString(), anyMap());
        // Un essai précédent en échec a pu lever l'alerte : elle n'a plus d'objet.
        verify(alerts).resolveOpen(ALERT_TYPE);
    }

    @Test
    void noConnectAccount_staysPending_andRaisesAdminAlert() {
        traveler.setStripeAccountId(null);
        traveler.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);

        assertThat(service.payOut(FEE_ID)).isFalse();

        verify(port).markPayoutPending(FEE_ID, "NO_CONNECT_ACCOUNT");
        verify(port, never()).markPaidOut(any(), any());
        transfers.verifyNoInteractions();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> context = ArgumentCaptor.forClass(Map.class);
        verify(alerts).raiseOnce(eq(ALERT_TYPE), anyString(), context.capture());
        assertThat(context.getValue())
                .containsEntry("feeId", FEE_ID.toString())
                .containsEntry("bidId", BID_ID.toString())
                .containsEntry("travelerId", TRAVELER_ID.toString())
                .containsEntry("reason", "NO_CONNECT_ACCOUNT");
    }

    @Test
    void blankConnectAccount_staysPending() {
        traveler.setStripeAccountId("  ");

        assertThat(service.payOut(FEE_ID)).isFalse();

        verify(port).markPayoutPending(FEE_ID, "NO_CONNECT_ACCOUNT");
        transfers.verifyNoInteractions();
    }

    @Test
    void frozenTraveler_staysPending() {
        when(holdPolicy.statusOf(TRAVELER_ID)).thenReturn(
                new PayoutHoldStatus(LocalDateTime.now(), List.of(PayoutHoldReason.BANNED)));

        assertThat(service.payOut(FEE_ID)).isFalse();

        verify(port).markPayoutPending(FEE_ID, "PAYOUT_HELD_BANNED");
        verify(port, never()).markPaidOut(any(), any());
        verify(alerts).raiseOnce(eq(ALERT_TYPE), anyString(), anyMap());
        transfers.verifyNoInteractions();
    }

    @ParameterizedTest
    @EnumSource(value = StripeAccountStatus.class, names = {"DISABLED", "REJECTED"})
    void unusableConnectAccount_staysPending(StripeAccountStatus status) {
        traveler.setStripeAccountStatus(status);

        assertThat(service.payOut(FEE_ID)).isFalse();

        verify(port).markPayoutPending(FEE_ID, "STRIPE_ACCOUNT_" + status.name());
        verify(alerts).raiseOnce(eq(ALERT_TYPE), anyString(), anyMap());
        transfers.verifyNoInteractions();
    }

    @Test
    void onboardingAccount_isLeftToStripe() {
        // Même règle que la livraison : seuls DISABLED et REJECTED sont refusés d'avance.
        traveler.setStripeAccountStatus(StripeAccountStatus.PENDING_ONBOARDING);
        transfers.when(() -> Transfer.create(any(TransferCreateParams.class), any(RequestOptions.class)))
                .thenReturn(transfer("tr_1"));

        assertThat(service.payOut(FEE_ID)).isTrue();

        verify(port).markPaidOut(FEE_ID, "tr_1");
    }

    @Test
    void chargeback_staysPending() {
        payment.setDisputed(true);

        assertThat(service.payOut(FEE_ID)).isFalse();

        verify(port).markPayoutPending(FEE_ID, "CHARGEBACK");
        transfers.verifyNoInteractions();
    }

    @Test
    void adminDisputeOpen_staysPending() {
        when(disputes.existsByBidIdAndStatusAndTypeStartingWith(BID_ID, DisputeTypes.STATUS_OPEN,
                DisputeTypes.ADMIN_PREFIX)).thenReturn(true);

        assertThat(service.payOut(FEE_ID)).isFalse();

        verify(port).markPayoutPending(FEE_ID, "ADMIN_DISPUTE_OPEN");
        transfers.verifyNoInteractions();
    }

    @Test
    void noPayment_staysPending() {
        when(payments.findForBid(BID_ID)).thenReturn(Optional.empty());

        assertThat(service.payOut(FEE_ID)).isFalse();

        verify(port).markPayoutPending(FEE_ID, "NO_PAYMENT");
        verify(alerts).raiseOnce(eq(ALERT_TYPE), anyString(), anyMap());
        transfers.verifyNoInteractions();
    }

    @Test
    void paymentNotRefunded_staysPending() {
        payment.setStatus(PaymentStatus.ESCROW);

        assertThat(service.payOut(FEE_ID)).isFalse();

        verify(port).markPayoutPending(FEE_ID, "PAYMENT_ESCROW");
        transfers.verifyNoInteractions();
    }

    @Test
    void travelerNotFound_staysPending() {
        when(users.findById(TRAVELER_ID)).thenReturn(Optional.empty());

        assertThat(service.payOut(FEE_ID)).isFalse();

        verify(port).markPayoutPending(FEE_ID, "TRAVELER_NOT_FOUND");
        transfers.verifyNoInteractions();
    }

    @Test
    void travelerNetZero_marksPaidOutWithoutTransfer() {
        when(port.findPayable(FEE_ID)).thenReturn(Optional.of(fee("0.00", "EUR", PayableCancellationFee.Status.RETAINED)));

        assertThat(service.payOut(FEE_ID)).isTrue();

        verify(port).markPaidOut(eq(FEE_ID), isNull());
        verify(port, never()).markPayoutPending(any(), any());
        transfers.verifyNoInteractions();
        verifyNoInteractions(payments, users, holdPolicy, disputes);
    }

    @Test
    void negativeNet_staysPendingWithoutTransfer() {
        when(port.findPayable(FEE_ID)).thenReturn(Optional.of(fee("-1.00", "EUR", PayableCancellationFee.Status.RETAINED)));

        assertThat(service.payOut(FEE_ID)).isFalse();

        verify(port).markPayoutPending(FEE_ID, "NEGATIVE_NET");
        transfers.verifyNoInteractions();
    }

    @Test
    void replay_sameIdempotencyKey() {
        ArgumentCaptor<RequestOptions> options = ArgumentCaptor.forClass(RequestOptions.class);
        transfers.when(() -> Transfer.create(any(TransferCreateParams.class), options.capture()))
                .thenThrow(new ApiConnectionException("timeout"))
                .thenReturn(transfer("tr_1"));

        assertThat(service.payOut(FEE_ID)).isFalse();
        verify(port).markPayoutPending(eq(FEE_ID), startsWith("STRIPE_ERROR"));

        when(port.findPayable(FEE_ID)).thenReturn(
                Optional.of(fee("26.84", "EUR", PayableCancellationFee.Status.PAYOUT_PENDING)));
        assertThat(service.payOut(FEE_ID)).isTrue();

        assertThat(options.getAllValues()).extracting(RequestOptions::getIdempotencyKey).containsExactly(KEY, KEY);
        verify(port).markPaidOut(FEE_ID, "tr_1");
    }

    @Test
    void replay_alreadyPaidOut_noSecondTransfer() {
        when(port.findPayable(FEE_ID)).thenReturn(Optional.empty());

        assertThat(service.payOut(FEE_ID)).isFalse();

        transfers.verifyNoInteractions();
        verify(port, never()).markPaidOut(any(), any());
        verify(port, never()).markPayoutPending(any(), any());
        verifyNoInteractions(alerts);
    }

    @Test
    void existingTransferOfTheFee_isRecordedWithoutASecondTransfer() {
        // Clé d'idempotence Stripe expirée (24 h) après un Transfer réussi dont l'écriture locale a échoué :
        // le Transfer du groupe de la retenue est repris, aucun second envoi.
        Transfer reversed = transfer("tr_reversed");
        reversed.setReversed(true);
        Transfer live = transfer("tr_old");
        live.setReversed(false);
        ArgumentCaptor<TransferListParams> listed = ArgumentCaptor.forClass(TransferListParams.class);
        transfers.when(() -> Transfer.list(listed.capture())).thenReturn(collection(reversed, live));

        assertThat(service.payOut(FEE_ID)).isTrue();

        assertThat(listed.getValue().getTransferGroup()).isEqualTo("cancel_fee_" + FEE_ID);
        verify(port).markPaidOut(FEE_ID, "tr_old");
        transfers.verify(() -> Transfer.create(any(TransferCreateParams.class), any(RequestOptions.class)), never());
    }

    @Test
    void transferLookupFailure_staysPendingWithoutSending() {
        transfers.when(() -> Transfer.list(any(TransferListParams.class)))
                .thenThrow(new ApiException("down", null, null, 500, null));

        assertThat(service.payOut(FEE_ID)).isFalse();

        verify(port).markPayoutPending(eq(FEE_ID), startsWith("STRIPE_LOOKUP_FAILED"));
        verify(alerts).raiseOnce(eq(ALERT_TYPE), anyString(), anyMap());
        transfers.verify(() -> Transfer.create(any(TransferCreateParams.class), any(RequestOptions.class)), never());
    }

    @Test
    void stripeFailure_staysPendingAndRaisesAdminAlert() {
        transfers.when(() -> Transfer.create(any(TransferCreateParams.class), any(RequestOptions.class)))
                .thenThrow(new ApiException("insufficient funds", null, "balance_insufficient", 400, null));

        assertThat(service.payOut(FEE_ID)).isFalse();

        verify(port).markPayoutPending(eq(FEE_ID), startsWith("STRIPE_ERROR"));
        verify(port, never()).markPaidOut(any(), any());
        verify(alerts).raiseOnce(eq(ALERT_TYPE), anyString(), anyMap());
        verify(alerts, never()).resolveOpen(anyString());
    }

    @Test
    void stripeFailure_rowAlreadyPaidByAConcurrentAttempt_noAlert() {
        transfers.when(() -> Transfer.create(any(TransferCreateParams.class), any(RequestOptions.class)))
                .thenThrow(new ApiException("idempotency_key_in_use", null, null, 409, null));
        when(port.markPayoutPending(eq(FEE_ID), anyString())).thenReturn(false);

        assertThat(service.payOut(FEE_ID)).isFalse();

        verify(alerts, never()).raiseOnce(anyString(), anyString(), anyMap());
    }

    @Test
    void paidOutWriteFailsAfterTheTransfer_rowLeftPendingForTheRetry() {
        transfers.when(() -> Transfer.create(any(TransferCreateParams.class), any(RequestOptions.class)))
                .thenReturn(transfer("tr_1"));
        when(port.markPaidOut(FEE_ID, "tr_1")).thenThrow(new IllegalStateException("db down"));

        assertThat(service.payOut(FEE_ID)).isFalse();

        // Jamais RETAINED en silence : le prochain passage reprend la ligne, même clé, Transfer retrouvé.
        verify(port).markPayoutPending(eq(FEE_ID), startsWith("UNEXPECTED"));
        verify(alerts).raiseOnce(eq(ALERT_TYPE), anyString(), anyMap());
    }

    @Test
    void unexpectedError_marksPendingAndNeverThrows() {
        when(users.findById(TRAVELER_ID)).thenThrow(new IllegalStateException("boom"));

        assertThat(service.payOut(FEE_ID)).isFalse();

        verify(port).markPayoutPending(eq(FEE_ID), startsWith("UNEXPECTED"));
        transfers.verifyNoInteractions();
    }

    @Test
    void pendingWriteAndAlertFail_neverThrows() {
        traveler.setStripeAccountId(null);
        when(port.markPayoutPending(eq(FEE_ID), anyString())).thenThrow(new IllegalStateException("db down"));
        when(alerts.raiseOnce(anyString(), anyString(), anyMap())).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> assertThat(service.payOut(FEE_ID)).isFalse()).doesNotThrowAnyException();
    }

    @Test
    void mobileMoneyRail_leftUntouched() {
        // Versement pawaPay : tâche 7. Ni Stripe, ni transition, ni alerte.
        payment.setRail(PaymentRail.PAWAPAY);

        assertThat(service.payOut(FEE_ID)).isFalse();

        transfers.verifyNoInteractions();
        verify(port, never()).markPaidOut(any(), any());
        verify(port, never()).markPayoutPending(any(), any());
        verifyNoInteractions(alerts);
    }

    @Test
    void noChargeId_transfersFromTheBalance() {
        payment.setStripeChargeId(null);
        ArgumentCaptor<TransferCreateParams> params = ArgumentCaptor.forClass(TransferCreateParams.class);
        transfers.when(() -> Transfer.create(params.capture(), any(RequestOptions.class))).thenReturn(transfer("tr_1"));

        assertThat(service.payOut(FEE_ID)).isTrue();

        assertThat(params.getValue().getSourceTransaction()).isNull();
    }

    @Test
    void xofFee_transferredInWholeFrancs() {
        payment.setCurrency("XOF");
        when(port.findPayable(FEE_ID)).thenReturn(Optional.of(fee("17540", "XOF", PayableCancellationFee.Status.RETAINED)));
        ArgumentCaptor<TransferCreateParams> params = ArgumentCaptor.forClass(TransferCreateParams.class);
        transfers.when(() -> Transfer.create(params.capture(), any(RequestOptions.class))).thenReturn(transfer("tr_1"));

        assertThat(service.payOut(FEE_ID)).isTrue();

        assertThat(params.getValue().getAmount()).isEqualTo(17540L);
        assertThat(params.getValue().getCurrency()).isEqualTo("xof");
    }

    @Test
    void unsupportedCurrency_staysPending() {
        when(port.findPayable(FEE_ID)).thenReturn(Optional.of(fee("10.00", "JPY", PayableCancellationFee.Status.RETAINED)));

        assertThat(service.payOut(FEE_ID)).isFalse();

        verify(port).markPayoutPending(FEE_ID, "UNSUPPORTED_CURRENCY_JPY");
        transfers.verifyNoInteractions();
    }

    @Test
    void transferWithoutId_staysPending() {
        transfers.when(() -> Transfer.create(any(TransferCreateParams.class), any(RequestOptions.class)))
                .thenReturn(new Transfer());

        assertThat(service.payOut(FEE_ID)).isFalse();

        verify(port).markPayoutPending(FEE_ID, "STRIPE_NO_TRANSFER_ID");
        verify(port, never()).markPaidOut(any(), any());
    }

    @Test
    void emptyLookupPage_isNoExistingTransfer() {
        transfers.when(() -> Transfer.list(any(TransferListParams.class))).thenReturn(new TransferCollection());
        transfers.when(() -> Transfer.create(any(TransferCreateParams.class), any(RequestOptions.class)))
                .thenReturn(transfer("tr_1"));

        assertThat(service.payOut(FEE_ID)).isTrue();

        verify(port).markPaidOut(FEE_ID, "tr_1");
    }

    @Test
    void rowNoLongerPayableWhenRecording_falseWithoutAlert() {
        transfers.when(() -> Transfer.create(any(TransferCreateParams.class), any(RequestOptions.class)))
                .thenReturn(transfer("tr_1"));
        when(port.markPaidOut(FEE_ID, "tr_1")).thenReturn(false);

        assertThat(service.payOut(FEE_ID)).isFalse();

        verify(port, never()).markPayoutPending(any(), any());
        verifyNoInteractions(alerts);
    }

    @Test
    void unreadableRow_pendingAttemptAndAlertWithTheFeeIdOnly() {
        when(port.findPayable(FEE_ID)).thenThrow(new IllegalStateException("db down"));

        assertThat(service.payOut(FEE_ID)).isFalse();

        verify(port).markPayoutPending(eq(FEE_ID), startsWith("UNEXPECTED"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> context = ArgumentCaptor.forClass(Map.class);
        verify(alerts).raiseOnce(eq(ALERT_TYPE), anyString(), context.capture());
        assertThat(context.getValue()).containsEntry("feeId", FEE_ID.toString()).doesNotContainKey("bidId");
        transfers.verifyNoInteractions();
    }

    @Test
    void alertClosureFailure_doesNotUndoThePayout() {
        transfers.when(() -> Transfer.create(any(TransferCreateParams.class), any(RequestOptions.class)))
                .thenReturn(transfer("tr_1"));
        when(alerts.resolveOpen(ALERT_TYPE)).thenThrow(new IllegalStateException("db down"));

        assertThat(service.payOut(FEE_ID)).isTrue();

        verify(port, never()).markPayoutPending(any(), any());
    }

    @Test
    void alertType_fitsTheAdminAlertsColumn() {
        assertThat((CancellationFeePayoutService.ALERT_PREFIX + UUID.randomUUID()).length())
                .isLessThanOrEqualTo(AdminAlertEscalator.TYPE_MAX_LENGTH);
    }

    private void stubNoExistingTransfer() {
        transfers.when(() -> Transfer.list(any(TransferListParams.class))).thenReturn(collection());
    }

    private static PayableCancellationFee fee(String net, String currency, PayableCancellationFee.Status status) {
        return new PayableCancellationFee(FEE_ID, BID_ID, TRAVELER_ID, new BigDecimal(net), currency, status);
    }

    /** Objets Stripe réels : un mock créé pendant un stub statique laisserait ce stub inachevé. */
    private static Transfer transfer(String id) {
        Transfer t = new Transfer();
        t.setId(id);
        return t;
    }

    private static TransferCollection collection(Transfer... items) {
        TransferCollection page = new TransferCollection();
        page.setData(List.of(items));
        return page;
    }
}
