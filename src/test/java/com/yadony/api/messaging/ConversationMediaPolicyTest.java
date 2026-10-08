package com.yadony.api.messaging;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("ConversationMediaPolicy — photos permises (FLUTTER-B4)")
class ConversationMediaPolicyTest {

    static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
    static final LocalDateTime NOW_LDT = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    BidRepository bidRepository = mock(BidRepository.class);
    UserRepository userRepository = mock(UserRepository.class);
    BlockVisibility blockVisibility = mock(BlockVisibility.class);
    ConversationMediaPolicy policy;

    UUID sender = UUID.randomUUID();
    UUID traveler = UUID.randomUUID();
    UUID bidId = UUID.randomUUID();
    UserEntity senderUser;
    ConversationEntity conv;

    @BeforeEach
    void setUp() throws Exception {
        policy = new ConversationMediaPolicy(bidRepository, userRepository, blockVisibility, 3,
                Clock.fixed(NOW, ZoneOffset.UTC));
        senderUser = user(sender);
        when(userRepository.findById(sender)).thenReturn(Optional.of(senderUser));
        conv = withId(new ConversationEntity(bidId, sender, traveler, "conv_" + bidId));
    }

    static UserEntity user(UUID id) throws Exception {
        UserEntity u = new UserEntity();
        Field f = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
        f.setAccessible(true);
        f.set(u, id);
        u.setFirebaseUid("uid-" + id);
        return u;
    }

    static ConversationEntity withId(ConversationEntity c) throws Exception {
        Field f = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
        f.setAccessible(true);
        f.set(c, UUID.randomUUID());
        return c;
    }

    @ParameterizedTest(name = "{0} → permis")
    @EnumSource(value = BidStatus.class, names = {"ACCEPTED", "HANDED_OVER", "IN_TRANSIT", "ARRIVED"})
    void acceptedAndPaidStatuses_allowMedia(BidStatus status) {
        assertThat(policy.check(conv, sender, status, null, null)).isEmpty();
    }

    @ParameterizedTest(name = "{0} → refusé")
    @EnumSource(value = BidStatus.class, names = {"AWAITING_PAYMENT", "PENDING", "PAYMENT_ESCROWED",
            "NEGOTIATING", "CANCELLED", "NO_SHOW", "PARCEL_REFUSED", "REJECTED", "EXPIRED", "NEGOTIATION_CLOSED"})
    void otherStatuses_denyMedia(BidStatus status) {
        assertThat(policy.check(conv, sender, status, NOW_LDT.minusHours(1), null))
                .contains(ConversationMediaPolicy.Denial.OUT_OF_WINDOW);
    }

    static Stream<Arguments> paymentModes() {
        return Stream.of(
                // Mobile money : BidAcceptedEvent publié avant paiement, le bid reste AWAITING_PAYMENT.
                Arguments.of(PaymentMethod.MOBILE_MONEY, BidStatus.AWAITING_PAYMENT, false),
                Arguments.of(PaymentMethod.MOBILE_MONEY, BidStatus.PENDING, false),
                Arguments.of(PaymentMethod.MOBILE_MONEY, BidStatus.ACCEPTED, true),
                Arguments.of(PaymentMethod.STRIPE, BidStatus.AWAITING_PAYMENT, false),
                Arguments.of(PaymentMethod.STRIPE, BidStatus.PAYMENT_ESCROWED, false),
                Arguments.of(PaymentMethod.STRIPE, BidStatus.ACCEPTED, true),
                Arguments.of(PaymentMethod.CASH, BidStatus.PENDING, false),
                Arguments.of(PaymentMethod.CASH, BidStatus.ACCEPTED, true));
    }

    @ParameterizedTest(name = "{0} / {1} → {2}")
    @MethodSource("paymentModes")
    void decisionFollowsStatus_whateverThePaymentMode(PaymentMethod method, BidStatus status, boolean allowed) {
        BidEntity bid = new BidEntity();
        bid.setPaymentMethod(method);
        bid.setStatus(status);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        assertThat(policy.check(conv, sender).isEmpty()).isEqualTo(allowed);
    }

