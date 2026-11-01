package com.yadony.api.payments.cash;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidGridItemRepository;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.cash.dto.AcceptBidResponse;
import com.yadony.api.payments.cash.dto.AcceptanceStatusDto;
import com.yadony.api.payments.wallet.WalletService;
import com.yadony.api.payments.wallet.WalletTransactionRepository;
import com.yadony.api.payments.wallet.WalletTransactionType;
import com.yadony.api.requests.entity.NegotiationThreadEntity;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Audit doublons de paiement (01/11) : une commission espèces ne doit jamais être payée deux
 * fois, par carte puis par portefeuille, ou par deux PaymentIntents carte. Couvre le règlement
 * d'une commission de négociation et le rapprochement des succès carte reçus par webhook.
 */
@ExtendWith(MockitoExtension.class)
class CashCommissionDuplicateCardChargeTest {

    @Mock private UserRepository userRepo;
    @Mock private BidRepository bidRepo;
    @Mock private AnnouncementRepository announcementRepo;
    @Mock private ApplicationEventPublisher events;
    @Mock private WalletService walletService;
    @Mock private WalletTransactionRepository walletTransactionRepository;
    @Mock private AuditService auditService;
    @Mock private CommissionRateResolver commissionRateResolver;
    @Mock private NegotiationThreadRepository negotiationThreadRepository;
    @Mock private StripeCashGateway stripeCashGateway;
    @Mock private BidGridItemRepository bidGridItemRepository;
    @Mock private com.yadony.api.voucher.CommissionVoucherService voucherService;
    @Mock private com.yadony.api.payments.currency.ActiveCurrencyResolver activeCurrencyResolver;
    @Mock private com.yadony.api.payments.currency.ExchangeRateService exchangeRateService;
    @Mock private AdminAlertEscalator alertEscalator;

    private final CommissionProperties props =
            new CommissionProperties(new BigDecimal("0.12"), new BigDecimal("1.00"), 24);

    private CashCommissionService service;
    private final UUID travelerId = UUID.randomUUID();
    private final UUID senderId = UUID.randomUUID();
    private final UUID threadId = UUID.randomUUID();
    private NegotiationThreadEntity thread;

    @BeforeEach
    void setUp() {
        lenient().when(activeCurrencyResolver.resolve(any())).thenReturn("EUR");
        FixedRateConversions.stubRoundedConversion(exchangeRateService);
        FirebaseContactService contacts = mock(FirebaseContactService.class);
        service = new CashCommissionService(props, userRepo, bidRepo, announcementRepo, events,
                walletService, walletTransactionRepository, auditService, commissionRateResolver,
                negotiationThreadRepository, stripeCashGateway, bidGridItemRepository, contacts,
                voucherService, activeCurrencyResolver,
                new WalletCommissionCollector(walletService, exchangeRateService,
                        mock(com.yadony.api.payments.currency.ExchangeRateFreshness.class)), null,
                mock(com.yadony.api.promo.PromoService.class));
        ReflectionTestUtils.setField(service, "alertEscalator", alertEscalator);

        thread = new NegotiationThreadEntity();
        ReflectionTestUtils.setField(thread, "id", threadId);
        ReflectionTestUtils.setField(thread, "travelerId", travelerId);
        lenient().when(negotiationThreadRepository.findById(threadId)).thenReturn(Optional.of(thread));
        // Fil sans grille, taux 5 % : 100.00 EUR de net donnent 5.00 EUR de commission.
        lenient().when(commissionRateResolver.resolve(eq(travelerId), eq(senderId), isNull(), isNull(), any()))
                .thenReturn(new BigDecimal("0.05"));
        lenient().when(walletService.getBalanceForUpdate(travelerId, "EUR")).thenReturn(new BigDecimal("20.00"));
    }

    private static PaymentIntent intent(String id, String status) {
        PaymentIntent pi = new PaymentIntent();
        pi.setId(id);
        pi.setStatus(status);
        pi.setAmount(500L);
        pi.setCurrency("eur");
        return pi;
    }

