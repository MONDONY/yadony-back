package com.yadony.api.cancellation;

import com.yadony.api.cancellation.events.DeliveryNoShowReportedEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.disputes.events.DisputeOpenedEvent;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.payments.cash.CommissionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CancellationServiceDeliveryNoShowTest {

    @Mock CancellationRepository cancellationRepository;
    @Mock RematchSuggestionRepository rematchSuggestionRepository;
    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock RematchService rematchService;
    @Mock com.yadony.api.common.StorageService storageService;

    CancellationService service;
    static final UUID BID_ID = UUID.randomUUID();
    static final UUID SENDER_ID = UUID.randomUUID();
    static final UUID TRAVELER_ID = UUID.randomUUID();
    static final UUID ANNOUNCEMENT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        CommissionProperties props = new CommissionProperties(BigDecimal.ZERO, BigDecimal.ZERO, 24);
        service = new CancellationService(cancellationRepository, rematchSuggestionRepository,
                bidRepository, announcementRepository, userRepository, auditService, eventPublisher, props,
                rematchService, storageService);
    }

    private BidEntity inTransitBid(LocalDateTime departureDate) {
        BidEntity bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", BID_ID);
        bid.setSenderId(SENDER_ID);
        bid.setAnnouncementId(ANNOUNCEMENT_ID);
        bid.setStatus(BidStatus.IN_TRANSIT);
        return bid;
    }

    private AnnouncementEntity announcement(java.time.LocalDate departureDate) {
        AnnouncementEntity a = new AnnouncementEntity();
        ReflectionTestUtils.setField(a, "id", ANNOUNCEMENT_ID);
        a.setTravelerId(TRAVELER_ID);
        a.setDepartureDate(departureDate);
        return a;
    }

    @Test
    void reportDeliveryNoShow_rejectsIfBidNotInTransit() {
        BidEntity bid = inTransitBid(null);
        bid.setStatus(BidStatus.ACCEPTED);
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));

        assertThatThrownBy(() -> service.reportDeliveryNoShow(BID_ID, TRAVELER_ID))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(ex -> assertThat(((YadonyBusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    /** Régression C3 : les signalements d'absence à la livraison ne se déclenchent
     *  qu'à destination, donc sur un bid que le voyageur vient de marquer ARRIVED.
     *  La garde stricte {@code == IN_TRANSIT} bloquait donc TOUS les signalements réels. */
    @Test
    void reportDeliveryNoShow_acceptsArrivedBid() {
        BidEntity bid = inTransitBid(null);
        bid.setStatus(BidStatus.ARRIVED);
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(ANNOUNCEMENT_ID))
                .thenReturn(Optional.of(announcement(java.time.LocalDate.now().minusDays(1))));
        when(cancellationRepository.existsByBidIdAndScopeAndNoShowStatusIn(any(), any(), any()))
                .thenReturn(false);
        when(cancellationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CancellationEntity result = service.reportDeliveryNoShow(BID_ID, TRAVELER_ID);

        assertThat(result.getScope()).isEqualTo(CancellationScope.DELIVERY);
        assertThat(result.getNoShowStatus()).isEqualTo(CancellationStatus.PENDING_CONFIRMATION);
        verify(eventPublisher).publishEvent(any(DeliveryNoShowReportedEvent.class));
    }

    /** Régression C3, versant expéditeur : « voyageur absent à la livraison » se
     *  signale lui aussi sur un bid ARRIVED. */
    @Test
    void reportTravelerDeliveryNoShow_acceptsArrivedBid() {
        BidEntity bid = inTransitBid(null);
        bid.setStatus(BidStatus.ARRIVED);
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(ANNOUNCEMENT_ID))
                .thenReturn(Optional.of(announcement(java.time.LocalDate.now().minusDays(1))));
        when(cancellationRepository.existsByBidIdAndScopeAndNoShowStatusIn(any(), any(), any()))
                .thenReturn(false);
        when(cancellationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CancellationEntity result = service.reportTravelerDeliveryNoShow(BID_ID, SENDER_ID);

        assertThat(result.getScope()).isEqualTo(CancellationScope.DELIVERY);
        assertThat(result.getNoShowStatus()).isEqualTo(CancellationStatus.PENDING_CONFIRMATION);
    }

    @Test
    void reportDeliveryNoShow_rejectsIfTripNotYetDeparted() {
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(inTransitBid(null)));
        when(announcementRepository.findById(ANNOUNCEMENT_ID))
                .thenReturn(Optional.of(announcement(java.time.LocalDate.now().plusDays(1))));

        assertThatThrownBy(() -> service.reportDeliveryNoShow(BID_ID, TRAVELER_ID))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(ex -> assertThat(((YadonyBusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void reportDeliveryNoShow_rejectsIfAlreadyPendingOrContested() {
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(inTransitBid(null)));
        when(announcementRepository.findById(ANNOUNCEMENT_ID))
                .thenReturn(Optional.of(announcement(java.time.LocalDate.now().minusDays(1))));
        when(cancellationRepository.existsByBidIdAndScopeAndNoShowStatusIn(
                BID_ID, CancellationScope.DELIVERY,
                List.of(CancellationStatus.PENDING_CONFIRMATION, CancellationStatus.CONTESTED)))
                .thenReturn(true);

        assertThatThrownBy(() -> service.reportDeliveryNoShow(BID_ID, TRAVELER_ID))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(ex -> assertThat(((YadonyBusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void reportDeliveryNoShow_createsPendingCancellationAndPublishesEvent() {
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(inTransitBid(null)));
        when(announcementRepository.findById(ANNOUNCEMENT_ID))
                .thenReturn(Optional.of(announcement(java.time.LocalDate.now().minusDays(1))));
        when(cancellationRepository.existsByBidIdAndScopeAndNoShowStatusIn(any(), any(), any()))
                .thenReturn(false);
        when(cancellationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CancellationEntity result = service.reportDeliveryNoShow(BID_ID, TRAVELER_ID);

        assertThat(result.getScope()).isEqualTo(CancellationScope.DELIVERY);
        assertThat(result.getNoShowStatus()).isEqualTo(CancellationStatus.PENDING_CONFIRMATION);
        assertThat(result.getContestationDeadline()).isAfter(OffsetDateTime.now());
        verify(eventPublisher).publishEvent(any(DeliveryNoShowReportedEvent.class));
    }

    @Test
    void reportDeliveryNoShow_forbiddenIfNotAssignedTraveler() {
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(inTransitBid(null)));
        when(announcementRepository.findById(ANNOUNCEMENT_ID))
                .thenReturn(Optional.of(announcement(java.time.LocalDate.now().minusDays(1))));

        assertThatThrownBy(() -> service.reportDeliveryNoShow(BID_ID, UUID.randomUUID()))
                .isInstanceOf(com.yadony.api.common.YadonyBusinessException.class);
    }

    @Test
    void reportTravelerDeliveryNoShow_forbiddenIfNotSenderOfBid() {
        BidEntity bid = inTransitBid(null);
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid));

        assertThatThrownBy(() -> service.reportTravelerDeliveryNoShow(BID_ID, UUID.randomUUID()))
                .isInstanceOf(com.yadony.api.common.YadonyBusinessException.class);
    }

    @Test
    void reportTravelerDeliveryNoShow_createsPendingCancellation() {
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(inTransitBid(null)));
        when(announcementRepository.findById(ANNOUNCEMENT_ID))
                .thenReturn(Optional.of(announcement(java.time.LocalDate.now().minusDays(1))));
        when(cancellationRepository.existsByBidIdAndScopeAndNoShowStatusIn(any(), any(), any()))
                .thenReturn(false);
        when(cancellationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CancellationEntity result = service.reportTravelerDeliveryNoShow(BID_ID, SENDER_ID);

        assertThat(result.getScope()).isEqualTo(CancellationScope.DELIVERY);
        assertThat(result.getCancelledBy()).isEqualTo(SENDER_ID);
        verify(eventPublisher).publishEvent(any(DeliveryNoShowReportedEvent.class));
    }

    @Test
    void contestDeliveryNoShow_rejectsIfDeadlinePassed() {
        CancellationEntity c = new CancellationEntity();
        c.setBidId(BID_ID);
        c.setScope(CancellationScope.DELIVERY);
        c.setReason("RECIPIENT_NO_SHOW");
        c.setContestationDeadline(OffsetDateTime.now().minusHours(1));
        when(cancellationRepository.findByBidIdAndScope(BID_ID, CancellationScope.DELIVERY))
                .thenReturn(Optional.of(c));

        assertThatThrownBy(() -> service.contestDeliveryNoShow(BID_ID, SENDER_ID))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(ex -> assertThat(((YadonyBusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void contestDeliveryNoShow_bySender_publishesRecipientNoShowContestedDispute() {
        CancellationEntity c = new CancellationEntity();
        c.setBidId(BID_ID);
        c.setScope(CancellationScope.DELIVERY);
        c.setReason("RECIPIENT_NO_SHOW"); // signalé par le voyageur → contesté par le sender
        c.setContestationDeadline(OffsetDateTime.now().plusHours(1));
        when(cancellationRepository.findByBidIdAndScope(BID_ID, CancellationScope.DELIVERY))
                .thenReturn(Optional.of(c));
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(inTransitBid(null)));
        when(announcementRepository.findById(ANNOUNCEMENT_ID))
                .thenReturn(Optional.of(announcement(java.time.LocalDate.now().minusDays(1))));

        service.contestDeliveryNoShow(BID_ID, SENDER_ID);

        assertThat(c.getNoShowStatus()).isEqualTo(CancellationStatus.CONTESTED);
        verify(eventPublisher).publishEvent(argThat((DisputeOpenedEvent e) ->
                "RECIPIENT_NO_SHOW_CONTESTED".equals(e.getType())
                        && e.getBidId().equals(BID_ID)));
    }

    @Test
    void contestDeliveryNoShow_byTraveler_publishesTravelerDeliveryNoShowContestedDispute() {
        CancellationEntity c = new CancellationEntity();
        c.setBidId(BID_ID);
        c.setScope(CancellationScope.DELIVERY);
        c.setReason("TRAVELER_DELIVERY_NO_SHOW"); // signalé par l'expéditeur → contesté par le voyageur
        c.setContestationDeadline(OffsetDateTime.now().plusHours(1));
        when(cancellationRepository.findByBidIdAndScope(BID_ID, CancellationScope.DELIVERY))
                .thenReturn(Optional.of(c));
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(inTransitBid(null)));
        when(announcementRepository.findById(ANNOUNCEMENT_ID))
                .thenReturn(Optional.of(announcement(java.time.LocalDate.now().minusDays(1))));

        service.contestDeliveryNoShow(BID_ID, TRAVELER_ID);

        assertThat(c.getNoShowStatus()).isEqualTo(CancellationStatus.CONTESTED);
        verify(eventPublisher).publishEvent(argThat((DisputeOpenedEvent e) ->
                "TRAVELER_DELIVERY_NO_SHOW_CONTESTED".equals(e.getType())));
    }

    @Test
    void contestDeliveryNoShow_forbiddenIfCallerNotSenderForRecipientNoShow() {
        CancellationEntity c = new CancellationEntity();
        c.setBidId(BID_ID);
        c.setScope(CancellationScope.DELIVERY);
        c.setReason("RECIPIENT_NO_SHOW");
        c.setContestationDeadline(OffsetDateTime.now().plusHours(1));
        when(cancellationRepository.findByBidIdAndScope(BID_ID, CancellationScope.DELIVERY))
                .thenReturn(Optional.of(c));
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(inTransitBid(null)));
        when(announcementRepository.findById(ANNOUNCEMENT_ID))
                .thenReturn(Optional.of(announcement(java.time.LocalDate.now().minusDays(1))));

        assertThatThrownBy(() -> service.contestDeliveryNoShow(BID_ID, UUID.randomUUID()))
                .isInstanceOf(com.yadony.api.common.YadonyBusinessException.class);
    }

    @Test
    void contestDeliveryNoShow_forbiddenIfCallerNotTravelerForTravelerDeliveryNoShow() {
        CancellationEntity c = new CancellationEntity();
        c.setBidId(BID_ID);
        c.setScope(CancellationScope.DELIVERY);
        c.setReason("TRAVELER_DELIVERY_NO_SHOW");
        c.setContestationDeadline(OffsetDateTime.now().plusHours(1));
        when(cancellationRepository.findByBidIdAndScope(BID_ID, CancellationScope.DELIVERY))
                .thenReturn(Optional.of(c));
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(inTransitBid(null)));
        when(announcementRepository.findById(ANNOUNCEMENT_ID))
                .thenReturn(Optional.of(announcement(java.time.LocalDate.now().minusDays(1))));

        assertThatThrownBy(() -> service.contestDeliveryNoShow(BID_ID, UUID.randomUUID()))
                .isInstanceOf(com.yadony.api.common.YadonyBusinessException.class);
    }
}