    @Test
    void completed_allowedUntilDeliveryPlusThreeDays() {
        assertThat(policy.check(conv, sender, BidStatus.COMPLETED, NOW_LDT.minusDays(2).minusHours(23), null)).isEmpty();
        assertThat(policy.check(conv, sender, BidStatus.COMPLETED, NOW_LDT.minusDays(3), null))
                .contains(ConversationMediaPolicy.Denial.OUT_OF_WINDOW);
        assertThat(policy.check(conv, sender, BidStatus.COMPLETED, NOW_LDT.minusDays(10), null))
                .contains(ConversationMediaPolicy.Denial.OUT_OF_WINDOW);
        // Livré sans date connue : hors fenêtre (même règle que l'appel).
        assertThat(policy.check(conv, sender, BidStatus.COMPLETED, null, null))
                .contains(ConversationMediaPolicy.Denial.OUT_OF_WINDOW);
    }

    @Test
    void missingBid_isOutOfWindow() {
        when(bidRepository.findById(any())).thenReturn(Optional.empty());
        assertThat(policy.check(conv, sender)).contains(ConversationMediaPolicy.Denial.OUT_OF_WINDOW);
    }

    @Test
    void closedReadOnlyDeletedOrForeignConversation_isUnavailable() throws Exception {
        ConversationEntity closed = withId(ConversationEntity.forRecipient(bidId, sender, traveler, "conv_r"));
        closed.close(NOW_LDT);
        assertThat(policy.check(closed, traveler, BidStatus.ACCEPTED, null, null))
                .contains(ConversationMediaPolicy.Denial.CONVERSATION_UNAVAILABLE);

        ConversationEntity readOnly = withId(new ConversationEntity(bidId, sender, traveler, "conv_ro"));
        readOnly.deleteForUser(traveler);
        assertThat(policy.check(readOnly, sender, BidStatus.ACCEPTED, null, null))
                .contains(ConversationMediaPolicy.Denial.CONVERSATION_UNAVAILABLE);
        assertThat(policy.check(readOnly, traveler, BidStatus.ACCEPTED, null, null))
                .contains(ConversationMediaPolicy.Denial.CONVERSATION_UNAVAILABLE);

        ConversationEntity softDeleted = withId(new ConversationEntity(bidId, sender, traveler, "conv_sd"));
        softDeleted.softDelete();
        assertThat(policy.check(softDeleted, sender, BidStatus.ACCEPTED, null, null))
                .contains(ConversationMediaPolicy.Denial.CONVERSATION_UNAVAILABLE);

        assertThat(policy.check(conv, UUID.randomUUID(), BidStatus.ACCEPTED, null, null))
                .contains(ConversationMediaPolicy.Denial.CONVERSATION_UNAVAILABLE);
        assertThat(policy.check(null, sender, BidStatus.ACCEPTED, null, null))
                .contains(ConversationMediaPolicy.Denial.CONVERSATION_UNAVAILABLE);
        assertThat(policy.check(conv, null, BidStatus.ACCEPTED, null, null))
                .contains(ConversationMediaPolicy.Denial.CONVERSATION_UNAVAILABLE);
    }

    @Test
    void recipientConversation_followsTheSameRule() throws Exception {
        UUID recipient = UUID.randomUUID();
        when(userRepository.findById(recipient)).thenReturn(Optional.of(user(recipient)));
        ConversationEntity rc = withId(ConversationEntity.forRecipient(bidId, recipient, traveler, "conv_rt"));

        assertThat(policy.check(rc, recipient, BidStatus.IN_TRANSIT, null, null)).isEmpty();
        assertThat(policy.check(rc, recipient, BidStatus.AWAITING_PAYMENT, null, null))
                .contains(ConversationMediaPolicy.Denial.OUT_OF_WINDOW);
    }

    @Test
    void blockedInEitherDirection_denies() {
        when(blockVisibility.isHidden(sender, traveler)).thenReturn(true);
        assertThat(policy.check(conv, sender, BidStatus.ACCEPTED, null, null))
                .contains(ConversationMediaPolicy.Denial.BLOCKED);

        when(blockVisibility.isHidden(sender, traveler)).thenReturn(false);
        when(blockVisibility.isHidden(traveler, sender)).thenReturn(true);
        assertThat(policy.check(conv, sender, BidStatus.ACCEPTED, null, null))
                .contains(ConversationMediaPolicy.Denial.BLOCKED);
    }

