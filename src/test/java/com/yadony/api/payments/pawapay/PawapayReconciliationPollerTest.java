package com.yadony.api.payments.pawapay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yadony.api.admin.AdminAlertEntity;
import com.yadony.api.admin.AdminAlertRepository;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.payments.pawapay.dto.PawapayOperationSnapshot;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;

@ExtendWith(MockitoExtension.class)
class PawapayReconciliationPollerTest {

    @Mock PawapayOperationRepository repository;
    @Mock PawapayClient client;
    @Mock PawapayOperationService operations;
    @Mock AdminAlertService alerts;
    @Mock AdminAlertRepository alertRepository;

    private PawapayReconciliationPoller poller() {
        return new PawapayReconciliationPoller(repository, client, operations, alerts, alertRepository, props(true));
    }

    private static PawapayProperties props(boolean enabled) {
        return new PawapayProperties(enabled, "https://x", "t", false, 30, "https://r", "yadony://bids/%s/mobile-money/awaiting",
                new PawapayProperties.BalanceMin(BigDecimal.ZERO, BigDecimal.ZERO));
    }

    private PawapayOperationEntity op(PawapayOperationStatus status, LocalDateTime createdAt) {
        PawapayOperationEntity o = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.DEPOSIT,
                UUID.randomUUID(), null, new BigDecimal("15000"), "XOF", "ORANGE_SEN", "SN", "221771234567");
        o.setStatus(status);
        ReflectionTestUtils.setField(o, "createdAt", createdAt);
        ReflectionTestUtils.setField(o, "updatedAt", createdAt);
        return o;
    }

    @Test
    void openOperation_isReconciledFromPawapayStatus() {
        PawapayOperationEntity o = op(PawapayOperationStatus.ACCEPTED, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5));
        when(repository.findByStatusInAndUpdatedAtBefore(eq(PawapayOperationStatus.OPEN), any(), any())).thenReturn(List.of(o));
        when(client.getStatus(PawapayOperationKind.DEPOSIT, o.getId())).thenReturn(Optional.of(
                new PawapayOperationSnapshot(PawapayOperationStatus.COMPLETED, null, null, "ptx", null, "{}")));

        poller().reconcile();

        verify(operations).apply(eq(o.getId()), eq(PawapayOperationStatus.COMPLETED), isNull(), isNull(), eq("ptx"),
                isNull(), eq("{}"), eq(PawapayOperationService.Source.POLL));
    }

    @Test
    void createdForLong_andUnknownAtPawapay_becomesSubmitRejected() {
        PawapayOperationEntity o = op(PawapayOperationStatus.CREATED, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5));
        when(repository.findByStatusInAndUpdatedAtBefore(any(), any(), any())).thenReturn(List.of(o));
        when(client.getStatus(PawapayOperationKind.DEPOSIT, o.getId())).thenReturn(Optional.empty());

        poller().reconcile();

        verify(operations).apply(eq(o.getId()), eq(PawapayOperationStatus.SUBMIT_REJECTED), eq("SUBMIT_TIMEOUT"), any(),
                isNull(), isNull(), isNull(), eq(PawapayOperationService.Source.SYSTEM));
    }

    @Test
    void createdRecently_andUnknown_isLeftAlone() {
        PawapayOperationEntity o = op(PawapayOperationStatus.CREATED, LocalDateTime.now(ZoneOffset.UTC).minusSeconds(90));
        when(repository.findByStatusInAndUpdatedAtBefore(any(), any(), any())).thenReturn(List.of(o));
        when(client.getStatus(PawapayOperationKind.DEPOSIT, o.getId())).thenReturn(Optional.empty());

        poller().reconcile();

        verify(operations, never()).apply(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void acceptedButUnknown_isNotRejected_onlyLogged() {
        PawapayOperationEntity o = op(PawapayOperationStatus.ACCEPTED, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(10));
        when(repository.findByStatusInAndUpdatedAtBefore(any(), any(), any())).thenReturn(List.of(o));
        when(client.getStatus(PawapayOperationKind.DEPOSIT, o.getId())).thenReturn(Optional.empty());

        poller().reconcile();

        verify(operations, never()).apply(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void networkError_onOne_doesNotStopTheOthers() {
        PawapayOperationEntity a = op(PawapayOperationStatus.PROCESSING, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5));
        PawapayOperationEntity b = op(PawapayOperationStatus.PROCESSING, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5));
        when(repository.findByStatusInAndUpdatedAtBefore(any(), any(), any())).thenReturn(List.of(a, b));
        when(client.getStatus(PawapayOperationKind.DEPOSIT, a.getId())).thenThrow(new ResourceAccessException("boom"));
        when(client.getStatus(PawapayOperationKind.DEPOSIT, b.getId())).thenReturn(Optional.of(
                new PawapayOperationSnapshot(PawapayOperationStatus.FAILED, "PAYMENT_NOT_APPROVED", "no", null, null, "{}")));

        poller().reconcile();

        verify(operations).apply(eq(b.getId()), eq(PawapayOperationStatus.FAILED), eq("PAYMENT_NOT_APPROVED"), eq("no"),
                isNull(), isNull(), eq("{}"), eq(PawapayOperationService.Source.POLL));
    }

    /**
     * Revue finale, point 6 (Important) : ce poller était gardé par {@code props.enabled()},
     * alors que {@code MobileMoneyPaymentDeadlineScheduler} ne l'est DÉLIBÉRÉMENT PAS (voir sa
     * Javadoc). L'asymétrie était l'erreur : couper le rail pendant un incident arrêtait la
     * réconciliation pendant que l'expiration continuait — un dépôt bloqué n'était alors plus
     * jamais rattrapé, sans la moindre alerte puisque l'escalade vit précisément dans ce poller.
     * Un interrupteur d'urgence doit arrêter les NOUVEAUX mouvements d'argent, pas la
     * réconciliation de ceux déjà en vol. LE TEST DEMANDÉ PAR LA REVUE FINALE, point 6.
     */
    @Test
    void disabled_stillReconciles() {
        when(repository.findByStatusInAndUpdatedAtBefore(any(), any(), any())).thenReturn(List.of());

        new PawapayReconciliationPoller(repository, client, operations, alerts, alertRepository, props(false)).reconcile();

        verify(repository).findByStatusInAndUpdatedAtBefore(eq(PawapayOperationStatus.OPEN), any(), any());
    }

    // Revue ronde 1, point 3 : sans borne, un incident prolongé chez pawaPay accumulerait
    // des centaines d'opérations OPEN et ferait durer un passage des heures sur l'unique
    // pool de scheduling partagé par tous les crons du dépôt.

    @Test
    void reconcile_requestsBoundedOldestUpdatedFirstPage() {
        when(repository.findByStatusInAndUpdatedAtBefore(any(), any(), any())).thenReturn(List.of());

        poller().reconcile();

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByStatusInAndUpdatedAtBefore(eq(PawapayOperationStatus.OPEN), any(), captor.capture());
        Pageable page = captor.getValue();
        assertThat(page.getPageNumber()).isZero();
        assertThat(page.getPageSize()).isEqualTo(200);
        assertThat(page.getSort()).isEqualTo(Sort.by("updatedAt").ascending());
    }

    // Revue ronde 1, point 4 : une opération non-CREATED inconnue de pawaPay ne fait
    // qu'un log.warn, qui ne rafraîchit pas updatedAt (donc rest sélectionnée pour
    // toujours) et ne crée aucun événement Sentry (minimum-event-level = ERROR). Un
    // versement réellement perdu ne serait jamais payé, et personne ne le saurait.

    @Test
    void acceptedAndUnknownForOverAnHour_escalatesToAdmin() {
        PawapayOperationEntity o = op(PawapayOperationStatus.ACCEPTED, LocalDateTime.now(ZoneOffset.UTC).minusHours(2));
        when(repository.findByStatusInAndUpdatedAtBefore(any(), any(), any())).thenReturn(List.of(o));
        when(client.getStatus(PawapayOperationKind.DEPOSIT, o.getId())).thenReturn(Optional.empty());
        String expectedType = "PAWAPAY_UNKNOWN_OP_" + o.getId();
        when(alertRepository.findByTypeAndResolved(expectedType, false)).thenReturn(List.of());

        poller().reconcile();

        verify(alertRepository).save(any(AdminAlertEntity.class));
        verify(alerts).raise(eq(expectedType), any(), any());
    }

    @Test
    void acceptedAndUnknownForOverAnHour_doesNotDuplicateWhenAlreadyEscalated() {
        PawapayOperationEntity o = op(PawapayOperationStatus.ACCEPTED, LocalDateTime.now(ZoneOffset.UTC).minusHours(2));
        when(repository.findByStatusInAndUpdatedAtBefore(any(), any(), any())).thenReturn(List.of(o));
        when(client.getStatus(PawapayOperationKind.DEPOSIT, o.getId())).thenReturn(Optional.empty());
        String expectedType = "PAWAPAY_UNKNOWN_OP_" + o.getId();
        AdminAlertEntity existing = new AdminAlertEntity();
        existing.setType(expectedType);
        when(alertRepository.findByTypeAndResolved(expectedType, false)).thenReturn(List.of(existing));

        poller().reconcile();

        verify(alertRepository, never()).save(any());
        verify(alerts, never()).raise(any(), any(), any());
    }

    @Test
    void acceptedAndUnknownWithinTheHour_doesNotEscalateYet() {
        PawapayOperationEntity o = op(PawapayOperationStatus.ACCEPTED, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(30));
        when(repository.findByStatusInAndUpdatedAtBefore(any(), any(), any())).thenReturn(List.of(o));
        when(client.getStatus(PawapayOperationKind.DEPOSIT, o.getId())).thenReturn(Optional.empty());

        poller().reconcile();

        verify(alertRepository, never()).findByTypeAndResolved(any(), anyBoolean());
        verify(alerts, never()).raise(any(), any(), any());
    }
}
