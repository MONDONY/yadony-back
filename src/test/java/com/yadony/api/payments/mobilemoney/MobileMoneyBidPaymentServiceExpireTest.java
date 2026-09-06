package com.yadony.api.payments.mobilemoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yadony.api.common.stripe.AdminAlertService;
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
import com.yadony.api.payments.mobilemoney.MobileMoneyBidPaymentService.ExpireOutcome;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Tâche 15 — expiration du délai de paiement mobile money.
 *
 * <p><b>Ronde 2 (revue)</b> : {@code expire()} ne lève plus d'alerte administrateur et n'évince
 * plus le cache lui-même (voir Javadoc de {@link MobileMoneyBidPaymentService.ExpireOutcome}) —
 * c'est désormais {@code MobileMoneyPaymentDeadlineScheduler} qui agit sur la valeur de retour,
 * hors des verrous tenus par {@code expire}. Chaque test asserte donc en plus l'
 * {@link ExpireOutcome} rendu, et les deux tests d'anomalie (paiement absent, dépôt
 * {@code COMPLETED} non appliqué) vérifient {@code verifyNoInteractions(adminAlert)} — la preuve
 * que {@code expire()} elle-même ne touche plus jamais à l'alerte.
 *
 * <p><b>Ronde 1 (revue)</b> : le fichier a été entièrement réécrit après trois corrections de
 * fond (voir task-15-report.md, section Ronde 1) — ordre des verrous (paiement avant bid),
 * dépôt {@code COMPLETED} traité comme en vol et non comme mort, deadline revérifiée
 * ({@code setUp} pose désormais {@code awaitingPaymentExpiresAt} dans le passé).
 *
 * <p>Écart par rapport au cahier des charges (voir task-15-report.md) : le constructeur de
 * {@link MobileMoneyBidPaymentService} prend 16 paramètres depuis les tâches 13/14 ; le
 * {@code setUp} complète les arguments manquants par {@code null}, tous inutilisés par
 * {@link MobileMoneyBidPaymentService#expire}. {@code CapacityUnit.KG} (cité dans le brief)
 * n'existe pas dans l'énum réelle : remplacé par {@code KG_EXACT}.
 */
@ExtendWith(MockitoExtension.class)
class MobileMoneyBidPaymentServiceExpireTest {

    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock PawapayOperationService operations;
    @Mock ApplicationEventPublisher events;
    @Mock com.yadony.api.common.AuditService audit;
    @Mock AdminAlertService adminAlert;

    private MobileMoneyBidPaymentService service;
    private BidEntity bid;
    private AnnouncementEntity announcement;
    private PaymentEntity payment;

    @BeforeEach
    void setUp() {
        service = new MobileMoneyBidPaymentService(bidRepository, announcementRepository, null, paymentRepository,
                operations, null, null, null, null, audit, events, null, null, null, null);
        ReflectionTestUtils.setField(service, "adminAlert", adminAlert);
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
        // Ronde 1, point 7 : la deadline doit être dans le passé pour que la garde de expire()
        // laisse passer les scénarios "délai dépassé" — sans cette ligne, TOUS les tests
        // échoueraient (champ null par défaut, jamais renseigné par le brief d'origine).
        bid.setAwaitingPaymentExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5));
        payment = new PaymentEntity();
        ReflectionTestUtils.setField(payment, "id", UUID.randomUUID());
        payment.setBidId(bid.getId());
        payment.setRail(PaymentRail.PAWAPAY);
        payment.setStatus(PaymentStatus.PENDING);
    }

    @Test
    void expire_cancelsPaymentAndBid_restoresCapacity_andNotifies() {
        when(paymentRepository.findByBidIdForUpdate(bid.getId())).thenReturn(Optional.of(payment));
        when(bidRepository.findByIdForUpdate(bid.getId())).thenReturn(Optional.of(bid));
        when(operations.findLatest(payment.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.empty());
        when(paymentRepository.markCancelledIfPending(payment.getId())).thenReturn(1);
        when(announcementRepository.findByIdForUpdate(announcement.getId())).thenReturn(Optional.of(announcement));

        ExpireOutcome outcome = service.expire(bid.getId());

        assertThat(outcome).isEqualTo(ExpireOutcome.CANCELLED);
        verify(paymentRepository).markCancelledIfPending(payment.getId());
        assertThat(bid.getStatus()).isEqualTo(BidStatus.CANCELLED);
        assertThat(bid.getAwaitingPaymentExpiresAt()).isNull();
        assertThat(announcement.getAvailableKg()).isEqualByComparingTo("5");
        assertThat(announcement.getStatus()).isEqualTo(AnnouncementStatus.ACTIVE);
        verify(audit).log(eq("BID"), eq(bid.getId()), eq("MM_PAYMENT_EXPIRED"), any(), any());

        // Ronde 1, point 5 : le contenu de l'événement est asserté, pas seulement son type —
        // une interversion senderId/travelerId déciderait qui reçoit quelle notification.
        ArgumentCaptor<MobileMoneyPaymentExpiredEvent> captor = ArgumentCaptor.forClass(MobileMoneyPaymentExpiredEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().bidId()).isEqualTo(bid.getId());
        assertThat(captor.getValue().senderId()).isEqualTo(bid.getSenderId());
        assertThat(captor.getValue().travelerId()).isEqualTo(announcement.getTravelerId());
    }

    @Test
    void expire_waitsWhileADepositIsStillOpen() {
        when(paymentRepository.findByBidIdForUpdate(bid.getId())).thenReturn(Optional.of(payment));
        when(bidRepository.findByIdForUpdate(bid.getId())).thenReturn(Optional.of(bid));
        PawapayOperationEntity open = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.DEPOSIT, payment.getId(), null,
                new BigDecimal("16800"), "XOF", "ORANGE_SEN", "SN", "221771234567");
        open.setStatus(PawapayOperationStatus.PROCESSING);
        when(operations.findLatest(payment.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.of(open));

        ExpireOutcome outcome = service.expire(bid.getId());

        assertThat(outcome).isEqualTo(ExpireOutcome.IGNORED);
        assertThat(bid.getStatus()).isEqualTo(BidStatus.AWAITING_PAYMENT);
        verify(paymentRepository, never()).markCancelledIfPending(any());
        verify(events, never()).publishEvent(any());
        verifyNoInteractions(adminAlert);
    }

    @Test
    void expire_isIdempotent_whenBidAlreadyLeftAwaiting() {
        bid.setStatus(BidStatus.ACCEPTED);
        when(bidRepository.findByIdForUpdate(bid.getId())).thenReturn(Optional.of(bid));
        ExpireOutcome outcome = service.expire(bid.getId());
        assertThat(outcome).isEqualTo(ExpireOutcome.IGNORED);
        verify(paymentRepository, never()).markCancelledIfPending(any());
        verify(events, never()).publishEvent(any());
    }

    /**
     * LE ZÉRO — course perdue contre {@code confirmEscrow} (tâche 14) :
     * {@code markCancelledIfPending} rend 0 (le paiement a déjà quitté PENDING — confirmEscrow
     * est passé en premier, ou il s'agit d'un rejeu de cette même méthode). expire() ne doit
     * RIEN faire d'autre : ni bid, ni annonce, ni audit, ni événement.
     */
    @Test
    void expire_doesNothing_whenPaymentAlreadyLeftPending() {
        when(paymentRepository.findByBidIdForUpdate(bid.getId())).thenReturn(Optional.of(payment));
        when(bidRepository.findByIdForUpdate(bid.getId())).thenReturn(Optional.of(bid));
        when(operations.findLatest(payment.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.empty());
        when(paymentRepository.markCancelledIfPending(payment.getId())).thenReturn(0);

        ExpireOutcome outcome = service.expire(bid.getId());

        assertThat(outcome).isEqualTo(ExpireOutcome.IGNORED);
        assertThat(bid.getStatus()).isEqualTo(BidStatus.AWAITING_PAYMENT);
        verify(bidRepository, never()).save(any());
        verifyNoInteractions(announcementRepository);
        verify(audit, never()).log(any(), any(), any(), any(), any());
        verify(events, never()).publishEvent(any());
    }

    /**
     * Ronde 1, point 2 (CRITIQUE) — un dépôt pawaPay {@code COMPLETED} avec un paiement encore
     * {@code PENDING} n'est PAS mort, il est EN VOL ({@code confirmEscrow} n'est pas encore
     * passé). Preuve : ni {@code markCancelledIfPending}, ni bid, ni événement — seulement un
     * log ERROR et l'issue {@link ExpireOutcome#DEPOSIT_COMPLETED_NOT_APPLIED}. Ronde 2, point 1 :
     * {@code expire()} elle-même ne lève plus d'alerte — {@code verifyNoInteractions(adminAlert)}
     * le prouve ; c'est au scheduler appelant de le faire, dédupliqué, hors verrou.
     */
    @Test
    void expire_doesNotCancel_whenDepositCompletedButPaymentStillPending() {
        when(paymentRepository.findByBidIdForUpdate(bid.getId())).thenReturn(Optional.of(payment));
        when(bidRepository.findByIdForUpdate(bid.getId())).thenReturn(Optional.of(bid));
        PawapayOperationEntity completed = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.DEPOSIT, payment.getId(), null,
                new BigDecimal("16800"), "XOF", "ORANGE_SEN", "SN", "221771234567");
        completed.setStatus(PawapayOperationStatus.COMPLETED);
        when(operations.findLatest(payment.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.of(completed));

        ExpireOutcome outcome = service.expire(bid.getId());

        assertThat(outcome).isEqualTo(ExpireOutcome.DEPOSIT_COMPLETED_NOT_APPLIED);
        assertThat(bid.getStatus()).isEqualTo(BidStatus.AWAITING_PAYMENT);
        verify(paymentRepository, never()).markCancelledIfPending(any());
        verify(bidRepository, never()).save(any());
        verifyNoInteractions(announcementRepository);
        verify(events, never()).publishEvent(any());
        verifyNoInteractions(adminAlert);
    }

    /**
     * Ronde 1, point 6 — paiement introuvable : état structurellement impossible (invariant
     * tâche 12), mais sur le chemin de l'argent on échoue fermé plutôt que d'annuler à
     * l'aveugle. Ronde 2, point 1 : {@code expire()} ne lève plus l'alerte elle-même — elle rend
     * {@link ExpireOutcome#PAYMENT_MISSING}, au scheduler d'alerter.
     */
    @Test
    void expire_doesNothing_whenNoPaymentFound() {
        when(paymentRepository.findByBidIdForUpdate(bid.getId())).thenReturn(Optional.empty());
        when(bidRepository.findByIdForUpdate(bid.getId())).thenReturn(Optional.of(bid));

        ExpireOutcome outcome = service.expire(bid.getId());

        assertThat(outcome).isEqualTo(ExpireOutcome.PAYMENT_MISSING);
        assertThat(bid.getStatus()).isEqualTo(BidStatus.AWAITING_PAYMENT);
        verify(bidRepository, never()).save(any());
        verifyNoInteractions(announcementRepository);
        verify(events, never()).publishEvent(any());
        verifyNoInteractions(adminAlert);
    }

    /**
     * Ronde 1, point 6 (couverture) — l'annonce a disparu entre-temps : le bid est quand même
     * annulé (rien à restituer), sans écriture sur l'annonce, et l'événement porte
     * {@code travelerId = null}.
     */
    @Test
    void expire_stillCancels_whenAnnouncementNotFound() {
        when(paymentRepository.findByBidIdForUpdate(bid.getId())).thenReturn(Optional.of(payment));
        when(bidRepository.findByIdForUpdate(bid.getId())).thenReturn(Optional.of(bid));
        when(operations.findLatest(payment.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.empty());
        when(paymentRepository.markCancelledIfPending(payment.getId())).thenReturn(1);
        when(announcementRepository.findByIdForUpdate(announcement.getId())).thenReturn(Optional.empty());

        ExpireOutcome outcome = service.expire(bid.getId());

        assertThat(outcome).isEqualTo(ExpireOutcome.CANCELLED);
        assertThat(bid.getStatus()).isEqualTo(BidStatus.CANCELLED);
        verify(announcementRepository, never()).save(any());
        ArgumentCaptor<MobileMoneyPaymentExpiredEvent> captor = ArgumentCaptor.forClass(MobileMoneyPaymentExpiredEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().travelerId()).isNull();
    }

    /**
     * Ronde 1, point 6 (couverture) — annonce {@code KG_FREE} : ni le poids ni le statut ne
     * doivent bouger (la capacité n'a jamais été décrémentée à l'acceptation pour ce mode), le
     * bid est quand même annulé.
     */
    @Test
    void expire_doesNotTouchCapacity_whenAnnouncementIsKgFree() {
        announcement.setCapacityUnit(CapacityUnit.KG_FREE);
        announcement.setStatus(AnnouncementStatus.ACTIVE);
        announcement.setAvailableKg(BigDecimal.ZERO);
        when(paymentRepository.findByBidIdForUpdate(bid.getId())).thenReturn(Optional.of(payment));
        when(bidRepository.findByIdForUpdate(bid.getId())).thenReturn(Optional.of(bid));
        when(operations.findLatest(payment.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.empty());
        when(paymentRepository.markCancelledIfPending(payment.getId())).thenReturn(1);
        when(announcementRepository.findByIdForUpdate(announcement.getId())).thenReturn(Optional.of(announcement));

        ExpireOutcome outcome = service.expire(bid.getId());

        assertThat(outcome).isEqualTo(ExpireOutcome.CANCELLED);
        assertThat(bid.getStatus()).isEqualTo(BidStatus.CANCELLED);
        assertThat(announcement.getAvailableKg()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(announcement.getStatus()).isEqualTo(AnnouncementStatus.ACTIVE);
        verify(announcementRepository, never()).save(any());
    }

    /** Ronde 1, point 7 — la deadline elle-même est revérifiée, pas seulement le statut. */
    @Test
    void expire_doesNothing_whenDeadlineNotYetReached() {
        bid.setAwaitingPaymentExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10));
        when(bidRepository.findByIdForUpdate(bid.getId())).thenReturn(Optional.of(bid));

        ExpireOutcome outcome = service.expire(bid.getId());

        assertThat(outcome).isEqualTo(ExpireOutcome.IGNORED);
        assertThat(bid.getStatus()).isEqualTo(BidStatus.AWAITING_PAYMENT);
        verify(paymentRepository, never()).markCancelledIfPending(any());
        verify(events, never()).publishEvent(any());
    }

    /**
     * Ronde 1, point 1 (CRITIQUE) — garde-fou de non-régression : le paiement doit être
     * verrouillé AVANT le bid, exactement comme {@code confirmEscrow}/{@code initiateDeposit}.
     * L'ordre inverse croise les deux transactions et PostgreSQL tue l'une des deux au bout
     * d'une seconde — sans gravité si c'est {@code expire}, définitif si c'est
     * {@code confirmEscrow} (voir Javadoc de {@code expire}).
     */
    @Test
    void expire_locksPaymentBeforeBid_toAvoidDeadlockWithConfirmEscrow() {
        bid.setStatus(BidStatus.ACCEPTED); // sort tôt, seul l'ordre des appels nous intéresse ici
        when(paymentRepository.findByBidIdForUpdate(bid.getId())).thenReturn(Optional.of(payment));
        when(bidRepository.findByIdForUpdate(bid.getId())).thenReturn(Optional.of(bid));

        ExpireOutcome outcome = service.expire(bid.getId());

        assertThat(outcome).isEqualTo(ExpireOutcome.IGNORED);
        InOrder order = inOrder(paymentRepository, bidRepository);
        order.verify(paymentRepository).findByBidIdForUpdate(bid.getId());
        order.verify(bidRepository).findByIdForUpdate(bid.getId());
    }
}
