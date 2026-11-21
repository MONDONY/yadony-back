package com.yadony.api.cancellation.fee;

import com.yadony.api.common.AuditService;
import com.yadony.api.payments.fee.CancellationFeeBreakdown;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Port {@code payments} → {@code cancellation} : lecture du taux dû et règlement de la retenue. */
@ExtendWith(MockitoExtension.class)
class CancellationFeeRatePortAdapterTest {

    private static final UUID BID_ID = UUID.randomUUID();
    private static final CancellationFeeBreakdown BREAKDOWN = new CancellationFeeBreakdown(
            new BigDecimal("30.00"), new BigDecimal("1.75"), new BigDecimal("1.41"),
            new BigDecimal("26.84"), new BigDecimal("70.00"));

    @Mock CancellationFeeRepository fees;
    @Mock AuditService auditService;
    @InjectMocks CancellationFeeRatePortAdapter adapter;

    @Test
    void dueRate_dueRow_returnsRate() {
        when(fees.findByBidId(BID_ID)).thenReturn(Optional.of(fee(CancellationFeeStatus.DUE)));

        assertThat(adapter.dueRate(BID_ID)).hasValueSatisfying(rate -> assertThat(rate).isEqualByComparingTo("0.30"));
    }

    @ParameterizedTest
    @EnumSource(value = CancellationFeeStatus.class, names = "DUE", mode = EnumSource.Mode.EXCLUDE)
    void dueRate_onlyWhenDue(CancellationFeeStatus status) {
        when(fees.findByBidId(BID_ID)).thenReturn(Optional.of(fee(status)));

        assertThat(adapter.dueRate(BID_ID)).isEmpty();
    }

    @Test
    void dueRate_noRow_empty() {
        when(fees.findByBidId(BID_ID)).thenReturn(Optional.empty());

        assertThat(adapter.dueRate(BID_ID)).isEmpty();
    }

    @Test
    void markRetained_storesBreakdownAndRetains() {
        CancellationFeeEntity due = fee(CancellationFeeStatus.DUE);
        when(fees.findByBidIdForUpdate(BID_ID)).thenReturn(Optional.of(due));

        adapter.markRetained(BID_ID, BREAKDOWN, "EUR");

        verify(fees).save(due);
        assertThat(due.getStatus()).isEqualTo(CancellationFeeStatus.RETAINED);
        assertThat(due.getGrossFee()).isEqualByComparingTo("30.00");
        assertThat(due.getRefundFees()).isEqualByComparingTo("1.75");
        assertThat(due.getCommission()).isEqualByComparingTo("1.41");
        assertThat(due.getTravelerNet()).isEqualByComparingTo("26.84");
        assertThat(due.getCurrency()).isEqualTo("EUR");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("BID"), eq(BID_ID), eq(CancellationFeeRatePortAdapter.AUDIT_FEE_RETAINED),
                isNull(), payload.capture());
        assertThat(payload.getValue())
                .containsEntry("grossFee", "30.00")
                .containsEntry("travelerNet", "26.84")
                .containsEntry("currency", "EUR");
    }

    @Test
    void markRetained_twice_isIdempotent() {
        CancellationFeeEntity row = fee(CancellationFeeStatus.DUE);
        when(fees.findByBidIdForUpdate(BID_ID)).thenReturn(Optional.of(row));

        adapter.markRetained(BID_ID, BREAKDOWN, "EUR");
        adapter.markRetained(BID_ID, new CancellationFeeBreakdown(new BigDecimal("99.00"), BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("99.00"), BigDecimal.ZERO), "XOF");

        verify(fees, times(1)).save(any());
        verify(auditService, times(1)).log(any(), any(), any(), any(), any());
        assertThat(row.getGrossFee()).isEqualByComparingTo("30.00");
        assertThat(row.getCurrency()).isEqualTo("EUR");
    }

    @ParameterizedTest
    @EnumSource(value = CancellationFeeStatus.class, names = "DUE", mode = EnumSource.Mode.EXCLUDE)
    void markRetained_notDue_noOp(CancellationFeeStatus status) {
        CancellationFeeEntity row = fee(status);
        when(fees.findByBidIdForUpdate(BID_ID)).thenReturn(Optional.of(row));

        adapter.markRetained(BID_ID, BREAKDOWN, "EUR");

        verify(fees, never()).save(any());
        verifyNoInteractions(auditService);
        assertThat(row.getStatus()).isEqualTo(status);
    }

    @Test
    void markRetained_noRow_noOp() {
        when(fees.findByBidIdForUpdate(BID_ID)).thenReturn(Optional.empty());

        adapter.markRetained(BID_ID, BREAKDOWN, "EUR");

        verify(fees, never()).save(any());
        verifyNoInteractions(auditService);
    }

    private static CancellationFeeEntity fee(CancellationFeeStatus status) {
        CancellationFeeEntity f = new CancellationFeeEntity();
        f.setBidId(BID_ID);
        f.setSenderId(UUID.randomUUID());
        f.setTravelerId(UUID.randomUUID());
        f.setRate(new BigDecimal("0.3000"));
        f.setReason(CancellationFeeReason.SENDER_CANCEL);
        f.setStatus(status);
        f.setGrossFee(BigDecimal.ZERO);
        f.setRefundFees(BigDecimal.ZERO);
        f.setCommission(BigDecimal.ZERO);
        f.setTravelerNet(BigDecimal.ZERO);
        f.setCurrency("EUR");
        return f;
    }
}
