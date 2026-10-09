package com.yadony.api.tracking;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditLogRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.tracking.dto.PickupCodeRequestResponse;
import com.yadony.api.tracking.events.ConfirmationCodeRequestedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PickupCodeRequestServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");
    private static final LocalDateTime NOW_UTC = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final String UID = "uid-traveler";

    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;
    @Mock AuditLogRepository auditLogRepository;
    @Mock ApplicationEventPublisher eventPublisher;

    private PickupCodeRequestService service;
    private final UUID bidId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private final UUID senderId = UUID.randomUUID();
    private BidEntity bid;

    @BeforeEach
    void setUp() {
        service = new PickupCodeRequestService(bidRepository, announcementRepository, userRepository,
                auditService, auditLogRepository, eventPublisher, TestMessages.resolver(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        UserEntity traveler = new UserEntity();
        ReflectionTestUtils.setField(traveler, "id", travelerId);
        lenient().when(userRepository.findByFirebaseUid(UID)).thenReturn(Optional.of(traveler));

        AnnouncementEntity announcement = new AnnouncementEntity();
        announcement.setTravelerId(travelerId);
        UUID announcementId = UUID.randomUUID();
        lenient().when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement));

        bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", bidId);
        bid.setAnnouncementId(announcementId);
        bid.setSenderId(senderId);
        bid.setStatus(BidStatus.IN_TRANSIT);
        lenient().when(bidRepository.findByIdForUpdate(bidId)).thenReturn(Optional.of(bid));
        lenient().when(auditLogRepository.findLastActionAt(
                PickupCodeRequestService.AUDIT_ENTITY, bidId, PickupCodeRequestService.AUDIT_ACTION))
                .thenReturn(Optional.empty());
    }

    @AfterEach
    void clearRequest() {
        TestMessages.clearRequest();
    }

    @Test
    void blockedCode_auditsAndNotifiesSender() {
        PickupCodeRequestResponse response = service.requestNewCode(bidId, UID);

        OffsetDateTime requestedAt = NOW.atOffset(ZoneOffset.UTC);
        assertThat(response.requestedAt()).isEqualTo(requestedAt);
        assertThat(response.nextRequestAllowedAt()).isEqualTo(requestedAt.plusMinutes(15));
        verify(auditService).log(PickupCodeRequestService.AUDIT_ENTITY, bidId, PickupCodeRequestService.AUDIT_ACTION,
                travelerId, Map.of("bidId", bidId.toString(), "bidStatus", "IN_TRANSIT", "reason", "CODE_MISSING"));
        verify(eventPublisher).publishEvent(new ConfirmationCodeRequestedEvent(bidId, senderId));
    }

    @Test
    void expiredCode_isAccepted_withExpiredReason() {
        bid.setConfirmationCode("123456");
        bid.setConfirmationCodeExpiry(NOW_UTC.minusMinutes(1));

        service.requestNewCode(bidId, UID);

        verify(auditService).log(eq(PickupCodeRequestService.AUDIT_ENTITY), eq(bidId),
                eq(PickupCodeRequestService.AUDIT_ACTION), eq(travelerId),
                eq(Map.of("bidId", bidId.toString(), "bidStatus", "IN_TRANSIT", "reason", "CODE_EXPIRED")));
    }

    @ParameterizedTest
    @EnumSource(value = BidStatus.class, names = {"HANDED_OVER", "IN_TRANSIT", "ARRIVED"})
    void parcelInTravelerHands_isAccepted(BidStatus status) {
        bid.setStatus(status);
        service.requestNewCode(bidId, UID);
        verify(eventPublisher).publishEvent(new ConfirmationCodeRequestedEvent(bidId, senderId));
    }

    @ParameterizedTest
    @EnumSource(value = BidStatus.class, names = {"HANDED_OVER", "IN_TRANSIT", "ARRIVED"}, mode = EnumSource.Mode.EXCLUDE)
    void parcelNotInTravelerHands_409(BidStatus status) {
        bid.setStatus(status);
        assertThatThrownBy(() -> service.requestNewCode(bidId, UID))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getErrorCode()).isEqualTo("code-request-not-allowed");
                });
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void validCode_409() {
        bid.setConfirmationCode("123456");
        bid.setConfirmationCodeExpiry(NOW_UTC.plusHours(1));
        assertThatThrownBy(() -> service.requestNewCode(bidId, UID))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getErrorCode()).isEqualTo("code-still-valid");
                });
        verify(auditService, never()).log(anyString(), any(), anyString(), any(), anyMap());
    }

    @Test
    void notTheTraveler_403() {
        UserEntity other = new UserEntity();
        ReflectionTestUtils.setField(other, "id", senderId);
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.requestNewCode(bidId, "uid-sender"))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void announcementMissing_403() {
        bid.setAnnouncementId(UUID.randomUUID());
        assertThatThrownBy(() -> service.requestNewCode(bidId, UID))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void unknownBid_404_unknownUser_401() {
        UUID unknown = UUID.randomUUID();
        when(bidRepository.findByIdForUpdate(unknown)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.requestNewCode(unknown, UID))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        when(userRepository.findByFirebaseUid("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.requestNewCode(bidId, "ghost"))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void requestFiveMinutesAgo_429_withRemainingMinutesAndNextAllowedAt() {
        lastRequestAt(NOW_UTC.minusMinutes(5).minusSeconds(30));

        assertThatThrownBy(() -> service.requestNewCode(bidId, UID))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(e.getErrorCode()).isEqualTo("code-request-too-soon");
                    // 9 min 30 s restantes : arrondi à la minute supérieure.
                    assertThat(e.getMessage()).isEqualTo(
                            "Demande déjà envoyée à l'expéditeur. Réessayez dans 10 min.");
                    assertThat(e.getProperties())
                            .containsEntry("retryAfterSeconds", 570L)
                            .containsEntry("nextRequestAllowedAt", "2026-10-09T10:09:30Z");
                });
        verify(eventPublisher, never()).publishEvent(any());
        verify(auditService, never()).log(anyString(), any(), anyString(), any(), anyMap());
    }

    @Test
    void tooSoon_inEnglish() {
        TestMessages.requestWithAcceptLanguage("en");
        lastRequestAt(NOW_UTC.minusMinutes(14));

        assertThatThrownBy(() -> service.requestNewCode(bidId, UID))
                .hasMessage("Request already sent to the sender. Try again in 1 min.");
    }

    @Test
    void requestExactly15MinutesAgo_isAccepted() {
        lastRequestAt(NOW_UTC.minusMinutes(15));
        service.requestNewCode(bidId, UID);
        verify(eventPublisher).publishEvent(new ConfirmationCodeRequestedEvent(bidId, senderId));
    }

    private void lastRequestAt(LocalDateTime at) {
        when(auditLogRepository.findLastActionAt(
                PickupCodeRequestService.AUDIT_ENTITY, bidId, PickupCodeRequestService.AUDIT_ACTION))
                .thenReturn(Optional.of(at));
    }
}
