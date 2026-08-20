package com.yadony.api.payments.wallet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.model.Charge;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.stripe.AdminAlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WalletSelfRefundServiceTest {

    @Mock WalletAccountRepository walletAccountRepository;
    @Mock WalletTransactionRepository walletTransactionRepository;
    @Mock WalletRefundRequestRepository refundRequestRepository;
    @Mock WalletRefundRequestItemRepository refundRequestItemRepository;
    @Mock WalletService walletService;
    @Mock AuditService auditService;
    @Mock AdminAlertService adminAlertService;

    WalletSelfRefundService service;

    private static final UUID USER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new WalletSelfRefundService(walletAccountRepository, walletTransactionRepository,
                refundRequestRepository, refundRequestItemRepository, walletService,
                auditService, adminAlertService, new ObjectMapper());
    }

    @Test
    void isEligible_trueWhenWalletRefundEligible() {
        when(walletAccountRepository.findByUserIdAndCurrency(USER_ID, "EUR"))
                .thenReturn(Optional.of(eligibleWallet()));

        assertThat(service.isEligible(USER_ID, "EUR")).isTrue();
    }

    @Test
    void request_throwsWhenNotEligible() {
        WalletAccountEntity tainted = eligibleWallet();
        tainted.setRefundEligibleAmount(new BigDecimal("10.00"));
        when(walletAccountRepository.findByUserIdAndCurrency(USER_ID, "EUR")).thenReturn(Optional.of(tainted));

        assertThatThrownBy(() -> service.request(USER_ID, "EUR"))
                .isInstanceOf(YadonyBusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", "wallet-not-refund-eligible");
    }

    @Test
    void request_createsParentAndOneItemPerEligibleTopUp_thenCallsStripePerItem() {
        WalletAccountEntity wallet = eligibleWallet();
        when(refundRequestRepository.findByUserIdAndCurrencyAndStatusIn(
                USER_ID, "EUR", List.of(WalletRefundRequestStatus.PENDING, WalletRefundRequestStatus.PROCESSING)))
                .thenReturn(Optional.empty());
        when(walletAccountRepository.findByUserIdAndCurrency(USER_ID, "EUR")).thenReturn(Optional.of(wallet));
        WalletTransactionEntity topup1 = topup("pi_111", "30.00");
        WalletTransactionEntity topup2 = topup("pi_222", "20.00");
        when(walletTransactionRepository.findByUserIdAndCurrencyAndTypeAndCreatedAtGreaterThanEqual(
                USER_ID, "EUR", WalletTransactionType.TOP_UP, wallet.getRefundEligibleSince()))
                .thenReturn(List.of(topup1, topup2));

        List<WalletRefundRequestItemEntity> savedItems = new ArrayList<>();
        when(refundRequestRepository.save(any())).thenAnswer(inv -> {
            WalletRefundRequestEntity request = inv.getArgument(0);
            if (request.getId() == null) setId(request, UUID.randomUUID());
            return request;
        });
        when(refundRequestItemRepository.save(any())).thenAnswer(inv -> {
            WalletRefundRequestItemEntity item = inv.getArgument(0);
            if (!savedItems.contains(item)) {
                savedItems.add(item);
            }
            return item;
        });
        when(refundRequestItemRepository.findByRefundRequestId(any())).thenAnswer(inv -> savedItems);

        try (MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            Refund refund = mock(Refund.class);
            when(refund.getId()).thenReturn("re_abc");
            refundStatic.when(() -> Refund.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                    .thenReturn(refund);

            WalletRefundRequestEntity result = service.request(USER_ID, "EUR");

            assertThat(result.getChannel()).isEqualTo(WalletRefundChannel.AUTOMATIC_STRIPE);
            assertThat(result.getStatus()).isEqualTo(WalletRefundRequestStatus.PROCESSING);
            assertThat(result.getAmount()).isEqualByComparingTo("50.00");
            assertThat(savedItems).hasSize(2);
            assertThat(savedItems).extracting(WalletRefundRequestItemEntity::getStatus)
                    .containsOnly(WalletRefundItemStatus.PROCESSING);
        }
    }

    @Test
    void request_reusesOpenRequest() {
        WalletRefundRequestEntity existing = new WalletRefundRequestEntity();
        existing.setUserId(USER_ID);
        existing.setCurrency("EUR");
        existing.setStatus(WalletRefundRequestStatus.PROCESSING);
        when(refundRequestRepository.findByUserIdAndCurrencyAndStatusIn(
                USER_ID, "EUR", List.of(WalletRefundRequestStatus.PENDING, WalletRefundRequestStatus.PROCESSING)))
                .thenReturn(Optional.of(existing));

        WalletRefundRequestEntity result = service.request(USER_ID, "EUR");

        assertThat(result).isSameAs(existing);
        verifyNoInteractions(walletAccountRepository, walletTransactionRepository, refundRequestItemRepository);
    }

    @Test
    void handleChargeRefunded_marksItemRefundedAndResolvesRequestWhenAllItemsTerminal() {
        UUID requestId = UUID.randomUUID();
        WalletRefundRequestItemEntity item = new WalletRefundRequestItemEntity();
        item.setRefundRequestId(requestId);
        item.setPaymentIntentId("pi_111");
        item.setAmount(new BigDecimal("30.00"));
        item.setStatus(WalletRefundItemStatus.PROCESSING);
        when(refundRequestItemRepository.findByPaymentIntentId("pi_111")).thenReturn(Optional.of(item));
        when(refundRequestItemRepository.findByRefundRequestId(requestId)).thenReturn(List.of(item));
        WalletRefundRequestEntity request = new WalletRefundRequestEntity();
        request.setUserId(USER_ID);
        request.setCurrency("EUR");
        request.setStatus(WalletRefundRequestStatus.PROCESSING);
        when(refundRequestRepository.findById(requestId)).thenReturn(Optional.of(request));
        when(refundRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(refundRequestItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Charge charge = mock(Charge.class);
        when(charge.getPaymentIntent()).thenReturn("pi_111");
        when(charge.getAmountRefunded()).thenReturn(3000L);
        when(charge.getAmount()).thenReturn(3000L);

        service.handleChargeRefunded(charge);

        assertThat(item.getStatus()).isEqualTo(WalletRefundItemStatus.REFUNDED);
        assertThat(request.getStatus()).isEqualTo(WalletRefundRequestStatus.REFUNDED);
        verify(walletService).debitConfirmedRefund(USER_ID, "EUR", new BigDecimal("30.00"),
                WalletTransactionType.SELF_REFUND_OUT);
    }

    @Test
    void handleChargeRefunded_noOpWhenPaymentIntentUnknown() {
        when(refundRequestItemRepository.findByPaymentIntentId("pi_unknown")).thenReturn(Optional.empty());
        Charge charge = mock(Charge.class);
        when(charge.getPaymentIntent()).thenReturn("pi_unknown");

        service.handleChargeRefunded(charge);

        verifyNoInteractions(walletService);
    }

    private WalletAccountEntity eligibleWallet() {
        WalletAccountEntity wallet = new WalletAccountEntity();
        wallet.setUserId(USER_ID);
        wallet.setCurrency("EUR");
        wallet.setBalance(new BigDecimal("50.00"));
        wallet.setRefundEligibleAmount(new BigDecimal("50.00"));
        wallet.setRefundEligibleSince(Instant.now().minusSeconds(60));
        return wallet;
    }

    private WalletTransactionEntity topup(String paymentRef, String amount) {
        WalletTransactionEntity topup = new WalletTransactionEntity();
        setId(topup, UUID.randomUUID());
        topup.setUserId(USER_ID);
        topup.setCurrency("EUR");
        topup.setPaymentRef(paymentRef);
        topup.setAmount(new BigDecimal(amount));
        return topup;
    }

    private static void setId(Object entity, UUID id) {
        try {
            Class<?> type = entity.getClass();
            Field field = null;
            while (type != null && field == null) {
                try {
                    field = type.getDeclaredField("id");
                } catch (NoSuchFieldException ignored) {
                    type = type.getSuperclass();
                }
            }
            if (field == null) {
                throw new NoSuchFieldException("id");
            }
            field.setAccessible(true);
            field.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
