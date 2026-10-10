package com.yadony.api.cancellation;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.cancellation.PrePaymentReleasePort.Outcome;
import com.yadony.api.cancellation.dto.PrePaymentCancellationResponse;
import com.yadony.api.cancellation.events.BidCancelledBeforePaymentEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrePaymentCancellationServiceTest {

    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock UserRepository userRepository;
    @Mock PrePaymentReleasePort releasePort;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock EntityManager entityManager;

    PrePaymentCancellationService service;

    private final UUID senderId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private final UUID bidId = UUID.randomUUID();
    private final UUID announcementId = UUID.randomUUID();
    private BidEntity bid;
    private AnnouncementEntity announcement;

    @BeforeEach
    void setUp() {
        service = new PrePaymentCancellationService(bidRepository, announcementRepository, userRepository,
                releasePort, auditService, eventPublisher, entityManager);
        UserEntity sender = new UserEntity();
        ReflectionTestUtils.setField(sender, "id", senderId);
        lenient().when(userRepository.findByFirebaseUid("uid")).thenReturn(Optional.of(sender));

        bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", bidId);
        bid.setSenderId(senderId);
        bid.setAnnouncementId(announcementId);
        bid.setStatus(BidStatus.AWAITING_PAYMENT);
        bid.setPaymentMethod(PaymentMethod.STRIPE);
        bid.setWeightKg(new BigDecimal("5"));
        bid.setPaymentIntentId("pi_1");
        lenient().when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        lenient().when(entityManager.contains(bid)).thenReturn(true);

        announcement = new AnnouncementEntity();
        ReflectionTestUtils.setField(announcement, "id", announcementId);
        announcement.setTravelerId(travelerId);
        announcement.setAvailableKg(new BigDecimal("0"));
        announcement.setStatus(AnnouncementStatus.FULL);
        lenient().when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement));
        lenient().when(announcementRepository.findByIdForUpdate(announcementId)).thenReturn(Optional.of(announcement));
    }

    @Test
    void directCard_cancelled_travelerNotAware_noCapacityTouched() {
        when(releasePort.releaseBeforeCancellation(bidId, "pi_1", senderId)).thenReturn(Outcome.RELEASED);

        PrePaymentCancellationResponse response = service.cancel("uid", bidId);

        assertThat(response).isEqualTo(new PrePaymentCancellationResponse(bidId, "CANCELLED", false));
        assertThat(bid.getStatus()).isEqualTo(BidStatus.CANCELLED);
        assertThat(bid.getRejectionReason()).isEqualTo(PrePaymentCancellationService.REASON);
        assertThat(bid.getAwaitingPaymentExpiresAt()).isNull();
        verify(entityManager).refresh(bid, LockModeType.PESSIMISTIC_WRITE);
        verify(announcementRepository, never()).findByIdForUpdate(any());
        verify(auditService).log(eq("BID"), eq(bidId), eq(PrePaymentCancellationService.AUDIT_ACTION), eq(senderId),
                argThat((Map<String, Object> p) -> "RELEASED".equals(p.get("paymentOutcome"))
                        && "SENDER".equals(p.get("actor")) && !p.containsKey("releasedKg")));
        BidCancelledBeforePaymentEvent event = publishedEvent();
        assertThat(event.travelerAware()).isFalse();
        assertThat(event.travelerId()).isEqualTo(travelerId);
        assertThat(event.releasedKg()).isNull();
        assertThat(event.paymentMethod()).isEqualTo("STRIPE");
    }

    @Test
    void negotiatedCard_travelerAware() {
        bid.setNegotiatedGrossEur(new BigDecimal("30"));
        when(releasePort.releaseBeforeCancellation(bidId, "pi_1", senderId)).thenReturn(Outcome.NOTHING_TO_RELEASE);

        service.cancel("uid", bidId);

        assertThat(publishedEvent().travelerAware()).isTrue();
    }

    @Test
    void negotiationRound_alone_travelerAware() {
        ReflectionTestUtils.setField(bid, "negotiationRound", 2);
        assertThat(PrePaymentCancellationService.isTravelerAware(bid)).isTrue();
    }

    @Test
    void mobileMoney_capacityGivenBack_travelerAware() {
        bid.setPaymentMethod(PaymentMethod.MOBILE_MONEY);
        when(releasePort.releaseBeforeCancellation(bidId, "pi_1", senderId)).thenReturn(Outcome.RELEASED);

        service.cancel("uid", bidId);

        assertThat(announcement.getAvailableKg()).isEqualByComparingTo("5");
        assertThat(announcement.getStatus()).isEqualTo(AnnouncementStatus.ACTIVE);
        verify(announcementRepository).save(announcement);
        BidCancelledBeforePaymentEvent event = publishedEvent();
        assertThat(event.travelerAware()).isTrue();
        assertThat(event.releasedKg()).isEqualByComparingTo("5");
        verify(auditService).log(eq("BID"), eq(bidId), any(), any(),
                argThat((Map<String, Object> p) -> "5".equals(p.get("releasedKg"))));
    }

    @Test
    void mobileMoney_withoutWeight_nothingGivenBack() {
        bid.setPaymentMethod(PaymentMethod.MOBILE_MONEY);
        bid.setWeightKg(null);
        when(releasePort.releaseBeforeCancellation(bidId, "pi_1", senderId)).thenReturn(Outcome.RELEASED);

        service.cancel("uid", bidId);

        verify(announcementRepository, never()).save(any());
        assertThat(publishedEvent().releasedKg()).isNull();
    }

    @Test
    void mobileMoney_announcementGone_stillCancelled() {
        bid.setPaymentMethod(PaymentMethod.MOBILE_MONEY);
        when(announcementRepository.findByIdForUpdate(announcementId)).thenReturn(Optional.empty());
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.empty());
        when(releasePort.releaseBeforeCancellation(bidId, "pi_1", senderId)).thenReturn(Outcome.RELEASED);

        service.cancel("uid", bidId);

        assertThat(bid.getStatus()).isEqualTo(BidStatus.CANCELLED);
        assertThat(publishedEvent().travelerId()).isNull();
    }

    @Test
    void alreadyCancelled_isIdempotent_noSideEffect() {
        bid.setStatus(BidStatus.CANCELLED);

        PrePaymentCancellationResponse response = service.cancel("uid", bidId);

        assertThat(response.alreadyCancelled()).isTrue();
        verify(releasePort, never()).releaseBeforeCancellation(any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any());
        verify(auditService, never()).log(any(), any(), any(), any(), anyMap());
    }

    @Test
    void expiredAtTheSameInstant_returnsAlreadyCancelled() {
        when(releasePort.releaseBeforeCancellation(bidId, "pi_1", senderId)).thenReturn(Outcome.NOTHING_TO_RELEASE);
        doAnswer(inv -> {
            bid.setStatus(BidStatus.CANCELLED);
            return null;
        }).when(entityManager).refresh(bid, LockModeType.PESSIMISTIC_WRITE);

        assertThat(service.cancel("uid", bidId).alreadyCancelled()).isTrue();
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void promotedUnderTheLock_conflict() {
        when(releasePort.releaseBeforeCancellation(bidId, "pi_1", senderId)).thenReturn(Outcome.NOTHING_TO_RELEASE);
        doAnswer(inv -> {
            bid.setStatus(BidStatus.PAYMENT_ESCROWED);
            return null;
        }).when(entityManager).refresh(bid, LockModeType.PESSIMISTIC_WRITE);

        assertConflict("payment-already-authorized");
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void detachedBid_isReloadedUnderLock() {
        when(entityManager.contains(bid)).thenReturn(false);
        when(entityManager.find(BidEntity.class, bidId, LockModeType.PESSIMISTIC_WRITE)).thenReturn(bid);
        when(releasePort.releaseBeforeCancellation(bidId, "pi_1", senderId)).thenReturn(Outcome.RELEASED);

        service.cancel("uid", bidId);

        assertThat(bid.getStatus()).isEqualTo(BidStatus.CANCELLED);
    }

    @Test
    void paymentAlreadyAuthorized_conflict_bidUntouched() {
        when(releasePort.releaseBeforeCancellation(bidId, "pi_1", senderId)).thenReturn(Outcome.ALREADY_PAID);

        assertConflict("payment-already-authorized");
        assertThat(bid.getStatus()).isEqualTo(BidStatus.AWAITING_PAYMENT);
        verify(bidRepository, never()).save(any());
    }

    @Test
    void paymentInProgress_conflict() {
        when(releasePort.releaseBeforeCancellation(bidId, "pi_1", senderId)).thenReturn(Outcome.PAYMENT_IN_PROGRESS);

        assertConflict("payment-in-progress");
        assertThat(bid.getStatus()).isEqualTo(BidStatus.AWAITING_PAYMENT);
    }

    @ParameterizedTest
    @EnumSource(value = BidStatus.class, names = {"PAYMENT_ESCROWED", "ACCEPTED", "HANDED_OVER", "IN_TRANSIT",
            "ARRIVED", "COMPLETED"})
    void paidStatuses_conflictWithRefundHint(BidStatus status) {
        bid.setStatus(status);
        assertConflict("payment-already-authorized");
        verify(releasePort, never()).releaseBeforeCancellation(any(), any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = BidStatus.class, names = {"PENDING", "REJECTED", "EXPIRED", "NEGOTIATING",
            "NEGOTIATION_CLOSED", "NO_SHOW", "PARCEL_REFUSED"})
    void otherStatuses_conflict(BidStatus status) {
        bid.setStatus(status);
        assertConflict("bid-not-awaiting-payment");
    }

    @Test
    void notTheSender_forbidden() {
        bid.setSenderId(UUID.randomUUID());

        assertThatThrownBy(() -> service.cancel("uid", bidId))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(releasePort, never()).releaseBeforeCancellation(any(), any(), any());
    }

    @Test
    void unknownBid_notFound() {
        UUID other = UUID.randomUUID();
        when(bidRepository.findById(other)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel("uid", other))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void unknownUser_unauthorized() {
        when(userRepository.findByFirebaseUid("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel("ghost", bidId))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    private void assertConflict(String code) {
        assertThatThrownBy(() -> service.cancel("uid", bidId))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getErrorCode()).isEqualTo(code);
                });
    }

    private BidCancelledBeforePaymentEvent publishedEvent() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        return (BidCancelledBeforePaymentEvent) captor.getValue();
    }
}
