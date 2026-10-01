package com.yadony.api.matching.reception;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.reception.dto.ReceptionResponse;
import com.yadony.api.notifications.NotificationDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReceptionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
    private static final LocalDateTime NOW_LDT = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    @Mock BidRecipientLinkRepository linkRepository;
    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock UserRepository userRepository;
    @Mock ReceptionLinker linker;
    @Mock NotificationDispatcher notificationDispatcher;
    @Mock AuditService auditService;

    ReceptionService service;

    private final UUID meId = UUID.randomUUID();
    private final UUID senderId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private final UUID annId = UUID.randomUUID();
    private UserEntity me;

    @BeforeEach
    void setUp() {
        service = new ReceptionService(linkRepository, bidRepository, announcementRepository, userRepository,
                linker, notificationDispatcher, auditService, Clock.fixed(NOW, ZoneOffset.UTC));
        me = user(meId, "Fatou");
        me.setFirebaseUid("uid-me");
        lenient().when(userRepository.findByFirebaseUid("uid-me")).thenReturn(Optional.of(me));
        lenient().when(userRepository.findById(senderId)).thenReturn(Optional.of(user(senderId, "Awa")));
        lenient().when(userRepository.findById(travelerId)).thenReturn(Optional.of(user(travelerId, "Moussa")));
        lenient().when(announcementRepository.findById(annId)).thenReturn(Optional.of(announcement()));
        lenient().when(notificationDispatcher.messagesFor(any())).thenReturn(TestMessages.fr());
    }

    // ── list ────────────────────────────────────────────────────────────────

    @Test
    void list_filtersByStatusAndSortsByBidUpdatedAtDesc() {
        BidEntity old = bid(BidStatus.ACCEPTED, NOW_LDT.minusDays(3));
        BidEntity recent = bid(BidStatus.IN_TRANSIT, NOW_LDT.minusHours(1));
        BidEntity completedRecentConfirmed = bid(BidStatus.COMPLETED, NOW_LDT.minusDays(13));
        BidEntity completedRecentPending = bid(BidStatus.COMPLETED, NOW_LDT.minusDays(1));
        BidEntity completedOld = bid(BidStatus.COMPLETED, NOW_LDT.minusDays(15));
        BidEntity cancelled = bid(BidStatus.CANCELLED, NOW_LDT);
        List<BidRecipientLinkEntity> links = new ArrayList<>(List.of(
                link(old, ReceptionLinkStatus.PENDING),
                link(recent, ReceptionLinkStatus.CONFIRMED),
                link(completedRecentConfirmed, ReceptionLinkStatus.CONFIRMED),
                link(completedRecentPending, ReceptionLinkStatus.PENDING),
                link(completedOld, ReceptionLinkStatus.CONFIRMED),
                link(cancelled, ReceptionLinkStatus.CONFIRMED)));
        // Lien dont le colis a été supprimé (soft delete) : absent de findAllById.
        links.add(new BidRecipientLinkEntity(UUID.randomUUID(), meId));
        when(linkRepository.findByRecipientUserIdAndStatusIn(eq(meId), anyCollection())).thenReturn(links);
        when(bidRepository.findAllById(any())).thenReturn(
                List.of(old, recent, completedRecentConfirmed, completedRecentPending, completedOld, cancelled));

        List<ReceptionResponse> result = service.list("uid-me");

        assertThat(result).extracting(ReceptionResponse::bidId)
                .containsExactly(recent.getId(), old.getId(), completedRecentConfirmed.getId());
        verify(linker).catchUp(me);
    }

    @Test
    void list_catchUpFailure_stillServesTheList() {
        doThrow(new IllegalStateException("firebase down")).when(linker).catchUp(me);
        BidEntity b = bid(BidStatus.ACCEPTED, NOW_LDT);
        when(linkRepository.findByRecipientUserIdAndStatusIn(eq(meId), anyCollection()))
                .thenReturn(List.of(link(b, ReceptionLinkStatus.PENDING)));
        when(bidRepository.findAllById(any())).thenReturn(List.of(b));

        assertThat(service.list("uid-me")).hasSize(1);
    }

    @Test
    void list_withoutLinks_returnsEmpty() {
        when(linkRepository.findByRecipientUserIdAndStatusIn(eq(meId), anyCollection())).thenReturn(List.of());
        assertThat(service.list("uid-me")).isEmpty();
        verify(bidRepository, never()).findAllById(any());
    }

    @Test
    void list_unknownUser_returns401() {
        when(userRepository.findByFirebaseUid("ghost")).thenReturn(Optional.empty());
        var ex = catchThrowableOfType(() -> service.list("ghost"), YadonyBusinessException.class);
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── toResponse : masquage ───────────────────────────────────────────────

    @Test
    void pendingLink_hidesEverythingButIdentification() {
        BidEntity b = bid(BidStatus.IN_TRANSIT, NOW_LDT);
        ReceptionResponse r = service.toResponse(link(b, ReceptionLinkStatus.PENDING), b);

        assertThat(r.linkStatus()).isEqualTo("PENDING");
        assertThat(r.bidStatus()).isEqualTo("IN_TRANSIT");
        assertThat(r.senderFirstName()).isEqualTo("Awa");
        assertThat(r.departureCity()).isEqualTo("Paris");
        assertThat(r.arrivalCity()).isEqualTo("Dakar");
        assertThat(r.departureDate()).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(r.arrivalDate()).isEqualTo(LocalDate.of(2026, 10, 11));
        assertThat(r.recipientName()).isEqualTo("Fatou Diop");
        assertThat(r.trackingNumber()).isNull();
        assertThat(r.travelerFirstName()).isNull();
        assertThat(r.arrivalInstructions()).isNull();
        assertThat(r.weightKg()).isNull();
        assertThat(r.confirmationCode()).isNull();
        assertThat(r.updatedAt()).isEqualTo(NOW_LDT.toInstant(ZoneOffset.UTC));
    }

    @ParameterizedTest
    @EnumSource(value = BidStatus.class, names = {"HANDED_OVER", "IN_TRANSIT", "ARRIVED"})
    void confirmedLink_enRoute_revealsCode(BidStatus status) {
        BidEntity b = bid(status, NOW_LDT);
        ReceptionResponse r = service.toResponse(link(b, ReceptionLinkStatus.CONFIRMED), b);

        assertThat(r.confirmationCode()).isEqualTo("654321");
        assertThat(r.trackingNumber()).isEqualTo("DON-ABCDEFGH");
        assertThat(r.travelerFirstName()).isEqualTo("Moussa");
        assertThat(r.arrivalInstructions()).isEqualTo("Marché Sandaga");
        assertThat(r.weightKg()).isEqualByComparingTo("3");
    }

    @ParameterizedTest
    @EnumSource(value = BidStatus.class, names = {"ACCEPTED", "COMPLETED"})
    void confirmedLink_notEnRoute_hidesCode(BidStatus status) {
        BidEntity b = bid(status, NOW_LDT);
        ReceptionResponse r = service.toResponse(link(b, ReceptionLinkStatus.CONFIRMED), b);

        assertThat(r.confirmationCode()).isNull();
        assertThat(r.trackingNumber()).isEqualTo("DON-ABCDEFGH");
    }

    @Test
    void missingAnnouncement_leavesTripFieldsNull() {
        when(announcementRepository.findById(annId)).thenReturn(Optional.empty());
        BidEntity b = bid(BidStatus.ARRIVED, null);
        BidRecipientLinkEntity l = link(b, ReceptionLinkStatus.CONFIRMED);
        ReflectionTestUtils.setField(l, "updatedAt", null);

        ReceptionResponse r = service.toResponse(l, b);

        assertThat(r.departureCity()).isNull();
        assertThat(r.travelerFirstName()).isNull();
        assertThat(r.arrivalInstructions()).isNull();
        assertThat(r.updatedAt()).isNull();
    }

    // ── get ─────────────────────────────────────────────────────────────────

    @Test
    void get_returnsVisibleLink() {
        BidEntity b = bid(BidStatus.ACCEPTED, NOW_LDT);
        when(linkRepository.findByBidIdAndRecipientUserId(b.getId(), meId))
                .thenReturn(Optional.of(link(b, ReceptionLinkStatus.PENDING)));
        when(bidRepository.findById(b.getId())).thenReturn(Optional.of(b));

        assertThat(service.get(b.getId(), "uid-me").bidId()).isEqualTo(b.getId());
    }

    @Test
    void get_declinedLink_is404() {
        BidEntity b = bid(BidStatus.ACCEPTED, NOW_LDT);
        when(linkRepository.findByBidIdAndRecipientUserId(b.getId(), meId))
                .thenReturn(Optional.of(link(b, ReceptionLinkStatus.DECLINED)));

        assertNotFound(() -> service.get(b.getId(), "uid-me"));
    }

    @Test
    void get_noLink_is404() {
        UUID bidId = UUID.randomUUID();
        when(linkRepository.findByBidIdAndRecipientUserId(bidId, meId)).thenReturn(Optional.empty());
        assertNotFound(() -> service.get(bidId, "uid-me"));
    }

    @Test
    void get_softDeletedBid_is404() {
        BidEntity b = bid(BidStatus.ACCEPTED, NOW_LDT);
        when(linkRepository.findByBidIdAndRecipientUserId(b.getId(), meId))
                .thenReturn(Optional.of(link(b, ReceptionLinkStatus.CONFIRMED)));
        when(bidRepository.findById(b.getId())).thenReturn(Optional.empty());

        assertNotFound(() -> service.get(b.getId(), "uid-me"));
    }

    // ── confirm ─────────────────────────────────────────────────────────────

    @Test
    void confirm_pending_confirmsAuditsAndNotifiesSender() {
        BidEntity b = bid(BidStatus.HANDED_OVER, NOW_LDT);
        BidRecipientLinkEntity l = link(b, ReceptionLinkStatus.PENDING);
        when(linkRepository.findByBidIdAndRecipientUserId(b.getId(), meId)).thenReturn(Optional.of(l));
        when(bidRepository.findById(b.getId())).thenReturn(Optional.of(b));

        ReceptionResponse r = service.confirm(b.getId(), "uid-me");

        assertThat(r.linkStatus()).isEqualTo("CONFIRMED");
        assertThat(r.confirmationCode()).isEqualTo("654321");
        assertThat(l.getRespondedAt()).isNotNull();
        verify(linkRepository).save(l);
        verify(auditService).log(eq("BID_RECIPIENT_LINK"), any(), eq("RECEPTION_CONFIRMED"), eq(meId), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> data = ArgumentCaptor.forClass(Map.class);
        verify(notificationDispatcher).notifyUser(eq(senderId), eq("Destinataire dans Yadony"),
                eq("Fatou suit le colis dans l'app et y verra le code de retrait."), data.capture());
        assertThat(data.getValue()).containsEntry("type", "RECIPIENT_CONFIRMED")
                .containsEntry("bidId", b.getId().toString());
    }

    @Test
    void confirm_alreadyConfirmed_isIdempotent() {
        BidEntity b = bid(BidStatus.ACCEPTED, NOW_LDT);
        when(linkRepository.findByBidIdAndRecipientUserId(b.getId(), meId))
                .thenReturn(Optional.of(link(b, ReceptionLinkStatus.CONFIRMED)));
        when(bidRepository.findById(b.getId())).thenReturn(Optional.of(b));

        assertThat(service.confirm(b.getId(), "uid-me").linkStatus()).isEqualTo("CONFIRMED");
        verify(linkRepository, never()).save(any());
        verify(notificationDispatcher, never()).notifyUser(any(), anyString(), anyString(), any());
    }

    @Test
    void confirm_declined_is404() {
        BidEntity b = bid(BidStatus.ACCEPTED, NOW_LDT);
        when(linkRepository.findByBidIdAndRecipientUserId(b.getId(), meId))
                .thenReturn(Optional.of(link(b, ReceptionLinkStatus.DECLINED)));

        assertNotFound(() -> service.confirm(b.getId(), "uid-me"));
    }

    // ── decline ─────────────────────────────────────────────────────────────

    @Test
    void decline_pending_declinesAuditsAndNotifiesSender() {
        BidEntity b = bid(BidStatus.ACCEPTED, NOW_LDT);
        BidRecipientLinkEntity l = link(b, ReceptionLinkStatus.PENDING);
        when(linkRepository.findByBidIdAndRecipientUserId(b.getId(), meId)).thenReturn(Optional.of(l));
        when(bidRepository.findById(b.getId())).thenReturn(Optional.of(b));

        service.decline(b.getId(), "uid-me");

        assertThat(l.getStatus()).isEqualTo(ReceptionLinkStatus.DECLINED);
        assertThat(l.getRespondedAt()).isNotNull();
        verify(auditService).log(eq("BID_RECIPIENT_LINK"), any(), eq("RECEPTION_DECLINED"), eq(meId), any());
        verify(notificationDispatcher).notifyUser(eq(senderId), eq("Destinataire à vérifier"), anyString(),
                eq(Map.of("type", "RECIPIENT_DECLINED", "bidId", b.getId().toString())));
    }

    @Test
    void decline_confirmed_is409() {
        BidEntity b = bid(BidStatus.ACCEPTED, NOW_LDT);
        when(linkRepository.findByBidIdAndRecipientUserId(b.getId(), meId))
                .thenReturn(Optional.of(link(b, ReceptionLinkStatus.CONFIRMED)));
        when(bidRepository.findById(b.getId())).thenReturn(Optional.of(b));

        var ex = catchThrowableOfType(() -> service.decline(b.getId(), "uid-me"), YadonyBusinessException.class);
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getErrorCode()).isEqualTo("reception-already-confirmed");
    }

    @Test
    void decline_alreadyDeclined_isIdempotent() {
        BidEntity b = bid(BidStatus.ACCEPTED, NOW_LDT);
        when(linkRepository.findByBidIdAndRecipientUserId(b.getId(), meId))
                .thenReturn(Optional.of(link(b, ReceptionLinkStatus.DECLINED)));
        when(bidRepository.findById(b.getId())).thenReturn(Optional.of(b));

        service.decline(b.getId(), "uid-me");
        verify(linkRepository, never()).save(any());
        verify(notificationDispatcher, never()).notifyUser(any(), anyString(), anyString(), any());
    }

    @Test
    void decline_noLink_is404() {
        UUID bidId = UUID.randomUUID();
        when(linkRepository.findByBidIdAndRecipientUserId(bidId, meId)).thenReturn(Optional.empty());
        assertNotFound(() -> service.decline(bidId, "uid-me"));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        var ex = catchThrowableOfType(call, YadonyBusinessException.class);
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getErrorCode()).isEqualTo("reception-not-found");
    }

    private UserEntity user(UUID id, String firstName) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", id);
        u.setFirstName(firstName);
        u.setLastName("Diop");
        return u;
    }

    private AnnouncementEntity announcement() {
        AnnouncementEntity a = new AnnouncementEntity();
        ReflectionTestUtils.setField(a, "id", annId);
        a.setTravelerId(travelerId);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.of(2026, 10, 10));
        a.setArrivalDate(LocalDate.of(2026, 10, 11));
        a.setArrivalInstructions("Marché Sandaga");
        return a;
    }

    private BidEntity bid(BidStatus status, LocalDateTime updatedAt) {
        BidEntity b = new BidEntity();
        ReflectionTestUtils.setField(b, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(b, "updatedAt", updatedAt);
        b.setSenderId(senderId);
        b.setAnnouncementId(annId);
        b.setStatus(status);
        b.setRecipientName("Fatou Diop");
        b.setTrackingNumber("DON-ABCDEFGH");
        b.setConfirmationCode("654321");
        b.setWeightKg(new BigDecimal("3.00"));
        return b;
    }

    private BidRecipientLinkEntity link(BidEntity b, ReceptionLinkStatus status) {
        BidRecipientLinkEntity l = new BidRecipientLinkEntity(b.getId(), meId);
        ReflectionTestUtils.setField(l, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(l, "updatedAt", NOW_LDT.minusDays(20));
        if (status != ReceptionLinkStatus.PENDING) {
            l.respond(status, NOW.atOffset(ZoneOffset.UTC));
        }
        return l;
    }
}
