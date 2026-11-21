package com.yadony.api.cancellation.fee;

import com.yadony.api.common.AuditService;
import com.yadony.api.payments.fee.PayableCancellationFee;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Port {@code payments} → {@code cancellation} : lecture et transitions du versement de la retenue. */
@ExtendWith(MockitoExtension.class)
class CancellationFeePayoutPortAdapterTest {

    private static final UUID FEE_ID = UUID.randomUUID();
    private static final UUID BID_ID = UUID.randomUUID();
    private static final UUID TRAVELER_ID = UUID.randomUUID();

    @Mock CancellationFeeRepository fees;
    @Mock AuditService auditService;

    private CancellationFeePayoutPortAdapter adapter() {
        return new CancellationFeePayoutPortAdapter(fees, auditService);
    }

    @Test
    void findPayable_retainedRow() {
        when(fees.findActiveById(FEE_ID)).thenReturn(Optional.of(fee(CancellationFeeStatus.RETAINED, "26.84")));

        assertThat(adapter().findPayable(FEE_ID)).contains(new PayableCancellationFee(FEE_ID, BID_ID, TRAVELER_ID,
                new BigDecimal("26.84"), "EUR", PayableCancellationFee.Status.RETAINED));
    }

    @Test
    void findPayable_pendingRow() {
        when(fees.findActiveById(FEE_ID)).thenReturn(Optional.of(fee(CancellationFeeStatus.PAYOUT_PENDING, "26.84")));

        assertThat(adapter().findPayable(FEE_ID)).hasValueSatisfying(
                f -> assertThat(f.status()).isEqualTo(PayableCancellationFee.Status.PAYOUT_PENDING));
    }

    @ParameterizedTest
    @EnumSource(value = CancellationFeeStatus.class, names = {"DUE", "PAID_OUT", "NOT_APPLICABLE"})
    void findPayable_notPayable_empty(CancellationFeeStatus status) {
        when(fees.findActiveById(FEE_ID)).thenReturn(Optional.of(fee(status, "26.84")));

        assertThat(adapter().findPayable(FEE_ID)).isEmpty();
    }

    @Test
    void findPayable_noRow_empty() {
        when(fees.findActiveById(FEE_ID)).thenReturn(Optional.empty());

        assertThat(adapter().findPayable(FEE_ID)).isEmpty();
    }

    @Test
    void findPayableByBid_retainedRow() {
        when(fees.findByBidId(BID_ID)).thenReturn(Optional.of(fee(CancellationFeeStatus.RETAINED, "26.84")));

        assertThat(adapter().findPayableByBid(BID_ID)).hasValueSatisfying(f -> assertThat(f.feeId()).isEqualTo(FEE_ID));
    }

    @Test
    void findPayableByBid_paidOut_empty() {
        when(fees.findByBidId(BID_ID)).thenReturn(Optional.of(fee(CancellationFeeStatus.PAID_OUT, "26.84")));

        assertThat(adapter().findPayableByBid(BID_ID)).isEmpty();
    }

    @Test
    void findPayoutRetries_pendingAndRetainedBeforeTheCutoff_inUtc() {
        UUID id = UUID.randomUUID();
        when(fees.findPayoutRetryIds(CancellationFeeStatus.PAYOUT_PENDING, CancellationFeeStatus.RETAINED,
                LocalDateTime.parse("2026-11-21T09:50:00"))).thenReturn(List.of(id));

        assertThat(adapter().findPayoutRetries(Instant.parse("2026-11-21T09:50:00Z"))).containsExactly(id);
    }

    @ParameterizedTest
    @EnumSource(value = CancellationFeeStatus.class, names = {"RETAINED", "PAYOUT_PENDING"})
    void markPaidOut_payableRow_paysAndAudits(CancellationFeeStatus status) {
        CancellationFeeEntity row = fee(status, "26.84");
        when(fees.findByIdForUpdate(FEE_ID)).thenReturn(Optional.of(row));

        assertThat(adapter().markPaidOut(FEE_ID, "tr_1")).isTrue();

        verify(fees).save(row);
        assertThat(row.getStatus()).isEqualTo(CancellationFeeStatus.PAID_OUT);
        assertThat(row.getPayoutRef()).isEqualTo("tr_1");
        Map<String, Object> payload = audited(CancellationFeePayoutPortAdapter.AUDIT_FEE_PAID_OUT);
        assertThat(payload)
                .containsEntry("cancellationFeeId", FEE_ID.toString())
                .containsEntry("payoutRef", "tr_1")
                .containsEntry("travelerId", TRAVELER_ID.toString())
                .containsEntry("travelerNet", "26.84")
                .containsEntry("currency", "EUR")
                .doesNotContainKey("note");
    }

