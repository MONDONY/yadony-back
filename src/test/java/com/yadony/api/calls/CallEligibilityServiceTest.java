package com.yadony.api.calls;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.messaging.ConversationEntity;
import com.yadony.api.messaging.ConversationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static com.yadony.api.calls.CallEligibilityService.Reason.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class CallEligibilityServiceTest {

    @Mock ConversationRepository conversations;
    @Mock BidRepository bids;
    @Mock UserRepository users;
    @Mock BlockVisibility blocks;

    static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    final UUID sender = UUID.randomUUID();
    final UUID traveler = UUID.randomUUID();
    final UUID bidId = UUID.randomUUID();
    ConversationEntity conv;
    BidEntity bid;
    UserEntity travelerUser;
    CallEligibilityService service;

    @BeforeEach
    void setUp() {
        service = new CallEligibilityService(conversations, bids, users, blocks,
                new StreamProperties(true, "u", "k", "s", "audio_call", 3), Clock.fixed(NOW, ZoneOffset.UTC));
        conv = new ConversationEntity(bidId, sender, traveler, "fs-1");
        ReflectionTestUtils.setField(conv, "id", UUID.randomUUID());
        bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", bidId);
        bid.setStatus(BidStatus.ACCEPTED);
        travelerUser = new UserEntity();
        ReflectionTestUtils.setField(travelerUser, "id", traveler);
        travelerUser.setStatus(UserStatus.ACTIVE);
        lenient().when(conversations.findById(conv.getId())).thenReturn(Optional.of(conv));
        lenient().when(bids.findById(bidId)).thenReturn(Optional.of(bid));
        lenient().when(users.findById(traveler)).thenReturn(Optional.of(travelerUser));
    }

    @ParameterizedTest
    @EnumSource(value = BidStatus.class, names = {"ACCEPTED", "HANDED_OVER", "IN_TRANSIT", "ARRIVED"})
    void autoriseDansLaFenetre(BidStatus status) {
        bid.setStatus(status);
        var e = service.check(sender, conv.getId());
        assertThat(e.allowed()).isTrue();
        assertThat(e.calleeId()).isEqualTo(traveler);
    }

    @ParameterizedTest
    @EnumSource(value = BidStatus.class, names = {"NEGOTIATING", "PENDING", "PAYMENT_ESCROWED", "CANCELLED", "REJECTED", "EXPIRED", "NO_SHOW", "PARCEL_REFUSED", "AWAITING_PAYMENT", "NEGOTIATION_CLOSED"})
    void refuseHorsFenetre(BidStatus status) {
        bid.setStatus(status);
        assertThat(service.check(sender, conv.getId()).reason()).isEqualTo(OUT_OF_WINDOW);
    }

    @Test
    void livreDepuisMoinsDe3Jours() {
        bid.setStatus(BidStatus.COMPLETED);
        bid.markDelivered(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).minusDays(3).plusMinutes(1));
        assertThat(service.check(sender, conv.getId()).allowed()).isTrue();
    }

    @Test
    void livreDepuisPlusDe3Jours() {
        bid.setStatus(BidStatus.COMPLETED);
        bid.markDelivered(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).minusDays(3).minusMinutes(1));
        assertThat(service.check(sender, conv.getId()).reason()).isEqualTo(OUT_OF_WINDOW);
    }

    @Test
    void livreSansDateEstHorsFenetre() {
        bid.setStatus(BidStatus.COMPLETED);
        assertThat(service.check(sender, conv.getId()).reason()).isEqualTo(OUT_OF_WINDOW);
    }

    @Test
    void bidIntrouvableEstHorsFenetre() {
        lenient().when(bids.findById(bidId)).thenReturn(Optional.empty());
        assertThat(service.check(sender, conv.getId()).reason()).isEqualTo(OUT_OF_WINDOW);
    }

    @Test
    void interrupteurCoupe() {
        service = new CallEligibilityService(conversations, bids, users, blocks,
                new StreamProperties(false, "u", "k", "s", "audio_call", 3), Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(service.check(sender, conv.getId()).reason()).isEqualTo(CALLS_DISABLED);
    }

    @Test
    void conversationInconnue() {
        assertThat(service.check(sender, UUID.randomUUID()).reason()).isEqualTo(NOT_FOUND);
    }

    @Test
    void conversationSupprimee() {
        conv.softDelete();
        assertThat(service.check(sender, conv.getId()).reason()).isEqualTo(NOT_FOUND);
    }

    @Test
    void etrangerALaConversation() {
        assertThat(service.check(UUID.randomUUID(), conv.getId()).reason()).isEqualTo(NOT_PARTICIPANT);
    }

    @Test
    void conversationFermee() {
        conv.close(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        assertThat(service.check(sender, conv.getId()).reason()).isEqualTo(CONVERSATION_CLOSED);
    }

    @Test
    void conversationSupprimeeParLAutre() {
        conv.deleteForUser(traveler);
        assertThat(service.check(sender, conv.getId()).reason()).isEqualTo(CONVERSATION_CLOSED);
    }

    @Test
    void bloqueDansUnSens() {
        lenient().when(blocks.isHidden(traveler, sender)).thenReturn(true);
        assertThat(service.check(sender, conv.getId()).reason()).isEqualTo(BLOCKED);
    }

    @Test
    void bloqueDansLAutreSens() {
        lenient().when(blocks.isHidden(sender, traveler)).thenReturn(true);
        assertThat(service.check(sender, conv.getId()).reason()).isEqualTo(BLOCKED);
    }

    @Test
    void appeleSuspendu() {
        travelerUser.setStatus(UserStatus.SUSPENDED);
        assertThat(service.check(sender, conv.getId()).reason()).isEqualTo(CALLEE_UNAVAILABLE);
    }

    @Test
    void appeleSupprime() {
        lenient().when(users.findById(traveler)).thenReturn(Optional.empty());
        assertThat(service.check(sender, conv.getId()).reason()).isEqualTo(CALLEE_UNAVAILABLE);
    }

    @Test
    void conversationDestinataireVoyageur() {
        UUID recipient = UUID.randomUUID();
        ConversationEntity rc = ConversationEntity.forRecipient(bidId, recipient, traveler, "fs-2");
        ReflectionTestUtils.setField(rc, "id", UUID.randomUUID());
        lenient().when(conversations.findById(rc.getId())).thenReturn(Optional.of(rc));
        var e = service.check(recipient, rc.getId());
        assertThat(e.allowed()).isTrue();
        assertThat(e.calleeId()).isEqualTo(traveler);
    }

    @Test
    void leVoyageurAppelleLeDestinataire() {
        UUID recipient = UUID.randomUUID();
        UserEntity recipientUser = new UserEntity();
        recipientUser.setStatus(UserStatus.ACTIVE);
        lenient().when(users.findById(recipient)).thenReturn(Optional.of(recipientUser));
        ConversationEntity rc = ConversationEntity.forRecipient(bidId, recipient, traveler, "fs-2");
        ReflectionTestUtils.setField(rc, "id", UUID.randomUUID());
        lenient().when(conversations.findById(rc.getId())).thenReturn(Optional.of(rc));
        assertThat(service.check(traveler, rc.getId()).calleeId()).isEqualTo(recipient);
    }

    @Test
    void canCallDelegueACheck() {
        assertThat(service.canCall(sender, conv.getId())).isTrue();
        assertThat(service.canCall(UUID.randomUUID(), conv.getId())).isFalse();
    }

    // ── Retour d'un colis annulé après remise (FLUTTER-FM) ──────────────────

    private void cancelledAwaitingReturn() {
        bid.setStatus(BidStatus.CANCELLED);
        bid.setReturnDeadline(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).plusDays(2));
    }

    @Test
    void retourEnCours_lExpediteurPeutAppelerLeVoyageur() {
        cancelledAwaitingReturn();
        var e = service.check(sender, conv.getId());
        assertThat(e.allowed()).isTrue();
        assertThat(e.calleeId()).isEqualTo(traveler);
    }

    @Test
    void colisRestitue_appelRefuse() {
        cancelledAwaitingReturn();
        bid.setReturnedAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).minusHours(1));
        assertThat(service.check(sender, conv.getId()).reason()).isEqualTo(OUT_OF_WINDOW);
    }

    @Test
    void delaiDeRetourEcoule_appelRefuse() {
        bid.setStatus(BidStatus.CANCELLED);
        bid.setReturnDeadline(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).minusMinutes(1));
        assertThat(service.check(sender, conv.getId()).reason()).isEqualTo(OUT_OF_WINDOW);
    }

    @Test
    void retourEnCours_neRouvrePasLaConversationDestinataire() {
        cancelledAwaitingReturn();
        ConversationEntity recipientConv = ConversationEntity.forRecipient(bidId, sender, traveler, "fs-r");
        ReflectionTestUtils.setField(recipientConv, "id", UUID.randomUUID());
        lenient().when(conversations.findById(recipientConv.getId())).thenReturn(Optional.of(recipientConv));
        assertThat(service.check(sender, recipientConv.getId()).reason()).isEqualTo(OUT_OF_WINDOW);
    }
}
