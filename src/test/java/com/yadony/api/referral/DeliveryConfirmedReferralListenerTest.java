package com.yadony.api.referral;

import com.yadony.api.common.AuditService;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.referral.events.ReferralRewardGrantedEvent;
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DeliveryConfirmedReferralListener — unit tests")
class DeliveryConfirmedReferralListenerTest {

    @Mock private ReferralInvitationRepository referralInvitationRepository;
    @Mock private UserCreditRepository userCreditRepository;
    @Mock private BidRepository bidRepository;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private ReferralConfig config;
    private DeliveryConfirmedReferralListener listener;

    private static final UUID SENDER_ID   = UUID.randomUUID();
    private static final UUID TRAVELER_ID = UUID.randomUUID();
    private static final UUID BID_ID      = UUID.randomUUID();
    private static final UUID REFERRER_ID = UUID.randomUUID();
    private static final UUID INV_ID      = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        config = new ReferralConfig();
        config.setRewardAmountCents(500);
        config.setCodeRegenerationCooldownDays(30);
        listener = new DeliveryConfirmedReferralListener(
                referralInvitationRepository, userCreditRepository,
                bidRepository, auditService, config, eventPublisher,
                resolverReturning("EUR"));
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

        verifyNoInteractions(bidRepository, userCreditRepository, eventPublisher);
        verify(referralInvitationRepository, never()).save(any());
    }

    // ── 2. firstDelivery_rewards ──────────────────────────────────────────────

    @Test
    @DisplayName("firstDelivery_rewards — first COMPLETED bid triggers reward and credit")
    void firstDelivery_rewards() {
        ReferralInvitationEntity inv = buildSignedUpInvitation();
        when(referralInvitationRepository.findByRefereeUserIdAndStatus(SENDER_ID, "SIGNED_UP"))
                .thenReturn(Optional.of(inv));
        when(bidRepository.countByStatusAndSenderId(BidStatus.COMPLETED, SENDER_ID)).thenReturn(1L);
        when(referralInvitationRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(userCreditRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        listener.onDeliveryConfirmed(event());

        // Invitation must be updated to REWARDED
        ArgumentCaptor<ReferralInvitationEntity> invCaptor =
                ArgumentCaptor.forClass(ReferralInvitationEntity.class);
        verify(referralInvitationRepository).save(invCaptor.capture());
        ReferralInvitationEntity saved = invCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo("REWARDED");
        assertThat(saved.getCreditAmountCents()).isEqualTo(500);
        assertThat(saved.getRewardedAt()).isNotNull();

        // Credit entry must be created for the referrer
        ArgumentCaptor<UserCreditEntity> creditCaptor =
                ArgumentCaptor.forClass(UserCreditEntity.class);
        verify(userCreditRepository).save(creditCaptor.capture());
        UserCreditEntity credit = creditCaptor.getValue();
        assertThat(credit.getUserId()).isEqualTo(REFERRER_ID);
        assertThat(credit.getAmountCents()).isEqualTo(500);
        assertThat(credit.getSource()).isEqualTo("REFERRAL_REWARD");
        assertThat(credit.getReferenceId()).isEqualTo(INV_ID);

        // A wallet-credit event must be published for the referrer
        ArgumentCaptor<ReferralRewardGrantedEvent> evCaptor =
                ArgumentCaptor.forClass(ReferralRewardGrantedEvent.class);
        verify(eventPublisher).publishEvent(evCaptor.capture());
        ReferralRewardGrantedEvent ev = evCaptor.getValue();
        assertThat(ev.referrerUserId()).isEqualTo(REFERRER_ID);
        assertThat(ev.amountCents()).isEqualTo(500);
        assertThat(ev.invitationId()).isEqualTo(INV_ID);
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
        verifyNoInteractions(userCreditRepository);
        // No wallet credit event when no reward is granted
        verifyNoInteractions(eventPublisher);
    }

    // ── 4. rewardCreatesUserCredit ────────────────────────────────────────────

    @Test
    @DisplayName("rewardCreatesUserCredit — credit has correct referenceId = invitationId")
    void rewardCreatesUserCredit() {
        ReferralInvitationEntity inv = buildSignedUpInvitation();
        when(referralInvitationRepository.findByRefereeUserIdAndStatus(SENDER_ID, "SIGNED_UP"))
                .thenReturn(Optional.of(inv));
        when(bidRepository.countByStatusAndSenderId(BidStatus.COMPLETED, SENDER_ID)).thenReturn(1L);
        when(referralInvitationRepository.save(any())).thenReturn(inv);
        when(userCreditRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        listener.onDeliveryConfirmed(event());

        ArgumentCaptor<UserCreditEntity> cap = ArgumentCaptor.forClass(UserCreditEntity.class);
        verify(userCreditRepository).save(cap.capture());
        assertThat(cap.getValue().getReferenceId()).isEqualTo(INV_ID);
    }

    // ── 5. zeroBidCount_doesNotReward ─────────────────────────────────────────

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
        verifyNoInteractions(userCreditRepository, eventPublisher);
    }

    // ── 6. exceptionInCredit_propagates ──────────────────────────────────────

    @Test
    @DisplayName("exceptionInCredit_propagates — credit save failure is visible (not swallowed)")
    void exceptionInCredit_propagates() {
        ReferralInvitationEntity inv = buildSignedUpInvitation();
        when(referralInvitationRepository.findByRefereeUserIdAndStatus(SENDER_ID, "SIGNED_UP"))
                .thenReturn(Optional.of(inv));
        when(bidRepository.countByStatusAndSenderId(BidStatus.COMPLETED, SENDER_ID)).thenReturn(1L);
        when(referralInvitationRepository.save(any())).thenReturn(inv);
        when(userCreditRepository.save(any())).thenThrow(new RuntimeException("DB constraint violation"));

        assertThatThrownBy(() -> listener.onDeliveryConfirmed(event()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DB constraint violation");
    }

    // Mockito renvoie null pour un retour String non stubé (et non Optional.empty()) :
    // sans ce stub la devise résolue serait nulle et le repli ne serait pas testé.
    private static com.yadony.api.payments.currency.ActiveCurrencyResolver resolverReturning(String code) {
        var resolver = org.mockito.Mockito.mock(
                com.yadony.api.payments.currency.ActiveCurrencyResolver.class);
        org.mockito.Mockito.lenient()
                .when(resolver.resolve(org.mockito.ArgumentMatchers.any()))
                .thenReturn(code);
        return resolver;
    }

}