    @Test
    void markPaidOut_zeroNet_withoutReference_auditsTheNote() {
        CancellationFeeEntity row = fee(CancellationFeeStatus.RETAINED, "0.00");
        when(fees.findByIdForUpdate(FEE_ID)).thenReturn(Optional.of(row));

        assertThat(adapter().markPaidOut(FEE_ID, null)).isTrue();

        assertThat(row.getStatus()).isEqualTo(CancellationFeeStatus.PAID_OUT);
        assertThat(row.getPayoutRef()).isNull();
        assertThat(audited(CancellationFeePayoutPortAdapter.AUDIT_FEE_PAID_OUT))
                .containsEntry("payoutRef", "")
                .containsEntry("note", "NOTHING_TO_TRANSFER");
    }

    @Test
    void markPaidOut_withoutReferenceWhileMoneyIsOwed_refused() {
        // Jamais « versé » sans Transfer quand il y a un net à verser.
        CancellationFeeEntity row = fee(CancellationFeeStatus.RETAINED, "26.84");
        when(fees.findByIdForUpdate(FEE_ID)).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> adapter().markPaidOut(FEE_ID, null)).isInstanceOf(IllegalArgumentException.class);

        verify(fees, never()).save(any());
        verifyNoInteractions(auditService);
        assertThat(row.getStatus()).isEqualTo(CancellationFeeStatus.RETAINED);
    }

    @Test
    void markPaidOut_alreadyPaidOut_trueWithoutSecondAudit() {
        CancellationFeeEntity row = fee(CancellationFeeStatus.PAID_OUT, "26.84");
        row.setPayoutRef("tr_first");
        when(fees.findByIdForUpdate(FEE_ID)).thenReturn(Optional.of(row));

        assertThat(adapter().markPaidOut(FEE_ID, "tr_other")).isTrue();

        verify(fees, never()).save(any());
        verifyNoInteractions(auditService);
        assertThat(row.getPayoutRef()).isEqualTo("tr_first");
    }

    @ParameterizedTest
    @EnumSource(value = CancellationFeeStatus.class, names = {"DUE", "NOT_APPLICABLE"})
    void markPaidOut_notPayable_noOp(CancellationFeeStatus status) {
        CancellationFeeEntity row = fee(status, "0.00");
        when(fees.findByIdForUpdate(FEE_ID)).thenReturn(Optional.of(row));

        assertThat(adapter().markPaidOut(FEE_ID, "tr_1")).isFalse();

        verify(fees, never()).save(any());
        verifyNoInteractions(auditService);
        assertThat(row.getStatus()).isEqualTo(status);
    }

    @Test
    void markPaidOut_noRow_false() {
        when(fees.findByIdForUpdate(FEE_ID)).thenReturn(Optional.empty());

        assertThat(adapter().markPaidOut(FEE_ID, "tr_1")).isFalse();

        verifyNoInteractions(auditService);
    }

    @Test
    void markPayoutPending_retainedRow_pendsAndAudits() {
        CancellationFeeEntity row = fee(CancellationFeeStatus.RETAINED, "26.84");
        when(fees.findByIdForUpdate(FEE_ID)).thenReturn(Optional.of(row));

        assertThat(adapter().markPayoutPending(FEE_ID, "NO_CONNECT_ACCOUNT")).isTrue();

        verify(fees).save(row);
        assertThat(row.getStatus()).isEqualTo(CancellationFeeStatus.PAYOUT_PENDING);
        assertThat(row.getPayoutPendingReason()).isEqualTo("NO_CONNECT_ACCOUNT");
        assertThat(audited(CancellationFeePayoutPortAdapter.AUDIT_FEE_PAYOUT_PENDING))
                .containsEntry("cancellationFeeId", FEE_ID.toString())
                .containsEntry("reason", "NO_CONNECT_ACCOUNT");
    }

    @Test
    void markPayoutPending_alreadyPendingForTheSameReason_trueWithoutSecondAudit() {
        CancellationFeeEntity row = fee(CancellationFeeStatus.PAYOUT_PENDING, "26.84");
        row.setPayoutPendingReason("STRIPE_ERROR");
        when(fees.findByIdForUpdate(FEE_ID)).thenReturn(Optional.of(row));

        assertThat(adapter().markPayoutPending(FEE_ID, "STRIPE_ERROR")).isTrue();

        verify(fees, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void markPayoutPending_alreadyPendingForAnotherReason_reasonRecordedAndAudited() {
        // Revue tâche 7 : l'arrêt des essais automatiques doit se voir, pas seulement le premier motif.
        CancellationFeeEntity row = fee(CancellationFeeStatus.PAYOUT_PENDING, "26.84");
        row.setPayoutPendingReason("PAWAPAY_PAYOUT_FAILED_RECIPIENT_NOT_FOUND");
        when(fees.findByIdForUpdate(FEE_ID)).thenReturn(Optional.of(row));

        assertThat(adapter().markPayoutPending(FEE_ID, "TOO_MANY_FAILED_PAYOUTS")).isTrue();

        verify(fees).save(row);
        assertThat(row.getStatus()).isEqualTo(CancellationFeeStatus.PAYOUT_PENDING);
        assertThat(row.getPayoutPendingReason()).isEqualTo("TOO_MANY_FAILED_PAYOUTS");
        assertThat(audited(CancellationFeePayoutPortAdapter.AUDIT_FEE_PAYOUT_PENDING))
                .containsEntry("reason", "TOO_MANY_FAILED_PAYOUTS")
                .containsEntry("previousReason", "PAWAPAY_PAYOUT_FAILED_RECIPIENT_NOT_FOUND");
    }

    @Test
    void markPayoutPending_longReason_boundedToTheColumn() {
        CancellationFeeEntity row = fee(CancellationFeeStatus.RETAINED, "26.84");
        when(fees.findByIdForUpdate(FEE_ID)).thenReturn(Optional.of(row));

        adapter().markPayoutPending(FEE_ID, "STRIPE_ERROR: " + "x".repeat(400));

        assertThat(row.getPayoutPendingReason()).hasSize(CancellationFeeEntity.PAYOUT_PENDING_REASON_MAX);
    }

    @Test
    void findPayable_rearmedRow_carriesTheRearmInstant() {
        CancellationFeeEntity row = fee(CancellationFeeStatus.PAYOUT_PENDING, "26.84");
        row.setPayoutRearmedAt(LocalDateTime.of(2026, 11, 21, 10, 0));
        when(fees.findActiveById(FEE_ID)).thenReturn(Optional.of(row));

        assertThat(adapter().findPayable(FEE_ID)).hasValueSatisfying(
                f -> assertThat(f.payoutRearmedAt()).isEqualTo(Instant.parse("2026-11-21T10:00:00Z")));
    }

    @ParameterizedTest
    @EnumSource(value = CancellationFeeStatus.class, names = {"PAID_OUT", "DUE", "NOT_APPLICABLE"})
    void markPayoutPending_neverTouchesAnUnpayableRow(CancellationFeeStatus status) {
        CancellationFeeEntity row = fee(status, "26.84");
        when(fees.findByIdForUpdate(FEE_ID)).thenReturn(Optional.of(row));

        assertThat(adapter().markPayoutPending(FEE_ID, "STRIPE_ERROR")).isFalse();

        verify(fees, never()).save(any());
        verifyNoInteractions(auditService);
        assertThat(row.getStatus()).isEqualTo(status);
    }

    @Test
    void markPayoutPending_noRow_false() {
        when(fees.findByIdForUpdate(FEE_ID)).thenReturn(Optional.empty());

        assertThat(adapter().markPayoutPending(FEE_ID, "STRIPE_ERROR")).isFalse();
    }

    private Map<String, Object> audited(String action) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("BID"), eq(BID_ID), eq(action), isNull(), payload.capture());
        return payload.getValue();
    }

    private static CancellationFeeEntity fee(CancellationFeeStatus status, String net) {
        CancellationFeeEntity f = new CancellationFeeEntity();
        ReflectionTestUtils.setField(f, "id", FEE_ID);
        f.setBidId(BID_ID);
        f.setSenderId(UUID.randomUUID());
        f.setTravelerId(TRAVELER_ID);
        f.setRate(new BigDecimal("0.3000"));
        f.setReason(CancellationFeeReason.SENDER_CANCEL);
        f.setStatus(status);
        f.setGrossFee(new BigDecimal("30.00"));
        f.setRefundFees(new BigDecimal("1.75"));
        f.setCommission(new BigDecimal("1.41"));
        f.setTravelerNet(new BigDecimal(net));
        f.setCurrency("EUR");
        return f;
    }
}
