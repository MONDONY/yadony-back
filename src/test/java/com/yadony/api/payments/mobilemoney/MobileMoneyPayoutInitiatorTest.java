package com.yadony.api.payments.mobilemoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yadony.api.admin.AdminAlertEntity;
import com.yadony.api.admin.AdminAlertRepository;
import com.yadony.api.auth.MobileMoneyPayoutStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRail;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import com.yadony.api.payments.pawapay.PawapaySubmissionService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
class MobileMoneyPayoutInitiatorTest {

    @Mock UserRepository userRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock PawapayOperationService operations;
    @Mock PawapaySubmissionService submission;
    @Mock AdminAlertService adminAlert;
    @Mock AdminAlertRepository alertRepository;
    @Mock AuditService audit;
    // Ronde 1, point 2 : TransactionTemplate réel construit avec ce gestionnaire MOCKÉ. Non
    // stubbé : getTransaction(...) rend null par défaut, la callback s'exécute quand même avec
    // ce status null (jamais déréférencé), commit(null) est un no-op sur le mock — suffisant
    // pour vérifier que audit.log(...) est bien appelé (voir MobileMoneyBidPaymentServiceTest,
    // même pattern).
    @Mock PlatformTransactionManager transactionManager;
    @InjectMocks MobileMoneyPayoutInitiator initiator;