    @Test
    void visibilityAlreadyChecked_skipsBlockLookups_butKeepsOtherRules() {
        // Liste des conversations, déjà filtrée des contreparties masquées.
        assertThat(policy.check(conv, sender, bidWithStatus(BidStatus.ACCEPTED), null, true)).isEmpty();
        assertThat(policy.check(conv, sender, bidWithStatus(BidStatus.AWAITING_PAYMENT), null, true))
                .contains(ConversationMediaPolicy.Denial.OUT_OF_WINDOW);
        verifyNoInteractions(blockVisibility);
    }

    @Test
    void mutedOrUnknownUser_denies() {
        senderUser.setMessagingMutedUntil(NOW.plusSeconds(3600));
        assertThat(policy.check(conv, sender, BidStatus.ACCEPTED, null, null))
                .contains(ConversationMediaPolicy.Denial.MESSAGING_MUTED);

        senderUser.setMessagingMutedUntil(NOW.minusSeconds(1));
        assertThat(policy.check(conv, sender, BidStatus.ACCEPTED, null, null)).isEmpty();

        when(userRepository.findById(sender)).thenReturn(Optional.empty());
        assertThat(policy.check(conv, sender, BidStatus.ACCEPTED, null, null))
                .contains(ConversationMediaPolicy.Denial.MESSAGING_MUTED);
    }

    @Test
    void assertAllowed_throws403MediaNotAllowed_orPasses() {
        BidEntity bid = new BidEntity();
        bid.setStatus(BidStatus.AWAITING_PAYMENT);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        assertThatThrownBy(() -> policy.assertAllowed(conv, senderUser))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(403);
                    assertThat(e.getErrorCode()).isEqualTo("media-not-allowed");
                });

        bid.setStatus(BidStatus.ARRIVED);
        assertThatCode(() -> policy.assertAllowed(conv, senderUser)).doesNotThrowAnyException();
    }

    // ── Retour d'un colis annulé après remise (FLUTTER-FM) ──────────────────

    private static BidEntity bidWithStatus(BidStatus status) {
        BidEntity bid = new BidEntity();
        bid.setStatus(status);
        return bid;
    }

    private BidEntity cancelledBid(LocalDateTime deadline, LocalDateTime returnedAt) {
        BidEntity bid = new BidEntity();
        bid.setStatus(BidStatus.CANCELLED);
        bid.setReturnDeadline(deadline);
        bid.setReturnedAt(returnedAt);
        return bid;
    }

    @Test
    void returnInProgress_allowsMedia_untilReturnedOrDeadline() {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
        assertThat(policy.check(conv, sender, cancelledBid(now.plusDays(1), null), null)).isEmpty();
        assertThat(policy.check(conv, sender, cancelledBid(now.plusDays(1), now.minusHours(1)), null))
                .contains(ConversationMediaPolicy.Denial.OUT_OF_WINDOW);
        assertThat(policy.check(conv, sender, cancelledBid(now.minusMinutes(1), null), null))
                .contains(ConversationMediaPolicy.Denial.OUT_OF_WINDOW);
        assertThat(policy.check(conv, sender, (BidEntity) null, null))
                .contains(ConversationMediaPolicy.Denial.OUT_OF_WINDOW);
    }

    @Test
    void returnInProgress_doesNotOpenTheRecipientConversation() throws Exception {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
        ConversationEntity recipientConv = withId(ConversationEntity.forRecipient(bidId, sender, traveler, "r_" + bidId));
        assertThat(policy.check(recipientConv, sender, cancelledBid(now.plusDays(1), null), null))
                .contains(ConversationMediaPolicy.Denial.OUT_OF_WINDOW);
        BidEntity accepted = new BidEntity();
        accepted.setStatus(BidStatus.ACCEPTED);
        assertThat(policy.check(recipientConv, sender, accepted, null)).isEmpty();
    }
}
