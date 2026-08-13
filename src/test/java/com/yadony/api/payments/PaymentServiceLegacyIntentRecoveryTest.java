package com.yadony.api.payments;

import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCancelParams;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidGridItemRepository;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.currency.CurrencyMatchGuard;
import com.yadony.api.payments.dto.CreatePaymentRequest;
import com.yadony.api.payments.dto.PaymentResponse;
import com.yadony.api.promo.PromoService;
import com.yadony.api.settings.UserBusinessPrefsEntity;
import com.yadony.api.payments.currency.ActiveCurrencyResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceLegacyIntentRecoveryTest {

    @Mock UserRepository userRepository;
    @Mock BidRepository bidRepository;
    @Mock BidGridItemRepository bidGridItemRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock AdminAlertService adminAlertService;
    @Mock CommissionRateResolver commissionRateResolver;
    @Mock PromoService promoService;
    @Mock StripeGateway stripeGateway;
    @Mock ActiveCurrencyResolver activeCurrencyResolver;

    @org.junit.jupiter.api.BeforeEach
    void stubDefaultActiveCurrency() {
        org.mockito.Mockito.lenient()
                .when(activeCurrencyResolver.resolve(org.mockito.ArgumentMatchers.any()))
                .thenReturn("EUR");
    }

    private PaymentService service;

    private final UUID senderId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private final UUID bidId = UUID.randomUUID();
    private final UUID announcementId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        lenient().when(commissionRateResolver.resolve(any(), any()))
                .thenReturn(new BigDecimal("0.12"));
        service = new PaymentService(
                userRepository,
                bidRepository,
                bidGridItemRepository,
                announcementRepository,
                paymentRepository,
                auditService,
                eventPublisher,
                PaymentServiceTestFactory.defaultConnectProperties(),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                adminAlertService,
                commissionRateResolver,
                promoService,
                stripeGateway,
                PaymentServiceTestFactory.stubbedContacts(),
                activeCurrencyResolver,
                new CurrencyMatchGuard());
    }

    @Test
    void bidRetrieveFailure_propagatesWithoutMutationRecycleOrCreate() throws Exception {
        PaymentEntity existing = bidPayment("pi_legacy", "28.00", "cad");
        stubExistingBid(existing, new BigDecimal("0.12"), null);
        StripeException stripeFailure = mock(StripeException.class);
        when(stripeGateway.retrievePaymentIntent("pi_legacy")).thenThrow(stripeFailure);

        Throwable thrown = catchThrowable(() -> service.createEscrow(paymentRequest(), "uid-sender"));

        assertStripeRecoveryStopped(thrown, existing);
    }

    @Test
    void bidCancelFailure_propagatesWithoutMutationRecycleOrCreate() throws Exception {
        PaymentEntity existing = bidPayment("pi_legacy", "25.00", "eur");
        stubExistingBid(existing, new BigDecimal("0.12"), null);
        PaymentIntent incompatible = incompatibleIntent("requires_payment_method", 2500L, "eur");
        when(stripeGateway.retrievePaymentIntent("pi_legacy")).thenReturn(incompatible);
        when(incompatible.cancel(any(PaymentIntentCancelParams.class)))
                .thenThrow(mock(StripeException.class));

        Throwable thrown = catchThrowable(() -> service.createEscrow(paymentRequest(), "uid-sender"));

        assertStripeRecoveryStopped(thrown, existing);
    }

    @Test
    void negotiationRetrieveFailure_propagatesWithoutMutationRecycleOrCreate() throws Exception {
        UUID threadId = UUID.randomUUID();
        PaymentEntity existing = negotiationPayment(threadId, "pi_legacy", "37.10", "eur");
        stubExistingNegotiation(threadId, existing);
        when(commissionRateResolver.resolve(travelerId, senderId))
                .thenReturn(new BigDecimal("0.06"));
        when(stripeGateway.retrievePaymentIntent("pi_legacy"))
                .thenThrow(mock(StripeException.class));

        Throwable thrown = catchThrowable(() -> service.createNegotiationEscrow(
                threadId, senderId, travelerId, new BigDecimal("35.00"), null, "EUR"));

        assertStripeRecoveryStopped(thrown, existing);
    }

    @Test
    void negotiationCancelFailure_propagatesWithoutMutationRecycleOrCreate() throws Exception {
        UUID threadId = UUID.randomUUID();
        PaymentEntity existing = negotiationPayment(threadId, "pi_legacy", "39.20", "eur");
        stubExistingNegotiation(threadId, existing);
        PaymentIntent incompatible = incompatibleIntent("requires_payment_method", 3920L, "cad");
        when(stripeGateway.retrievePaymentIntent("pi_legacy")).thenReturn(incompatible);
        when(incompatible.cancel(any(PaymentIntentCancelParams.class)))
                .thenThrow(mock(StripeException.class));

        Throwable thrown = catchThrowable(() -> service.createNegotiationEscrow(
                threadId, senderId, travelerId, new BigDecimal("35.00"), null, "EUR"));

        assertStripeRecoveryStopped(thrown, existing);
    }

    @Test
    void bidRetryAfterConfirmedCancelAndFailedCreate_recyclesCanceledIntent() throws Exception {
        PaymentEntity existing = bidPayment("pi_legacy", "25.00", "eur");
        stubExistingBid(existing, new BigDecimal("0.12"), null);

        PaymentIntent incompatible = incompatibleIntent("requires_payment_method", 2500L, "eur");
        PaymentIntent canceled = mock(PaymentIntent.class);
        when(canceled.getStatus()).thenReturn("canceled");
        when(incompatible.cancel(any(PaymentIntentCancelParams.class))).thenReturn(canceled);
        when(stripeGateway.retrievePaymentIntent("pi_legacy"))
                .thenReturn(incompatible, canceled);

        StripeException creationFailure = mock(StripeException.class);
        PaymentIntent fresh = freshIntent("pi_fresh");
        when(stripeGateway.createPaymentIntent(any()))
                .thenThrow(creationFailure)
                .thenReturn(fresh);
        when(paymentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Throwable firstFailure = catchThrowable(
                () -> service.createEscrow(paymentRequest(), "uid-sender"));
        assertThat(firstFailure).isInstanceOf(YadonyBusinessException.class);
        assertThat(((YadonyBusinessException) firstFailure).getErrorCode())
                .isEqualTo("payment-creation-failed");
        assertThat(existing.getStripePaymentIntentId()).isEqualTo("pi_legacy");

        PaymentResponse retried = service.createEscrow(paymentRequest(), "uid-sender");

        assertThat(retried.getStripePaymentIntentId()).isEqualTo("pi_fresh");
        assertThat(existing.getStripePaymentIntentId()).isEqualTo("pi_fresh");
        assertThat(existing.getStatus()).isEqualTo(PaymentStatus.PENDING);
        verify(incompatible).cancel(any(PaymentIntentCancelParams.class));
        verify(stripeGateway, org.mockito.Mockito.times(2)).createPaymentIntent(any());
        verify(paymentRepository).save(existing);
        verify(bidRepository, never()).save(any());
    }

    @Test
    void existingBidWithConsumedPromoAndChangedOverride_reusesPersistedRate() throws Exception {
        BidEntity bid = stubExistingBid(
                bidPayment("pi_existing", "26.50", "cad"),
                new BigDecimal("0.06"),
                "ONEUSE");
        UUID promoId = UUID.randomUUID();
        bid.setPromoCodeId(promoId);
        lenient().when(commissionRateResolver.resolve(travelerId, senderId, "ONEUSE"))
                .thenThrow(new YadonyBusinessException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "promo-limit-reached",
                        "Promo Limit Reached",
                        "perUserLimit=1 déjà consommé"));
        lenient().when(commissionRateResolver.resolve(travelerId, senderId))
                .thenReturn(new BigDecimal("0.01"));

        PaymentIntent compatible = mock(PaymentIntent.class);
        when(compatible.getStatus()).thenReturn("requires_payment_method");
        when(compatible.getAmount()).thenReturn(2650L);
        when(compatible.getCurrency()).thenReturn("cad");
        when(compatible.getClientSecret()).thenReturn("pi_existing_secret");
        when(compatible.getPaymentMethodTypes()).thenReturn(java.util.List.of("card"));
        when(stripeGateway.retrievePaymentIntent("pi_existing")).thenReturn(compatible);

        PaymentResponse response = service.createEscrow(paymentRequest(), "uid-sender");

        assertThat(response.getStripePaymentIntentId()).isEqualTo("pi_existing");
        assertThat(bid.getCommissionRate()).isEqualByComparingTo("0.06");
        assertThat(bid.getPromoCode()).isEqualTo("ONEUSE");
        assertThat(bid.getPromoCodeId()).isEqualTo(promoId);
        verifyNoInteractions(commissionRateResolver, promoService);
        verify(stripeGateway, never()).createPaymentIntent(any());
        verify(compatible, never()).cancel(any(PaymentIntentCancelParams.class));
        verify(bidRepository, never()).save(any());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void existingBidWithoutPersistedCommissionRate_failsClosedWithoutSideEffects() throws Exception {
        PaymentEntity existing = bidPayment("pi_existing", "26.50", "cad");
        BidEntity bid = stubExistingBid(existing, null, "WELCOME6");
        UUID promoId = UUID.randomUUID();
        bid.setPromoCodeId(promoId);

        Throwable thrown = catchThrowable(() -> service.createEscrow(paymentRequest(), "uid-sender"));

        assertAll(
                () -> assertThat(thrown).isInstanceOf(YadonyBusinessException.class),
                () -> assertThat(thrown).isInstanceOfSatisfying(
                        YadonyBusinessException.class,
                        error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT)),
                () -> assertThat(thrown).isInstanceOfSatisfying(
                        YadonyBusinessException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo("payment-pricing-context-missing")),
                () -> verifyNoInteractions(stripeGateway, commissionRateResolver, promoService, auditService),
                () -> verify(bidRepository, never()).save(any()),
                () -> verify(paymentRepository, never()).save(any()),
                () -> verify(userRepository, never()).save(any()),
                () -> verify(announcementRepository, never()).save(any()),
                () -> verify(bidGridItemRepository, never()).save(any()),
                () -> assertThat(bid.getCommissionRate()).isNull(),
                () -> assertThat(bid.getPromoCode()).isEqualTo("WELCOME6"),
                () -> assertThat(bid.getPromoCodeId()).isEqualTo(promoId),
                () -> assertThat(existing.getStripePaymentIntentId()).isEqualTo("pi_existing"),
                () -> assertThat(existing.getStatus()).isEqualTo(PaymentStatus.PENDING));
    }

    @Test
    void compatibleNegotiationResume_returnsAppliedRateAndPromo() throws Exception {
        UUID threadId = UUID.randomUUID();
        PaymentEntity existing = negotiationPayment(threadId, "pi_existing", "37.10", "eur");
        existing.setCommissionAmount(new BigDecimal("2.10"));
        stubExistingNegotiation(threadId, existing);
        when(commissionRateResolver.resolve(travelerId, senderId, "WELCOME6"))
                .thenReturn(new BigDecimal("0.06"));

        PaymentIntent compatible = mock(PaymentIntent.class);
        when(compatible.getStatus()).thenReturn("requires_payment_method");
        when(compatible.getAmount()).thenReturn(3710L);
        when(compatible.getCurrency()).thenReturn("eur");
        when(compatible.getClientSecret()).thenReturn("pi_existing_secret");
        when(compatible.getPaymentMethodTypes()).thenReturn(java.util.List.of("card"));
        when(stripeGateway.retrievePaymentIntent("pi_existing")).thenReturn(compatible);

        PaymentResponse response = service.createNegotiationEscrow(
                threadId, senderId, travelerId, new BigDecimal("35.00"), "WELCOME6", "EUR");

        assertThat(response.getStripePaymentIntentId()).isEqualTo("pi_existing");
        assertThat(response.getCommissionRate()).isEqualByComparingTo("0.06");
        assertThat(response.isPromoApplied()).isTrue();
        verify(stripeGateway, never()).createPaymentIntent(any());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void compatibleNegotiationResume_usesPersistedThreadRateWithoutPromoRevalidation() throws Exception {
        UUID threadId = UUID.randomUUID();
        PaymentEntity existing = negotiationPayment(threadId, "pi_existing", "37.10", "eur");
        existing.setCommissionAmount(new BigDecimal("2.10"));
        stubExistingNegotiation(threadId, existing);
        lenient().when(commissionRateResolver.resolve(travelerId, senderId, "WELCOME6"))
                .thenThrow(new YadonyBusinessException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "promo-limit-reached",
                        "Promo Limit Reached",
                        "perUserLimit=1 déjà consommé"));

        PaymentIntent compatible = mock(PaymentIntent.class);
        when(compatible.getStatus()).thenReturn("requires_payment_method");
        when(compatible.getAmount()).thenReturn(3710L);
        when(compatible.getCurrency()).thenReturn("eur");
        when(compatible.getClientSecret()).thenReturn("pi_existing_secret");
        when(compatible.getPaymentMethodTypes()).thenReturn(java.util.List.of("card"));
        when(stripeGateway.retrievePaymentIntent("pi_existing")).thenReturn(compatible);

        PaymentResponse response = service.createNegotiationEscrow(
                threadId,
                senderId,
                travelerId,
                new BigDecimal("35.00"),
                "WELCOME6",
                new BigDecimal("0.06"),
                "EUR");

        assertThat(response.getStripePaymentIntentId()).isEqualTo("pi_existing");
        assertThat(response.getCommissionRate()).isEqualByComparingTo("0.06");
        assertThat(response.isPromoApplied()).isTrue();
        verifyNoInteractions(commissionRateResolver);
        verify(stripeGateway, never()).createPaymentIntent(any());
        verify(paymentRepository, never()).save(any());
    }

    private BidEntity stubExistingBid(PaymentEntity payment, BigDecimal persistedRate, String promoCode)
            throws StripeException {
        UserEntity sender = sender();
        UserEntity traveler = traveler();
        BidEntity bid = bid(persistedRate, promoCode);
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        lenient().when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement()));
        when(activeCurrencyResolver.resolve(senderId)).thenReturn("CAD");
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.of(payment));
        Account account = activeAccount();
        lenient().when(stripeGateway.retrieveAccount("acct_traveler")).thenReturn(account);
        return bid;
    }

    private void stubExistingNegotiation(UUID threadId, PaymentEntity payment) throws StripeException {
        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender()));
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler()));
        when(activeCurrencyResolver.resolve(senderId)).thenReturn("EUR");
        when(paymentRepository.findByNegotiationThreadId(threadId)).thenReturn(Optional.of(payment));
        Account account = activeAccount();
        lenient().when(stripeGateway.retrieveAccount("acct_traveler")).thenReturn(account);
    }

    private void assertStripeRecoveryStopped(Throwable thrown, PaymentEntity payment) {
        assertAll(
                () -> assertThat(thrown).isInstanceOf(YadonyBusinessException.class),
                () -> assertThat(thrown).isInstanceOfSatisfying(
                        YadonyBusinessException.class,
                        error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY)),
                () -> assertThat(thrown).isInstanceOfSatisfying(
                        YadonyBusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo("stripe-error")),
                () -> verify(stripeGateway, never()).createPaymentIntent(any()),
                () -> verify(paymentRepository, never()).save(any()),
                () -> verify(bidRepository, never()).save(any()),
                () -> verifyNoInteractions(promoService, auditService),
                () -> assertThat(payment.getStripePaymentIntentId()).isEqualTo("pi_legacy"),
                () -> assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING));
    }

    private CreatePaymentRequest paymentRequest() {
        CreatePaymentRequest request = mock(CreatePaymentRequest.class);
        when(request.getBidId()).thenReturn(bidId);
        return request;
    }

    private UserEntity sender() {
        UserEntity sender = new UserEntity();
        PaymentServiceTestFactory.setId(sender, senderId);
        sender.setFirebaseUid("uid-sender");
        sender.setStripeCustomerId("cus_existing");
        return sender;
    }

    private UserEntity traveler() {
        UserEntity traveler = new UserEntity();
        PaymentServiceTestFactory.setId(traveler, travelerId);
        traveler.setStripeAccountId("acct_traveler");
        traveler.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);
        return traveler;
    }

    private BidEntity bid(BigDecimal persistedRate, String promoCode) {
        BidEntity bid = new BidEntity();
        PaymentServiceTestFactory.setId(bid, bidId);
        bid.setAnnouncementId(announcementId);
        bid.setSenderId(senderId);
        bid.setWeightKg(new BigDecimal("5.00"));
        bid.setStatus(BidStatus.ACCEPTED);
        bid.setCurrency("CAD");
        bid.setCommissionRate(persistedRate);
        bid.setPromoCode(promoCode);
        return bid;
    }

    private AnnouncementEntity announcement() {
        AnnouncementEntity announcement = new AnnouncementEntity();
        PaymentServiceTestFactory.setId(announcement, announcementId);
        announcement.setTravelerId(travelerId);
        announcement.setPricePerKg(new BigDecimal("5.00"));
        return announcement;
    }

    private PaymentEntity bidPayment(String piId, String amount, String currency) {
        PaymentEntity payment = payment(piId, amount, currency);
        payment.setBidId(bidId);
        return payment;
    }

    private PaymentEntity negotiationPayment(UUID threadId, String piId, String amount, String currency) {
        PaymentEntity payment = payment(piId, amount, currency);
        payment.setNegotiationThreadId(threadId);
        return payment;
    }

    private PaymentEntity payment(String piId, String amount, String currency) {
        PaymentEntity payment = new PaymentEntity();
        PaymentServiceTestFactory.setId(payment, UUID.randomUUID());
        payment.setStripePaymentIntentId(piId);
        payment.setAmount(new BigDecimal(amount));
        payment.setCommissionAmount(new BigDecimal("3.00"));
        payment.setCurrency(currency);
        payment.setStatus(PaymentStatus.PENDING);
        return payment;
    }

    private UserBusinessPrefsEntity prefs(String currency) {
        UserBusinessPrefsEntity prefs = new UserBusinessPrefsEntity();
        prefs.setUserId(senderId);
        prefs.setCurrencyCode(currency);
        return prefs;
    }

    private PaymentIntent incompatibleIntent(String status, long amount, String currency) {
        PaymentIntent paymentIntent = mock(PaymentIntent.class);
        when(paymentIntent.getStatus()).thenReturn(status);
        when(paymentIntent.getAmount()).thenReturn(amount);
        when(paymentIntent.getCurrency()).thenReturn(currency);
        return paymentIntent;
    }

    private PaymentIntent freshIntent(String id) {
        PaymentIntent paymentIntent = mock(PaymentIntent.class);
        lenient().when(paymentIntent.getId()).thenReturn(id);
        lenient().when(paymentIntent.getClientSecret()).thenReturn(id + "_secret");
        return paymentIntent;
    }

    private Account activeAccount() {
        Account account = mock(Account.class);
        Account.Capabilities capabilities = mock(Account.Capabilities.class);
        lenient().when(capabilities.getCardPayments()).thenReturn("active");
        lenient().when(account.getCapabilities()).thenReturn(capabilities);
        return account;
    }
}
