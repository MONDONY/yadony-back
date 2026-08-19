package com.yadony.api.referral;

import com.yadony.api.common.AuditService;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
import com.yadony.api.voucher.CommissionVoucherEntity;
import com.yadony.api.voucher.CommissionVoucherService;
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
    @Mock private CommissionVoucherService voucherService;

    private DeliveryConfirmedReferralListener listener;

    private static final UUID SENDER_ID   = UUID.randomUUID();
    private static final UUID TRAVELER_ID = UUID.randomUUID();
    private static final UUID BID_ID      = UUID.randomUUID();
    private static final UUID REFERRER_ID = UUID.randomUUID();
    private static final UUID INV_ID      = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new DeliveryConfirmedReferralListener(
                referralInvitationRepository, bidRepository, auditService, voucherService);
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

    private CommissionVoucherEntity grantedVoucher() {
        CommissionVoucherEntity v = mock(CommissionVoucherEntity.class);
        lenient().when(v.getId()).thenReturn(UUID.randomUUID());
        lenient().when(v.getFactor()).thenReturn(new BigDecimal("0.50"));
        return v;
    }

    // ── 1. noInvitation_doesNothing ───────────────────────────────────────────

    @Test
    @DisplayName("noInvitation_doesNothing — no SIGNED_UP invitation → no reward")
    void noInvitation_doesNothing() {
        when(referralInvitationRepository.findByRefereeUserIdAndStatus(SENDER_ID, "SIGNED_UP"))
                .thenReturn(Optional.empty());

        listener.onDeliveryConfirmed(event());

        verifyNoInteractions(bidRepository, voucherService);
        verify(referralInvitationRepository, never()).save(any());
    }

    // ── 2. firstDelivery_rewards ──────────────────────────────────────────────

    @Test
    @DisplayName("firstDelivery_rewards — first COMPLETED bid triggers a voucher grant")
    void firstDelivery_rewards() {
        ReferralInvitationEntity inv = buildSignedUpInvitation();
        when(referralInvitationRepository.findByRefereeUserIdAndStatus(SENDER_ID, "SIGNED_UP"))
                .thenReturn(Optional.of(inv));
        when(bidRepository.countByStatusAndSenderId(BidStatus.COMPLETED, SENDER_ID)).thenReturn(1L);
        when(referralInvitationRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        CommissionVoucherEntity voucher = grantedVoucher();
        when(voucherService.grant(REFERRER_ID, INV_ID)).thenReturn(voucher);

        listener.onDeliveryConfirmed(event());

        // Invitation must be updated to REWARDED
        ArgumentCaptor<ReferralInvitationEntity> invCaptor =
                ArgumentCaptor.forClass(ReferralInvitationEntity.class);
        verify(referralInvitationRepository).save(invCaptor.capture());
        ReferralInvitationEntity saved = invCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo("REWARDED");
        assertThat(saved.getRewardedAt()).isNotNull();

        // A voucher must be granted to the referrer, tied to this invitation
        verify(voucherService).grant(REFERRER_ID, INV_ID);
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

        verify(referralInvitationRepository, never()).save(any());
        verifyNoInteractions(voucherService);
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

        verify(referralInvitationRepository, never()).save(any());
        verifyNoInteractions(voucherService);
    }

    // ── 5. exceptionInGrant_propagates ────────────────────────────────────────

    @Test
    @DisplayName("exceptionInGrant_propagates — voucher grant failure is visible (not swallowed)")
    void exceptionInGrant_propagates() {
        ReferralInvitationEntity inv = buildSignedUpInvitation();
        when(referralInvitationRepository.findByRefereeUserIdAndStatus(SENDER_ID, "SIGNED_UP"))
                .thenReturn(Optional.of(inv));
        when(bidRepository.countByStatusAndSenderId(BidStatus.COMPLETED, SENDER_ID)).thenReturn(1L);
        when(referralInvitationRepository.save(any())).thenReturn(inv);
        when(voucherService.grant(any(), any())).thenThrow(new RuntimeException("DB constraint violation"));

        assertThatThrownBy(() -> listener.onDeliveryConfirmed(event()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DB constraint violation");
    }
}
