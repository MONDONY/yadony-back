package com.yadony.api.referral;

import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.referral.events.ReferralRewardAvailableEvent;
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
import com.yadony.api.voucher.CommissionVoucherRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Prouve que le premier envoi livré d'un filleul rend la récompense du parrain
 * DISPONIBLE (à choisir), sans plus octroyer de bon -50 %, et que le parrain en est
 * prévenu par une notification qui ouvre l'écran Parrainage.
 *
 * <p>{@link BidRepository} est mocké pour fixer le rang de la livraison sans avoir à
 * faire persister un colis complet ; tout le reste (transactions, base, événements
 * AFTER_COMMIT) est réel. {@link NotificationDispatcher} est mocké pour observer la
 * notification sans dépendre de FCM.
 */
@SpringBootTest
@ActiveProfiles("test")
@RecordApplicationEvents
@DisplayName("Parrainage — le premier envoi livré rend la récompense disponible")
class ReferralRewardEndToEndIT {

    @Autowired private DeliveryConfirmedReferralListener listener;
    @Autowired private ReferralInvitationRepository invitationRepository;
    @Autowired private CommissionVoucherRepository voucherRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private ApplicationEvents events;

    @MockitoBean private BidRepository bidRepository;
    @MockitoBean private NotificationDispatcher notificationDispatcher;

    /** Crée une invitation acceptée mais pas encore récompensée. */
    private UUID seedSignedUpInvitation(UUID referrerId, UUID refereeId) {
        return transactionTemplate.execute(status -> {
            ReferralInvitationEntity inv = new ReferralInvitationEntity();
            inv.setReferrerUserId(referrerId);
            inv.setRefereeUserId(refereeId);
            inv.setStatus("SIGNED_UP");
            return invitationRepository.save(inv).getId();
        });
    }

    /** Fixe le nombre de livraisons déjà terminées par le filleul. */
    private void givenCompletedDeliveries(UUID refereeId, long count) {
        when(bidRepository.countByStatusAndSenderId(eq(BidStatus.COMPLETED), eq(refereeId)))
                .thenReturn(count);
    }

    private long availableEvents(UUID referrerId) {
        return events.stream(ReferralRewardAvailableEvent.class)
                .filter(e -> e.referrerUserId().equals(referrerId))
                .count();
    }

    @Test
    @DisplayName("première livraison : récompense disponible, aucun bon, parrain notifié")
    void firstDelivery_makesTheRewardAvailable() {
        UUID referrerId = UUID.randomUUID();
        UUID refereeId = UUID.randomUUID();
        UUID invitationId = seedSignedUpInvitation(referrerId, refereeId);
        givenCompletedDeliveries(refereeId, 1);
        when(notificationDispatcher.messagesFor(referrerId)).thenReturn(TestMessages.fr());

        listener.onDeliveryConfirmed(
                new DeliveryConfirmedEvent(UUID.randomUUID(), refereeId, UUID.randomUUID()));

        ReferralInvitationEntity inv = invitationRepository.findById(invitationId).orElseThrow();
        assertThat(inv.getStatus()).isEqualTo("FIRST_DELIVERY");
        assertThat(inv.getRewardStatus()).isEqualTo(ReferralRewardStatus.AVAILABLE);
        assertThat(inv.getRewardAvailableAt()).isNotNull();
        assertThat(inv.getRewardChoice()).isNull();
        assertThat(voucherRepository.findActiveByUserId(referrerId, LocalDateTime.now().plusSeconds(1))).isEmpty();
        assertThat(availableEvents(referrerId)).isEqualTo(1);

        // Écouteur AFTER_COMMIT + @Async : la notification part sur un autre thread.
        verify(notificationDispatcher, timeout(5_000)).notifyUser(eq(referrerId), anyString(), anyString(),
                argThat(data -> "REFERRAL_REWARD_AVAILABLE".equals(data.get("type"))
                        && "/profile/referral".equals(data.get("route"))
                        && invitationId.toString().equals(data.get("invitationId"))));
    }

    @Test
    @DisplayName("événement rejoué : la récompense n'est rendue disponible qu'une fois")
    void replayedEvent_makesTheRewardAvailableOnce() {
        UUID referrerId = UUID.randomUUID();
        UUID refereeId = UUID.randomUUID();
        UUID invitationId = seedSignedUpInvitation(referrerId, refereeId);
        givenCompletedDeliveries(refereeId, 1);
        when(notificationDispatcher.messagesFor(any())).thenReturn(TestMessages.fr());
        DeliveryConfirmedEvent event =
                new DeliveryConfirmedEvent(UUID.randomUUID(), refereeId, UUID.randomUUID());

        listener.onDeliveryConfirmed(event);
        listener.onDeliveryConfirmed(event);

        assertThat(availableEvents(referrerId)).isEqualTo(1);
        assertThat(invitationRepository.findById(invitationId).orElseThrow().getRewardStatus())
                .isEqualTo(ReferralRewardStatus.AVAILABLE);
        verify(notificationDispatcher, timeout(5_000)).notifyUser(eq(referrerId), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("deuxième livraison : rien ne change, la promesse ne vaut qu'une fois")
    void laterDelivery_changesNothing() {
        UUID referrerId = UUID.randomUUID();
        UUID refereeId = UUID.randomUUID();
        UUID invitationId = seedSignedUpInvitation(referrerId, refereeId);
        givenCompletedDeliveries(refereeId, 2);

        listener.onDeliveryConfirmed(
                new DeliveryConfirmedEvent(UUID.randomUUID(), refereeId, UUID.randomUUID()));

        ReferralInvitationEntity inv = invitationRepository.findById(invitationId).orElseThrow();
        assertThat(inv.getStatus()).isEqualTo("SIGNED_UP");
        assertThat(inv.getRewardStatus()).isEqualTo(ReferralRewardStatus.NONE);
        assertThat(availableEvents(referrerId)).isZero();
    }

    @Test
    @DisplayName("filleul sans parrain : rien n'est publié et rien n'échoue")
    void deliveryWithoutInvitation_isANoOp() {
        UUID refereeId = UUID.randomUUID();
        when(bidRepository.countByStatusAndSenderId(any(), any())).thenReturn(1L);

        listener.onDeliveryConfirmed(
                new DeliveryConfirmedEvent(UUID.randomUUID(), refereeId, UUID.randomUUID()));

        assertThat(events.stream(ReferralRewardAvailableEvent.class)).isEmpty();
    }
}