    private static PaymentIntent negotiationIntent(UUID threadId, String id) {
        PaymentIntent pi = intent(id, "succeeded");
        Map<String, String> meta = new HashMap<>();
        meta.put("commission_purpose", "cash_negotiation");
        meta.put("negotiation_thread_id", threadId.toString());
        pi.setMetadata(meta);
        return pi;
    }

    private AcceptBidResponse settle(CommissionSource source) {
        return service.settleNegotiationCommission(
                travelerId, senderId, threadId, new BigDecimal("100.00"), source);
    }

    private void verifyWalletNeverDebited() {
        verify(walletService, never()).debit(any(), any(), any(), any(), any(), any());
        verify(walletService, never()).getBalanceForUpdate(any(), any());
    }

    // ===================== settleNegotiationCommission =====================

    @Nested
    class SettleNegotiation {

        @Test
        void walletFirst_liveCardAttempt_isCanceledBeforeTheWalletIsDebited() throws StripeException {
            thread.setCommissionStatus("REQUIRES_3DS");
            thread.setCommissionPaymentIntentId("pi_nego_live");
            thread.setCommissionRetryCount(1);
            when(stripeCashGateway.retrievePaymentIntent("pi_nego_live"))
                    .thenReturn(intent("pi_nego_live", "requires_action"));
            when(stripeCashGateway.cancelPaymentIntent("pi_nego_live"))
                    .thenReturn(intent("pi_nego_live", "canceled"));

            AcceptBidResponse resp = settle(CommissionSource.WALLET_FIRST);

            assertThat(resp.status()).isEqualTo(AcceptanceStatusDto.ACCEPTED);
            var order = inOrder(stripeCashGateway, walletService);
            order.verify(stripeCashGateway).cancelPaymentIntent("pi_nego_live");
            order.verify(walletService).debit(eq(travelerId), eq("EUR"), eq(new BigDecimal("5.00")),
                    eq(WalletTransactionType.COMMISSION_DEDUCTED), eq(threadId.toString()),
                    eq("nego_commission_wallet_" + threadId));
            assertThat(thread.getCommissionChargedVia()).isEqualTo("WALLET");
            assertThat(thread.getCommissionPaymentIntentId()).isNull();
            assertThat(thread.getCommissionRetryCount()).isEqualTo(2);
        }

        @Test
        void walletFirst_cardAttemptAlreadySucceeded_settlesByCardWithoutTouchingTheWallet() throws StripeException {
            thread.setCommissionStatus("REQUIRES_3DS");
            thread.setCommissionPaymentIntentId("pi_nego_done");
            when(stripeCashGateway.retrievePaymentIntent("pi_nego_done"))
                    .thenReturn(intent("pi_nego_done", "succeeded"));

            AcceptBidResponse resp = settle(CommissionSource.WALLET_FIRST);

            assertThat(resp.status()).isEqualTo(AcceptanceStatusDto.ACCEPTED);
            assertThat(thread.getCommissionStatus()).isEqualTo("CHARGED");
            assertThat(thread.getCommissionChargedVia()).isEqualTo("CARD");
            verifyWalletNeverDebited();
            verify(stripeCashGateway, never()).cancelPaymentIntent(anyString());
        }

        @Test
        void card_succeededIntentLinkedByWebhook_settlesWithoutASecondCharge() throws StripeException {
            // Issue réseau inconnue au premier essai : le PaymentIntent a abouti, le webhook l'a rattaché.
            thread.setCommissionPaymentIntentId("pi_nego_linked");
            when(stripeCashGateway.retrievePaymentIntent("pi_nego_linked"))
                    .thenReturn(intent("pi_nego_linked", "succeeded"));

            AcceptBidResponse resp = settle(CommissionSource.CARD);

            assertThat(resp.status()).isEqualTo(AcceptanceStatusDto.ACCEPTED);
            assertThat(thread.getCommissionStatus()).isEqualTo("CHARGED");
            assertThat(thread.getCommissionChargedVia()).isEqualTo("CARD");
            verify(stripeCashGateway, never()).createPaymentIntent(any(PaymentIntentCreateParams.class), any(RequestOptions.class));
        }

