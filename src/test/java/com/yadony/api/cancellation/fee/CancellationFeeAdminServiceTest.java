package com.yadony.api.cancellation.fee;

import com.yadony.api.cancellation.dto.AdminCancellationFeeResponse;
import com.yadony.api.cancellation.events.CancellationFeePayoutRetryRequestedEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Gestes de l'administration sur une retenue d'annulation : renoncer à une retenue encore due, relancer un
 * versement en attente. Aucun ne déplace d'argent ; chacun est audité avec l'administrateur pour acteur.
 */
@ExtendWith(MockitoExtension.class)
class CancellationFeeAdminServiceTest {

    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final UUID BID_ID = UUID.randomUUID();
    private static final UUID FEE_ID = UUID.randomUUID();
    private static final String REASON = "Partiel refusé par l'opérateur, remboursement intégral";
    private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");

    @Mock CancellationFeeRepository fees;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher events;
    @Mock SenderRefundInFlightPort senderRefunds;

    private CancellationFeeAdminService service() {
        return new CancellationFeeAdminService(fees, auditService, events, Clock.fixed(NOW, ZoneOffset.UTC),
                senderRefunds);
    }

    @Test
    void waive_dueRow_closedNotApplicable_auditedWithTheAdminAndTheReason() {
        CancellationFeeEntity row = fee(CancellationFeeStatus.DUE);
        when(fees.findByBidIdForUpdate(BID_ID)).thenReturn(Optional.of(row));

        AdminCancellationFeeResponse response = service().waive(BID_ID, ADMIN_ID, REASON);

        assertThat(row.getStatus()).isEqualTo(CancellationFeeStatus.NOT_APPLICABLE);
        verify(fees).save(row);
        assertThat(response.status()).isEqualTo("NOT_APPLICABLE");
        assertThat(response.id()).isEqualTo(FEE_ID);
        assertThat(response.bidId()).isEqualTo(BID_ID);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("BID"), eq(BID_ID), eq("CANCELLATION_FEE_NOT_APPLICABLE"), eq(ADMIN_ID),
                payload.capture());
        assertThat(payload.getValue())
                .containsEntry("cancellationFeeId", FEE_ID.toString())
                .containsEntry("reason", "ADMIN_WAIVED")
                .containsEntry("adminReason", REASON);
        // Aucun mouvement d'argent ni événement : le remboursement suivant rend tout à l'expéditeur.
        verifyNoInteractions(events);
    }

    @ParameterizedTest
    @EnumSource(value = CancellationFeeStatus.class, names = {"RETAINED", "PAYOUT_PENDING", "PAID_OUT", "NOT_APPLICABLE"})
    void waive_rowNoLongerDue_409WithoutAnyWrite(CancellationFeeStatus status) {
        CancellationFeeEntity row = fee(status);
        when(fees.findByBidIdForUpdate(BID_ID)).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service().waive(BID_ID, ADMIN_ID, REASON))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getErrorCode()).isEqualTo("cancellation-fee-not-due");
                });

        assertThat(row.getStatus()).isEqualTo(status);
        verify(fees, never()).save(any());
        verifyNoInteractions(auditService, events);
    }

    @Test
    void waive_senderRefundStillInFlight_409WithoutAnyWrite() {
        // Remboursement partiel vivant (tentative annulée après la soumission) : renoncer laisserait l'expéditeur
        // remboursé d'une partie seulement, le voyageur sans rien.
        CancellationFeeEntity row = fee(CancellationFeeStatus.DUE);
        when(fees.findByBidIdForUpdate(BID_ID)).thenReturn(Optional.of(row));
        when(senderRefunds.senderRefundInFlight(BID_ID)).thenReturn(true);

        assertThatThrownBy(() -> service().waive(BID_ID, ADMIN_ID, REASON))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getErrorCode()).isEqualTo("sender-refund-in-flight");
                    assertThat(e.getMessage()).contains("remboursement");
                });

        assertThat(row.getStatus()).isEqualTo(CancellationFeeStatus.DUE);
        verify(fees, never()).save(any());
        verifyNoInteractions(auditService, events);
    }

    @Test
    void waive_noRow_404() {
        when(fees.findByBidIdForUpdate(BID_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().waive(BID_ID, ADMIN_ID, REASON))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getErrorCode()).isEqualTo("cancellation-fee-not-found");
                });
        verifyNoInteractions(auditService, events);
    }

    @Test
    void retryPayout_pendingRow_rearmedAudited_andTheAttemptRequested() {
        CancellationFeeEntity row = fee(CancellationFeeStatus.PAYOUT_PENDING);
        row.setPayoutPendingReason("TOO_MANY_FAILED_PAYOUTS");
        when(fees.findByIdForUpdate(FEE_ID)).thenReturn(Optional.of(row));

        service().retryPayout(FEE_ID, ADMIN_ID, REASON);

        assertThat(row.getPayoutRearmedAt()).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        assertThat(row.getStatus()).as("reste en attente : seul le versement la fera avancer")
                .isEqualTo(CancellationFeeStatus.PAYOUT_PENDING);
        verify(fees).save(row);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("BID"), eq(BID_ID), eq("CANCELLATION_FEE_PAYOUT_RETRY_REQUESTED"), eq(ADMIN_ID),
                payload.capture());
        assertThat(payload.getValue())
                .containsEntry("cancellationFeeId", FEE_ID.toString())
                .containsEntry("adminReason", REASON)
                .containsEntry("previousReason", "TOO_MANY_FAILED_PAYOUTS");
        verify(events).publishEvent(new CancellationFeePayoutRetryRequestedEvent(FEE_ID));
    }

    @ParameterizedTest
    @EnumSource(value = CancellationFeeStatus.class, names = {"DUE", "RETAINED", "PAID_OUT", "NOT_APPLICABLE"})
    void retryPayout_rowNotPending_409(CancellationFeeStatus status) {
        when(fees.findByIdForUpdate(FEE_ID)).thenReturn(Optional.of(fee(status)));

        assertThatThrownBy(() -> service().retryPayout(FEE_ID, ADMIN_ID, REASON))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getErrorCode()).isEqualTo("cancellation-fee-not-pending");
                });
        verify(fees, never()).save(any());
        verifyNoInteractions(auditService, events);
    }

    @Test
    void retryPayout_noRow_404() {
        when(fees.findByIdForUpdate(FEE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().retryPayout(FEE_ID, ADMIN_ID, REASON))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(auditService, events);
    }

    @Test
    void describe_readsTheRow() {
        CancellationFeeEntity row = fee(CancellationFeeStatus.PAID_OUT);
        row.setPayoutRef("op-1");
        when(fees.findActiveById(FEE_ID)).thenReturn(Optional.of(row));

        AdminCancellationFeeResponse response = service().describe(FEE_ID);

        assertThat(response).isEqualTo(new AdminCancellationFeeResponse(FEE_ID, BID_ID, "PAID_OUT",
                new BigDecimal("17323"), "XOF", "op-1", null));
    }

    @Test
    void describe_noRow_404() {
        when(fees.findActiveById(FEE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().describe(FEE_ID))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    private static CancellationFeeEntity fee(CancellationFeeStatus status) {
        CancellationFeeEntity f = new CancellationFeeEntity();
        ReflectionTestUtils.setField(f, "id", FEE_ID);
        f.setBidId(BID_ID);
        f.setSenderId(UUID.randomUUID());
        f.setTravelerId(UUID.randomUUID());
        f.setRate(new BigDecimal("0.30"));
        f.setReason(CancellationFeeReason.SENDER_CANCEL);
        f.setStatus(status);
        f.setGrossFee(BigDecimal.ZERO);
        f.setRefundFees(BigDecimal.ZERO);
        f.setCommission(BigDecimal.ZERO);
        f.setTravelerNet(new BigDecimal("17323"));
        f.setCurrency("XOF");
        return f;
    }
}
