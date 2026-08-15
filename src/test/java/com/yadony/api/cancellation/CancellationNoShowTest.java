package com.yadony.api.cancellation;

import com.yadony.api.cancellation.events.CancellationConfirmedEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.disputes.events.DisputeOpenedEvent;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.payments.cash.CommissionProperties;
import com.yadony.api.payments.cash.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CancellationNoShowTest {

    @Mock private CancellationRepository cancellationRepository;
    @Mock private RematchSuggestionRepository rematchSuggestionRepository;
    @Mock private BidRepository bidRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private com.yadony.api.auth.UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private RematchService rematchService;
    @Mock private com.yadony.api.common.StorageService storageService;

    private CancellationService service;

    private static final UUID TRAVELER_ID = UUID.randomUUID();
    private static final UUID BID_ID      = UUID.randomUUID();
    private static final UUID SENDER_ID   = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        CommissionProperties commissionProps = new CommissionProperties(
                new BigDecimal("0.12"), new BigDecimal("1.00"), 24);
        service = new CancellationService(
                cancellationRepository, rematchSuggestionRepository,
                bidRepository, announcementRepository,
                userRepository, auditService, eventPublisher, commissionProps, rematchService, storageService);
    }

    private static final UUID ANNOUNCEMENT_ID = UUID.randomUUID();

    private BidEntity cashBid(BidStatus status, LocalDateTime handoverEnd) {
        BidEntity bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", BID_ID);
        bid.setPaymentMethod(PaymentMethod.CASH);
        bid.setStatus(status);
        bid.setHandoverDeadline(handoverEnd);
        bid.setSenderId(SENDER_ID);
        bid.setAnnouncementId(ANNOUNCEMENT_ID);
        return bid;
    }

    private AnnouncementEntity announcementWithTraveler(UUID travelerId) {
        AnnouncementEntity a = new AnnouncementEntity();
        ReflectionTestUtils.setField(a, "id", ANNOUNCEMENT_ID);
        a.setTravelerId(travelerId);
        return a;
    }

    // ═══════════════════════════════ reportSenderNoShow ═══════════════════════

    @Nested
    class ReportSenderNoShow {

        @Test
        void allowsStripeBid() {
            // Produit : le signalement « expéditeur absent » couvre désormais cash ET Stripe.
            BidEntity bid = cashBid(BidStatus.ACCEPTED, LocalDateTime.now().minusHours(1));
            bid.setPaymentMethod(PaymentMethod.STRIPE);
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(cancellationRepository.existsByBidIdAndNoShowStatusIn(any(), any())).thenReturn(false);
            when(cancellationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.reportSenderNoShow(BID_ID, TRAVELER_ID);

            ArgumentCaptor<CancellationEntity> captor = ArgumentCaptor.forClass(CancellationEntity.class);
            verify(cancellationRepository).save(captor.capture());
            assertThat(captor.getValue().getNoShowStatus())
                    .isEqualTo(CancellationStatus.PENDING_CONFIRMATION);
        }

        @Test
        void failsWhenBidNotAccepted() {
            BidEntity bid = cashBid(BidStatus.PENDING, LocalDateTime.now().minusHours(1));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));

            assertThatThrownBy(() -> service.reportSenderNoShow(BID_ID, TRAVELER_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ACCEPTED");
        }

        @Test
        void failsBeforeHandoverDeadline() {
            BidEntity bid = cashBid(BidStatus.ACCEPTED, LocalDateTime.now().plusHours(2));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));

            assertThatThrownBy(() -> service.reportSenderNoShow(BID_ID, TRAVELER_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("remise");
        }

        @Test
        void failsWhenCancellationAlreadyPending() {
            BidEntity bid = cashBid(BidStatus.ACCEPTED, LocalDateTime.now().minusHours(1));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(cancellationRepository.existsByBidIdAndNoShowStatusIn(BID_ID,
                    List.of(CancellationStatus.PENDING_CONFIRMATION, CancellationStatus.CONFIRMED)))
                    .thenReturn(true);

            assertThatThrownBy(() -> service.reportSenderNoShow(BID_ID, TRAVELER_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("déjà");
        }

        @Test
        void createsPendingCancellationWithContestationDeadline() {
            BidEntity bid = cashBid(BidStatus.ACCEPTED, LocalDateTime.now().minusHours(1));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
            when(cancellationRepository.existsByBidIdAndNoShowStatusIn(any(), any())).thenReturn(false);
            when(cancellationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.reportSenderNoShow(BID_ID, TRAVELER_ID);

            ArgumentCaptor<CancellationEntity> captor = ArgumentCaptor.forClass(CancellationEntity.class);
            verify(cancellationRepository).save(captor.capture());
            CancellationEntity saved = captor.getValue();
            assertThat(saved.getNoShowStatus()).isEqualTo(CancellationStatus.PENDING_CONFIRMATION);
            assertThat(saved.getReason()).isEqualTo(CancellationReason.SENDER_NO_SHOW.name());
            assertThat(saved.getContestationDeadline()).isNotNull();
            assertThat(saved.getContestationDeadline()).isAfterOrEqualTo(
                    java.time.OffsetDateTime.now().plusHours(23));
        }
    }

    // ═══════════════════════════════ confirmSenderNoShow ══════════════════════

    @Nested
    class ConfirmSenderNoShow {

        @Test
        void confirmsAndPublishesCancellationConfirmedEvent() {
            CancellationEntity c = new CancellationEntity();
            ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
            c.setBidId(BID_ID);
            c.setNoShowStatus(CancellationStatus.PENDING_CONFIRMATION);
            c.setContestationDeadline(java.time.OffsetDateTime.now().plusHours(20));

            when(cancellationRepository.findByBidId(BID_ID)).thenReturn(Optional.of(c));
            when(cancellationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.confirmSenderNoShow(BID_ID);

            assertThat(c.getNoShowStatus()).isEqualTo(CancellationStatus.CONFIRMED);

            ArgumentCaptor<CancellationConfirmedEvent> captor =
                    ArgumentCaptor.forClass(CancellationConfirmedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().bidId()).isEqualTo(BID_ID);
            assertThat(captor.getValue().reason()).isEqualTo(CancellationReason.SENDER_NO_SHOW);
        }

        @Test
        void isNoOpWhenAlreadyConfirmed() {
            CancellationEntity c = new CancellationEntity();
            c.setBidId(BID_ID);
            c.setNoShowStatus(CancellationStatus.CONFIRMED);

            when(cancellationRepository.findByBidId(BID_ID)).thenReturn(Optional.of(c));

            service.confirmSenderNoShow(BID_ID);

            verify(cancellationRepository, never()).save(any());
            verifyNoInteractions(eventPublisher);
        }

        @Test
        void throws404WhenNoCancellation_notNoSuchElement() {
            // Régression : un bid sans annulation en attente levait
            // NoSuchElementException (orElseThrow sans argument) → 500. Doit
            // désormais être une YadonyBusinessException 404 (détecté par le load
            // test all-endpoints).
            when(cancellationRepository.findByBidId(BID_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.confirmSenderNoShow(BID_ID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        void senderOverload_ownerConfirms() {
            CancellationEntity c = new CancellationEntity();
            ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
            c.setBidId(BID_ID);
            c.setNoShowStatus(CancellationStatus.PENDING_CONFIRMATION);

            when(bidRepository.findById(BID_ID)).thenReturn(
                    Optional.of(cashBid(BidStatus.ACCEPTED, LocalDateTime.now().minusHours(1))));
            when(cancellationRepository.findByBidId(BID_ID)).thenReturn(Optional.of(c));
            when(cancellationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.confirmSenderNoShow(BID_ID, SENDER_ID);

            assertThat(c.getNoShowStatus()).isEqualTo(CancellationStatus.CONFIRMED);
            verify(eventPublisher).publishEvent(any(CancellationConfirmedEvent.class));
        }

        @Test
        void senderOverload_nonOwnerForbidden() {
            when(bidRepository.findById(BID_ID)).thenReturn(
                    Optional.of(cashBid(BidStatus.ACCEPTED, LocalDateTime.now().minusHours(1))));

            assertThatThrownBy(() -> service.confirmSenderNoShow(BID_ID, UUID.randomUUID()))
                    .isInstanceOf(com.yadony.api.common.YadonyBusinessException.class)
                    .hasMessageContaining("expéditeur");
        }
    }

    // ═══════════════════════════════ contestSenderNoShow ══════════════════════

    @Nested
    class ContestSenderNoShow {

        @Test
        void publishesDisputeOpenedEvent() {
            CancellationEntity c = new CancellationEntity();
            ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
            c.setBidId(BID_ID);
            c.setNoShowStatus(CancellationStatus.PENDING_CONFIRMATION);
            c.setContestationDeadline(java.time.OffsetDateTime.now().plusHours(10));

            when(bidRepository.findById(BID_ID))
                    .thenReturn(Optional.of(cashBid(BidStatus.ACCEPTED, LocalDateTime.now().minusHours(1))));
            when(cancellationRepository.findByBidId(BID_ID)).thenReturn(Optional.of(c));
            when(cancellationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(announcementRepository.findById(ANNOUNCEMENT_ID))
                    .thenReturn(Optional.of(announcementWithTraveler(TRAVELER_ID)));

            service.contestSenderNoShow(BID_ID, SENDER_ID);

            ArgumentCaptor<DisputeOpenedEvent> captor =
                    ArgumentCaptor.forClass(DisputeOpenedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            DisputeOpenedEvent event = captor.getValue();
            assertThat(event.getBidId()).isEqualTo(BID_ID);
            assertThat(event.getSenderId()).isEqualTo(SENDER_ID);
            assertThat(event.getTravelerId()).isEqualTo(TRAVELER_ID);
        }

        @Test
        void failsAfterDeadline() {
            BidEntity bid = cashBid(BidStatus.ACCEPTED, LocalDateTime.now().minusHours(1));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));

            CancellationEntity c = new CancellationEntity();
            c.setBidId(BID_ID);
            c.setNoShowStatus(CancellationStatus.PENDING_CONFIRMATION);
            c.setContestationDeadline(java.time.OffsetDateTime.now().minusHours(1));

            when(cancellationRepository.findByBidId(BID_ID)).thenReturn(Optional.of(c));

            assertThatThrownBy(() -> service.contestSenderNoShow(BID_ID, SENDER_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("délai");
        }

        @Test
        void failsIfDeadlineIsNull() {
            BidEntity bid = cashBid(BidStatus.ACCEPTED, LocalDateTime.now().minusHours(1));
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));

            CancellationEntity c = new CancellationEntity();
            c.setBidId(BID_ID);
            c.setNoShowStatus(CancellationStatus.PENDING_CONFIRMATION);
            c.setContestationDeadline(null);

            when(cancellationRepository.findByBidId(BID_ID)).thenReturn(Optional.of(c));

            assertThatThrownBy(() -> service.contestSenderNoShow(BID_ID, SENDER_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("délai");
        }

        @Test
        void failsWhenCallerIsNotSender() {
            UUID otherUserId = UUID.randomUUID();
            BidEntity bid = cashBid(BidStatus.ACCEPTED, LocalDateTime.now().minusHours(1));
            // SENDER_ID is set in cashBid(); otherUserId != SENDER_ID
            when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));

            assertThatThrownBy(() -> service.contestSenderNoShow(BID_ID, otherUserId))
                    .isInstanceOf(com.yadony.api.common.YadonyBusinessException.class)
                    .hasMessageContaining("expéditeur");
        }

        @Test
        void marksAsContestedBeforeDeadline() {
            CancellationEntity c = new CancellationEntity();
            ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
            c.setBidId(BID_ID);
            c.setNoShowStatus(CancellationStatus.PENDING_CONFIRMATION);
            c.setContestationDeadline(java.time.OffsetDateTime.now().plusHours(10));

            when(bidRepository.findById(BID_ID))
                    .thenReturn(Optional.of(cashBid(BidStatus.ACCEPTED, LocalDateTime.now().minusHours(1))));
            when(cancellationRepository.findByBidId(BID_ID)).thenReturn(Optional.of(c));
            when(cancellationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(announcementRepository.findById(ANNOUNCEMENT_ID))
                    .thenReturn(Optional.of(announcementWithTraveler(TRAVELER_ID)));

            service.contestSenderNoShow(BID_ID, SENDER_ID);

            assertThat(c.getNoShowStatus()).isEqualTo(CancellationStatus.CONTESTED);
        }
    }
}
