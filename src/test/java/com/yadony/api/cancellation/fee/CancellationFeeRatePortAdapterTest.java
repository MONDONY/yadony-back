package com.yadony.api.cancellation.fee;

import com.yadony.api.common.AuditService;
import com.yadony.api.payments.fee.CancellationFeeBreakdown;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import com.yadony.api.cancellation.events.SenderCancellationFeeRetainedEvent;

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
    private static final UUID FEE_ID = UUID.randomUUID();
    private static final CancellationFeeBreakdown BREAKDOWN = new CancellationFeeBreakdown(
            new BigDecimal("30.00"), new BigDecimal("1.75"), new BigDecimal("1.41"),
            new BigDecimal("26.84"), new BigDecimal("70.00"));

    @Mock CancellationFeeRepository fees;
    @Mock AuditService auditService;
    @Mock EntityManager entityManager;
    @Mock ApplicationEventPublisher events;
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

    // ── Lecture verrouillée du taux (revue tâche 7, tour 2 : renonciation concurrente du règlement) ───────────

    @Test
    void dueRateForUpdate_nothingDue_neverLocks() {
        // Remboursement ordinaire sans retenue : aucun verrou de plus (et aucun FOR NO KEY UPDATE sous H2).
        when(fees.findByBidId(BID_ID)).thenReturn(Optional.of(fee(CancellationFeeStatus.NOT_APPLICABLE)));

        assertThat(adapter.dueRateForUpdate(BID_ID)).isEmpty();

        verify(fees, never()).findByBidIdForUpdate(any());
        verifyNoInteractions(entityManager);
    }

    @Test
    void dueRateForUpdate_dueRow_lockedAndRereadBeforeAnswering() {
        CancellationFeeEntity row = fee(CancellationFeeStatus.DUE);
        when(fees.findByBidId(BID_ID)).thenReturn(Optional.of(row));
        when(fees.findByBidIdForUpdate(BID_ID)).thenReturn(Optional.of(row));

        assertThat(adapter.dueRateForUpdate(BID_ID)).hasValueSatisfying(r -> assertThat(r).isEqualByComparingTo("0.30"));

        // L'instance a pu être chargée avant le verrou (lecture simple) : son état est relu sous le verrou.
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(fees, entityManager);
        order.verify(fees).findByBidIdForUpdate(BID_ID);
        order.verify(entityManager).refresh(row);
    }

    @Test
    void dueRateForUpdate_waivedWhileWaitingForTheLock_empty() {
        CancellationFeeEntity row = fee(CancellationFeeStatus.DUE);
        when(fees.findByBidId(BID_ID)).thenReturn(Optional.of(row));
        when(fees.findByBidIdForUpdate(BID_ID)).thenReturn(Optional.of(row));
        // La renonciation a été commitée pendant l'attente du verrou : la relecture voit NOT_APPLICABLE.
        org.mockito.Mockito.doAnswer(inv -> {
            row.setStatus(CancellationFeeStatus.NOT_APPLICABLE);
            return null;
        }).when(entityManager).refresh(row);

        assertThat(adapter.dueRateForUpdate(BID_ID)).isEmpty();
    }

    @Test
    void markRetained_rereadsTheLockedRowBeforeWriting() {
        CancellationFeeEntity row = fee(CancellationFeeStatus.DUE);
        when(fees.findByBidIdForUpdate(BID_ID)).thenReturn(Optional.of(row));
        org.mockito.Mockito.doAnswer(inv -> {
            row.setStatus(CancellationFeeStatus.NOT_APPLICABLE);
            return null;
        }).when(entityManager).refresh(row);

        adapter.markRetained(BID_ID, BREAKDOWN, "EUR");

        assertThat(row.getStatus()).as("jamais RETAINED par-dessus une renonciation commitée")
                .isEqualTo(CancellationFeeStatus.NOT_APPLICABLE);
        verify(fees, never()).save(any());
        verifyNoInteractions(auditService);
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
    void markRetained_publishesTheSenderNotice_withTheRetainedAndRefundedAmounts() {
        CancellationFeeEntity due = fee(CancellationFeeStatus.DUE);
        due.setReason(CancellationFeeReason.SENDER_NO_SHOW);
        ReflectionTestUtils.setField(due, "id", FEE_ID);
        when(fees.findByBidIdForUpdate(BID_ID)).thenReturn(Optional.of(due));

        adapter.markRetained(BID_ID, BREAKDOWN, "EUR");

        ArgumentCaptor<SenderCancellationFeeRetainedEvent> event =
                ArgumentCaptor.forClass(SenderCancellationFeeRetainedEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().feeId()).isEqualTo(FEE_ID);
        assertThat(event.getValue().bidId()).isEqualTo(BID_ID);
        assertThat(event.getValue().senderId()).isEqualTo(due.getSenderId());
        assertThat(event.getValue().reason()).isEqualTo(CancellationFeeReason.SENDER_NO_SHOW);
        assertThat(event.getValue().retainedAmount()).isEqualByComparingTo("30.00");
        assertThat(event.getValue().refundedAmount()).isEqualByComparingTo("70.00");
        assertThat(event.getValue().currency()).isEqualTo("EUR");
    }

    @Test
    void markRetained_twice_publishesTheSenderNoticeOnce() {
        CancellationFeeEntity row = fee(CancellationFeeStatus.DUE);
        when(fees.findByBidIdForUpdate(BID_ID)).thenReturn(Optional.of(row));

        adapter.markRetained(BID_ID, BREAKDOWN, "EUR");
        adapter.markRetained(BID_ID, BREAKDOWN, "EUR");

        verify(events, times(1)).publishEvent(any(SenderCancellationFeeRetainedEvent.class));
    }

    @ParameterizedTest
    @EnumSource(value = CancellationFeeStatus.class, names = "DUE", mode = EnumSource.Mode.EXCLUDE)
    void markRetained_notDue_noSenderNotice(CancellationFeeStatus status) {
        when(fees.findByBidIdForUpdate(BID_ID)).thenReturn(Optional.of(fee(status)));

        adapter.markRetained(BID_ID, BREAKDOWN, "EUR");

        verifyNoInteractions(events);
    }

    @Test
    void markNotApplicable_neverNotifies() {
        when(fees.findByBidIdForUpdate(BID_ID)).thenReturn(Optional.of(fee(CancellationFeeStatus.DUE)));

        adapter.markNotApplicable(BID_ID, "ADMIN_FULL_REFUND");

        verifyNoInteractions(events);
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

    @Test
    void capEur_isTheConfiguredCap() {
        CancellationFeeRatePortAdapter configured = new CancellationFeeRatePortAdapter(fees, auditService,
                new CancellationFeeProperties(false, null, new BigDecimal("25"), null), entityManager, events);

        assertThat(configured.capEur()).isEqualByComparingTo("25");
    }

    @Test
    void markNotApplicable_dueRow_closesAndAudits() {
        CancellationFeeEntity due = fee(CancellationFeeStatus.DUE);
        when(fees.findByBidIdForUpdate(BID_ID)).thenReturn(Optional.of(due));

        adapter.markNotApplicable(BID_ID, "PAYMENT_NOT_CAPTURED");

        verify(fees).save(due);
        assertThat(due.getStatus()).isEqualTo(CancellationFeeStatus.NOT_APPLICABLE);
        // Rien n'a été retenu : les montants restent à zéro.
        assertThat(due.getGrossFee()).isEqualByComparingTo("0");
        assertThat(due.getTravelerNet()).isEqualByComparingTo("0");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("BID"), eq(BID_ID), eq(CancellationFeeRatePortAdapter.AUDIT_FEE_NOT_APPLICABLE),
                isNull(), payload.capture());
        assertThat(payload.getValue()).containsEntry("reason", "PAYMENT_NOT_CAPTURED").containsKey("cancellationFeeId");
    }

    @ParameterizedTest
    @EnumSource(value = CancellationFeeStatus.class, names = "DUE", mode = EnumSource.Mode.EXCLUDE)
    void markNotApplicable_notDue_noOp(CancellationFeeStatus status) {
        CancellationFeeEntity row = fee(status);
        when(fees.findByBidIdForUpdate(BID_ID)).thenReturn(Optional.of(row));

        adapter.markNotApplicable(BID_ID, "PAYMENT_NOT_CAPTURED");

        verify(fees, never()).save(any());
        verifyNoInteractions(auditService);
        assertThat(row.getStatus()).isEqualTo(status);
    }

    @Test
    void markNotApplicable_noRow_noOp() {
        when(fees.findByBidIdForUpdate(BID_ID)).thenReturn(Optional.empty());

        adapter.markNotApplicable(BID_ID, "PAYMENT_NOT_CAPTURED");

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
