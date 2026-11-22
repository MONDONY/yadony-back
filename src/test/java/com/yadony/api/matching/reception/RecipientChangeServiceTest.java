package com.yadony.api.matching.reception;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.RecipientAccount;
import com.yadony.api.common.RecipientAccountResolver;
import com.yadony.api.matching.BidRecipientDesignation;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.ArrivalRules;
import com.yadony.api.matching.TripDelayEntity;
import com.yadony.api.matching.TripDelayRepository;
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
import static org.mockito.Mockito.verifyNoInteractions;
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
    @Mock RecipientAccountResolver resolver;

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
        service.setDesignation(new BidRecipientDesignation(resolver, auditService));

        sender = new UserEntity();
        ReflectionTestUtils.setField(sender, "id", senderId);
        sender.setFirebaseUid(UID);

        announcement = new AnnouncementEntity();
        ReflectionTestUtils.setField(announcement, "id", UUID.randomUUID());
        announcement.setTravelerId(travelerId);
        // Arrivée toujours à venir : le code régénéré vaut au moins 24 h à partir de
        // l'heure réelle (renewedPickupCodeExpiry), une date figée expirait le test.
        announcement.setDepartureDate(LocalDate.now(java.time.ZoneOffset.UTC).plusDays(30));
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
    void newNumber_afterANonBoatDelay_callsTheCodeOnTheDelayedRangeEnd() {
        // M1 : la fin du retard annoncé (l'annonce hors bateau n'a que le début) cale le code régénéré.
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        announcement.setDepartureDate(today);
        announcement.setArrivalDate(today.plusDays(3));
        announcement.setArrivalTime(null);
        TripDelayRepository delays = org.mockito.Mockito.mock(TripDelayRepository.class);
        TripDelayEntity delay = new TripDelayEntity();
        delay.setNewArrivalDate(today.plusDays(8));
        delay.setNewArrivalDateMax(today.plusDays(10));
        when(delays.findFirstByAnnouncementIdOrderByCreatedAtDesc(announcement.getId()))
                .thenReturn(Optional.of(delay));
        service.setTripDelayRepository(delays);
        link(ReceptionLinkStatus.CONFIRMED);

        service.changeRecipient(bid.getId(), UID, request("Awa Sow", "+221781112233"));

        assertThat(bid.getConfirmationCodeExpiry()).isEqualTo(today.plusDays(13).atStartOfDay());
    }

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

    // ── Désignation par identifiant ─────────────────────────────────────────

    private final UUID accountId = UUID.randomUUID();

    private ChangeRecipientRequest byUsername(String username) {
        return new ChangeRecipientRequest(null, null, username);
    }

    private void assertProblem(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, HttpStatus status, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getErrorCode()).isEqualTo(code);
        });
    }

    @Test
    void numberToUsername_setsAccount_nullPhone_rotatesTokenAndCode_publishesEvent() {
        when(resolver.resolve(senderId, "awa.diallo")).thenReturn(new RecipientAccount(accountId, "awa.diallo"));
        BidRecipientLinkEntity l = link(ReceptionLinkStatus.CONFIRMED);

        service.changeRecipient(bid.getId(), UID, byUsername("awa.diallo"));

        assertThat(bid.getRecipientUserId()).isEqualTo(accountId);
        assertThat(bid.getRecipientPhone()).isNull();
        assertThat(bid.getRecipientName()).isEqualTo("@awa.diallo");
        ArgumentCaptor<RevokedTrackingTokenEntity> revoked = ArgumentCaptor.forClass(RevokedTrackingTokenEntity.class);
        verify(revokedTokenRepository).save(revoked.capture());
        assertThat(revoked.getValue().getToken()).isEqualTo(OLD_TOKEN);
        assertThat(bid.getTrackingToken()).isNotEqualTo(OLD_TOKEN);
        assertThat(bid.getConfirmationCode()).matches("\\d{6}").isNotEqualTo("123456");
        assertThat(bid.isConfirmationCodePublicEnabled()).isFalse();
        assertThat(l.getDeletedAt()).isNotNull();
        verify(bidRepository).save(bid);
        assertThat(publishedEvent()).isEqualTo(new BidRecipientChangedEvent(bid.getId(), oldRecipientId, travelerId));
        Map<String, Object> payload = auditPayload("RECIPIENT_CHANGED");
        assertThat(payload).containsEntry("newRecipientUserId", accountId.toString())
                .containsEntry("previousNumberMasked", "+221 •••• 67").doesNotContainKey("newNumberMasked");
        assertThat(payload.toString()).doesNotContain("1234567");
        verify(auditService).log("BID", bid.getId(), "RECIPIENT_DESIGNATED_BY_USERNAME", senderId,
                Map.of("recipientUserId", accountId.toString()));
    }

    @Test
    void usernameToNumber_clearsTheAccount() {
        bid.setRecipientUserId(accountId);
        bid.setRecipientName("@awa.diallo");
        bid.setRecipientPhone(null);
        when(linkRepository.findByBidId(bid.getId())).thenReturn(Optional.empty());

        service.changeRecipient(bid.getId(), UID, request("Awa Sow", "+221781112233"));

        assertThat(bid.getRecipientUserId()).isNull();
        assertThat(bid.getRecipientPhone()).isEqualTo("+221781112233");
        assertThat(bid.getRecipientName()).isEqualTo("Awa Sow");
        assertThat(bid.getTrackingToken()).isNotEqualTo(OLD_TOKEN);
        assertThat(auditPayload("RECIPIENT_CHANGED"))
                .containsEntry("previousRecipientUserId", accountId.toString())
                .containsEntry("previousNumberMasked", "inconnu");
    }

    @Test
    void sameUsername_onlyRefreshesTheLabel() {
        bid.setRecipientUserId(accountId);
        bid.setRecipientName("@ancien.nom");
        bid.setRecipientPhone(null);
        when(resolver.resolve(senderId, "awa.diallo")).thenReturn(new RecipientAccount(accountId, "awa.diallo"));

        service.changeRecipient(bid.getId(), UID, byUsername("awa.diallo"));

        assertThat(bid.getRecipientName()).isEqualTo("@awa.diallo");
        assertThat(bid.getTrackingToken()).isEqualTo(OLD_TOKEN);
        assertThat(bid.getConfirmationCode()).isEqualTo("123456");
        verify(revokedTokenRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
        assertThat(auditPayload("RECIPIENT_NAME_UPDATED")).containsOnlyKeys("bidId");
    }

    @Test
    void username_travelerSelfUnknownBlocked_sameErrorsAsCreation_noWrite() {
        when(resolver.resolve(senderId, "voyageur")).thenReturn(new RecipientAccount(travelerId, "voyageur"));
        assertProblem(() -> service.changeRecipient(bid.getId(), UID, byUsername("voyageur")),
                HttpStatus.UNPROCESSABLE_ENTITY, "recipient-is-traveler");

        when(resolver.resolve(senderId, "moi")).thenThrow(YadonyBusinessException.localized(
                HttpStatus.UNPROCESSABLE_ENTITY, "recipient-invitation-self", "problem.recipient-invitation-self"));
        assertProblem(() -> service.changeRecipient(bid.getId(), UID, byUsername("moi")),
                HttpStatus.UNPROCESSABLE_ENTITY, "recipient-invitation-self");

        when(resolver.resolve(senderId, "inconnu")).thenThrow(YadonyBusinessException.localized(
                HttpStatus.NOT_FOUND, "user-not-found", "problem.user-not-found"));
        assertProblem(() -> service.changeRecipient(bid.getId(), UID, byUsername("inconnu")),
                HttpStatus.NOT_FOUND, "user-not-found");

        assertThat(bid.getRecipientUserId()).isNull();
        assertThat(bid.getRecipientPhone()).isEqualTo("+221 77 123 45 67");
        assertThat(bid.getTrackingToken()).isEqualTo(OLD_TOKEN);
        verify(revokedTokenRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void usernameWithNameOrPhone_422_modeConflict() {
        assertProblem(() -> service.changeRecipient(bid.getId(), UID,
                new ChangeRecipientRequest("Awa", null, "awa.diallo")),
                HttpStatus.UNPROCESSABLE_ENTITY, "recipient-mode-conflict");
        assertProblem(() -> service.changeRecipient(bid.getId(), UID,
                new ChangeRecipientRequest(null, "+221781112233", "awa.diallo")),
                HttpStatus.UNPROCESSABLE_ENTITY, "recipient-mode-conflict");
        verify(revokedTokenRepository, never()).save(any());
    }

    @Test
    void neitherUsernameNorNamePlusPhone_422_recipientRequired() {
        for (ChangeRecipientRequest r : new ChangeRecipientRequest[]{
                new ChangeRecipientRequest(null, null, null),
                new ChangeRecipientRequest("Awa", null, null),
                new ChangeRecipientRequest(null, "+221781112233", null),
                new ChangeRecipientRequest(" ", "+221781112233", " ")}) {
            assertProblem(() -> service.changeRecipient(bid.getId(), UID, r),
                    HttpStatus.UNPROCESSABLE_ENTITY, "recipient-required");
        }
        verify(bidRepository, never()).save(any());
    }

    @Test
    void username_statusOutsideTheWindow_409_notSender_403() {
        bid.setStatus(BidStatus.COMPLETED);
        assertProblem(() -> service.changeRecipient(bid.getId(), UID, byUsername("awa.diallo")),
                HttpStatus.CONFLICT, "recipient-change-not-allowed");
        verifyNoInteractions(resolver);

        bid.setStatus(BidStatus.IN_TRANSIT);
        UserEntity other = new UserEntity();
        ReflectionTestUtils.setField(other, "id", travelerId);
        when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(other));
        assertProblem(() -> service.changeRecipient(bid.getId(), "uid-traveler", byUsername("awa.diallo")),
                HttpStatus.FORBIDDEN, "forbidden");
        verifyNoInteractions(resolver);
    }

    @Test
    void oldConstructor_stillBuildsTheRequest() {
        ChangeRecipientRequest r = new ChangeRecipientRequest("Awa", "+221781112233");
        assertThat(r.recipientUsername()).isNull();
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
