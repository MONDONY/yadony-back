package com.yadony.api.payments;

import com.stripe.model.Transfer;
import com.stripe.net.RequestOptions;
import com.stripe.param.TransferCreateParams;
import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.hold.PayoutHoldPolicy;
import com.yadony.api.voucher.CommissionVoucherService;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Versement finalisé ailleurs pendant notre appel : état explicite, jamais compté comme « versé ici ». */
class DeliveryEventListenerReleaseOutcomeTest {

    @Test
    void finaliseParUnAutreTraitement_releaseInProgress() {
        PaymentRepository payments = mock(PaymentRepository.class);
        UserRepository users = mock(UserRepository.class);
        AuditService audit = mock(AuditService.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        StripeReleaseIntentRepository intents = ReleaseTestSupport.intentRepository();
        when(intents.markDone(any(), any())).thenReturn(0);
        StripeReleaseExecutor executor = new StripeReleaseExecutor(intents, payments, mock(StripeTransferLookup.class),
                audit, events, mock(AdminAlertEscalator.class), TransactionOperations.withoutTransaction(),
                Clock.systemUTC(), StripeReleaseExecutor.NO_GUARD);
        EscrowCaptureService capture = mock(EscrowCaptureService.class,
                inv -> new EscrowCaptureService.Outcome("ch_1", false));
        DeliveryEventListener listener = new DeliveryEventListener(payments, users, audit, mock(BidRepository.class),
                mock(AdminAlertService.class), mock(CommissionVoucherService.class), null, mock(PayoutHoldPolicy.class),
                mock(AdminAlertEscalator.class), capture, executor, mock(DisputeRepository.class),
                TransactionOperations.withoutTransaction());

        UUID bidId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        PaymentEntity p = new PaymentEntity();
        ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
        p.setBidId(bidId);
        p.setStripePaymentIntentId("pi_1");
        p.setStatus(PaymentStatus.ESCROW);
        p.setAmount(new BigDecimal("30.00"));
        p.setCommissionAmount(new BigDecimal("3.60"));
        when(payments.findByBidId(bidId)).thenReturn(Optional.of(p));
        when(payments.markReleasedIfEscrowAndUnguarded(any(), any())).thenReturn(1);
        UserEntity traveler = new UserEntity();
        traveler.setStripeAccountId("acct_1");
        when(users.findById(travelerId)).thenReturn(Optional.of(traveler));

        try (MockedStatic<Transfer> tr = mockStatic(Transfer.class)) {
            tr.when(() -> Transfer.create(any(TransferCreateParams.class), any(RequestOptions.class)))
                    .thenReturn(mock(Transfer.class));
            assertThat(listener.releaseAfterLateEscrow(bidId, UUID.randomUUID(), travelerId, "delivery"))
                    .isEqualTo(EscrowReleaseOutcome.RELEASE_IN_PROGRESS);
        }
        verify(events, never()).publishEvent(any());
        assertThat(EscrowReleaseOutcome.RELEASE_IN_PROGRESS.released()).isFalse();
    }
}
