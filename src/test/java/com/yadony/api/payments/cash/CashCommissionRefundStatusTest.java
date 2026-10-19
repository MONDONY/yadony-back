package com.yadony.api.payments.cash;

import com.stripe.exception.ApiException;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CashCommissionService#refundCommission} lit le statut du Refund Stripe renvoyé :
 * un refus sans exception ({@code failed}, {@code canceled}) ne doit plus passer pour un
 * remboursement réussi.
 */
@ExtendWith(MockitoExtension.class)
class CashCommissionRefundStatusTest {

    @Mock private UserRepository userRepo;
    @Mock private BidRepository bidRepo;
    @Mock private AnnouncementRepository announcementRepo;
    @Mock private ApplicationEventPublisher events;
    @Mock private com.yadony.api.payments.wallet.WalletService walletService;
    @Mock private com.yadony.api.payments.wallet.WalletTransactionRepository walletTransactionRepository;
    @Mock private com.yadony.api.common.AuditService auditService;
    @Mock private CommissionRateResolver commissionRateResolver;
    @Mock private com.yadony.api.requests.repository.NegotiationThreadRepository negotiationThreadRepository;
    @Mock private com.yadony.api.matching.BidGridItemRepository bidGridItemRepository;
    @Mock private com.yadony.api.voucher.CommissionVoucherService voucherService;
    @Mock private com.yadony.api.payments.currency.ActiveCurrencyResolver activeCurrencyResolver;
    @Mock private com.yadony.api.payments.currency.ExchangeRateService exchangeRateService;
    @Mock private com.yadony.api.promo.PromoService promoService;
    @Mock private StripeCashGateway stripeCashGateway;

    private CashCommissionService service;
    private BidEntity bid;

    @BeforeEach
    void setUp() {
        service = new CashCommissionService(
                new CommissionProperties(new BigDecimal("0.12"), new BigDecimal("1.00"), 24),
                userRepo, bidRepo, announcementRepo, events, walletService, walletTransactionRepository,
                auditService, commissionRateResolver, negotiationThreadRepository, stripeCashGateway,
                bidGridItemRepository, mock(com.yadony.api.auth.FirebaseContactService.class), voucherService,
                activeCurrencyResolver, new WalletCommissionCollector(walletService, exchangeRateService),
                TestMessages.resolver(), promoService);
        bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", UUID.randomUUID());
        bid.setCommissionStatus(CommissionStatus.CHARGED);
        bid.setCommissionPaymentIntentId("pi_commission");
    }

    private void stripeReturns(String status) throws Exception {
        Refund refund = new Refund();
        refund.setId("re_1");
        refund.setStatus(status);
        when(stripeCashGateway.createRefund(any(RefundCreateParams.class), any(RequestOptions.class)))
                .thenReturn(refund);
    }

    @Test
    void succeeded_marksRefunded() throws Exception {
        stripeReturns("succeeded");

        service.refundCommission(bid);

        assertThat(bid.getCommissionStatus()).isEqualTo(CommissionStatus.REFUNDED);
        verify(bidRepo).save(bid);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "requires_action"})
    void inFlight_marksRefunded_webhookFollowsUp(String status) throws Exception {
        stripeReturns(status);

        service.refundCommission(bid);

        assertThat(bid.getCommissionStatus()).isEqualTo(CommissionStatus.REFUNDED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"failed", "canceled"})
    void refusedWithoutException_marksRefundFailed(String status) throws Exception {
        stripeReturns(status);

        service.refundCommission(bid);

        assertThat(bid.getCommissionStatus()).isEqualTo(CommissionStatus.REFUND_FAILED);
        verify(bidRepo).save(bid);
    }

    @Test
    void stripeException_marksRefundFailed() throws Exception {
        when(stripeCashGateway.createRefund(any(RefundCreateParams.class), any(RequestOptions.class)))
                .thenThrow(new ApiException("boom", "req_1", null, 500, null));

        service.refundCommission(bid);

        assertThat(bid.getCommissionStatus()).isEqualTo(CommissionStatus.REFUND_FAILED);
    }

    @Test
    void nullRefund_keepsHistoricalBehaviour_refunded() throws Exception {
        when(stripeCashGateway.createRefund(any(RefundCreateParams.class), any(RequestOptions.class)))
                .thenReturn(null);

        service.refundCommission(bid);

        assertThat(bid.getCommissionStatus()).isEqualTo(CommissionStatus.REFUNDED);
    }
}
