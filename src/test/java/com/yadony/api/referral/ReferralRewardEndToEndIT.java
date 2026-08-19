package com.yadony.api.referral;

import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
import com.yadony.api.voucher.CommissionVoucherEntity;
import com.yadony.api.voucher.CommissionVoucherRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Prouve que la promesse faite au parrain se traduit bien par un bon utilisable
 * quand son filleul confirme sa première livraison.
 *
 * <p>Lot 3 (2026-08-19/20) : remplace l'ancienne version qui prouvait un crédit
 * portefeuille — {@link DeliveryConfirmedReferralListener} injecte désormais
 * directement {@code CommissionVoucherService} (plus d'event cross-package), donc
 * le risque de "listener AFTER_COMMIT jamais déclenché" ne porte plus sur un
 * versement séparé mais sur l'octroi du bon lui-même.
 *
 * <p>{@link BidRepository} est mocké pour fixer le rang de la livraison sans avoir
 * à faire persister un colis complet ; tout le reste (transactions, base) est réel.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Parrainage — la récompense se traduit par un bon utilisable")
class ReferralRewardEndToEndIT {

    @Autowired private DeliveryConfirmedReferralListener listener;
    @Autowired private ReferralInvitationRepository invitationRepository;
    @Autowired private CommissionVoucherRepository voucherRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    @MockitoBean private BidRepository bidRepository;

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

    @Test
    @DisplayName("première livraison du filleul : un bon de 50 % est octroyé au parrain")
    void firstDelivery_grantsAVoucherToTheReferrer() {
        UUID referrerId = UUID.randomUUID();
        UUID refereeId = UUID.randomUUID();
        UUID invitationId = seedSignedUpInvitation(referrerId, refereeId);
        givenCompletedDeliveries(refereeId, 1);

        listener.onDeliveryConfirmed(
                new DeliveryConfirmedEvent(UUID.randomUUID(), refereeId, UUID.randomUUID()));

        List<CommissionVoucherEntity> vouchers =
                voucherRepository.findActiveByUserId(referrerId, java.time.LocalDateTime.now().plusSeconds(1));
        assertThat(vouchers).hasSize(1);
        assertThat(vouchers.getFirst().getFactor()).isEqualByComparingTo(new BigDecimal("0.50"));
        assertThat(vouchers.getFirst().getSourceInvitationId()).isEqualTo(invitationId);
        assertThat(vouchers.getFirst().getConsumedAt()).isNull();

        ReferralInvitationEntity inv = invitationRepository.findById(invitationId).orElseThrow();
        assertThat(inv.getStatus()).isEqualTo("REWARDED");
        assertThat(inv.getRewardedAt()).isNotNull();
    }

    @Test
    @DisplayName("une invitation ne génère jamais deux bons, même rejouée")
    void sameInvitation_neverGrantsTwoVouchers() {
        UUID referrerId = UUID.randomUUID();
        UUID refereeId = UUID.randomUUID();
        seedSignedUpInvitation(referrerId, refereeId);
        givenCompletedDeliveries(refereeId, 1);
        DeliveryConfirmedEvent event =
                new DeliveryConfirmedEvent(UUID.randomUUID(), refereeId, UUID.randomUUID());

        listener.onDeliveryConfirmed(event);
        listener.onDeliveryConfirmed(event);

        List<CommissionVoucherEntity> vouchers = voucherRepository
                .findActiveByUserId(referrerId, java.time.LocalDateTime.now().plusSeconds(1));
        assertThat(vouchers).hasSize(1);
    }

    @Test
    @DisplayName("deuxième livraison : aucun bon, la promesse ne vaut qu'une fois")
    void laterDelivery_grantsNoVoucher() {
        UUID referrerId = UUID.randomUUID();
        UUID refereeId = UUID.randomUUID();
        seedSignedUpInvitation(referrerId, refereeId);
        givenCompletedDeliveries(refereeId, 2);

        listener.onDeliveryConfirmed(
                new DeliveryConfirmedEvent(UUID.randomUUID(), refereeId, UUID.randomUUID()));

        assertThat(voucherRepository
                .findActiveByUserId(referrerId, java.time.LocalDateTime.now().plusSeconds(1)))
                .isEmpty();
    }

    @Test
    @DisplayName("filleul sans parrain : rien n'est octroyé et rien n'échoue")
    void deliveryWithoutInvitation_isANoOp() {
        UUID refereeId = UUID.randomUUID();
        when(bidRepository.countByStatusAndSenderId(any(), any())).thenReturn(1L);

        listener.onDeliveryConfirmed(
                new DeliveryConfirmedEvent(UUID.randomUUID(), refereeId, UUID.randomUUID()));

        assertThat(voucherRepository
                .findActiveByUserId(refereeId, java.time.LocalDateTime.now().plusSeconds(1)))
                .isEmpty();
    }
}
