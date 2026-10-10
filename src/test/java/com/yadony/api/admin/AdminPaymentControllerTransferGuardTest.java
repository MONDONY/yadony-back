package com.yadony.api.admin;

import com.stripe.model.PaymentIntent;
import com.stripe.model.Transfer;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.StripeTransferLookup;
import com.yadony.api.payments.chargeback.ChargebackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Libération forcée : anti double Transfer et gel par un litige ouvert par l'administration. */
@ExtendWith(MockitoExtension.class)
class AdminPaymentControllerTransferGuardTest {

    private final com.yadony.api.payments.EscrowCaptureService escrowCapture = mock(
            com.yadony.api.payments.EscrowCaptureService.class,
            invocation -> new com.yadony.api.payments.EscrowCaptureService.Outcome(null, false));

    @Mock PaymentRepository paymentRepository;
    @Mock AdminAlertRepository adminAlertRepository;
    @Mock AuditService auditService;
    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock UserRepository userRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock ChargebackRepository chargebackRepository;
    @Mock com.yadony.api.payments.mobilemoney.MobileMoneyPayoutInitiator payoutInitiator;
    @Mock com.yadony.api.payments.pawapay.PawapayOperationService pawapayOperations;
    @Mock com.yadony.api.payments.pawapay.PawapaySubmissionService pawapaySubmission;
    @Mock com.yadony.api.payments.RefundProcessor refundProcessor;
    @Mock jakarta.persistence.EntityManager entityManager;
    @Mock org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Mock com.yadony.api.payments.hold.PayoutHoldPolicy holdPolicy;
    @Mock AdminPaymentInsights insights;
    @Mock AdminPaymentTimeline timeline;
    @Mock StripeTransferLookup transferLookup;
    @Mock DisputeRepository disputeRepository;

    private AdminPaymentController controller;
    private final UUID paymentId = UUID.randomUUID();
    private final UUID threadId = UUID.randomUUID();
    private final UUID bidId = UUID.randomUUID();
    private final UUID announcementId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private PaymentEntity payment;

    @BeforeEach
    void setUp() {
        controller = new AdminPaymentController(paymentRepository, adminAlertRepository, auditService,
                bidRepository, announcementRepository, userRepository, eventPublisher, chargebackRepository,
                payoutInitiator, pawapayOperations, pawapaySubmission, refundProcessor, entityManager,
                transactionManager, holdPolicy, insights, timeline, escrowCapture, transferLookup, disputeRepository);
        payment = new PaymentEntity();
        payment.setNegotiationThreadId(threadId);
        payment.setStripePaymentIntentId("pi_1");
        payment.setStripeChargeId("ch_1");
        payment.setStatus(PaymentStatus.ESCROW);
        payment.setAmount(new BigDecimal("100.00"));
        payment.setCommissionAmount(new BigDecimal("12.00"));
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

        BidEntity bid = mock(BidEntity.class);
        when(bid.getId()).thenReturn(bidId);
        when(bid.getAnnouncementId()).thenReturn(announcementId);
        lenient().when(bid.getSenderId()).thenReturn(UUID.randomUUID());
        when(bidRepository.findByLinkedNegotiationThreadId(threadId)).thenReturn(Optional.of(bid));
        AnnouncementEntity ann = mock(AnnouncementEntity.class);
        when(ann.getTravelerId()).thenReturn(travelerId);
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(ann));
        UserEntity traveler = new UserEntity();
        traveler.setStripeAccountId("acct_t");
        lenient().when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
    }

    @Test
    void transferDejaEmis_aucunSecondTransfer_realigneEtAudite() throws Exception {
        when(paymentRepository.markReleasedIfEscrow(eq(paymentId), any())).thenReturn(1);
        when(transferLookup.findExistingTransfer(eq(paymentId), eq("acct_t"), any()))
                .thenReturn(Optional.of("tr_existing"));

        try (MockedStatic<Transfer> trStatic = mockStatic(Transfer.class);
             MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            controller.forceRelease(paymentId, null);
            trStatic.verifyNoInteractions();
        }

        verify(paymentRepository).recordStripeTransferId(paymentId, "tr_existing");
        verify(auditService).log(eq("PAYMENT"), eq(paymentId), eq("TRANSFER_ALREADY_EXISTS_REALIGNED"), eq(bidId), any());
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.RELEASED);
    }

    @Test
    void aucunTransferExistant_creeEtTraceLIdentifiant() throws Exception {
        when(paymentRepository.markReleasedIfEscrow(eq(paymentId), any())).thenReturn(1);
        when(transferLookup.findExistingTransfer(any(), any(), any())).thenReturn(Optional.empty());

        try (MockedStatic<Transfer> trStatic = mockStatic(Transfer.class)) {
            Transfer created = mock(Transfer.class);
            when(created.getId()).thenReturn("tr_new");
            trStatic.when(() -> Transfer.create(any(com.stripe.param.TransferCreateParams.class),
                    any(com.stripe.net.RequestOptions.class))).thenReturn(created);
            controller.forceRelease(paymentId, null);
        }
        verify(paymentRepository).recordStripeTransferId(paymentId, "tr_new");
    }

    @Test
    void litigeAdminOuvert_409_avantToutClaim() {
        when(disputeRepository.existsByBidIdAndStatusAndTypeStartingWith(bidId, "OPEN", "ADMIN_")).thenReturn(true);

        assertThatThrownBy(() -> controller.forceRelease(paymentId, null))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getErrorCode()).isEqualTo("dispute-open");
                });
        verify(paymentRepository, never()).markReleasedIfEscrow(any(), any());
    }
}
