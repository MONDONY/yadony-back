package com.yadony.api.payments.mobilemoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MobileMoneyPayoutInitiatorTest {

    @Mock UserRepository userRepository;
    @Mock PawapayOperationService operations;
    @Mock PawapaySubmissionService submission;
    @Mock AdminAlertService adminAlert;
    @Mock AuditService audit;
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
        // Écart déclaré par rapport au cahier des charges (voir task-16-report.md) : le
        // brief omettait ce champ, mais release() vérifie désormais la devise du compte
        // (parité tâche 13, acceptBid) — sans cette ligne, les trois autres tests de cette
        // classe échoueraient tous sur le nouveau garde-fou (currency null ne correspond
        // jamais à "XOF").
        traveler.setMobileMoneyCurrency("XOF");
        when(userRepository.findById(traveler.getId())).thenReturn(Optional.of(traveler));
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
        assertThat(payment.getPawapayPayoutId()).isEqualTo(accepted.getId());
        verify(audit).log(eq("PAYMENT"), eq(payment.getId()), eq("ESCROW_RELEASED_MOBILE_MONEY"), eq(bidId), any());
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
     * devise explicitement demandée par le cahier des charges (§ « Le compte de versement du
     * voyageur peut avoir disparu ») — parité avec MobileMoneyBidPaymentService#acceptBid
     * (tâche 13). Le compte est actif (ACTIVE) mais dans une devise différente de celle du
     * paiement : traité comme "compte absent", même alerte, même exception, aucune soumission.
     */
    @Test
    void release_currencyMismatch_alertsAndThrows() {
        traveler.setMobileMoneyCurrency("GHS");
        assertThatThrownBy(() -> initiator.release(payment, bidId, traveler.getId(), new BigDecimal("15000"), "delivery"))
                .isInstanceOf(IllegalStateException.class);
        verify(adminAlert).raise(eq("PAWAPAY_PAYOUT_NO_ACCOUNT"), any(), any());
        verify(submission, never()).submitPayout(any(), any(), any(), any(), any(), any(), any());
        assertThat(payment.getPawapayPayoutId()).isNull();
    }

    @Test
    void release_findsAnOrphanLivePayout_andDoesNotSubmitAgain() {
        PawapayOperationEntity live = payout(PawapayOperationStatus.ACCEPTED);
        when(operations.findLive(payment.getId(), PawapayOperationKind.PAYOUT)).thenReturn(Optional.of(live));

        PawapayOperationEntity op = initiator.release(payment, bidId, traveler.getId(), new BigDecimal("15000"), "delivery");

        assertThat(op).isSameAs(live);
        assertThat(payment.getPawapayPayoutId()).isEqualTo(live.getId());
        verify(submission, never()).submitPayout(any(), any(), any(), any(), any(), any(), any());
        verify(adminAlert).raise(eq("PAWAPAY_PAYOUT_ORPHANED"), any(), any());
        verify(audit).log(eq("PAYMENT"), eq(payment.getId()), eq("ESCROW_RELEASED_MOBILE_MONEY_RECOVERED"), eq(bidId), any());
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
        assertThat(payment.getPawapayPayoutId()).isNull();
    }
}
