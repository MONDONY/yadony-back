package com.yadony.api.payments.mobilemoney;

import com.yadony.api.payments.pawapay.PawapayValidationHints;
import com.yadony.api.common.i18n.TestMessages;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRail;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayProperties;
import com.yadony.api.payments.pawapay.PawapayProviderResolver;
import com.yadony.api.payments.pawapay.PawapaySubmissionService;
import com.yadony.api.requests.NegotiationMobileMoneyPort;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Paiement PENDING d'un fil à grille figée (spec §4.4) : la commission est celle calculée par
 * {@code NegotiationService} sur la grille ({@code ThreadCommissionPricing}), minimum compris ; le
 * taux reçu n'est qu'informatif. Sans grille, {@code net × taux} reste le calcul.
 */
@ExtendWith(MockitoExtension.class)
class MobileMoneyNegotiationPaymentServiceGridTest {

    @Mock PaymentRepository paymentRepository;
    @Mock UserRepository userRepository;
    @Mock PawapayOperationService operations;
    @Mock PawapaySubmissionService submission;
    @Mock PawapayProviderResolver providers;
    @Mock FirebaseContactService firebaseContact;
    @Mock AuditService audit;
    @Mock ApplicationEventPublisher events;
    @Mock PlatformTransactionManager transactionManager;

    MobileMoneyNegotiationPaymentService service;

    final UUID threadId = UUID.randomUUID();
    final UUID senderId = UUID.randomUUID();
    final UUID travelerId = UUID.randomUUID();
    final UUID gridId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        PawapayProperties props = new PawapayProperties(true, "https://api.sandbox.pawapay.io", "token", true, 30,
                "https://api-staging.yadony.com", "yadony://bids/%s/mobile-money/awaiting",
                "yadony://negotiations/%s/mobile-money/awaiting", "yadony://payments/wallet",
                new PawapayProperties.BalanceMin(null, null));
        service = new MobileMoneyNegotiationPaymentService(paymentRepository, userRepository, operations, submission,
                providers, firebaseContact, audit, events, transactionManager, props, new InMemoryPayoutAccounts(), new MobileMoneyOperationViews(TestMessages.resolver(), new PawapayValidationHints(Map.of("ORANGE_CIV", "SMS_LINK"))));
    }

    private PaymentEntity payment(PaymentStatus status, String amount, String commission) {
        PaymentEntity p = new PaymentEntity();
        ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
        p.setNegotiationThreadId(threadId);
        p.setRail(PaymentRail.PAWAPAY);
        p.setAmount(new BigDecimal(amount));
        p.setCommissionAmount(new BigDecimal(commission));
        p.setCurrency("XOF");
        p.setStatus(status);
        return p;
    }

    @Test
    void createPendingPayment_grid_usesGridNotRate() {
        when(paymentRepository.findByNegotiationThreadId(threadId)).thenReturn(Optional.empty());
        when(paymentRepository.save(any())).thenAnswer(inv -> {
            ReflectionTestUtils.setField((Object) inv.getArgument(0), "id", UUID.randomUUID());
            return inv.getArgument(0);
        });

        // Taux 10 % (500) ignoré : la grille impose le minimum de 650 FCFA.
        NegotiationMobileMoneyPort.PendingDeposit pending = service.createPendingPayment(threadId, senderId, travelerId,
                new BigDecimal("5000"), new BigDecimal("0.10"), "XOF", gridId, new BigDecimal("650"));

        ArgumentCaptor<PaymentEntity> saved = ArgumentCaptor.forClass(PaymentEntity.class);
        verify(paymentRepository).save(saved.capture());
        assertThat(saved.getValue().getCommissionAmount()).isEqualByComparingTo("650");
        assertThat(saved.getValue().getAmount()).isEqualByComparingTo("5650");
        assertThat(saved.getValue().getAmount().scale()).as("francs entiers").isZero();
        assertThat(pending.gross()).isEqualByComparingTo("5650");
        assertThat(pending.commission()).isEqualByComparingTo("650");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(audit).log(eq("PAYMENT"), any(), eq(MobileMoneyNegotiationPaymentService.AUDIT_CREATED), eq(senderId),
                payload.capture());
        assertThat(payload.getValue()).containsEntry("commissionGridId", gridId.toString())
                .containsEntry("amount", "5650").containsEntry("commission", "650");
    }

    @Test
    void createPendingPayment_grid_recycledCancelled_usesGridCommission() {
        PaymentEntity cancelled = payment(PaymentStatus.CANCELLED, "5500", "500");
        when(paymentRepository.findByNegotiationThreadId(threadId)).thenReturn(Optional.of(cancelled));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var pending = service.createPendingPayment(threadId, senderId, travelerId,
                new BigDecimal("5000"), new BigDecimal("0.10"), "XOF", gridId, new BigDecimal("650"));

        assertThat(cancelled.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(cancelled.getAmount()).isEqualByComparingTo("5650");
        assertThat(cancelled.getCommissionAmount()).isEqualByComparingTo("650");
        assertThat(pending.gross()).isEqualByComparingTo("5650");
    }

    /** Idempotence : un PENDING existant garde ses montants, jamais re-tarifé. */
    @Test
    void createPendingPayment_grid_existingPending_isReturnedUntouched() {
        PaymentEntity pendingPayment = payment(PaymentStatus.PENDING, "5650", "650");
        when(paymentRepository.findByNegotiationThreadId(threadId)).thenReturn(Optional.of(pendingPayment));

        var pending = service.createPendingPayment(threadId, senderId, travelerId,
                new BigDecimal("5000"), new BigDecimal("0.10"), "XOF", gridId, new BigDecimal("700"));

        assertThat(pending.paymentId()).isEqualTo(pendingPayment.getId());
        assertThat(pending.gross()).isEqualByComparingTo("5650");
        assertThat(pending.commission()).isEqualByComparingTo("650");
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void createPendingPayment_noGrid_keepsNetTimesRate() {
        when(paymentRepository.findByNegotiationThreadId(threadId)).thenReturn(Optional.empty());
        when(paymentRepository.save(any())).thenAnswer(inv -> {
            ReflectionTestUtils.setField((Object) inv.getArgument(0), "id", UUID.randomUUID());
            return inv.getArgument(0);
        });

        var pending = service.createPendingPayment(threadId, senderId, travelerId,
                new BigDecimal("5000"), new BigDecimal("0.10"), "XOF", null, null);

        assertThat(pending.commission()).as("aucun plancher sans grille").isEqualByComparingTo("500");
        assertThat(pending.gross()).isEqualByComparingTo("5500");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(audit).log(eq("PAYMENT"), any(), eq(MobileMoneyNegotiationPaymentService.AUDIT_CREATED), eq(senderId),
                payload.capture());
        assertThat(payload.getValue()).doesNotContainKey("commissionGridId");
    }

    @Test
    void createPendingPayment_gridWithoutCommission_isRejected() {
        assertThatThrownBy(() -> service.createPendingPayment(threadId, senderId, travelerId,
                new BigDecimal("5000"), new BigDecimal("0.10"), "XOF", gridId, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createPendingPayment(threadId, senderId, travelerId,
                new BigDecimal("5000"), new BigDecimal("0.10"), "XOF", null, new BigDecimal("650")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(paymentRepository, never()).save(any());
    }
}
