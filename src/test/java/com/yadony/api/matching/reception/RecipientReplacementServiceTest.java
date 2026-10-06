package com.yadony.api.matching.reception;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidService;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.dto.BidResponse;
import com.yadony.api.matching.events.RecipientReplacementRequestedEvent;
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
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecipientReplacementServiceTest {

    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock UserRepository userRepository;
    @Mock BidRecipientLinkRepository linkRepository;
    @Mock BidService bidService;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;

    private RecipientReplacementService service;

    private static final String TRAVELER_UID = "uid-traveler";
    private static final String SENDER_UID = "uid-sender";
    private final UUID senderId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-06T10:00:00Z");
    private final LocalDateTime nowUtc = LocalDateTime.ofInstant(now, ZoneOffset.UTC);

    private BidEntity bid;
    private BidRecipientLinkEntity link;

    @BeforeEach
    void setUp() {
        service = new RecipientReplacementService(bidRepository, announcementRepository, userRepository,
                linkRepository, bidService, auditService, eventPublisher, Clock.fixed(now, ZoneOffset.UTC));

        UserEntity traveler = user(travelerId, TRAVELER_UID);
        UserEntity sender = user(senderId, SENDER_UID);

        AnnouncementEntity announcement = new AnnouncementEntity();
        ReflectionTestUtils.setField(announcement, "id", UUID.randomUUID());
        announcement.setTravelerId(travelerId);

        bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", UUID.randomUUID());
        bid.setSenderId(senderId);
        bid.setAnnouncementId(announcement.getId());
        bid.setStatus(BidStatus.IN_TRANSIT);
        bid.setConfirmationCode("123456");

        link = new BidRecipientLinkEntity(bid.getId(), UUID.randomUUID());
        link.respond(ReceptionLinkStatus.DECLINED, OffsetDateTime.parse("2026-10-05T08:00:00Z"));

        lenient().when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(Optional.of(traveler));
        lenient().when(userRepository.findByFirebaseUid(SENDER_UID)).thenReturn(Optional.of(sender));
        lenient().when(bidRepository.findByIdForUpdate(bid.getId())).thenReturn(Optional.of(bid));
        lenient().when(announcementRepository.findById(announcement.getId())).thenReturn(Optional.of(announcement));
        lenient().when(linkRepository.findByBidId(bid.getId())).thenReturn(Optional.of(link));
    }

    @Test
    void traveler_firstRequest_auditsPublishesAndReturnsTheBid() {
        BidResponse response = mock(BidResponse.class);
        when(bidService.getBidAfterOwnMutation(bid.getId(), TRAVELER_UID)).thenReturn(response);

        assertThat(service.requestReplacement(bid.getId(), TRAVELER_UID)).isSameAs(response);

        verify(auditService).log("BID", bid.getId(), "RECIPIENT_REPLACEMENT_REQUESTED", travelerId,
                Map.of("bidId", bid.getId().toString(), "bidStatus", "IN_TRANSIT"));
        verify(eventPublisher).publishEvent(new RecipientReplacementRequestedEvent(bid.getId(), senderId));
        // Le code de retrait n'est pas touché : la remise reste possible.
        assertThat(bid.getConfirmationCode()).isEqualTo("123456");
        verify(bidRepository, never()).save(any());
    }

    @Test
    void secondRequestWithin12h_is429_withNextAllowedDate() {
        when(linkRepository.lastReplacementRequestAt(link)).thenReturn(Optional.of(nowUtc.minusHours(11)));

        var ex = catchThrowableOfType(() -> service.requestReplacement(bid.getId(), TRAVELER_UID),
                YadonyBusinessException.class);

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(ex.getErrorCode()).isEqualTo("recipient-replacement-too-soon");
        assertThat(ex.getProperties()).containsEntry("nextRequestAllowedAt", "2026-10-06T11:00Z");
        verifyNoInteractions(auditService, eventPublisher);
    }

    @Test
    void requestAfter12h_isAccepted() {
        when(linkRepository.lastReplacementRequestAt(link)).thenReturn(Optional.of(nowUtc.minusHours(12)));

        service.requestReplacement(bid.getId(), TRAVELER_UID);

        verify(auditService).log(eq("BID"), eq(bid.getId()), eq("RECIPIENT_REPLACEMENT_REQUESTED"),
                eq(travelerId), any());
        verify(eventPublisher).publishEvent(any(RecipientReplacementRequestedEvent.class));
    }

    @Test
    void sender_is403() {
        assertError(SENDER_UID, HttpStatus.FORBIDDEN, "forbidden");
    }

    @Test
    void stranger_is403() {
        UserEntity stranger = user(UUID.randomUUID(), "uid-stranger");
        when(userRepository.findByFirebaseUid("uid-stranger")).thenReturn(Optional.of(stranger));
        assertError("uid-stranger", HttpStatus.FORBIDDEN, "forbidden");
    }

    @Test
    void tripNotFound_is403() {
        when(announcementRepository.findById(bid.getAnnouncementId())).thenReturn(Optional.empty());
        assertError(TRAVELER_UID, HttpStatus.FORBIDDEN, "forbidden");
    }

    @Test
    void unknownBid_is404() {
        UUID unknown = UUID.randomUUID();
        when(bidRepository.findByIdForUpdate(unknown)).thenReturn(Optional.empty());
        var ex = catchThrowableOfType(() -> service.requestReplacement(unknown, TRAVELER_UID),
                YadonyBusinessException.class);
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getErrorCode()).isEqualTo("bid-not-found");
    }

    @Test
    void unknownUser_is404() {
        var ex = catchThrowableOfType(() -> service.requestReplacement(bid.getId(), "uid-ghost"),
                YadonyBusinessException.class);
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getErrorCode()).isEqualTo("user-not-found");
    }

    @ParameterizedTest
    @EnumSource(value = BidStatus.class, names = {"ACCEPTED", "HANDED_OVER", "IN_TRANSIT", "ARRIVED"},
            mode = EnumSource.Mode.EXCLUDE)
    void statusNotChangeable_is409(BidStatus status) {
        bid.setStatus(status);
        assertError(TRAVELER_UID, HttpStatus.CONFLICT, "recipient-replacement-not-allowed");
    }

    @ParameterizedTest
    @EnumSource(value = ReceptionLinkStatus.class, names = {"PENDING", "CONFIRMED"})
    void linkNotDeclined_is409(ReceptionLinkStatus status) {
        link.respond(status, OffsetDateTime.parse("2026-10-05T08:00:00Z"));
        assertError(TRAVELER_UID, HttpStatus.CONFLICT, "recipient-not-declined");
    }

    @Test
    void noLink_is409() {
        when(linkRepository.findByBidId(bid.getId())).thenReturn(Optional.empty());
        assertError(TRAVELER_UID, HttpStatus.CONFLICT, "recipient-not-declined");
    }

    private void assertError(String uid, HttpStatus status, String code) {
        var ex = catchThrowableOfType(() -> service.requestReplacement(bid.getId(), uid),
                YadonyBusinessException.class);
        assertThat(ex.getStatus()).isEqualTo(status);
        assertThat(ex.getErrorCode()).isEqualTo(code);
        verify(auditService, never()).log(anyString(), any(), anyString(), any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    private static UserEntity user(UUID id, String uid) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", id);
        u.setFirebaseUid(uid);
        return u;
    }
}
