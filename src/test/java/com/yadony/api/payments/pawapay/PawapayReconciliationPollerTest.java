package com.yadony.api.payments.pawapay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.payments.pawapay.dto.PawapayOpenOperation;
import com.yadony.api.payments.pawapay.dto.PawapayOperationSnapshot;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.client.ResourceAccessException;

/**
 * Le poller ne reçoit ni {@code PawapayProperties} ni dépôt d'alertes : il ne peut donc pas
 * dépendre de {@code yadony.pawapay.enabled} (un interrupteur d'urgence arrête les nouveaux
 * mouvements d'argent, jamais la réconciliation de ceux en vol), et la déduplication des
 * alertes vit dans {@link AdminAlertEscalator}, testée à part.
 */
@ExtendWith(MockitoExtension.class)
class PawapayReconciliationPollerTest {

    @Mock PawapayOperationRepository repository;
    @Mock PawapayClient client;
    @Mock PawapayOperationService operations;
    @Mock AdminAlertEscalator alerts;
    @InjectMocks PawapayReconciliationPoller poller;

    private static PawapayOpenOperation op(PawapayOperationStatus status, LocalDateTime createdAt) {
        return new PawapayOpenOperation(UUID.randomUUID(), PawapayOperationKind.DEPOSIT, status, createdAt);
    }

    private void stubOpen(PawapayOpenOperation... ops) {
        when(repository.findOpenForReconciliation(eq(PawapayOperationStatus.OPEN), any(), any())).thenReturn(List.of(ops));
    }

    @Test
    void openOperation_isReconciledFromPawapayStatus() {
        PawapayOpenOperation o = op(PawapayOperationStatus.ACCEPTED, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5));
        stubOpen(o);
        when(client.getStatus(PawapayOperationKind.DEPOSIT, o.id())).thenReturn(Optional.of(
                new PawapayOperationSnapshot(PawapayOperationStatus.COMPLETED, null, null, "ptx", null, "{}")));

        poller.reconcile();

        verify(operations).apply(eq(o.id()), eq(PawapayOperationStatus.COMPLETED), isNull(), isNull(), eq("ptx"),
                isNull(), eq("{}"), eq(PawapayOperationService.Source.POLL));
    }

    @Test
    void createdForLong_andUnknownAtPawapay_becomesSubmitRejected() {
        PawapayOpenOperation o = op(PawapayOperationStatus.CREATED, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5));
        stubOpen(o);
        when(client.getStatus(PawapayOperationKind.DEPOSIT, o.id())).thenReturn(Optional.empty());

        poller.reconcile();

        verify(operations).apply(eq(o.id()), eq(PawapayOperationStatus.SUBMIT_REJECTED), eq("SUBMIT_TIMEOUT"), any(),
                isNull(), isNull(), isNull(), eq(PawapayOperationService.Source.SYSTEM));
    }

    @Test
    void createdRecently_andUnknown_isLeftAlone() {
        PawapayOpenOperation o = op(PawapayOperationStatus.CREATED, LocalDateTime.now(ZoneOffset.UTC).minusSeconds(90));
        stubOpen(o);
        when(client.getStatus(PawapayOperationKind.DEPOSIT, o.id())).thenReturn(Optional.empty());

        poller.reconcile();

        verify(operations, never()).apply(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void acceptedButUnknown_isNotRejected_onlyLogged() {
        PawapayOpenOperation o = op(PawapayOperationStatus.ACCEPTED, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(10));
        stubOpen(o);
        when(client.getStatus(PawapayOperationKind.DEPOSIT, o.id())).thenReturn(Optional.empty());

        poller.reconcile();

        verify(operations, never()).apply(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void networkError_onOne_doesNotStopTheOthers() {
        PawapayOpenOperation a = op(PawapayOperationStatus.PROCESSING, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5));
        PawapayOpenOperation b = op(PawapayOperationStatus.PROCESSING, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5));
        stubOpen(a, b);
        when(client.getStatus(PawapayOperationKind.DEPOSIT, a.id())).thenThrow(new ResourceAccessException("boom"));
        when(client.getStatus(PawapayOperationKind.DEPOSIT, b.id())).thenReturn(Optional.of(
                new PawapayOperationSnapshot(PawapayOperationStatus.FAILED, "PAYMENT_NOT_APPROVED", "no", null, null, "{}")));

        poller.reconcile();

        verify(operations).apply(eq(b.id()), eq(PawapayOperationStatus.FAILED), eq("PAYMENT_NOT_APPROVED"), eq("no"),
                isNull(), isNull(), eq("{}"), eq(PawapayOperationService.Source.POLL));
    }

    // Sans borne, un incident prolongé chez pawaPay accumulerait des centaines d'opérations
    // OPEN et ferait durer un passage des heures sur l'unique pool de scheduling partagé par
    // tous les crons du dépôt.

    @Test
    void reconcile_requestsBoundedOldestUpdatedFirstPage() {
        stubOpen();

        poller.reconcile();

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findOpenForReconciliation(eq(PawapayOperationStatus.OPEN), any(), captor.capture());
        Pageable page = captor.getValue();
        assertThat(page.getPageNumber()).isZero();
        assertThat(page.getPageSize()).isEqualTo(200);
        assertThat(page.getSort()).isEqualTo(Sort.by("updatedAt").ascending());
    }

    // Une opération non-CREATED inconnue de pawaPay ne fait qu'un log.warn, qui ne rafraîchit
    // pas updatedAt (donc reste sélectionnée pour toujours) et ne crée aucun événement Sentry
    // (minimum-event-level = ERROR). Un versement réellement perdu ne serait jamais payé, et
    // personne ne le saurait.

    @Test
    void acceptedAndUnknownForOverAnHour_escalatesToAdmin_dedupedByOperation() {
        PawapayOpenOperation o = op(PawapayOperationStatus.ACCEPTED, LocalDateTime.now(ZoneOffset.UTC).minusHours(2));
        stubOpen(o);
        when(client.getStatus(PawapayOperationKind.DEPOSIT, o.id())).thenReturn(Optional.empty());

        poller.reconcile();

        verify(alerts).raiseOnce(eq("PAWAPAY_UNKNOWN_OP_" + o.id()), any(), any());
    }

    @Test
    void acceptedAndUnknownWithinTheHour_doesNotEscalateYet() {
        PawapayOpenOperation o = op(PawapayOperationStatus.ACCEPTED, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(30));
        stubOpen(o);
        when(client.getStatus(PawapayOperationKind.DEPOSIT, o.id())).thenReturn(Optional.empty());

        poller.reconcile();

        verifyNoInteractions(alerts);
    }

    /** {@code admin_alerts.type} est {@code VARCHAR(60)} : préfixe + UUID (36) doit tenir. */
    @Test
    void unknownAlertType_fitsInAdminAlertsTypeColumn() {
        String type = PawapayReconciliationPoller.UNKNOWN_ALERT_TYPE_PREFIX + UUID.randomUUID();
        assertThat(type.length()).isLessThanOrEqualTo(AdminAlertEscalator.TYPE_MAX_LENGTH);
    }
}
