package com.yadony.api.cancellation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.cancellation.dto.ReturnCodeResponse;
import com.yadony.api.cancellation.events.ParcelReturnedEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.cash.CommissionProperties;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/** Unit tests de confirmReturn / getReturnCode (tranche C — redemption du code de retour). */
@ExtendWith(MockitoExtension.class)
class CancellationServiceReturnCodeTest {

    @Mock private CancellationRepository cancellationRepository;
    @Mock private RematchSuggestionRepository rematchSuggestionRepository;
    @Mock private BidRepository bidRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private RematchService rematchService;
    @Mock private com.yadony.api.common.StorageService storageService;
    @Mock private DeliveryNoShowProcedureService deliveryNoShowProcedure;

    private CancellationService service;

    private static final String FIREBASE = "fb-uid";
    private static final UUID BID_ID = UUID.randomUUID();
    private static final UUID ANN_ID = UUID.randomUUID();
    private static final UUID SENDER_ID = UUID.randomUUID();
    private static final UUID TRAVELER_ID = UUID.randomUUID();
    private static final String CODE = "123456";

    @BeforeEach
    void setUp() {
        service = new CancellationService(
                cancellationRepository, rematchSuggestionRepository, bidRepository,
                announcementRepository, userRepository, auditService, eventPublisher,
                new CommissionProperties(new BigDecimal("0.12"), new BigDecimal("1.00"), 24),
                rematchService, storageService, deliveryNoShowProcedure,
                org.mockito.Mockito.mock(com.yadony.api.requests.repository.NegotiationThreadRepository.class));
    }

    private UserEntity user(UUID id) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", id);
        return u;
    }

    private BidEntity bid(String code, LocalDateTime expiry, int attempts, LocalDateTime returnedAt) {
        BidEntity b = new BidEntity();
        ReflectionTestUtils.setField(b, "id", BID_ID);
        ReflectionTestUtils.setField(b, "senderId", SENDER_ID);
        ReflectionTestUtils.setField(b, "announcementId", ANN_ID);
        ReflectionTestUtils.setField(b, "status", BidStatus.CANCELLED);
        b.setReturnCode(code);
        b.setReturnCodeExpiry(expiry);
        b.setReturnCodeAttempts(attempts);
        b.setReturnDeadline(LocalDateTime.now().plusDays(3));
        b.setReturnedAt(returnedAt);
        return b;
    }

    private AnnouncementEntity ann() {
        AnnouncementEntity a = new AnnouncementEntity();
        ReflectionTestUtils.setField(a, "id", ANN_ID);
        ReflectionTestUtils.setField(a, "travelerId", TRAVELER_ID);
        return a;
    }

    private void stubTraveler(BidEntity bid) {
        when(userRepository.findByFirebaseUid(FIREBASE)).thenReturn(Optional.of(user(TRAVELER_ID)));
        when(bidRepository.findByIdForUpdate(BID_ID)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(ANN_ID)).thenReturn(Optional.of(ann()));
    }

    @Test
    void correct_code_marks_returned_and_publishes_event() {
        stubTraveler(bid(CODE, LocalDateTime.now().plusDays(2), 0, null));

        ReturnCodeResponse res = service.confirmReturn(FIREBASE, BID_ID, CODE);

        ArgumentCaptor<BidEntity> bidCap = ArgumentCaptor.forClass(BidEntity.class);
        verify(bidRepository).save(bidCap.capture());
        assertThat(bidCap.getValue().getReturnedAt()).isNotNull();
        assertThat(bidCap.getValue().getReturnCode()).isNull();
        assertThat(res.returnedAt()).isNotNull();
        verify(eventPublisher).publishEvent(any(ParcelReturnedEvent.class));
    }

    @Test
    void wrong_code_increments_attempts_and_no_event() {
        stubTraveler(bid(CODE, LocalDateTime.now().plusDays(2), 0, null));

        assertThatThrownBy(() -> service.confirmReturn(FIREBASE, BID_ID, "000000"))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        verify(eventPublisher, never()).publishEvent(any(ParcelReturnedEvent.class));
    }

    @Test
    void no_return_code_is_unprocessable() {
        stubTraveler(bid(null, null, 0, null));
        assertThatThrownBy(() -> service.confirmReturn(FIREBASE, BID_ID, CODE))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void already_returned_is_conflict() {
        stubTraveler(bid(CODE, LocalDateTime.now().plusDays(2), 0, LocalDateTime.now().minusHours(1)));
        assertThatThrownBy(() -> service.confirmReturn(FIREBASE, BID_ID, CODE))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void expired_code_is_unprocessable() {
        stubTraveler(bid(CODE, LocalDateTime.now().minusMinutes(1), 0, null));
        assertThatThrownBy(() -> service.confirmReturn(FIREBASE, BID_ID, CODE))
                .isInstanceOf(YadonyBusinessException.class);
    }

    @Test
    void too_many_attempts_is_unprocessable() {
        stubTraveler(bid(CODE, LocalDateTime.now().plusDays(2), 3, null));
        assertThatThrownBy(() -> service.confirmReturn(FIREBASE, BID_ID, CODE))
                .isInstanceOf(YadonyBusinessException.class);
    }

    @Test
    void non_traveler_is_forbidden() {
        when(userRepository.findByFirebaseUid(FIREBASE))
                .thenReturn(Optional.of(user(UUID.randomUUID())));
        when(bidRepository.findByIdForUpdate(BID_ID))
                .thenReturn(Optional.of(bid(CODE, LocalDateTime.now().plusDays(2), 0, null)));
        when(announcementRepository.findById(ANN_ID)).thenReturn(Optional.of(ann()));

        assertThatThrownBy(() -> service.confirmReturn(FIREBASE, BID_ID, CODE))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void get_return_code_by_sender_returns_code() {
        when(userRepository.findByFirebaseUid(FIREBASE)).thenReturn(Optional.of(user(SENDER_ID)));
        when(bidRepository.findById(BID_ID))
                .thenReturn(Optional.of(bid(CODE, LocalDateTime.now().plusDays(2), 0, null)));

        ReturnCodeResponse res = service.getReturnCode(FIREBASE, BID_ID);
        assertThat(res.returnCode()).isEqualTo(CODE);
        assertThat(res.returnDeadline()).isNotNull();
    }

    @Test
    void get_return_code_by_non_sender_is_forbidden() {
        when(userRepository.findByFirebaseUid(FIREBASE))
                .thenReturn(Optional.of(user(UUID.randomUUID())));
        when(bidRepository.findById(BID_ID))
                .thenReturn(Optional.of(bid(CODE, LocalDateTime.now().plusDays(2), 0, null)));

        assertThatThrownBy(() -> service.getReturnCode(FIREBASE, BID_ID))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }
}
