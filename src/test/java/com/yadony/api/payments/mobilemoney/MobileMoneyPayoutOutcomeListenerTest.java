package com.yadony.api.payments.mobilemoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.events.PaymentReleasedEvent;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.events.PawapayOperationCompletedEvent;
import com.yadony.api.payments.pawapay.events.PawapayOperationFailedEvent;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MobileMoneyPayoutOutcomeListenerTest {

    @Mock PawapayOperationService operations;
    @Mock PaymentRepository paymentRepository;
    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock ApplicationEventPublisher events;
    @Mock AdminAlertService adminAlert;
    @Mock AuditService audit;
    @InjectMocks MobileMoneyPayoutOutcomeListener listener;

    @Test
    void completedPayout_publishesPaymentReleased_withNetAndCurrency() {
        UUID paymentId = UUID.randomUUID();
        PawapayOperationEntity op = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.PAYOUT, paymentId, null,
                new BigDecimal("15000"), "XOF", "ORANGE_SEN", "SN", "221771234567");
        PaymentEntity payment = new PaymentEntity();
        ReflectionTestUtils.setField(payment, "id", paymentId);
        payment.setBidId(UUID.randomUUID());
        BidEntity bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", payment.getBidId());
        bid.setSenderId(UUID.randomUUID());
        bid.setAnnouncementId(UUID.randomUUID());
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(UUID.randomUUID());
        when(operations.get(op.getId())).thenReturn(op);
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
        when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(bid.getAnnouncementId())).thenReturn(Optional.of(a));

        listener.onCompleted(new PawapayOperationCompletedEvent(op.getId(), PawapayOperationKind.PAYOUT, paymentId));

        // Écart déclaré (test-only, mécanique) : ArgumentMatchers.publishEvent(Object) et
        // publishEvent(ApplicationEvent) (surcharge par défaut de l'interface Spring)
        // rendaient argThat(e -> e instanceof PaymentReleasedEvent r ...) ambigu à la
        // compilation (javac inférait T=ApplicationEvent, incompatible avec
        // PaymentReleasedEvent qui n'en hérite pas) — un simple witness de type explicite
        // lève l'ambiguïté sans changer ce qui est vérifié.
        verify(events).publishEvent(ArgumentMatchers.<PaymentReleasedEvent>argThat(r ->
                r.getAmount().compareTo(new BigDecimal("15000")) == 0
                && "XOF".equals(r.getCurrency()) && r.isMobileMoney()
                && r.getTravelerId().equals(a.getTravelerId()) && r.getSenderId().equals(bid.getSenderId())));
        verify(audit).log(eq("PAYMENT"), eq(paymentId), eq("MM_PAYOUT_COMPLETED"), any(), any());
    }

    @Test
    void failedPayout_raisesAlert_andAudits() {
        UUID paymentId = UUID.randomUUID();
        listener.onFailed(new PawapayOperationFailedEvent(UUID.randomUUID(), PawapayOperationKind.PAYOUT, paymentId, "RECIPIENT_NOT_FOUND", "no wallet"));
        verify(adminAlert).raise(eq("PAWAPAY_PAYOUT_FAILED"), any(), any());
        verify(audit).log(eq("PAYMENT"), eq(paymentId), eq("MM_PAYOUT_FAILED"), any(), any());
    }

    /**
     * Invariant projet : toute valeur non authentifiée journalisée est tronquée à 64
     * caractères — {@code failureMessage} est {@code TEXT} en base (non bornée), une réponse
     * pawaPay verbeuse ne doit ni gonfler l'audit ni le message Telegram de l'alerte.
     */
    @Test
    void failedPayout_truncatesLongFailureMessageTo64Chars() {
        UUID paymentId = UUID.randomUUID();
        String longMessage = "x".repeat(200);
        String longCode = "y".repeat(200);
        listener.onFailed(new PawapayOperationFailedEvent(UUID.randomUUID(), PawapayOperationKind.PAYOUT, paymentId, longCode, longMessage));

        org.mockito.ArgumentCaptor<java.util.Map<String, Object>> auditPayload =
                org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(audit).log(eq("PAYMENT"), eq(paymentId), eq("MM_PAYOUT_FAILED"), any(), auditPayload.capture());
        assertThat(((String) auditPayload.getValue().get("failureCode")).length()).isEqualTo(64);

        org.mockito.ArgumentCaptor<java.util.Map<String, Object>> alertContext =
                org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(adminAlert).raise(eq("PAWAPAY_PAYOUT_FAILED"), any(), alertContext.capture());
        assertThat(((String) alertContext.getValue().get("failureCode")).length()).isEqualTo(64);
        assertThat(((String) alertContext.getValue().get("failureMessage")).length()).isEqualTo(64);
    }

    @Test
    void depositEvents_areIgnoredHere() {
        listener.onCompleted(new PawapayOperationCompletedEvent(UUID.randomUUID(), PawapayOperationKind.DEPOSIT, UUID.randomUUID()));
        verify(events, never()).publishEvent(any());
    }

    /**
     * Second volet de la garde ligne 82 : {@code kind == PAYOUT} mais {@code paymentId == null}.
     * Le seul test {@code depositEvents_areIgnoredHere} ne couvre que le court-circuit sur
     * {@code kind}, jamais celui sur {@code paymentId} — sans ce test, un appelant qui publierait
     * un {@code PawapayOperationCompletedEvent} de kind PAYOUT sans paymentId ferait planter
     * {@code operations.get(event.operationId())} avec un NPE en aval plutôt que d'être ignoré
     * proprement.
     */
    @Test
    void completedPayout_withNullPaymentId_isIgnored() {
        listener.onCompleted(new PawapayOperationCompletedEvent(UUID.randomUUID(), PawapayOperationKind.PAYOUT, null));

        verify(operations, never()).get(any());
        verify(paymentRepository, never()).findById(any());
        verify(events, never()).publishEvent(any());
        verify(audit, never()).log(any(), any(), any(), any(), any());
    }

    /**
     * Les trois tests suivants couvrent les gardes {@code isEmpty()} des lignes 89/95/101 :
     * des cas jugés « structurellement impossibles aujourd'hui » par le commentaire du listener
     * (le paiement, son bid et son annonce existent nécessairement pour avoir pu être versés),
     * mais qui restent de vrais chemins défensifs — s'ils se déclenchent un jour (incohérence de
     * données), la notification doit être abandonnée silencieusement plutôt que de publier un
     * {@link PaymentReleasedEvent} à moitié rempli ou de lever une NPE.
     */
    @Test
    void completedPayout_whenPaymentNotFound_abandonsWithoutSideEffects() {
        UUID paymentId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        PawapayOperationEntity op = new PawapayOperationEntity(operationId, PawapayOperationKind.PAYOUT, paymentId, null,
                new BigDecimal("15000"), "XOF", "ORANGE_SEN", "SN", "221771234567");
        when(operations.get(operationId)).thenReturn(op);
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.empty());

        listener.onCompleted(new PawapayOperationCompletedEvent(operationId, PawapayOperationKind.PAYOUT, paymentId));

        verify(bidRepository, never()).findById(any());
        verify(events, never()).publishEvent(any());
        verify(audit, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void completedPayout_whenBidNotFound_abandonsWithoutSideEffects() {
        UUID paymentId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        UUID bidId = UUID.randomUUID();
        PawapayOperationEntity op = new PawapayOperationEntity(operationId, PawapayOperationKind.PAYOUT, paymentId, null,
                new BigDecimal("15000"), "XOF", "ORANGE_SEN", "SN", "221771234567");
        PaymentEntity payment = new PaymentEntity();
        ReflectionTestUtils.setField(payment, "id", paymentId);
        payment.setBidId(bidId);
        when(operations.get(operationId)).thenReturn(op);
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
        when(bidRepository.findById(bidId)).thenReturn(Optional.empty());

        listener.onCompleted(new PawapayOperationCompletedEvent(operationId, PawapayOperationKind.PAYOUT, paymentId));

        verify(announcementRepository, never()).findById(any());
        verify(events, never()).publishEvent(any());
        verify(audit, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void completedPayout_whenAnnouncementNotFound_abandonsWithoutSideEffects() {
        UUID paymentId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        UUID bidId = UUID.randomUUID();
        UUID announcementId = UUID.randomUUID();
        PawapayOperationEntity op = new PawapayOperationEntity(operationId, PawapayOperationKind.PAYOUT, paymentId, null,
                new BigDecimal("15000"), "XOF", "ORANGE_SEN", "SN", "221771234567");
        PaymentEntity payment = new PaymentEntity();
        ReflectionTestUtils.setField(payment, "id", paymentId);
        payment.setBidId(bidId);
        BidEntity bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", bidId);
        bid.setAnnouncementId(announcementId);
        when(operations.get(operationId)).thenReturn(op);
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.empty());

        listener.onCompleted(new PawapayOperationCompletedEvent(operationId, PawapayOperationKind.PAYOUT, paymentId));

        verify(events, never()).publishEvent(any());
        verify(audit, never()).log(any(), any(), any(), any(), any());
    }

    /**
     * Tâche 8 : un paiement keyé sur un fil de négociation (pas de bidId, {@code
     * negotiationThreadId} renseigné) doit retrouver son bid par {@code
     * findByLinkedNegotiationThreadId} plutôt que {@code findById(null)}.
     */
    @Test
    void payoutCompleted_threadKeyedPayment_findsBidByLinkedThread_andPublishesReleased() {
        UUID threadId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        PawapayOperationEntity op = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.PAYOUT, paymentId, null,
                new BigDecimal("30000"), "XOF", "ORANGE_SEN", "SN", "221771234567");
        PaymentEntity payment = new PaymentEntity();
        ReflectionTestUtils.setField(payment, "id", paymentId);
        payment.setNegotiationThreadId(threadId);
        BidEntity bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", UUID.randomUUID());
        bid.setSenderId(UUID.randomUUID());
        bid.setAnnouncementId(UUID.randomUUID());
        AnnouncementEntity ann = new AnnouncementEntity();
        ann.setTravelerId(UUID.randomUUID());
        when(operations.get(op.getId())).thenReturn(op);
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
        when(bidRepository.findByLinkedNegotiationThreadId(threadId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(bid.getAnnouncementId())).thenReturn(Optional.of(ann));

        listener.onCompleted(new PawapayOperationCompletedEvent(op.getId(), PawapayOperationKind.PAYOUT, paymentId));

        verify(bidRepository, never()).findById(any());
        verify(events).publishEvent(ArgumentMatchers.<PaymentReleasedEvent>argThat(r ->
                r.getAmount().compareTo(new BigDecimal("30000")) == 0
                && "XOF".equals(r.getCurrency()) && r.isMobileMoney()
                && r.getTravelerId().equals(ann.getTravelerId()) && r.getSenderId().equals(bid.getSenderId())));
    }

    /**
     * Pendant de {@code depositEvents_areIgnoredHere} pour {@code onFailed} : la garde ligne 116
     * a sa propre paire de branches (kind, paymentId) distincte de celle de {@code onCompleted},
     * JaCoCo les compte séparément. Sans ce test, un événement FAILED de kind DEPOSIT/REFUND
     * déclencherait à tort une alerte admin PAWAPAY_PAYOUT_FAILED.
     */
    @Test
    void failedPayout_withDepositKind_isIgnored() {
        listener.onFailed(new PawapayOperationFailedEvent(UUID.randomUUID(), PawapayOperationKind.DEPOSIT, UUID.randomUUID(),
                "CODE", "message"));

        verify(audit, never()).log(any(), any(), any(), any(), any());
        verify(adminAlert, never()).raise(any(), any(), any());
    }

    @Test
    void failedPayout_withNullPaymentId_isIgnored() {
        listener.onFailed(new PawapayOperationFailedEvent(UUID.randomUUID(), PawapayOperationKind.PAYOUT, null,
                "CODE", "message"));

        verify(audit, never()).log(any(), any(), any(), any(), any());
        verify(adminAlert, never()).raise(any(), any(), any());
    }

    /**
     * Couvre la branche {@code value == null} de {@code truncate()} (ligne 135), jamais atteinte
     * par les deux tests existants qui passent tous deux des chaînes non nulles. pawaPay ne
     * garantit pas {@code failureCode}/{@code failureMessage} sur tous les échecs : la défense en
     * profondeur du commentaire de {@code truncate} doit produire la chaîne littérale
     * {@code "null"} (via {@code String.valueOf}), jamais une NPE, dans l'audit et l'alerte.
     */
    @Test
    void failedPayout_withNullFailureCodeAndMessage_stringifiesToLiteralNull() {
        UUID paymentId = UUID.randomUUID();
        listener.onFailed(new PawapayOperationFailedEvent(UUID.randomUUID(), PawapayOperationKind.PAYOUT, paymentId, null, null));

        org.mockito.ArgumentCaptor<java.util.Map<String, Object>> auditPayload =
                org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(audit).log(eq("PAYMENT"), eq(paymentId), eq("MM_PAYOUT_FAILED"), any(), auditPayload.capture());
        assertThat(auditPayload.getValue().get("failureCode")).isEqualTo("null");

        org.mockito.ArgumentCaptor<java.util.Map<String, Object>> alertContext =
                org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(adminAlert).raise(eq("PAWAPAY_PAYOUT_FAILED"), any(), alertContext.capture());
        assertThat(alertContext.getValue().get("failureCode")).isEqualTo("null");
        assertThat(alertContext.getValue().get("failureMessage")).isEqualTo("null");
    }
}