    private PaymentEntity payment;
    private UserEntity traveler;
    private final UUID bidId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        payment = new PaymentEntity();
        ReflectionTestUtils.setField(payment, "id", UUID.randomUUID());
        payment.setRail(PaymentRail.PAWAPAY);
        payment.setAmount(new BigDecimal("16800"));
        payment.setCommissionAmount(new BigDecimal("1800"));
        payment.setCurrency("XOF");
        traveler = new UserEntity();
        ReflectionTestUtils.setField(traveler, "id", UUID.randomUUID());
        traveler.setMobileMoneyStatus(MobileMoneyPayoutStatus.ACTIVE);
        traveler.setMobileMoneyMsisdn("221771234567");
        traveler.setMobileMoneyProvider("ORANGE_SEN");
        traveler.setMobileMoneyCountry("SN");
        // Écart déclaré par rapport au cahier des charges (voir task-16-report.md) : le brief
        // omettait ce champ, mais release() vérifie désormais la devise du compte (parité
        // tâche 13, acceptBid) — sans cette ligne, les autres tests de cette classe échoueraient
        // tous sur ce garde-fou (currency null ne correspond jamais à "XOF").
        traveler.setMobileMoneyCurrency("XOF");
        // lenient : orphanAlertType_fitsInAdminAlertsTypeColumn n'appelle jamais release(), donc
        // jamais ce stub — Mockito STRICT_STUBS le signalerait sinon en UnnecessaryStubbing.
        org.mockito.Mockito.lenient().when(userRepository.findById(traveler.getId())).thenReturn(Optional.of(traveler));
    }

    private PawapayOperationEntity payout(PawapayOperationStatus status) {
        PawapayOperationEntity o = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.PAYOUT, payment.getId(), null,
                new BigDecimal("15000"), "XOF", "ORANGE_SEN", "SN", "221771234567");
        o.setStatus(status);
        return o;
    }

    @Test
    void release_submitsNetToTravelerAccount_andRecordsPayoutId() {
        when(operations.findLive(payment.getId(), PawapayOperationKind.PAYOUT)).thenReturn(Optional.empty());
        PawapayOperationEntity accepted = payout(PawapayOperationStatus.ACCEPTED);
        when(submission.submitPayout(payment.getId(), "221771234567", "ORANGE_SEN", "SN", new BigDecimal("15000"), "XOF", "bid-" + bidId))
                .thenReturn(accepted);

        PawapayOperationEntity op = initiator.release(payment, bidId, traveler.getId(), new BigDecimal("15000"), "delivery");

        assertThat(op).isSameAs(accepted);
        // Ronde 1, point 1 (CRITIQUE) : JAMAIS payment.setPawapayPayoutId(...) sur l'entité gérée
        // après le claim — UPDATE ciblé via le repository, seule façon vérifiable ici.
        verify(paymentRepository).attachPayoutId(payment.getId(), accepted.getId());
        // Ronde 2, point 1 : verify(...) seul n'est pas exclusif — un setter réintroduit EN PLUS
        // de l'appel repository laisserait ce test vert. Cette assertion sur le POJO passé par
        // l'appelant est la sentinelle réelle : elle exige qu'aucune mutation n'ait jamais eu
        // lieu sur cette entité, quel que soit ce qui a pu être appelé par ailleurs. Constatée
        // rouge avec un payment.setPawapayPayoutId(accepted.getId()) réintroduit dans release()
        // juste après l'appel repository, verte sans (voir task-16-report.md, Ronde 2).
        assertThat(payment.getPawapayPayoutId()).isNull();
        verify(audit).log(eq("PAYMENT"), eq(payment.getId()), eq("ESCROW_RELEASED_MOBILE_MONEY"), eq(bidId), any());
    }

    /** Ronde 1, point 2 : l'audit d'un versement accepté part dans SA PROPRE transaction. */
    @Test
    void release_auditOfAcceptedPayout_usesItsOwnTransaction() {
        when(operations.findLive(payment.getId(), PawapayOperationKind.PAYOUT)).thenReturn(Optional.empty());
        PawapayOperationEntity accepted = payout(PawapayOperationStatus.ACCEPTED);
        when(submission.submitPayout(any(), any(), any(), any(), any(), any(), any())).thenReturn(accepted);

        initiator.release(payment, bidId, traveler.getId(), new BigDecimal("15000"), "delivery");

        org.mockito.ArgumentCaptor<org.springframework.transaction.TransactionDefinition> definition =
                org.mockito.ArgumentCaptor.forClass(org.springframework.transaction.TransactionDefinition.class);
        verify(transactionManager).getTransaction(definition.capture());
        assertThat(definition.getValue().getPropagationBehavior())
                .isEqualTo(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Test
    void release_withoutActiveAccount_alertsAndThrows() {
        traveler.setMobileMoneyStatus(MobileMoneyPayoutStatus.DISABLED);
        assertThatThrownBy(() -> initiator.release(payment, bidId, traveler.getId(), new BigDecimal("15000"), "delivery"))
                .isInstanceOf(IllegalStateException.class);
        verify(adminAlert).raise(eq("PAWAPAY_PAYOUT_NO_ACCOUNT"), any(), any());
        verify(submission, never()).submitPayout(any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * Écart déclaré : test ajouté (absent du brief), preuve directe de la vérification de
     * devise explicitement demandée par le cahier des charges — parité avec
     * MobileMoneyBidPaymentService#acceptBid (tâche 13). Compte actif mais dans une devise
     * différente de celle du paiement : traité comme "compte absent".
     */
    @Test
    void release_currencyMismatch_alertsAndThrows() {
        traveler.setMobileMoneyCurrency("GHS");
        assertThatThrownBy(() -> initiator.release(payment, bidId, traveler.getId(), new BigDecimal("15000"), "delivery"))
                .isInstanceOf(IllegalStateException.class);
        verify(adminAlert).raise(eq("PAWAPAY_PAYOUT_NO_ACCOUNT"), any(), any());
        verify(submission, never()).submitPayout(any(), any(), any(), any(), any(), any(), any());
        verify(paymentRepository, never()).attachPayoutId(any(), any());
    }

    @Test
    void release_findsAnOrphanLivePayout_andDoesNotSubmitAgain() {
        PawapayOperationEntity live = payout(PawapayOperationStatus.ACCEPTED);
        when(operations.findLive(payment.getId(), PawapayOperationKind.PAYOUT)).thenReturn(Optional.of(live));
        String expectedType = "MM_PAYOUT_ORPHAN_" + payment.getId();
        when(alertRepository.findByTypeAndResolved(expectedType, false)).thenReturn(List.of());

        PawapayOperationEntity op = initiator.release(payment, bidId, traveler.getId(), new BigDecimal("15000"), "delivery");

        assertThat(op).isSameAs(live);
        verify(paymentRepository).attachPayoutId(payment.getId(), live.getId());
        // Ronde 2, point 1 : même sentinelle que le test nominal ci-dessus — verify(...) n'est
        // pas exclusif, cette assertion sur le POJO l'est.
        assertThat(payment.getPawapayPayoutId()).isNull();
        verify(submission, never()).submitPayout(any(), any(), any(), any(), any(), any(), any());
        verify(alertRepository).save(any(AdminAlertEntity.class));
        verify(adminAlert).raise(eq(expectedType), any(), any());
        verify(audit).log(eq("PAYMENT"), eq(payment.getId()), eq("ESCROW_RELEASED_MOBILE_MONEY_RECOVERED"), eq(bidId), any());
    }

    /**
     * Ronde 1, point 3 (Important) — LE TEST DEMANDÉ PAR LA REVUE : la relance admin (tâche 18)
     * rappelle release() sur un payout déjà vivant à chaque tentative d'un opérateur — c'est son
     * cas nominal de vérification. Deux appels consécutifs sur le même paiement orphelin ne
     * doivent produire qu'UNE SEULE alerte Telegram.
     */
    @Test
    void release_orphanAlert_deduplicatesAcrossRepeatedRelance() {
        PawapayOperationEntity live = payout(PawapayOperationStatus.ACCEPTED);
        when(operations.findLive(payment.getId(), PawapayOperationKind.PAYOUT)).thenReturn(Optional.of(live));
        String expectedType = "MM_PAYOUT_ORPHAN_" + payment.getId();
        // Premier appel : pas encore d'alerte. Second : l'alerte posée par le premier existe déjà.
        when(alertRepository.findByTypeAndResolved(expectedType, false))
                .thenReturn(List.of())
                .thenReturn(List.of(new AdminAlertEntity()));

        initiator.release(payment, bidId, traveler.getId(), new BigDecimal("15000"), "delivery");
        initiator.release(payment, bidId, traveler.getId(), new BigDecimal("15000"), "admin-relance");

        verify(paymentRepository, times(2)).attachPayoutId(payment.getId(), live.getId());
        verify(alertRepository, times(1)).save(any());
        verify(adminAlert, times(1)).raise(eq(expectedType), any(), any());
    }

    @Test
    void release_orphanAlert_skipsWhenAlreadyEscalated() {
        PawapayOperationEntity live = payout(PawapayOperationStatus.ACCEPTED);
        when(operations.findLive(payment.getId(), PawapayOperationKind.PAYOUT)).thenReturn(Optional.of(live));
        String expectedType = "MM_PAYOUT_ORPHAN_" + payment.getId();
        AdminAlertEntity existing = new AdminAlertEntity();
        existing.setType(expectedType);
        when(alertRepository.findByTypeAndResolved(expectedType, false)).thenReturn(List.of(existing));

        initiator.release(payment, bidId, traveler.getId(), new BigDecimal("15000"), "delivery");

        verify(alertRepository, never()).save(any());
        verify(adminAlert, never()).raise(any(), any(), any());
    }

    /**
     * Ronde 1, point 3 — {@code admin_alerts.type} est {@code VARCHAR(60)} : garde contre une
     * régression de longueur, même motif que {@code MobileMoneyPaymentDeadlineSchedulerTest}.
     */
    @Test
    void orphanAlertType_fitsInAdminAlertsTypeColumn() {
        String type = MobileMoneyPayoutInitiator.ORPHAN_ALERT_PREFIX + UUID.randomUUID();
        assertThat(type.length()).isLessThanOrEqualTo(60);
    }

    @Test
    void release_rejectedByPawapay_alertsAndThrows() {
        when(operations.findLive(any(), any())).thenReturn(Optional.empty());
        PawapayOperationEntity rejected = payout(PawapayOperationStatus.SUBMIT_REJECTED);
        rejected.setFailureCode("INSUFFICIENT_BALANCE");
        when(submission.submitPayout(any(), any(), any(), any(), any(), any(), any())).thenReturn(rejected);

        assertThatThrownBy(() -> initiator.release(payment, bidId, traveler.getId(), new BigDecimal("15000"), "delivery"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("INSUFFICIENT_BALANCE");
        verify(adminAlert).raise(eq("PAWAPAY_PAYOUT_REJECTED"), any(), any());
        verify(paymentRepository, never()).attachPayoutId(any(), any());
    }
}
