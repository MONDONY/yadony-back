package com.yadony.api.matching.reception;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.ArrivalRules;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidService;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.RevokedTrackingTokenEntity;
import com.yadony.api.matching.RevokedTrackingTokenRepository;
import com.yadony.api.matching.events.BidRecipientChangedEvent;
import com.yadony.api.matching.reception.dto.ChangeRecipientRequest;
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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecipientChangeServiceTest {

    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock UserRepository userRepository;
    @Mock BidRecipientLinkRepository linkRepository;
    @Mock RevokedTrackingTokenRepository revokedTokenRepository;
    @Mock BidService bidService;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;

    private RecipientChangeService service;

    private static final String UID = "uid-sender";
    private static final String OLD_TOKEN = UUID.randomUUID().toString();
    private final UUID senderId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private final UUID oldRecipientId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-01T10:00:00Z");

    private UserEntity sender;
    private AnnouncementEntity announcement;
    private BidEntity bid;

    @BeforeEach
    void setUp() {
        service = new RecipientChangeService(bidRepository, announcementRepository, userRepository,
                linkRepository, revokedTokenRepository, bidService, auditService, eventPublisher,
                Clock.fixed(now, ZoneOffset.UTC));

        sender = new UserEntity();
        ReflectionTestUtils.setField(sender, "id", senderId);
        sender.setFirebaseUid(UID);

        announcement = new AnnouncementEntity();
        ReflectionTestUtils.setField(announcement, "id", UUID.randomUUID());
        announcement.setTravelerId(travelerId);
        announcement.setDepartureDate(LocalDate.of(2026, 10, 10));
        announcement.setArrivalTime(LocalTime.of(18, 30));

        bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", UUID.randomUUID());
        bid.setSenderId(senderId);
        bid.setAnnouncementId(announcement.getId());
        bid.setStatus(BidStatus.IN_TRANSIT);
        bid.setRecipientName("Fatou Diop");
        bid.setRecipientPhone("+221 77 123 45 67");
        bid.setTrackingToken(OLD_TOKEN);
        bid.setConfirmationCode("123456");
        bid.setConfirmationCodeAttempts(2);
        bid.setConfirmationCodeExpiry(LocalDateTime.of(2026, 10, 1, 0, 0));
        bid.setConfirmationCodePublicEnabled(true);
        bid.setConfirmationCodeRefreshCount(3);
        bid.setConfirmationCodeRefreshWindowStart(LocalDateTime.of(2026, 10, 1, 8, 0));

        lenient().when(userRepository.findByFirebaseUid(UID)).thenReturn(Optional.of(sender));
        lenient().when(bidRepository.findByIdForUpdate(bid.getId())).thenReturn(Optional.of(bid));
        lenient().when(announcementRepository.findById(announcement.getId())).thenReturn(Optional.of(announcement));
    }

    private ChangeRecipientRequest request(String name, String phone) {
        return new ChangeRecipientRequest(name, phone);
    }

    private BidRecipientLinkEntity link(ReceptionLinkStatus status) {
        BidRecipientLinkEntity l = new BidRecipientLinkEntity(bid.getId(), oldRecipientId);
        if (status != ReceptionLinkStatus.PENDING) {
            l.respond(status, null);
        }
        when(linkRepository.findByBidId(bid.getId())).thenReturn(Optional.of(l));
        return l;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> auditPayload(String action) {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("BID"), eq(bid.getId()), eq(action), eq(senderId), captor.capture());
        return captor.getValue();
    }

    private BidRecipientChangedEvent publishedEvent() {
        ArgumentCaptor<BidRecipientChangedEvent> captor = ArgumentCaptor.forClass(BidRecipientChangedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        return captor.getValue();
    }

    // ── Même numéro ─────────────────────────────────────────────────────────

    @Test
    void sameNumberAfterNormalisation_updatesNameOnly() {
        service.changeRecipient(bid.getId(), UID, request("  Fatou Ndiaye ", "+221771234567"));

        assertThat(bid.getRecipientName()).isEqualTo("Fatou Ndiaye");
        assertThat(bid.getRecipientPhone()).isEqualTo("+221 77 123 45 67");
        assertThat(bid.getTrackingToken()).isEqualTo(OLD_TOKEN);
        assertThat(bid.getConfirmationCode()).isEqualTo("123456");
        assertThat(bid.isConfirmationCodePublicEnabled()).isTrue();
        assertThat(auditPayload("RECIPIENT_NAME_UPDATED")).containsOnlyKeys("bidId");
        verify(revokedTokenRepository, never()).save(any());
        verify(linkRepository, never()).findByBidId(any());
        verify(eventPublisher, never()).publishEvent(any());
        verify(bidService).getBidAfterOwnMutation(bid.getId(), UID);
    }

    // ── Numéro changé ───────────────────────────────────────────────────────

    @Test
    void newNumber_revokesTokenRegeneratesCodeAndDropsTheLink() {
        BidRecipientLinkEntity l = link(ReceptionLinkStatus.CONFIRMED);

        service.changeRecipient(bid.getId(), UID, request("Awa Sow", "+221781112233"));

        // a. jeton révoqué puis remplacé
        ArgumentCaptor<RevokedTrackingTokenEntity> revoked = ArgumentCaptor.forClass(RevokedTrackingTokenEntity.class);
        verify(revokedTokenRepository).save(revoked.capture());
        assertThat(revoked.getValue().getToken()).isEqualTo(OLD_TOKEN);
        assertThat(revoked.getValue().getBidId()).isEqualTo(bid.getId());
        assertThat(revoked.getValue().getReason()).isEqualTo(RevokedTrackingTokenEntity.Reason.RECIPIENT_CHANGED);
        assertThat(revoked.getValue().getRevokedAt().toInstant()).isEqualTo(now);
        assertThat(bid.getTrackingToken()).isNotEqualTo(OLD_TOKEN);
        assertThat(UUID.fromString(bid.getTrackingToken())).isNotNull();

        // b. code régénéré, hors quota, masqué de la page publique
        assertThat(bid.getConfirmationCode()).matches("\\d{6}");
        assertThat(bid.getConfirmationCodeAttempts()).isZero();
        assertThat(bid.getConfirmationCodeExpiry()).isEqualTo(ArrivalRules.pickupCodeExpiry(announcement));
        assertThat(bid.isConfirmationCodePublicEnabled()).isFalse();
        assertThat(bid.getConfirmationCodeRefreshCount()).isEqualTo(3);
        assertThat(bid.getConfirmationCodeRefreshWindowStart()).isEqualTo(LocalDateTime.of(2026, 10, 1, 8, 0));

        // c. lien soft-deleted
        assertThat(l.getDeletedAt()).isNotNull();
        verify(linkRepository).save(l);

        assertThat(bid.getRecipientName()).isEqualTo("Awa Sow");
        assertThat(bid.getRecipientPhone()).isEqualTo("+221781112233");
        verify(bidRepository).save(bid);

        // d, e. événement après commit
        assertThat(publishedEvent()).isEqualTo(new BidRecipientChangedEvent(bid.getId(), oldRecipientId, travelerId));

        // f. audit sans numéro en clair
        Map<String, Object> payload = auditPayload("RECIPIENT_CHANGED");
        assertThat(payload).containsEntry("previousNumberMasked", "+221 •••• 67")
                .containsEntry("newNumberMasked", "+221 •••• 33")
                .containsEntry("codeRegenerated", "true")
                .containsEntry("trackingTokenRevoked", "true")
                .containsEntry("previousLinkStatus", "CONFIRMED");
        assertThat(payload.toString()).doesNotContain("781112233").doesNotContain("1234567")
                .doesNotContain("Awa").doesNotContain("Fatou");
    }

    @Test
    void newNumber_pendingLink_previousRecipientIsNotified() {
        link(ReceptionLinkStatus.PENDING);
        service.changeRecipient(bid.getId(), UID, request("Awa", "+221781112233"));
        assertThat(publishedEvent().previousRecipientUserId()).isEqualTo(oldRecipientId);
    }

    @Test
    void newNumber_declinedLink_isDroppedWithoutNotifyingItsHolder() {
        BidRecipientLinkEntity l = link(ReceptionLinkStatus.DECLINED);
        service.changeRecipient(bid.getId(), UID, request("Awa", "+221781112233"));
        assertThat(l.getDeletedAt()).isNotNull();
        assertThat(publishedEvent().previousRecipientUserId()).isNull();
        assertThat(auditPayload("RECIPIENT_CHANGED")).containsEntry("previousLinkStatus", "DECLINED");
    }

    @Test
    void newNumber_withoutLink_onlyTravelerAndNewNumberFollow() {
        when(linkRepository.findByBidId(bid.getId())).thenReturn(Optional.empty());
        service.changeRecipient(bid.getId(), UID, request("Awa", "+221781112233"));
        assertThat(publishedEvent()).isEqualTo(new BidRecipientChangedEvent(bid.getId(), null, travelerId));
        assertThat(auditPayload("RECIPIENT_CHANGED")).doesNotContainKey("previousLinkStatus");
        verify(linkRepository, never()).save(any());
    }

    @Test
    void newNumber_beforeDeparture_noCodeToRegenerate() {
        bid.setStatus(BidStatus.ACCEPTED);
        bid.setConfirmationCode(null);
        bid.setConfirmationCodeExpiry(null);
        when(linkRepository.findByBidId(bid.getId())).thenReturn(Optional.empty());

        service.changeRecipient(bid.getId(), UID, request("Awa", "+33612345678"));

        assertThat(bid.getConfirmationCode()).isNull();
        assertThat(bid.getConfirmationCodeExpiry()).isNull();
        assertThat(auditPayload("RECIPIENT_CHANGED")).containsEntry("codeRegenerated", "false");
    }

    @Test
    void localPreviousNumber_isAlwaysTreatedAsDifferent() {
        bid.setRecipientPhone("77 123 45 67");
        when(linkRepository.findByBidId(bid.getId())).thenReturn(Optional.empty());

        service.changeRecipient(bid.getId(), UID, request("Fatou", "+221771234567"));

        assertThat(bid.getRecipientPhone()).isEqualTo("+221771234567");
        assertThat(auditPayload("RECIPIENT_CHANGED")).containsEntry("previousNumberMasked", "+771 •••• 67");
    }

    @Test
    void withoutTrackingToken_nothingToRevoke() {
        bid.setTrackingToken(null);
        when(linkRepository.findByBidId(bid.getId())).thenReturn(Optional.empty());
        when(announcementRepository.findById(announcement.getId())).thenReturn(Optional.empty());
        bid.setConfirmationCode(null);

        service.changeRecipient(bid.getId(), UID, request("Awa", "+221781112233"));

        verify(revokedTokenRepository, never()).save(any());
        assertThat(bid.getTrackingToken()).isNotNull();
        assertThat(publishedEvent().travelerId()).isNull();
        assertThat(auditPayload("RECIPIENT_CHANGED")).containsEntry("trackingTokenRevoked", "false");
    }

    @Test
    void newNumber_withCode_missingAnnouncement_404() {
        when(announcementRepository.findById(announcement.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeRecipient(bid.getId(), UID, request("Awa", "+221781112233")))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("announcement-not-found"));
    }

    // ── Refus ───────────────────────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(value = BidStatus.class, names = {"ACCEPTED", "HANDED_OVER", "IN_TRANSIT", "ARRIVED"},
            mode = EnumSource.Mode.EXCLUDE)
    void statusOutsideTheWindow_409(BidStatus status) {
        bid.setStatus(status);

        assertThatThrownBy(() -> service.changeRecipient(bid.getId(), UID, request("Awa", "+221781112233")))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getErrorCode()).isEqualTo("recipient-change-not-allowed");
                });
        verify(bidRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), anyMap());
    }

    @Test
    void notTheSender_403() {
        UserEntity other = new UserEntity();
        ReflectionTestUtils.setField(other, "id", travelerId);
        when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.changeRecipient(bid.getId(), "uid-traveler", request("Awa", "+221781112233")))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(bid.getRecipientPhone()).isEqualTo("+221 77 123 45 67");
    }

    @Test
    void unknownBidOrUser_404() {
        UUID unknown = UUID.randomUUID();
        when(bidRepository.findByIdForUpdate(unknown)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.changeRecipient(unknown, UID, request("Awa", "+221781112233")))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("bid-not-found"));

        when(userRepository.findByFirebaseUid("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.changeRecipient(bid.getId(), "ghost", request("Awa", "+221781112233")))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("user-not-found"));
    }

    @Test
    void maskedOrUnknown_neverLeaksTheNumber() {
        assertThat(RecipientChangeService.maskedOrUnknown("+221781112233")).isEqualTo("+221 •••• 33");
        assertThat(RecipientChangeService.maskedOrUnknown(null)).isEqualTo("inconnu");
        assertThat(RecipientChangeService.maskedOrUnknown("12")).isEqualTo("inconnu");
    }
}
