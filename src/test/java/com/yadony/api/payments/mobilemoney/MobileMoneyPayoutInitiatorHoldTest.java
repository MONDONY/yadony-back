package com.yadony.api.payments.mobilemoney;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRail;
import com.yadony.api.payments.hold.PayoutHeldException;
import com.yadony.api.payments.hold.PayoutHoldPolicy;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import com.yadony.api.payments.pawapay.PawapaySubmissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Defense en profondeur : {@link MobileMoneyPayoutInitiator#release} est le point commun de la
 * livraison, du force-release et de la relance admin. Aucun appelant, present ou futur, ne doit
 * pouvoir verser a un voyageur gele (ou sur un paiement dispute) sans le dire explicitement.
 */
@ExtendWith(MockitoExtension.class)
class MobileMoneyPayoutInitiatorHoldTest {

    @Mock MobileMoneyPayoutAccountRepository accountRepository;
    @Mock PawapayOperationService operations;
    @Mock PawapaySubmissionService submission;
    @Mock AdminAlertService adminAlert;
    @Mock AdminAlertEscalator alerts;
    @Mock AuditService audit;
    @Mock PlatformTransactionManager transactionManager;
    @Mock PayoutHoldPolicy holdPolicy;

    private MobileMoneyPayoutInitiator initiator;
    private PaymentEntity payment;
    private final UUID travelerId = UUID.randomUUID();
    private final UUID bidId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        initiator = new MobileMoneyPayoutInitiator(new MobileMoneyPayoutAccounts(accountRepository), operations, submission, adminAlert, alerts, audit,
                transactionManager, holdPolicy);
        payment = new PaymentEntity();
        ReflectionTestUtils.setField(payment, "id", UUID.randomUUID());
        payment.setRail(PaymentRail.PAWAPAY);
        payment.setAmount(new BigDecimal("16800"));
        payment.setCommissionAmount(new BigDecimal("1800"));
        payment.setCurrency("XOF");
        MobileMoneyPayoutAccountEntity account = new MobileMoneyPayoutAccountEntity(travelerId, "ORANGE_SEN", "SN", "XOF",
                "221771234567", "masked", Instant.parse("2026-03-04T10:11:12Z"));
        account.setMigrated(true);
        ReflectionTestUtils.setField(account, "id", UUID.randomUUID());
        lenient().when(accountRepository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtAsc(travelerId))
                .thenReturn(List.of(account));
    }

    private PawapayOperationEntity accepted() {
        PawapayOperationEntity o = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.PAYOUT, payment.getId(), null,
                new BigDecimal("15000"), "XOF", "ORANGE_SEN", "SN", "221771234567");
        o.setStatus(PawapayOperationStatus.ACCEPTED);
        return o;
    }

    @Test
    void voyageurGele_sansDerogation_refuseAvantToutAppelPawapay() {
        when(holdPolicy.isHeld(travelerId)).thenReturn(true);

        assertThatThrownBy(() -> initiator.release(payment, bidId, travelerId, new BigDecimal("15000"), "delivery"))
                .isInstanceOf(PayoutHeldException.class)
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(operations, submission);
    }

    @Test
    void paiementDispute_sansDerogation_refuse() {
        payment.setDisputed(true);

        assertThatThrownBy(() -> initiator.release(payment, bidId, travelerId, new BigDecimal("15000"), "admin-retry", false))
                .isInstanceOf(PayoutHeldException.class);

        verifyNoInteractions(operations, submission);
    }

    @Test
    void voyageurGele_avecDerogationExplicite_verse() {
        lenient().when(holdPolicy.isHeld(travelerId)).thenReturn(true);
        payment.setDisputed(true);
        when(operations.findLive(payment.getId(), PawapayOperationKind.PAYOUT)).thenReturn(Optional.empty());
        PawapayOperationEntity op = accepted();
        when(submission.submitPayout(eq(payment.getId()), anyString(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn(op);

        assertThat(initiator.release(payment, bidId, travelerId, new BigDecimal("15000"), "admin-force-release", true))
                .isSameAs(op);
        verify(holdPolicy, never()).isHeld(any());
    }

    @Test
    void voyageurNonGele_verseCommeAvant() {
        when(holdPolicy.isHeld(travelerId)).thenReturn(false);
        when(operations.findLive(payment.getId(), PawapayOperationKind.PAYOUT)).thenReturn(Optional.empty());
        PawapayOperationEntity op = accepted();
        when(submission.submitPayout(eq(payment.getId()), anyString(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn(op);

        assertThat(initiator.release(payment, bidId, travelerId, new BigDecimal("15000"), "delivery")).isSameAs(op);
    }
}
