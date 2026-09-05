package com.yadony.api.payments.mobilemoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.CapacityUnit;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRail;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.events.MobileMoneyPaymentExpiredEvent;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Tâche 15 — expiration du délai de paiement mobile money.
 *
 * <p>Écart par rapport au cahier des charges (voir task-15-report.md) : le constructeur de
 * {@link MobileMoneyBidPaymentService} prend 16 paramètres depuis les tâches 13/14
 * (promoService, voucherService, transactionManager, props ajoutés après la rédaction du
 * brief) — le {@code setUp} ci-dessous complète donc les 4 arguments manquants par
 * {@code null}, tous inutilisés par {@link MobileMoneyBidPaymentService#expire}.
 * {@code CapacityUnit.KG} (cité dans le brief) n'existe pas dans l'énum réelle
 * ({@code SUITCASE_23KG}, {@code SUITCASE_32KG}, {@code KG_FREE}, {@code KG_EXACT}) :
 * remplacé par {@code KG_EXACT}, la seule variante « capacité personnalisée en kg » autre
 * que {@code KG_FREE}, sémantiquement la plus proche de l'intention du brief.
 */
@ExtendWith(MockitoExtension.class)
class MobileMoneyBidPaymentServiceExpireTest {

    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock PawapayOperationService operations;
    @Mock ApplicationEventPublisher events;
    @Mock com.yadony.api.common.AuditService audit;

    private MobileMoneyBidPaymentService service;
    private BidEntity bid;
    private AnnouncementEntity announcement;
    private PaymentEntity payment;

    @BeforeEach
    void setUp() {
        service = new MobileMoneyBidPaymentService(bidRepository, announcementRepository, null, paymentRepository,
                operations, null, null, null, null, audit, events, null, null, null, null, null);
        announcement = new AnnouncementEntity();
        ReflectionTestUtils.setField(announcement, "id", UUID.randomUUID());
        announcement.setTravelerId(UUID.randomUUID());
        announcement.setCapacityUnit(CapacityUnit.KG_EXACT);
        announcement.setAvailableKg(new BigDecimal("0"));
        announcement.setStatus(AnnouncementStatus.FULL);
        bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", UUID.randomUUID());
        bid.setAnnouncementId(announcement.getId());
        bid.setSenderId(UUID.randomUUID());
        bid.setPaymentMethod(PaymentMethod.MOBILE_MONEY);
        bid.setStatus(BidStatus.AWAITING_PAYMENT);
        bid.setWeightKg(new BigDecimal("5"));
        payment = new PaymentEntity();
        ReflectionTestUtils.setField(payment, "id", UUID.randomUUID());
        payment.setBidId(bid.getId());
        payment.setRail(PaymentRail.PAWAPAY);
        payment.setStatus(PaymentStatus.PENDING);
    }

    @Test
    void expire_cancelsPaymentAndBid_restoresCapacity_andNotifies() {
        when(bidRepository.findByIdForUpdate(bid.getId())).thenReturn(Optional.of(bid));
        when(paymentRepository.findByBidId(bid.getId())).thenReturn(Optional.of(payment));
        when(operations.findLatest(payment.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.empty());
        when(paymentRepository.markCancelledIfPending(payment.getId())).thenReturn(1);
        when(announcementRepository.findByIdForUpdate(announcement.getId())).thenReturn(Optional.of(announcement));

        service.expire(bid.getId());

        verify(paymentRepository).markCancelledIfPending(payment.getId());
        assertThat(bid.getStatus()).isEqualTo(BidStatus.CANCELLED);
        assertThat(bid.getAwaitingPaymentExpiresAt()).isNull();
        assertThat(announcement.getAvailableKg()).isEqualByComparingTo("5");
        assertThat(announcement.getStatus()).isEqualTo(AnnouncementStatus.ACTIVE);
        verify(events).publishEvent(any(MobileMoneyPaymentExpiredEvent.class));
        verify(audit).log(eq("BID"), eq(bid.getId()), eq("MM_PAYMENT_EXPIRED"), any(), any());
    }

    @Test
    void expire_waitsWhileADepositIsStillOpen() {
        when(bidRepository.findByIdForUpdate(bid.getId())).thenReturn(Optional.of(bid));
        when(paymentRepository.findByBidId(bid.getId())).thenReturn(Optional.of(payment));
        PawapayOperationEntity open = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.DEPOSIT, payment.getId(), null,
                new BigDecimal("16800"), "XOF", "ORANGE_SEN", "SN", "221771234567");
        open.setStatus(PawapayOperationStatus.PROCESSING);
        when(operations.findLatest(payment.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.of(open));

        service.expire(bid.getId());

        assertThat(bid.getStatus()).isEqualTo(BidStatus.AWAITING_PAYMENT);
        verify(paymentRepository, never()).markCancelledIfPending(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void expire_isIdempotent_whenBidAlreadyLeftAwaiting() {
        bid.setStatus(BidStatus.ACCEPTED);
        when(bidRepository.findByIdForUpdate(bid.getId())).thenReturn(Optional.of(bid));
        service.expire(bid.getId());
        verify(paymentRepository, never()).markCancelledIfPending(any());
        verify(events, never()).publishEvent(any());
    }

    /**
     * LE ZÉRO — course perdue contre {@code confirmEscrow} (tâche 14) :
     * {@code markCancelledIfPending} rend 0 (le paiement a déjà quitté PENDING — confirmEscrow
     * est passé en premier, ou il s'agit d'un rejeu de cette même méthode). expire() ne doit
     * RIEN faire d'autre : ni bid, ni annonce, ni audit, ni événement. Agir quand même
     * annulerait un colis déjà payé et regonflerait à tort la capacité du trajet — c'est le
     * pire défaut possible pour cette tâche, celui que ce test interdit structurellement.
     */
    @Test
    void expire_doesNothing_whenPaymentAlreadyLeftPending() {
        when(bidRepository.findByIdForUpdate(bid.getId())).thenReturn(Optional.of(bid));
        when(paymentRepository.findByBidId(bid.getId())).thenReturn(Optional.of(payment));
        when(operations.findLatest(payment.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.empty());
        when(paymentRepository.markCancelledIfPending(payment.getId())).thenReturn(0);

        service.expire(bid.getId());

        assertThat(bid.getStatus()).isEqualTo(BidStatus.AWAITING_PAYMENT);
        verify(bidRepository, never()).save(any());
        verifyNoInteractions(announcementRepository);
        verify(audit, never()).log(any(), any(), any(), any(), any());
        verify(events, never()).publishEvent(any());
    }
}
