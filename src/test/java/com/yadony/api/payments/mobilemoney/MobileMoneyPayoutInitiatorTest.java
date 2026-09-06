package com.yadony.api.payments.mobilemoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.auth.MobileMoneyPayoutStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRail;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import com.yadony.api.payments.pawapay.PawapaySubmissionService;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

@ExtendWith(MockitoExtension.class)
class MobileMoneyPayoutInitiatorTest {

    @Mock UserRepository userRepository;
    @Mock PawapayOperationService operations;
    @Mock PawapaySubmissionService submission;
    @Mock AdminAlertService adminAlert;
    @Mock AdminAlertEscalator alerts;
    @Mock AuditService audit;
    // TransactionTemplate réel construit avec ce gestionnaire MOCKÉ. Non stubbé :
    // getTransaction(...) rend null par défaut, la callback s'exécute quand même avec ce status
    // null (jamais déréférencé), commit(null) est un no-op sur le mock — suffisant pour vérifier
    // que audit.log(...) est bien appelé (voir MobileMoneyBidPaymentServiceTest, même pattern).
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
        // release() vérifie la devise du compte (parité avec acceptBid) : sans cette ligne, les
        // autres tests de cette classe échoueraient tous sur ce garde-fou.
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
    void release_submitsNetToTravelerAccount_andAudits() {
        when(operations.findLive(payment.getId(), PawapayOperationKind.PAYOUT)).thenReturn(Optional.empty());
        PawapayOperationEntity accepted = payout(PawapayOperationStatus.ACCEPTED);
        when(submission.submitPayout(payment.getId(), "221771234567", "ORANGE_SEN", "SN", new BigDecimal("15000"), "XOF", "bid-" + bidId))
                .thenReturn(accepted);

        PawapayOperationEntity op = initiator.release(payment, bidId, traveler.getId(), new BigDecimal("15000"), "delivery");

        assertThat(op).isSameAs(accepted);
        // Jamais de mutation de l'entité gérée après le claim de l'appelant : le statut en
        // mémoire reste celui du POJO d'entrée (PENDING par défaut), release() n'y touche pas.
        assertThat(payment.getStatus()).isEqualTo(com.yadony.api.payments.PaymentStatus.PENDING);
        verify(audit).log(eq("PAYMENT"), eq(payment.getId()), eq("ESCROW_RELEASED_MOBILE_MONEY"), eq(bidId), any());
    }

    /** L'audit d'un versement accepté part dans SA PROPRE transaction. */
    @Test
    void release_auditOfAcceptedPayout_usesItsOwnTransaction() {
        when(operations.findLive(payment.getId(), PawapayOperationKind.PAYOUT)).thenReturn(Optional.empty());
        PawapayOperationEntity accepted = payout(PawapayOperationStatus.ACCEPTED);
        when(submission.submitPayout(any(), any(), any(), any(), any(), any(), any())).thenReturn(accepted);

        initiator.release(payment, bidId, traveler.getId(), new BigDecimal("15000"), "delivery");

        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(definition.capture());
        assertThat(definition.getValue().getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
     * Compte actif mais dans une devise différente de celle du paiement : traité comme « compte
     * absent » — même règle ({@code UserEntity#canReceiveMobileMoney}) qu'à l'acceptation.
     */
    @Test
    void release_currencyMismatch_alertsAndThrows() {
        traveler.setMobileMoneyCurrency("GHS");
        assertThatThrownBy(() -> initiator.release(payment, bidId, traveler.getId(), new BigDecimal("15000"), "delivery"))
                .isInstanceOf(IllegalStateException.class);
        verify(adminAlert).raise(eq("PAWAPAY_PAYOUT_NO_ACCOUNT"), any(), any());
        verify(submission, never()).submitPayout(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void release_findsAnOrphanLivePayout_andDoesNotSubmitAgain() {
        PawapayOperationEntity live = payout(PawapayOperationStatus.ACCEPTED);
        when(operations.findLive(payment.getId(), PawapayOperationKind.PAYOUT)).thenReturn(Optional.of(live));

        PawapayOperationEntity op = initiator.release(payment, bidId, traveler.getId(), new BigDecimal("15000"), "delivery");

        assertThat(op).isSameAs(live);
        verify(submission, never()).submitPayout(any(), any(), any(), any(), any(), any(), any());
        verify(alerts).raiseOnce(eq("MM_PAYOUT_ORPHAN_" + payment.getId()), any(), any());
        verify(audit).log(eq("PAYMENT"), eq(payment.getId()), eq("ESCROW_RELEASED_MOBILE_MONEY_RECOVERED"), eq(bidId), any());
    }

    /**
     * La relance admin rappelle release() sur un payout déjà vivant à chaque tentative d'un
     * opérateur — c'est son cas nominal de vérification. Chaque appel passe par l'alerte
     * dédupliquée (jamais {@code AdminAlertService#raise} directement), c'est l'escalateur qui
     * garantit qu'une seule part.
     */
    @Test
    void release_orphanAlert_goesThroughDedupedEscalator_onEveryRelance() {
        PawapayOperationEntity live = payout(PawapayOperationStatus.ACCEPTED);
        when(operations.findLive(payment.getId(), PawapayOperationKind.PAYOUT)).thenReturn(Optional.of(live));

        initiator.release(payment, bidId, traveler.getId(), new BigDecimal("15000"), "delivery");
        initiator.release(payment, bidId, traveler.getId(), new BigDecimal("15000"), "admin-relance");

        verify(alerts, times(2)).raiseOnce(eq("MM_PAYOUT_ORPHAN_" + payment.getId()), any(), any());
        verify(adminAlert, never()).raise(any(), any(), any());
    }

    /** {@code admin_alerts.type} est {@code VARCHAR(60)} : garde contre une régression de longueur. */
    @Test
    void orphanAlertType_fitsInAdminAlertsTypeColumn() {
        String type = MobileMoneyPayoutInitiator.ORPHAN_ALERT_PREFIX + UUID.randomUUID();
        assertThat(type.length()).isLessThanOrEqualTo(AdminAlertEscalator.TYPE_MAX_LENGTH);
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
        verify(audit, never()).log(any(), any(), any(), any(), any());
    }
}