        @Test
        void refundedCommission_itsSucceededIntentNoLongerCountsAsPayment() throws StripeException {
            // Un PaymentIntent remboursé reste « succeeded » chez Stripe.
            thread.setCommissionStatus("REFUNDED");
            thread.setCommissionPaymentIntentId("pi_nego_refunded");

            AcceptBidResponse resp = settle(CommissionSource.WALLET_FIRST);

            assertThat(resp.status()).isEqualTo(AcceptanceStatusDto.ACCEPTED);
            assertThat(thread.getCommissionChargedVia()).isEqualTo("WALLET");
            verify(walletService).debit(eq(travelerId), eq("EUR"), eq(new BigDecimal("5.00")),
                    eq(WalletTransactionType.COMMISSION_DEDUCTED), eq(threadId.toString()),
                    eq("nego_commission_wallet_" + threadId));
            verify(stripeCashGateway, never()).retrievePaymentIntent(anyString());
        }

        @Test
        void walletFirst_cardAttemptStillProcessing_refusedWithoutDebit() throws StripeException {
            thread.setCommissionStatus("REQUIRES_3DS");
            thread.setCommissionPaymentIntentId("pi_nego_processing");
            when(stripeCashGateway.retrievePaymentIntent("pi_nego_processing"))
                    .thenReturn(intent("pi_nego_processing", "processing"));

            assertThatThrownBy(() -> settle(CommissionSource.WALLET_FIRST))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        YadonyBusinessException y = (YadonyBusinessException) e;
                        assertThat(y.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(y.getErrorCode()).isEqualTo("commission-card-attempt-pending");
                    });
            verifyWalletNeverDebited();
        }

