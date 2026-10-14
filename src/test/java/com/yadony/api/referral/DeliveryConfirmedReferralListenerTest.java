package com.yadony.api.referral;

import com.yadony.api.common.AuditService;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.referral.events.ReferralRewardAvailableEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DeliveryConfirmedReferralListener — unit tests")
class DeliveryConfirmedReferralListenerTest {

    @Mock private ReferralInvitationRepository referralInvitationRepository;
    @Mock private BidRepository bidRepository;
    @Mock private AuditService auditService;
    @Mock private UserRepository userRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private DeliveryConfirmedReferralListener listener;

    private static final UUID SENDER_ID   = UUID.randomUUID();
    private static final UUID TRAVELER_ID = UUID.randomUUID();
    private static final UUID BID_ID      = UUID.randomUUID();
    private static final UUID REFERRER_ID = UUID.randomUUID();
    private static final UUID INV_ID      = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new DeliveryConfirmedReferralListener(
                referralInvitationRepository, bidRepository, userRepository, auditService, eventPublisher);
    }

    private static void setId(Object entity, UUID id) {
        try {
            Class<?> c = entity.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField("id");
                    f.setAccessible(true);
                    f.set(entity, id);
                    return;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ReferralInvitationEntity buildSignedUpInvitation() {
        ReferralInvitationEntity inv = new ReferralInvitationEntity();
        setId(inv, INV_ID);
        inv.setReferrerUserId(REFERRER_ID);
        inv.setRefereeUserId(SENDER_ID);
        inv.setStatus("SIGNED_UP");
        inv.setCodeUsed("TEST1234");
        return inv;
    }

    private DeliveryConfirmedEvent event() {
        return new DeliveryConfirmedEvent(BID_ID, SENDER_ID, TRAVELER_ID);
    }

    // ── 1. noInvitation_doesNothing ───────────────────────────────────────────

    @Test
    @DisplayName("noInvitation_doesNothing — no SIGNED_UP invitation → no reward")
    void noInvitation_doesNothing() {
        when(referralInvitationRepository.findByRefereeUserIdAndStatus(SENDER_ID, "SIGNED_UP"))
                .thenReturn(Optional.empty());

        listener.onDeliveryConfirmed(event());

        verifyNoInteractions(bidRepository, eventPublisher, auditService);
        verify(referralInvitationRepository, never()).save(any());
    }

    // ── 2. firstDelivery_makesRewardAvailable ─────────────────────────────────

    @Test
    @DisplayName("firstDelivery — reward becomes AVAILABLE, no voucher granted, referrer notified")
    void firstDelivery_makesRewardAvailable() {
        ReferralInvitationEntity inv = buildSignedUpInvitation();
        when(referralInvitationRepository.findByRefereeUserIdAndStatus(SENDER_ID, "SIGNED_UP"))
                .thenReturn(Optional.of(inv));
        when(bidRepository.countByStatusAndSenderId(BidStatus.COMPLETED, SENDER_ID)).thenReturn(1L);
        when(referralInvitationRepository.markRewardAvailableIfSignedUp(eq(INV_ID), any())).thenReturn(1);
        UserEntity referee = new UserEntity();
        referee.setFirstName("Awa");
        referee.setLastName("Diop");
        when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(referee));

        listener.onDeliveryConfirmed(event());

        verify(referralInvitationRepository).markRewardAvailableIfSignedUp(eq(INV_ID), any());
        verify(referralInvitationRepository, never()).save(any());

        verify(auditService).log(eq("REFERRAL_INVITATION"), eq(INV_ID), eq("REFERRAL_REWARD_AVAILABLE"),
                eq(REFERRER_ID), anyMap());
        verify(eventPublisher).publishEvent(new ReferralRewardAvailableEvent(INV_ID, REFERRER_ID, "Awa D."));
    }

    // ── 2b. concurrentOrReplayed_isIdempotent ─────────────────────────────────

    @Test
    @DisplayName("concurrent or replayed event — conditional update returns 0 → no audit, no notification")
    void concurrentOrReplayed_isIdempotent() {
        ReferralInvitationEntity inv = buildSignedUpInvitation();
        when(referralInvitationRepository.findByRefereeUserIdAndStatus(SENDER_ID, "SIGNED_UP"))
                .thenReturn(Optional.of(inv));
        when(bidRepository.countByStatusAndSenderId(BidStatus.COMPLETED, SENDER_ID)).thenReturn(1L);
        when(referralInvitationRepository.markRewardAvailableIfSignedUp(eq(INV_ID), any())).thenReturn(0);

        listener.onDeliveryConfirmed(event());

        verifyNoInteractions(auditService, eventPublisher, userRepository);
    }

    // ── 3. secondDelivery_doesNotReward ───────────────────────────────────────

    @Test
    @DisplayName("secondDelivery_doesNotReward — 2+ completed bids → no reward")
    void secondDelivery_doesNotReward() {
        ReferralInvitationEntity inv = buildSignedUpInvitation();
        when(referralInvitationRepository.findByRefereeUserIdAndStatus(SENDER_ID, "SIGNED_UP"))
                .thenReturn(Optional.of(inv));
        when(bidRepository.countByStatusAndSenderId(BidStatus.COMPLETED, SENDER_ID)).thenReturn(2L);

        listener.onDeliveryConfirmed(event());

        verify(referralInvitationRepository, never()).markRewardAvailableIfSignedUp(any(), any());
        verifyNoInteractions(eventPublisher);
    }

    // ── 4. zeroBidCount_doesNotReward ─────────────────────────────────────────

    @Test
    @DisplayName("zeroBidCount_doesNotReward — count=0 edge case skips reward (no NPE, no double)")
    void zeroBidCount_doesNotReward() {
        ReferralInvitationEntity inv = buildSignedUpInvitation();
        when(referralInvitationRepository.findByRefereeUserIdAndStatus(SENDER_ID, "SIGNED_UP"))
                .thenReturn(Optional.of(inv));
        // count=0 should never happen in production but must be handled gracefully
        when(bidRepository.countByStatusAndSenderId(BidStatus.COMPLETED, SENDER_ID)).thenReturn(0L);

        listener.onDeliveryConfirmed(event());

        verify(referralInvitationRepository, never()).markRewardAvailableIfSignedUp(any(), any());
        verifyNoInteractions(eventPublisher);
    }

    // ── 5. exceptionInUpdate_propagates ─────────────────────────────────────────

    @Test
    @DisplayName("exceptionInUpdate_propagates — persistence failure is visible (not swallowed)")
    void exceptionInUpdate_propagates() {
        ReferralInvitationEntity inv = buildSignedUpInvitation();
        when(referralInvitationRepository.findByRefereeUserIdAndStatus(SENDER_ID, "SIGNED_UP"))
                .thenReturn(Optional.of(inv));
        when(bidRepository.countByStatusAndSenderId(BidStatus.COMPLETED, SENDER_ID)).thenReturn(1L);
        when(referralInvitationRepository.markRewardAvailableIfSignedUp(eq(INV_ID), any()))
                .thenThrow(new RuntimeException("DB constraint violation"));

        assertThatThrownBy(() -> listener.onDeliveryConfirmed(event()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DB constraint violation");
        verifyNoInteractions(eventPublisher);
    }
}
