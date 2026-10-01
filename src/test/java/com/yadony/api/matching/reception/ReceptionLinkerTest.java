package com.yadony.api.matching.reception;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.notifications.NotificationDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReceptionLinkerTest {

    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock UserRepository userRepository;
    @Mock BidRecipientLinkRepository linkRepository;
    @Mock FirebaseContactService firebaseContact;
    @Mock BlockVisibility blockVisibility;
    @Mock NotificationDispatcher notificationDispatcher;
    @Mock AuditService auditService;
    @InjectMocks ReceptionLinker linker;

    private final UUID bidId = UUID.randomUUID();
    private final UUID senderId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private final UUID recipientId = UUID.randomUUID();
    private final UUID annId = UUID.randomUUID();
    private BidEntity bid;

    @BeforeEach
    void setUp() {
        bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", bidId);
        bid.setSenderId(senderId);
        bid.setAnnouncementId(annId);
        bid.setStatus(BidStatus.ACCEPTED);
        bid.setTrackingToken("token");
        bid.setRecipientPhone("+221 77 123 45 67");
        lenient().when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        lenient().when(announcementRepository.findById(annId)).thenReturn(Optional.of(announcement()));
        lenient().when(linkRepository.save(any())).thenAnswer(inv -> {
            BidRecipientLinkEntity l = inv.getArgument(0);
            ReflectionTestUtils.setField(l, "id", UUID.randomUUID());
            return l;
        });
        lenient().when(notificationDispatcher.messagesFor(any())).thenReturn(TestMessages.fr());
    }

    private AnnouncementEntity announcement() {
        AnnouncementEntity a = new AnnouncementEntity();
        ReflectionTestUtils.setField(a, "id", annId);
        a.setTravelerId(travelerId);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        return a;
    }

    private UserEntity user(UUID id, String uid, String firstName) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", id);
        u.setFirebaseUid(uid);
        u.setFirstName(firstName);
        return u;
    }

    private void recipientFound(UUID id) {
        when(firebaseContact.findUidByPhone("+221771234567")).thenReturn(Optional.of("uid-r"));
        when(userRepository.findByFirebaseUid("uid-r")).thenReturn(Optional.of(user(id, "uid-r", "Fatou")));
    }

    // ── linkIfPossible ──────────────────────────────────────────────────────

    @Test
    void linkIfPossible_createsPendingLinkAndNotifiesRecipient() {
        recipientFound(recipientId);
        when(userRepository.findById(senderId)).thenReturn(Optional.of(user(senderId, "uid-s", "Awa")));

        Optional<BidRecipientLinkEntity> link = linker.linkIfPossible(bidId);

        assertThat(link).isPresent();
        assertThat(link.get().getStatus()).isEqualTo(ReceptionLinkStatus.PENDING);
        assertThat(link.get().getRecipientUserId()).isEqualTo(recipientId);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> data = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notificationDispatcher).notifyUser(eq(recipientId), eq("Un colis pour vous ?"), body.capture(), data.capture());
        assertThat(body.getValue()).isEqualTo("Awa : colis Paris vers Dakar.");
        assertThat(data.getValue()).containsEntry("type", "RECIPIENT_PARCEL_INCOMING")
                .containsEntry("bidId", bidId.toString());
        verify(auditService).log(eq("BID_RECIPIENT_LINK"), any(), eq("LINKED_ON_ACCEPT"), eq(null), any());
    }

    @Test
    void linkIfPossible_unknownBid_doesNothing() {
        when(bidRepository.findById(bidId)).thenReturn(Optional.empty());
        assertThat(linker.linkIfPossible(bidId)).isEmpty();
        verify(linkRepository, never()).save(any());
    }

    @ParameterizedTest
    @EnumSource(value = BidStatus.class, names = {"AWAITING_PAYMENT", "PENDING", "COMPLETED", "CANCELLED", "NEGOTIATING"})
    void linkIfPossible_inactiveStatus_doesNothing(BidStatus status) {
        bid.setStatus(status);
        assertThat(linker.linkIfPossible(bidId)).isEmpty();
        verify(firebaseContact, never()).findUidByPhone(anyString());
    }

    @Test
    void linkIfPossible_withoutTrackingToken_doesNothing() {
        bid.setTrackingToken(null);
        assertThat(linker.linkIfPossible(bidId)).isEmpty();
    }

    @Test
    void linkIfPossible_alreadyLinked_isIdempotent() {
        when(linkRepository.existsByBidId(bidId)).thenReturn(true);
        assertThat(linker.linkIfPossible(bidId)).isEmpty();
        verify(linkRepository, never()).save(any());
    }

    @Test
    void linkIfPossible_localNumber_doesNothing() {
        bid.setRecipientPhone("77 123 45 67");
        assertThat(linker.linkIfPossible(bidId)).isEmpty();
        verify(firebaseContact, never()).findUidByPhone(anyString());
    }

    @Test
    void linkIfPossible_doubleZeroPrefix_isLookedUpAsE164() {
        bid.setRecipientPhone("00221 77 123 45 67");
        recipientFound(recipientId);
        assertThat(linker.linkIfPossible(bidId)).isPresent();
    }

    @Test
    void linkIfPossible_noAccountForNumber_doesNothing() {
        when(firebaseContact.findUidByPhone("+221771234567")).thenReturn(Optional.empty());
        assertThat(linker.linkIfPossible(bidId)).isEmpty();
    }

    @Test
    void linkIfPossible_firebaseUidWithoutLocalUser_doesNothing() {
        when(firebaseContact.findUidByPhone("+221771234567")).thenReturn(Optional.of("uid-r"));
        when(userRepository.findByFirebaseUid("uid-r")).thenReturn(Optional.empty());
        assertThat(linker.linkIfPossible(bidId)).isEmpty();
    }

    @Test
    void linkIfPossible_recipientIsSender_doesNothing() {
        recipientFound(senderId);
        assertThat(linker.linkIfPossible(bidId)).isEmpty();
        verify(linkRepository, never()).save(any());
    }

    @Test
    void linkIfPossible_recipientIsTraveler_doesNothing() {
        recipientFound(travelerId);
        assertThat(linker.linkIfPossible(bidId)).isEmpty();
        verify(linkRepository, never()).save(any());
    }

    @Test
    void linkIfPossible_blockedBetweenSenderAndRecipient_doesNothing() {
        recipientFound(recipientId);
        when(blockVisibility.isHidden(senderId, recipientId)).thenReturn(true);
        assertThat(linker.linkIfPossible(bidId)).isEmpty();
        verify(notificationDispatcher, never()).notifyUser(any(), any(), any(), any());
    }

    @Test
    void linkIfPossible_missingAnnouncement_stillLinksWithGenericBody() {
        when(announcementRepository.findById(annId)).thenReturn(Optional.empty());
        recipientFound(recipientId);

        assertThat(linker.linkIfPossible(bidId)).isPresent();
        verify(notificationDispatcher).notifyUser(eq(recipientId), any(),
                eq("Un expéditeur vous envoie un colis. Confirmez qu'il est pour vous."), any());
    }

    // ── catchUp ─────────────────────────────────────────────────────────────

    @Test
    void catchUp_linksMatchingBidsWithoutPush() {
        UserEntity me = user(recipientId, "uid-r", "Fatou");
        when(firebaseContact.getContact("uid-r"))
                .thenReturn(new FirebaseContactService.Contact("+221771234567", null));
        BidEntity otherNumber = new BidEntity();
        ReflectionTestUtils.setField(otherNumber, "id", UUID.randomUUID());
        otherNumber.setSenderId(senderId);
        otherNumber.setTrackingToken("t2");
        otherNumber.setRecipientPhone("+221 70 000 00 07");
        BidEntity noToken = new BidEntity();
        ReflectionTestUtils.setField(noToken, "id", UUID.randomUUID());
        noToken.setSenderId(senderId);
        noToken.setRecipientPhone("00221771234567");
        bid.setRecipientPhone("00221 77-123-45-67");
        when(linkRepository.findCatchUpCandidates(recipientId, BidStatus.IN_FLIGHT, "%7"))
                .thenReturn(List.of(bid, otherNumber, noToken));

        int created = linker.catchUp(me);

        assertThat(created).isEqualTo(1);
        ArgumentCaptor<BidRecipientLinkEntity> saved = ArgumentCaptor.forClass(BidRecipientLinkEntity.class);
        verify(linkRepository).save(saved.capture());
        assertThat(saved.getValue().getBidId()).isEqualTo(bidId);
        verify(notificationDispatcher, never()).notifyUser(any(), any(), any(), any());
        verify(auditService).log(eq("BID_RECIPIENT_LINK"), any(), eq("LINKED_ON_CATCH_UP"), eq(null), any());
    }

    @Test
    void catchUp_skipsBlockedSender() {
        UserEntity me = user(recipientId, "uid-r", "Fatou");
        when(firebaseContact.getContact("uid-r"))
                .thenReturn(new FirebaseContactService.Contact("+221771234567", null));
        when(linkRepository.findCatchUpCandidates(recipientId, BidStatus.IN_FLIGHT, "%7")).thenReturn(List.of(bid));
        when(blockVisibility.isHidden(senderId, recipientId)).thenReturn(true);

        assertThat(linker.catchUp(me)).isZero();
        verify(linkRepository, never()).save(any());
    }

    @Test
    void catchUp_withoutPhone_doesNothing() {
        UserEntity me = user(recipientId, "uid-r", "Fatou");
        when(firebaseContact.getContact("uid-r")).thenReturn(FirebaseContactService.Contact.EMPTY);

        assertThat(linker.catchUp(me)).isZero();
        verify(linkRepository, never()).findCatchUpCandidates(any(), any(), any());
    }
}
