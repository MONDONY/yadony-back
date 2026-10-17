package com.yadony.api.requests.service;

import com.yadony.api.requests.RequestsConfig;
import com.yadony.api.requests.entity.NegotiationThreadEntity;
import com.yadony.api.requests.entity.NegotiationThreadStatus;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import com.yadony.api.requests.repository.PackageRequestRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExpirationSchedulerTest {

    @Mock private PackageRequestRepository requestRepo;
    @Mock private NegotiationThreadRepository threadRepo;
    @Mock private NegotiationExpiryRunner runner;
    @Mock private RequestsConfig config;

    @InjectMocks private ExpirationScheduler scheduler;

    private static void setId(Object entity, UUID id) {
        try {
            var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private PackageRequestEntity request(PackageRequestStatus status) {
        PackageRequestEntity r = new PackageRequestEntity();
        r.setSenderId(UUID.randomUUID());
        r.setStatus(status);
        setId(r, UUID.randomUUID());
        return r;
    }

    private NegotiationThreadEntity thread(NegotiationThreadStatus status) {
        NegotiationThreadEntity t = new NegotiationThreadEntity();
        t.setPackageRequestId(UUID.randomUUID());
        t.setTravelerId(UUID.randomUUID());
        t.setStatus(status);
        setId(t, UUID.randomUUID());
        return t;
    }

    private void stubAllThreadFindersEmpty() {
        when(config.threadInactivityHours()).thenReturn(48);
        when(config.awaitingTripHours()).thenReturn(24);
        when(config.awaitingPaymentHours()).thenReturn(24);
        when(threadRepo.findInactive(any(LocalDateTime.class))).thenReturn(List.of());
        when(threadRepo.findAwaitingTripExpired(any(LocalDateTime.class))).thenReturn(List.of());
        when(threadRepo.findAwaitingPaymentExpired(any(LocalDateTime.class))).thenReturn(List.of());
    }

    @Test
    @DisplayName("expireRequests() expire aussi les demandes sans date créées il y a plus de 60 jours")
    void expireRequests_flexibleDate_sixtyDaysAfterCreation() {
        PackageRequestEntity flexible = request(PackageRequestStatus.OPEN);
        when(requestRepo.findExpired(any(LocalDate.class))).thenReturn(List.of());
        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        when(requestRepo.findFlexibleExpired(cutoff.capture())).thenReturn(List.of(flexible));

        LocalDateTime before = LocalDateTime.now(java.time.ZoneOffset.UTC);
        scheduler.expireRequests();
        LocalDateTime after = LocalDateTime.now(java.time.ZoneOffset.UTC);

        verify(runner).expireRequest(flexible.getId());
        Assertions.assertEquals(60, ExpirationScheduler.FLEXIBLE_REQUEST_LIFETIME_DAYS);
        Assertions.assertFalse(cutoff.getValue().isBefore(before.minusDays(60)));
        Assertions.assertFalse(cutoff.getValue().isAfter(after.minusDays(60)));
    }

    @Test
    @DisplayName("expireRequests() délègue chaque request expirée au runner (par id)")
    void expireRequests_delegatesPerItem() {
        PackageRequestEntity r = request(PackageRequestStatus.OPEN);
        when(requestRepo.findExpired(any(LocalDate.class))).thenReturn(List.of(r));

        scheduler.expireRequests();

        verify(runner).expireRequest(r.getId());
    }

    @Test
    @DisplayName("expireThreads() délègue chaque thread au runner avec le statut attendu + la raison")
    void expireThreads_delegatesPerItemWithStatusAndReason() {
        when(config.threadInactivityHours()).thenReturn(48);
        when(config.awaitingTripHours()).thenReturn(24);
        when(config.awaitingPaymentHours()).thenReturn(24);
        NegotiationThreadEntity open = thread(NegotiationThreadStatus.OPEN);
        NegotiationThreadEntity trip = thread(NegotiationThreadStatus.AWAITING_TRIP);
        NegotiationThreadEntity pay = thread(NegotiationThreadStatus.AWAITING_PAYMENT);
        when(threadRepo.findInactive(any())).thenReturn(List.of(open));
        when(threadRepo.findAwaitingTripExpired(any())).thenReturn(List.of(trip));
        when(threadRepo.findAwaitingPaymentExpired(any())).thenReturn(List.of(pay));

        scheduler.expireThreads();

        verify(runner).expireThread(open.getId(), NegotiationThreadStatus.OPEN, "INACTIVE_OPEN");
        verify(runner).expireThread(trip.getId(), NegotiationThreadStatus.AWAITING_TRIP, "AWAITING_TRIP_TIMEOUT");
        verify(runner).expireThread(pay.getId(), NegotiationThreadStatus.AWAITING_PAYMENT, "AWAITING_PAYMENT_TIMEOUT");
    }

    @Test
    @DisplayName("un conflit optimistic-lock sur un item ne stoppe pas le lot (les autres sont traités)")
    void expireThreads_oneItemConflict_othersStillProcessed() {
        when(config.threadInactivityHours()).thenReturn(48);
        when(config.awaitingTripHours()).thenReturn(24);
        when(config.awaitingPaymentHours()).thenReturn(24);
        when(threadRepo.findInactive(any())).thenReturn(List.of());
        when(threadRepo.findAwaitingTripExpired(any())).thenReturn(List.of());
        NegotiationThreadEntity a = thread(NegotiationThreadStatus.AWAITING_PAYMENT);
        NegotiationThreadEntity b = thread(NegotiationThreadStatus.AWAITING_PAYMENT);
        when(threadRepo.findAwaitingPaymentExpired(any())).thenReturn(List.of(a, b));
        doThrow(new ObjectOptimisticLockingFailureException("NegotiationThreadEntity", a.getId()))
            .when(runner).expireThread(eq(a.getId()), any(), any());

        // Ne doit PAS propager l'exception, et doit quand même traiter b.
        scheduler.expireThreads();

        verify(runner).expireThread(eq(b.getId()), eq(NegotiationThreadStatus.AWAITING_PAYMENT), any());
    }

    @Test
    @DisplayName("runExpiration() interroge requests + threads (délègue aux deux)")
    void runExpiration_runsBoth() {
        when(requestRepo.findExpired(any())).thenReturn(List.of());
        stubAllThreadFindersEmpty();

        scheduler.runExpiration();

        verify(requestRepo).findExpired(any());
        verify(threadRepo).findInactive(any());
        verify(threadRepo).findAwaitingTripExpired(any());
        verify(threadRepo).findAwaitingPaymentExpired(any());
    }

    @Test
    @DisplayName("expireThreads() confie chaque fil AWAITING_PAYMENT lié à un trajet au contrôle de trajet impayable")
    void expireThreads_delegatesTripUnavailableCheck() {
        stubAllThreadFindersEmpty();
        NegotiationThreadEntity t = thread(NegotiationThreadStatus.AWAITING_PAYMENT);
        when(threadRepo.findAwaitingPaymentWithTrip()).thenReturn(List.of(t));

        scheduler.expireThreads();

        verify(runner).expireThreadIfTripUnavailable(t.getId());
    }

    @Test
    @DisplayName("expireThreads() — conflit optimiste sur le contrôle de trajet → ignoré, lot poursuivi")
    void expireThreads_tripUnavailableConflict_skipped() {
        stubAllThreadFindersEmpty();
        NegotiationThreadEntity a = thread(NegotiationThreadStatus.AWAITING_PAYMENT);
        NegotiationThreadEntity b = thread(NegotiationThreadStatus.AWAITING_PAYMENT);
        when(threadRepo.findAwaitingPaymentWithTrip()).thenReturn(List.of(a, b));
        when(runner.expireThreadIfTripUnavailable(a.getId()))
            .thenThrow(new ObjectOptimisticLockingFailureException(NegotiationThreadEntity.class, a.getId()));

        scheduler.expireThreads();

        verify(runner).expireThreadIfTripUnavailable(b.getId());
    }
}
