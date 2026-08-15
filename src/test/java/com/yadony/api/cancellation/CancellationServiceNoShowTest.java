package com.yadony.api.cancellation;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.cancellation.events.TravelerNoShowReportedEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.AnnouncementRepository;
import org.springframework.http.HttpStatus;
import com.yadony.api.payments.cash.CommissionProperties;
import com.yadony.api.payments.cash.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests dedicated to {@link CancellationService#reportSenderNoShow(UUID, UUID)} after the
 * product decision to allow the sender-no-show report for Stripe bids (not only cash bids).
 *
 * <p>Case 1 proves the NEW behaviour (a Stripe bid is now accepted and a PENDING_CONFIRMATION
 * cancellation is saved). Cases 2-4 prove the remaining preconditions (status, handover window,
 * duplicate) are still enforced.
 */
@ExtendWith(MockitoExtension.class)
class CancellationServiceNoShowTest {

    @Mock private CancellationRepository cancellationRepository;
    @Mock private RematchSuggestionRepository rematchSuggestionRepository;
    @Mock private BidRepository bidRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private RematchService rematchService;
    @Mock private com.yadony.api.common.StorageService storageService;

    private CancellationService service;

    private static final UUID TRAVELER_ID = UUID.randomUUID();
    private static final UUID BID_ID      = UUID.randomUUID();
    private static final UUID SENDER_ID   = UUID.randomUUID();
    private static final UUID ANNOUNCEMENT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        CommissionProperties commissionProps = new CommissionProperties(
                new BigDecimal("0.12"), new BigDecimal("1.00"), 24);
        service = new CancellationService(
                cancellationRepository, rematchSuggestionRepository,
                bidRepository, announcementRepository,
                userRepository, auditService, eventPublisher, commissionProps, rematchService, storageService);
    }

    private BidEntity bid(BidStatus status, PaymentMethod pm, LocalDateTime handoverEnd) {
        BidEntity bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", BID_ID);
        bid.setPaymentMethod(pm);
        bid.setStatus(status);
        bid.setHandoverDeadline(handoverEnd);
        bid.setSenderId(SENDER_ID);
        bid.setAnnouncementId(ANNOUNCEMENT_ID);
        return bid;
    }

    /**
     * NEW behaviour (regression-critical): a Stripe bid that is ACCEPTED with a passed handover
     * window must produce a PENDING_CONFIRMATION cancellation — exactly what the cash path did.
     * Previously this threw "Le no-show n'est signalable que pour les bids cash."
     */
    @Test
    void stripeBidAcceptedAfterHandover_createsPendingCancellation() {
        BidEntity stripeBid = bid(BidStatus.ACCEPTED, PaymentMethod.STRIPE,
                LocalDateTime.now().minusHours(1));
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(stripeBid));
        when(cancellationRepository.existsByBidIdAndNoShowStatusIn(any(), any())).thenReturn(false);
        when(cancellationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.reportSenderNoShow(BID_ID, TRAVELER_ID);

        ArgumentCaptor<CancellationEntity> captor = ArgumentCaptor.forClass(CancellationEntity.class);
        verify(cancellationRepository).save(captor.capture());
        CancellationEntity saved = captor.getValue();
        assertThat(saved.getNoShowStatus()).isEqualTo(CancellationStatus.PENDING_CONFIRMATION);
        assertThat(saved.getBidId()).isEqualTo(BID_ID);
        assertThat(saved.getCancelledBy()).isEqualTo(TRAVELER_ID);
        assertThat(saved.getReason()).isEqualTo(CancellationReason.SENDER_NO_SHOW.name());
        assertThat(saved.getContestationDeadline()).isNotNull();
    }

    /** A bid already HANDED_OVER (not ACCEPTED) must be rejected. */
    @Test
    void handedOverBid_throws() {
        BidEntity handedOver = bid(BidStatus.HANDED_OVER, PaymentMethod.STRIPE,
                LocalDateTime.now().minusHours(1));
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(handedOver));

        assertThatThrownBy(() -> service.reportSenderNoShow(BID_ID, TRAVELER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACCEPTED");
    }

    /** The handover window must have passed; a future window is rejected. */
    @Test
    void handoverDeadlineInFuture_throws() {
        BidEntity future = bid(BidStatus.ACCEPTED, PaymentMethod.STRIPE,
                LocalDateTime.now().plusHours(1));
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(future));

        assertThatThrownBy(() -> service.reportSenderNoShow(BID_ID, TRAVELER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("remise");
    }

    /** A cancellation already in progress (PENDING_CONFIRMATION/CONFIRMED) blocks a duplicate report. */
    @Test
    void cancellationAlreadyInProgress_throws() {
        BidEntity accepted = bid(BidStatus.ACCEPTED, PaymentMethod.STRIPE,
                LocalDateTime.now().minusHours(1));
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(accepted));
        when(cancellationRepository.existsByBidIdAndNoShowStatusIn(BID_ID,
                List.of(CancellationStatus.PENDING_CONFIRMATION, CancellationStatus.CONFIRMED)))
                .thenReturn(true);

        assertThatThrownBy(() -> service.reportSenderNoShow(BID_ID, TRAVELER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("déjà");
    }

    // ─── reportTravelerNoShow (l'expéditeur signale le voyageur absent) ──────────

    /** Happy path : bid ACCEPTED de l'expéditeur, fenêtre passée → audit + event publié. */
    @Test
    void reportTravelerNoShow_happyPath_logsAuditAndPublishesEvent() {
        BidEntity accepted = bid(BidStatus.ACCEPTED, PaymentMethod.STRIPE,
                LocalDateTime.now().minusHours(1));
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(accepted));

        service.reportTravelerNoShow(BID_ID, SENDER_ID);

        verify(auditService).log(eq("BID"), eq(BID_ID), eq("TRAVELER_NO_SHOW_REPORTED"),
                eq(SENDER_ID), any());
        ArgumentCaptor<TravelerNoShowReportedEvent> captor =
                ArgumentCaptor.forClass(TravelerNoShowReportedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getBidId()).isEqualTo(BID_ID);
        assertThat(captor.getValue().getSenderId()).isEqualTo(SENDER_ID);
    }

    /** Bid introuvable → 404 YadonyBusinessException, aucun event. */
    @Test
    void reportTravelerNoShow_bidNotFound_throwsNotFound() {
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reportTravelerNoShow(BID_ID, SENDER_ID))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
        verify(eventPublisher, never()).publishEvent(any());
    }

    /** Le caller n'est pas l'expéditeur du bid → 403 YadonyBusinessException. */
    @Test
    void reportTravelerNoShow_ownershipMismatch_throwsForbidden() {
        BidEntity accepted = bid(BidStatus.ACCEPTED, PaymentMethod.STRIPE,
                LocalDateTime.now().minusHours(1));
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(accepted));

        UUID intruder = UUID.randomUUID();
        assertThatThrownBy(() -> service.reportTravelerNoShow(BID_ID, intruder))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN);
        verify(eventPublisher, never()).publishEvent(any());
    }

    /** Bid HANDED_OVER (non ACCEPTED) → IllegalStateException. */
    @Test
    void reportTravelerNoShow_handedOverBid_throws() {
        BidEntity handedOver = bid(BidStatus.HANDED_OVER, PaymentMethod.STRIPE,
                LocalDateTime.now().minusHours(1));
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(handedOver));

        assertThatThrownBy(() -> service.reportTravelerNoShow(BID_ID, SENDER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACCEPTED");
        verify(eventPublisher, never()).publishEvent(any());
    }

    /** Date limite de dépôt non encore passée → IllegalStateException. */
    @Test
    void reportTravelerNoShow_handoverDeadlineInFuture_throws() {
        BidEntity future = bid(BidStatus.ACCEPTED, PaymentMethod.STRIPE,
                LocalDateTime.now().plusHours(1));
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(future));

        assertThatThrownBy(() -> service.reportTravelerNoShow(BID_ID, SENDER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("remise");
        verify(eventPublisher, never()).publishEvent(any());
    }
}
