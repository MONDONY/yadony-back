package com.yadony.api.cancellation;

import com.yadony.api.cancellation.events.CancellationConfirmedEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NoShowContestationTimeoutJobTest {

    @Mock private CancellationRepository cancellationRepo;
    @Mock private ApplicationEventPublisher events;
    @Mock private BidRepository bidRepository;
    @Mock private AuditService auditService;

    private NoShowContestationTimeoutJob job;

    @BeforeEach
    void setUp() {
        job = new NoShowContestationTimeoutJob(cancellationRepo, events, bidRepository, auditService);
    }

    /** Déclaration échue, encore en attente en base. */
    private CancellationEntity pending() {
        CancellationEntity c = new CancellationEntity();
        ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
        c.setBidId(UUID.randomUUID());
        c.setNoShowStatus(CancellationStatus.PENDING_CONFIRMATION);
        c.setReason(CancellationReason.SENDER_NO_SHOW.name());
        c.setContestationDeadline(OffsetDateTime.now().minusMinutes(5));
        lenient().when(cancellationRepo.findNoShowStatusById(c.getId()))
                .thenReturn(Optional.of(CancellationStatus.PENDING_CONFIRMATION));
        return c;
    }

    private void bidIn(CancellationEntity c, BidStatus status) {
        BidEntity bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", c.getBidId());
        bid.setStatus(status);
        when(bidRepository.findById(c.getBidId())).thenReturn(Optional.of(bid));
    }

    @Test
    void confirmsExpiredPendingAndPublishesEvent_underTheParcelLock() {
        CancellationEntity c = pending();
        bidIn(c, BidStatus.ACCEPTED);
        when(cancellationRepo.findExpiredPending(any())).thenReturn(List.of(c));

        job.run();

        assertThat(c.getNoShowStatus()).isEqualTo(CancellationStatus.CONFIRMED);
        verify(cancellationRepo).save(c);
        ArgumentCaptor<CancellationConfirmedEvent> captor = ArgumentCaptor.forClass(CancellationConfirmedEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().reason()).isEqualTo(CancellationReason.SENDER_NO_SHOW);
        assertThat(captor.getValue().bidId()).isEqualTo(c.getBidId());
        // Verrou du colis AVANT la relecture du statut et du colis.
        InOrder order = inOrder(bidRepository, cancellationRepo);
        order.verify(bidRepository).lockForUpdate(c.getBidId());
        order.verify(cancellationRepo).findNoShowStatusById(c.getId());
        order.verify(bidRepository).findById(c.getBidId());
        verifyNoInteractions(auditService);
    }

    @ParameterizedTest
    @EnumSource(value = CancellationStatus.class, names = {"RESOLVED", "CONTESTED", "CONFIRMED"})
    void declarationChangedConcurrently_isLeftAlone(CancellationStatus now) {
        // Le scan de remise vient de la lever (RESOLVED), ou l'expéditeur a répondu : rien à confirmer.
        CancellationEntity c = pending();
        when(cancellationRepo.findNoShowStatusById(c.getId())).thenReturn(Optional.of(now));
        when(cancellationRepo.findExpiredPending(any())).thenReturn(List.of(c));

        job.run();

        assertThat(c.getNoShowStatus()).isEqualTo(CancellationStatus.PENDING_CONFIRMATION);
        verify(cancellationRepo, never()).save(any());
        verify(bidRepository, never()).findById(any());
        verifyNoInteractions(events, auditService);
    }

    @ParameterizedTest
    @EnumSource(value = BidStatus.class,
            names = {"HANDED_OVER", "IN_TRANSIT", "ARRIVED", "COMPLETED", "PARCEL_REFUSED"})
    void parcelAlreadyHandedOver_closesTheReportWithoutRefundOrPenalty(BidStatus handedOver) {
        CancellationEntity c = pending();
        bidIn(c, handedOver);
        when(cancellationRepo.findExpiredPending(any())).thenReturn(List.of(c));

        job.run();

        assertThat(c.getNoShowStatus()).isEqualTo(CancellationStatus.RESOLVED);
        verify(cancellationRepo).save(c);
        verifyNoInteractions(events);
        verify(auditService).log(eq("CANCELLATION"), eq(c.getId()), eq("SENDER_NO_SHOW_CLOSED_PARCEL_HANDED_OVER"),
                isNull(), eq(java.util.Map.of("bidId", c.getBidId().toString(), "bidStatus", handedOver.name(),
                        "previousStatus", "PENDING_CONFIRMATION")));
    }

    @Test
    void parcelNotFound_confirmsAsBefore() {
        CancellationEntity c = pending();
        when(bidRepository.findById(c.getBidId())).thenReturn(Optional.empty());
        when(cancellationRepo.findExpiredPending(any())).thenReturn(List.of(c));

        job.run();

        assertThat(c.getNoShowStatus()).isEqualTo(CancellationStatus.CONFIRMED);
        verify(events).publishEvent(any(CancellationConfirmedEvent.class));
    }

    @Test
    void onlyTheHandedOverParcelIsSkipped_theOthersAreConfirmed() {
        CancellationEntity handedOver = pending();
        CancellationEntity awaiting = pending();
        bidIn(handedOver, BidStatus.IN_TRANSIT);
        bidIn(awaiting, BidStatus.ACCEPTED);
        when(cancellationRepo.findExpiredPending(any())).thenReturn(List.of(handedOver, awaiting));

        job.run();

        assertThat(handedOver.getNoShowStatus()).isEqualTo(CancellationStatus.RESOLVED);
        assertThat(awaiting.getNoShowStatus()).isEqualTo(CancellationStatus.CONFIRMED);
        ArgumentCaptor<CancellationConfirmedEvent> captor = ArgumentCaptor.forClass(CancellationConfirmedEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().bidId()).isEqualTo(awaiting.getBidId());
    }

    @Test
    void noExpiredPendingIsNoOp() {
        when(cancellationRepo.findExpiredPending(any())).thenReturn(List.of());

        job.run();

        verifyNoInteractions(events, bidRepository);
        verify(cancellationRepo, never()).save(any());
    }
}