        @Test
        void walletFirst_cancelNotConfirmed_refusedWithoutDebit() throws StripeException {
            thread.setCommissionStatus("REQUIRES_3DS");
            thread.setCommissionPaymentIntentId("pi_nego_live");
            when(stripeCashGateway.retrievePaymentIntent("pi_nego_live"))
                    .thenReturn(intent("pi_nego_live", "requires_action"));
            when(stripeCashGateway.cancelPaymentIntent("pi_nego_live"))
                    .thenReturn(intent("pi_nego_live", "requires_action"));

            assertThatThrownBy(() -> settle(CommissionSource.WALLET_FIRST))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("commission-card-attempt-pending"));
            verifyWalletNeverDebited();
            assertThat(thread.getCommissionPaymentIntentId()).isEqualTo("pi_nego_live");
        }

        @Test
        void walletFirst_unreadableCardAttempt_refusedWithoutDebit() throws StripeException {
            thread.setCommissionStatus("REQUIRES_3DS");
            thread.setCommissionPaymentIntentId("pi_nego_live");
            when(stripeCashGateway.retrievePaymentIntent("pi_nego_live"))
                    .thenThrow(new ApiConnectionException("timeout"));

            assertThatThrownBy(() -> settle(CommissionSource.WALLET_FIRST))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.BAD_GATEWAY));
            verifyWalletNeverDebited();
        }

        @Test
        void locksTheThreadAndReadsTheCommittedCommissionStateFirst() throws StripeException {
            // Un webhook a rattaché un PaymentIntent abouti entre le chargement du fil et le verrou.
            NegotiationThreadRepository.CommissionState committed = new NegotiationThreadRepository.CommissionState() {
                @Override public String getCommissionStatus() { return null; }
                @Override public String getCommissionPaymentIntentId() { return "pi_nego_linked"; }
                @Override public String getCommissionChargedVia() { return null; }
            };
            when(negotiationThreadRepository.findCommissionStateById(threadId)).thenReturn(Optional.of(committed));
            when(stripeCashGateway.retrievePaymentIntent("pi_nego_linked"))
                    .thenReturn(intent("pi_nego_linked", "succeeded"));

            AcceptBidResponse resp = settle(CommissionSource.WALLET_FIRST);

            assertThat(resp.status()).isEqualTo(AcceptanceStatusDto.ACCEPTED);
            var order = inOrder(negotiationThreadRepository);
            order.verify(negotiationThreadRepository).lockForUpdate(threadId);
            order.verify(negotiationThreadRepository).findCommissionStateById(threadId);
            verifyWalletNeverDebited();
        }
    }

    // ===================== reconcileNegotiationCardCommission (webhook) =====================

    @Nested
    class ReconcileNegotiationWebhook {

        @Test
        void threadAlreadySettledByWallet_refundsTheCardChargeAsADuplicate() throws StripeException {
            thread.setCommissionStatus("CHARGED");
            thread.setCommissionChargedVia("WALLET");
            when(stripeCashGateway.createRefund(any(RefundCreateParams.class), any(RequestOptions.class)))
                    .thenReturn(new Refund());

            service.reconcileNegotiationCardCommission(negotiationIntent(threadId, "pi_dup"));

            ArgumentCaptor<RefundCreateParams> params = ArgumentCaptor.forClass(RefundCreateParams.class);
            ArgumentCaptor<RequestOptions> opts = ArgumentCaptor.forClass(RequestOptions.class);
            verify(stripeCashGateway).createRefund(params.capture(), opts.capture());
            assertThat(params.getValue().getPaymentIntent()).isEqualTo("pi_dup");
            assertThat(opts.getValue().getIdempotencyKey()).isEqualTo("commission_duplicate_refund_pi_dup");
            verify(auditService).log(eq("NEGOTIATION_THREAD"), eq(threadId), eq("CASH_COMMISSION_DUPLICATE_REFUNDED"),
                    isNull(), any());
            // Le règlement portefeuille, lui, reste intact.
            assertThat(thread.getCommissionStatus()).isEqualTo("CHARGED");
            assertThat(thread.getCommissionChargedVia()).isEqualTo("WALLET");
        }

        @Test
        void threadSettledByAnotherCardIntent_refundsTheLateOne() throws StripeException {
            thread.setCommissionStatus("CHARGED");
            thread.setCommissionChargedVia("CARD");
            thread.setCommissionPaymentIntentId("pi_kept");
            when(stripeCashGateway.createRefund(any(RefundCreateParams.class), any(RequestOptions.class)))
                    .thenReturn(new Refund());

            service.reconcileNegotiationCardCommission(negotiationIntent(threadId, "pi_late"));

            ArgumentCaptor<RefundCreateParams> params = ArgumentCaptor.forClass(RefundCreateParams.class);
            verify(stripeCashGateway).createRefund(params.capture(), any(RequestOptions.class));
            assertThat(params.getValue().getPaymentIntent()).isEqualTo("pi_late");
            assertThat(thread.getCommissionPaymentIntentId()).isEqualTo("pi_kept");
        }

        @Test
        void sameCardIntentAsTheSettlement_isANoOp() {
            thread.setCommissionStatus("CHARGED");
            thread.setCommissionChargedVia("CARD");
            thread.setCommissionPaymentIntentId("pi_kept");

            service.reconcileNegotiationCardCommission(negotiationIntent(threadId, "pi_kept"));

            verifyNoInteractions(stripeCashGateway);
            verify(negotiationThreadRepository, never()).save(any());
        }

        @Test
        void unsettledThreadWithoutIntent_linksTheSucceededIntent() {
            thread.setCommissionStatus(null);

            service.reconcileNegotiationCardCommission(negotiationIntent(threadId, "pi_lost_response"));

            assertThat(thread.getCommissionPaymentIntentId()).isEqualTo("pi_lost_response");
            assertThat(thread.getCommissionStatus()).as("le règlement reste celui de NegotiationService").isNull();
            verify(negotiationThreadRepository).save(thread);
            verifyNoInteractions(stripeCashGateway);
        }

        @Test
        void unsettledThreadWithAnotherIntent_alertsWithoutRelinking() {
            thread.setCommissionStatus("REQUIRES_3DS");
            thread.setCommissionPaymentIntentId("pi_current");

            service.reconcileNegotiationCardCommission(negotiationIntent(threadId, "pi_other"));

            assertThat(thread.getCommissionPaymentIntentId()).isEqualTo("pi_current");
            verify(alertEscalator).raiseOnce(eq("COMMISSION_PI_UNLINKED_pi_other"), anyString(), any());
            verifyNoInteractions(stripeCashGateway);
        }

        @Test
        void locksTheThreadBeforeReadingIt() {
            thread.setCommissionStatus("CHARGED");
            thread.setCommissionChargedVia("CARD");
            thread.setCommissionPaymentIntentId("pi_kept");

            service.reconcileNegotiationCardCommission(negotiationIntent(threadId, "pi_kept"));

            var order = inOrder(negotiationThreadRepository);
            order.verify(negotiationThreadRepository).lockForUpdate(threadId);
            order.verify(negotiationThreadRepository).findById(threadId);
        }

        @Test
        void missingThreadId_isIgnored() {
            PaymentIntent pi = intent("pi_x", "succeeded");
            pi.setMetadata(Map.of("commission_purpose", "cash_negotiation"));

            service.reconcileNegotiationCardCommission(pi);

            verifyNoInteractions(negotiationThreadRepository, stripeCashGateway);
        }
    }

    // ===================== refundDuplicateCardCommission =====================

    @Nested
    class RefundDuplicate {

        @Test
        void stripeError_alertsAndRethrowsSoTheWebhookIsReplayed() throws StripeException {
            UUID bidId = UUID.randomUUID();
            when(stripeCashGateway.createRefund(any(RefundCreateParams.class), any(RequestOptions.class)))
                    .thenThrow(new ApiConnectionException("reset"));

            assertThatThrownBy(() -> service.refundDuplicateCardCommission(intent("pi_dup", "succeeded"), "payment", bidId))
                    .isInstanceOf(IllegalStateException.class);
            verify(alertEscalator).raiseOnce(eq("COMMISSION_DUP_REFUND_pi_dup"), anyString(), any());
        }

        @Test
        void refundRefusedByStripe_alertsWithoutRethrowing() throws StripeException {
            Refund failed = new Refund();
            failed.setStatus("failed");
            when(stripeCashGateway.createRefund(any(RefundCreateParams.class), any(RequestOptions.class)))
                    .thenReturn(failed);

            service.refundDuplicateCardCommission(intent("pi_dup", "succeeded"), "payment", UUID.randomUUID());

            verify(alertEscalator).raiseOnce(eq("COMMISSION_DUP_REFUND_pi_dup"), anyString(), any());
            verify(auditService).log(eq("payment"), any(), eq("CASH_COMMISSION_DUPLICATE_REFUND_FAILED"), isNull(), any());
        }

        @Test
        void alertTypes_fitInAdminAlertsTypeColumn() {
            // Identifiant Stripe réaliste : « pi_ » + 24 caractères.
            String piId = "pi_" + "3QxYzAbCdEfGhIjKlMnOpQrS";
            assertThat(("COMMISSION_DUP_REFUND_" + piId).length()).isLessThanOrEqualTo(AdminAlertEscalator.TYPE_MAX_LENGTH);
            assertThat(("COMMISSION_PI_UNLINKED_" + piId).length()).isLessThanOrEqualTo(AdminAlertEscalator.TYPE_MAX_LENGTH);
        }
    }
}
