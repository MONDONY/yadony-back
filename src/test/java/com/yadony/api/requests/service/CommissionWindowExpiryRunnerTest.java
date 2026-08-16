package com.yadony.api.requests.service;

import com.yadony.api.common.AuditService;
import com.yadony.api.requests.CashGatePort;
import com.yadony.api.requests.NegotiationProperties;
import com.yadony.api.requests.entity.NegotiationThreadEntity;
import com.yadony.api.requests.entity.NegotiationThreadStatus;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.event.NegotiationCommissionExpiredEvent;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import com.yadony.api.requests.repository.PackageRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CommissionWindowExpiryRunnerTest {

    @Mock private PackageRequestRepository requestRepo;
    @Mock private NegotiationThreadRepository threadRepo;
    @Mock private com.yadony.api.matching.AnnouncementRepository announcementRepo;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AuditService auditService;
    @Mock private NegotiationProperties negotiationProperties;
    @Mock private CashGatePort cashGatePort;
    @InjectMocks private CommissionWindowExpiryRunner runner;

    @BeforeEach
    void stubWindow() {
        lenient().when(negotiationProperties.commissionWindowMinutes()).thenReturn(120);
        lenient().when(threadRepo.findUnrefundedChargedCommissions()).thenReturn(List.of());
    }

    private static void setId(Object entity, UUID id) {
        try {
            var f = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private NegotiationThreadEntity awaitingCommissionThread(UUID packageRequestId) {
        NegotiationThreadEntity t = new NegotiationThreadEntity();
        t.setPackageRequestId(packageRequestId);
        t.setTravelerId(UUID.randomUUID());
        t.setStatus(NegotiationThreadStatus.AWAITING_COMMISSION);
        t.setLastActivityAt(LocalDateTime.now(ZoneOffset.UTC).minusHours(3));
        setId(t, UUID.randomUUID());
        return t;
    }

    private PackageRequestEntity request(PackageRequestStatus status) {
        PackageRequestEntity r = new PackageRequestEntity();
        r.setSenderId(UUID.randomUUID());
        r.setStatus(status);
        setId(r, UUID.randomUUID());
        return r;
    }

    @Test
    @DisplayName("run() — thread AWAITING_COMMISSION hors délai → EXPIRED, demande redevient OPEN, trajet dédié soft-deleted")
    void expire_pastDeadline_cancelsThreadAndSoftDeletesDedicatedTrip() {
        PackageRequestEntity request = request(PackageRequestStatus.NEGOTIATING);
        NegotiationThreadEntity t = awaitingCommissionThread(request.getId());
        UUID annId = UUID.randomUUID();
        t.setTravelerAnnouncementId(annId);
        com.yadony.api.matching.AnnouncementEntity ann = new com.yadony.api.matching.AnnouncementEntity();
        ann.setLinkedPackageRequestId(request.getId());
        setId(ann, annId);

        when(threadRepo.findExpiredAwaitingCommission(any())).thenReturn(List.of(t));
        when(threadRepo.findById(t.getId())).thenReturn(Optional.of(t));
        when(requestRepo.findById(request.getId())).thenReturn(Optional.of(request));
        when(threadRepo.findByPackageRequestId(request.getId())).thenReturn(List.of(t));
        when(announcementRepo.findById(annId)).thenReturn(Optional.of(ann));

        runner.run();

        assertThat(t.getStatus()).isEqualTo(NegotiationThreadStatus.EXPIRED);
        assertThat(request.getStatus()).isEqualTo(PackageRequestStatus.OPEN);
        assertThat(ann.getDeletedAt()).isNotNull();
        verify(announcementRepo).save(ann);
        verify(eventPublisher).publishEvent(any(NegotiationCommissionExpiredEvent.class));
        verify(auditService).log(eq("NEGOTIATION_THREAD"), eq(t.getId()),
            eq("COMMISSION_WINDOW_EXPIRED"), isNull(), anyMap());
        verify(auditService).log(eq("ANNOUNCEMENT"), eq(annId),
            eq("DEDICATED_TRIP_ORPHANED_ON_COMMISSION_EXPIRE"), isNull(), anyMap());
    }

    @Test
    @DisplayName("run() — le repo ne retourne aucun thread (aucun hors délai) → aucune mutation, aucun event")
    void expire_withinWindow_leavesThreadUntouched() {
        when(threadRepo.findExpiredAwaitingCommission(any())).thenReturn(List.of());

        runner.run();

        verify(threadRepo, never()).save(any());
        verifyNoInteractions(eventPublisher);
        verifyNoInteractions(announcementRepo);
    }

    @Test
    @DisplayName("expireOne appelé deux fois sur le même thread → un seul traitement, une seule notification")
    void expire_isIdempotent_onAlreadyExpiredThread() {
        NegotiationThreadEntity t = awaitingCommissionThread(UUID.randomUUID());
        when(threadRepo.findById(t.getId())).thenReturn(Optional.of(t));
        when(requestRepo.findById(t.getPackageRequestId())).thenReturn(Optional.empty());

        runner.expireOne(t.getId());
        // Second appel : le thread relu porte désormais EXPIRED (même objet muté par
        // le premier appel) — la garde d'idempotence doit no-op sans reprocessing.
        runner.expireOne(t.getId());

        assertThat(t.getStatus()).isEqualTo(NegotiationThreadStatus.EXPIRED);
        verify(threadRepo, times(1)).save(t);
        verify(eventPublisher, times(1)).publishEvent(any(NegotiationCommissionExpiredEvent.class));
        verify(auditService, times(1)).log(eq("NEGOTIATION_THREAD"), eq(t.getId()),
            eq("COMMISSION_WINDOW_EXPIRED"), isNull(), anyMap());
    }

    @Test
    @DisplayName("expireOne — thread introuvable → no-op")
    void expireOne_notFound_noop() {
        UUID id = UUID.randomUUID();
        when(threadRepo.findById(id)).thenReturn(Optional.empty());

        runner.expireOne(id);

        verify(threadRepo, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("expireOne — trajet lié non dédié à cette demande (submitTrip) → jamais soft-deleted")
    void expireOne_doesNotTouchReusableTrip() {
        PackageRequestEntity request = request(PackageRequestStatus.OPEN);
        NegotiationThreadEntity t = awaitingCommissionThread(request.getId());
        UUID annId = UUID.randomUUID();
        t.setTravelerAnnouncementId(annId);
        com.yadony.api.matching.AnnouncementEntity ann = new com.yadony.api.matching.AnnouncementEntity();
        ann.setLinkedPackageRequestId(null); // trajet réel, jamais dédié
        setId(ann, annId);

        when(threadRepo.findById(t.getId())).thenReturn(Optional.of(t));
        when(requestRepo.findById(request.getId())).thenReturn(Optional.of(request));
        when(announcementRepo.findById(annId)).thenReturn(Optional.of(ann));

        runner.expireOne(t.getId());

        assertThat(ann.getDeletedAt()).isNull();
        verify(announcementRepo, never()).save(any());
    }

    @Test
    @DisplayName("expireOverdueThreads — un conflit de verrou optimiste sur un thread n'interrompt pas le reste du balayage")
    void expireOverdueThreads_optimisticLockOnOneThread_continuesBatch() {
        NegotiationThreadEntity failing = awaitingCommissionThread(UUID.randomUUID());
        NegotiationThreadEntity ok = awaitingCommissionThread(UUID.randomUUID());
        when(threadRepo.findExpiredAwaitingCommission(any())).thenReturn(List.of(failing, ok));
        when(threadRepo.findById(failing.getId())).thenReturn(Optional.of(failing));
        when(threadRepo.findById(ok.getId())).thenReturn(Optional.of(ok));
        when(requestRepo.findById(any())).thenReturn(Optional.empty());
        doThrow(new ObjectOptimisticLockingFailureException("NegotiationThreadEntity", failing.getId()))
            .when(threadRepo).save(failing);

        runner.expireOverdueThreads();

        assertThat(ok.getStatus()).isEqualTo(NegotiationThreadStatus.EXPIRED);
        verify(threadRepo).save(ok);
    }

    @Test
    @DisplayName("refundOrphanedCommissions — balaie les fils AUTO_REJECTED/EXPIRED non remboursés via CashGatePort")
    void refundOrphanedCommissions_delegatesToCashGatePort() {
        NegotiationThreadEntity lostToCompetitor = new NegotiationThreadEntity();
        lostToCompetitor.setTravelerId(UUID.randomUUID());
        lostToCompetitor.setStatus(NegotiationThreadStatus.AUTO_REJECTED);
        lostToCompetitor.setCommissionPaymentIntentId("pi_orphan_1");
        setId(lostToCompetitor, UUID.randomUUID());

        NegotiationThreadEntity expiredMidThreeDs = new NegotiationThreadEntity();
        expiredMidThreeDs.setTravelerId(UUID.randomUUID());
        expiredMidThreeDs.setStatus(NegotiationThreadStatus.EXPIRED);
        expiredMidThreeDs.setCommissionPaymentIntentId("pi_orphan_2");
        setId(expiredMidThreeDs, UUID.randomUUID());

        when(threadRepo.findExpiredAwaitingCommission(any())).thenReturn(List.of());
        when(threadRepo.findUnrefundedChargedCommissions())
            .thenReturn(List.of(lostToCompetitor, expiredMidThreeDs));

        runner.run();

        verify(cashGatePort).refundNegotiationCommissionIfCharged(
            lostToCompetitor.getTravelerId(), lostToCompetitor.getId());
        verify(cashGatePort).refundNegotiationCommissionIfCharged(
            expiredMidThreeDs.getTravelerId(), expiredMidThreeDs.getId());
    }

    @Test
    @DisplayName("refundOrphanedCommissions — rien à rembourser → aucune interaction avec CashGatePort")
    void refundOrphanedCommissions_nothingPending_noInteraction() {
        when(threadRepo.findExpiredAwaitingCommission(any())).thenReturn(List.of());

        runner.run();

        verifyNoInteractions(cashGatePort);
    }
}
