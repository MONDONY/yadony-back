package com.yadony.api.payments;

import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.config.StripeConnectProperties;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.dto.CreatePaymentRequest;
import com.yadony.api.payments.dto.PaymentResponse;
import com.yadony.api.payments.exceptions.TravelerNotEligibleForPaymentException;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.param.PaymentIntentCreateParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests specifically covering the escrow PaymentIntent params:
 * - on_behalf_of retiré (incompatible PayPal) ; PI Stripe-native carte + PayPal
 * - No transfer_data, no application_fee_amount (separate charges and transfers)
 * - TravelerNotEligibleForPaymentException thrown for all non-ONBOARDING_COMPLETE statuses
 *   or null/blank stripeAccountId
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceOnBehalfOfTest {

    @Mock UserRepository userRepository;
    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;

    PaymentService service;
    com.yadony.api.common.CommissionRateResolver commissionRateResolver;

    private final UUID senderId   = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private final UUID bidId      = UUID.randomUUID();
    private final UUID annId      = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        StripeConnectProperties props = new StripeConnectProperties(
                "4215",
                "Transport de colis entre particuliers via la plateforme Yadony",
                "https://yadony.app",
                "http://localhost:8080/api/v1/payments/onboarding/return",
                "http://localhost:8080/api/v1/payments/onboarding/refresh",
                "yadony://stripe/onboarding/complete",
                "yadony://stripe/onboarding/refresh"
        );
        commissionRateResolver = PaymentServiceTestFactory.stubbedResolver();
        service = new PaymentService(
                userRepository, bidRepository, mock(com.yadony.api.matching.BidGridItemRepository.class), announcementRepository,
                paymentRepository, auditService, eventPublisher,
                props,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                org.mockito.Mockito.mock(com.yadony.api.common.stripe.AdminAlertService.class), commissionRateResolver, org.mockito.Mockito.mock(com.yadony.api.promo.PromoService.class), new StripeGatewayImpl(),
                PaymentServiceTestFactory.stubbedContacts(), mock(com.yadony.api.voucher.CommissionVoucherService.class), mock(com.yadony.api.payments.ConnectAccountProvisioner.class)
);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void setId(Object entity, UUID id) {
        try {
            Class<?> clazz = entity.getClass();
            Field f = null;
            while (clazz != null) {
                try { f = clazz.getDeclaredField("id"); break; }
                catch (NoSuchFieldException e) { clazz = clazz.getSuperclass(); }
            }
            if (f == null) throw new NoSuchFieldException("id not found");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private UserEntity buildSender() {
        UserEntity u = new UserEntity();
        setId(u, senderId);
        u.setFirebaseUid("uid-sender");
        // Customer déjà présent : évite le passage par Customer.create (statique réel).
        // La création du customer est couverte par PaymentSheetSupportTest.
        u.setStripeCustomerId("cus_existing");
        return u;
    }

    private BidEntity buildBid() {
        return buildBid("EUR");
    }

    private BidEntity buildBid(String currency) {
        BidEntity b = new BidEntity();
        setId(b, bidId);
        b.setAnnouncementId(annId);
        b.setSenderId(senderId);
        b.setWeightKg(BigDecimal.valueOf(5.0));
        b.setStatus(BidStatus.ACCEPTED);
        b.setCurrency(currency);
        return b;
    }

    private AnnouncementEntity buildAnnouncement() {
        AnnouncementEntity ann = new AnnouncementEntity();
        setId(ann, annId);
        ann.setTravelerId(travelerId);
        ann.setPricePerKg(BigDecimal.valueOf(5.0));
        return ann;
    }

    private UserEntity buildTraveler(String stripeAccountId, StripeAccountStatus status) {
        UserEntity t = new UserEntity();
        setId(t, travelerId);
        t.setFirebaseUid("uid-traveler");
        t.setStripeAccountId(stripeAccountId);
        t.setStripeAccountStatus(status);
        return t;
    }

    private CreatePaymentRequest buildRequest() {
        return buildRequest(null);
    }

    private CreatePaymentRequest buildRequest(String requestCurrency) {
        var req = mock(CreatePaymentRequest.class);
        when(req.getBidId()).thenReturn(bidId);
        lenient().when(req.getCurrencyCode()).thenReturn(requestCurrency);
        return req;
    }

    private void stubCommonRepositories(UserEntity traveler) {
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(buildSender()));
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(buildBid()));
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.empty());
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(buildAnnouncement()));
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
    }

    // ── on_behalf_of success path ─────────────────────────────────────────────

    @Test
    void success_paymentIntent_no_onBehalfOf_cardAndPaypal_no_transferData_no_appFee() {
        UserEntity traveler = buildTraveler("acct_traveler_123", StripeAccountStatus.ONBOARDING_COMPLETE);
        stubCommonRepositories(traveler);
        when(paymentRepository.save(any())).thenAnswer(inv -> {
            PaymentEntity p = inv.getArgument(0);
            setId(p, UUID.randomUUID());
            return p;
        });

        try (MockedStatic<com.stripe.model.Account> acctStatic = mockStatic(com.stripe.model.Account.class);
             MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            com.stripe.model.Account mockAcct = mock(com.stripe.model.Account.class);
            com.stripe.model.Account.Capabilities caps = mock(com.stripe.model.Account.Capabilities.class);
            when(caps.getCardPayments()).thenReturn("active");
            when(mockAcct.getCapabilities()).thenReturn(caps);
            acctStatic.when(() -> com.stripe.model.Account.retrieve(any(String.class))).thenReturn(mockAcct);

            ArgumentCaptor<PaymentIntentCreateParams> paramsCaptor =
                    ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
            PaymentIntent mockPi = mock(PaymentIntent.class);
            when(mockPi.getId()).thenReturn("pi_test_new");
            when(mockPi.getClientSecret()).thenReturn("pi_secret");
            piStatic.when(() -> PaymentIntent.create(paramsCaptor.capture())).thenReturn(mockPi);

            PaymentResponse resp = service.createEscrow(buildRequest(), "uid-sender");

            assertThat(resp.getStatus()).isEqualTo("PENDING");

            PaymentIntentCreateParams params = paramsCaptor.getValue();
            // on_behalf_of retiré (incompatible PayPal) ; PI Stripe-native carte + PayPal
            assertThat(params.getOnBehalfOf()).isNull();
            assertThat(params.getPaymentMethodTypes()).containsExactly("card", "paypal");
            // statement descriptor suffix
            assertThat(params.getStatementDescriptorSuffix()).isEqualTo("YADONY");
            // capture_method = manual (escrow)
            assertThat(params.getCaptureMethod()).isEqualTo(PaymentIntentCreateParams.CaptureMethod.MANUAL);
            // CRITICAL: NO transfer_data, NO application_fee_amount — separate charges and transfers
            assertThat(params.getTransferData()).isNull();
            assertThat(params.getApplicationFeeAmount()).isNull();
        }
    }

    @Test
    void success_usesBidCadOneToOne_ignoresDivergentRequestCurrency_withoutFxQuote() {
        UserEntity sender = buildSender();
        UserEntity traveler = buildTraveler("acct_traveler_123", StripeAccountStatus.ONBOARDING_COMPLETE);
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(buildBid("CAD")));
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.empty());
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(buildAnnouncement()));
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
        when(paymentRepository.save(any())).thenAnswer(inv -> {
            PaymentEntity payment = inv.getArgument(0);
            setId(payment, UUID.randomUUID());
            return payment;
        });

        try (MockedStatic<com.stripe.model.Account> accountStatic = mockStatic(com.stripe.model.Account.class);
             MockedStatic<PaymentIntent> paymentIntentStatic = mockStatic(PaymentIntent.class)) {
            com.stripe.model.Account account = mock(com.stripe.model.Account.class);
            com.stripe.model.Account.Capabilities capabilities = mock(com.stripe.model.Account.Capabilities.class);
            when(capabilities.getCardPayments()).thenReturn("active");
            when(account.getCapabilities()).thenReturn(capabilities);
            accountStatic.when(() -> com.stripe.model.Account.retrieve("acct_traveler_123"))
                    .thenReturn(account);

            ArgumentCaptor<PaymentIntentCreateParams> paramsCaptor =
                    ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
            PaymentIntent paymentIntent = mock(PaymentIntent.class);
            when(paymentIntent.getId()).thenReturn("pi_cad");
            when(paymentIntent.getClientSecret()).thenReturn("pi_cad_secret");
            paymentIntentStatic.when(() -> PaymentIntent.create(paramsCaptor.capture()))
                    .thenReturn(paymentIntent);

            PaymentResponse response = service.createEscrow(buildRequest("EUR"), "uid-sender");

            PaymentIntentCreateParams params = paramsCaptor.getValue();
            assertThat(params.getAmount()).isEqualTo(2800L);
            assertThat(params.getCurrency()).isEqualTo("cad");
            assertThat(params.getExtraParams()).isNullOrEmpty();
            assertThat(params.getMetadata()).doesNotContainKeys("fx_quote_id", "fx_exchange_rate");
            assertThat(response.getAmount()).isEqualByComparingTo("28.00");
            assertThat(response.getCommissionAmount()).isEqualByComparingTo("3.00");
            assertThat(response.getCurrency()).isEqualTo("cad");

            ArgumentCaptor<PaymentEntity> paymentCaptor = ArgumentCaptor.forClass(PaymentEntity.class);
            verify(paymentRepository).save(paymentCaptor.capture());
            assertThat(paymentCaptor.getValue().getCurrency()).isEqualTo("cad");
            assertThat(paymentCaptor.getValue().getStripeFxQuoteId()).isNull();
        }
    }

    @Test
    void createEscrow_xofBid_rejectsBeforeTouchingStripe() {
        // Même règle que le test XAF ci-dessus, sur l'autre franc CFA. L'ancienne
        // couverture "0-decimal via Stripe" n'a plus de scénario valide : XOF ne peut
        // plus atteindre createEscrow avec succès. Le calcul 0-décimale lui-même reste
        // couvert au niveau unitaire par CurrencyAmountTest/CurrencyBoundsTest.
        UserEntity sender = buildSender();
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(buildBid("XOF")));

        Throwable thrown = catchThrowable(
                () -> service.createEscrow(buildRequest("EUR"), "uid-sender"));

        assertThat(thrown).isInstanceOf(com.yadony.api.common.YadonyBusinessException.class);
        assertThat(((com.yadony.api.common.YadonyBusinessException) thrown).getErrorCode())
                .isEqualTo("payment-method-unavailable-for-currency");
        verifyNoInteractions(paymentRepository, announcementRepository);
    }

    @Test
    void pendingLegacyEntityFxAmountAndCurrency_cancelsAndRecyclesEvenWhenStripeIntentMatchesServer()
            throws Exception {
        UserEntity sender = buildSender();
        UserEntity traveler = buildTraveler("acct_traveler_123", StripeAccountStatus.ONBOARDING_COMPLETE);
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        BidEntity bid = buildBid("CAD");
        bid.setCommissionRate(new BigDecimal("0.12"));
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(buildAnnouncement()));
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));

        PaymentEntity legacy = new PaymentEntity();
        setId(legacy, UUID.randomUUID());
        legacy.setBidId(bidId);
        legacy.setStripePaymentIntentId("pi_legacy_fx");
        legacy.setAmount(new BigDecimal("25.00"));
        legacy.setCommissionAmount(new BigDecimal("3.00"));
        legacy.setCurrency("EUR");
        legacy.setStripeFxQuoteId("fxq_legacy");
        legacy.setFxExchangeRate(new BigDecimal("1.1200000000"));
        legacy.setFxQuoteExpiresAt(java.time.Instant.parse("2026-08-09T10:00:00Z"));
        legacy.setStatus(PaymentStatus.PENDING);
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.of(legacy));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<com.stripe.model.Account> accountStatic = mockStatic(com.stripe.model.Account.class);
             MockedStatic<PaymentIntent> paymentIntentStatic = mockStatic(PaymentIntent.class)) {
            PaymentIntent legacyPi = mock(PaymentIntent.class);
            when(legacyPi.getStatus()).thenReturn("requires_payment_method", "canceled");
            when(legacyPi.getAmount()).thenReturn(2800L);
            when(legacyPi.getCurrency()).thenReturn("cad");
            when(legacyPi.cancel(any(PaymentIntentCancelParams.class))).thenReturn(legacyPi);
            paymentIntentStatic.when(() -> PaymentIntent.retrieve("pi_legacy_fx"))
                    .thenReturn(legacyPi);

            com.stripe.model.Account account = mock(com.stripe.model.Account.class);
            com.stripe.model.Account.Capabilities capabilities = mock(com.stripe.model.Account.Capabilities.class);
            when(capabilities.getCardPayments()).thenReturn("active");
            when(account.getCapabilities()).thenReturn(capabilities);
            accountStatic.when(() -> com.stripe.model.Account.retrieve("acct_traveler_123"))
                    .thenReturn(account);

            ArgumentCaptor<PaymentIntentCreateParams> paramsCaptor =
                    ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
            PaymentIntent freshPi = mock(PaymentIntent.class);
            when(freshPi.getId()).thenReturn("pi_fresh_cad");
            when(freshPi.getClientSecret()).thenReturn("pi_fresh_cad_secret");
            paymentIntentStatic.when(() -> PaymentIntent.create(paramsCaptor.capture()))
                    .thenReturn(freshPi);

            PaymentResponse response = service.createEscrow(buildRequest(), "uid-sender");

            assertThat(response.getStripePaymentIntentId()).isEqualTo("pi_fresh_cad");
            assertThat(paramsCaptor.getValue().getAmount()).isEqualTo(2800L);
            assertThat(paramsCaptor.getValue().getCurrency()).isEqualTo("cad");
            verify(legacyPi).cancel(any(PaymentIntentCancelParams.class));
            verify(paymentRepository).save(legacy);
            assertThat(legacy.getStripePaymentIntentId()).isEqualTo("pi_fresh_cad");
            assertThat(legacy.getAmount()).isEqualByComparingTo("28.00");
            assertThat(legacy.getCurrency()).isEqualTo("cad");
            assertThat(legacy.getStripeFxQuoteId()).isNull();
            assertThat(legacy.getFxExchangeRate()).isNull();
            assertThat(legacy.getFxQuoteExpiresAt()).isNull();
        }
    }

    @Test
    void createEscrow_paysBidFrozenCurrency_evenIfSendersCurrentPreferenceDiffers() {
        // Lot 2 (gel devise au premier mouvement) : createEscrow ne compare plus la
        // devise figée du bid à la préférence *courante* de l'expéditeur. Basculer de
        // devise après la création du bid ne doit plus jamais bloquer le paiement de
        // ses propres colis en cours (l'ancienne garde le faisait à tort).
        UserEntity traveler = buildTraveler("acct_traveler_123", StripeAccountStatus.ONBOARDING_COMPLETE);
        stubCommonRepositories(traveler);
        when(paymentRepository.save(any())).thenAnswer(inv -> {
            PaymentEntity p = inv.getArgument(0);
            setId(p, UUID.randomUUID());
            return p;
        });

        try (MockedStatic<com.stripe.model.Account> acctStatic = mockStatic(com.stripe.model.Account.class);
             MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            com.stripe.model.Account mockAcct = mock(com.stripe.model.Account.class);
            com.stripe.model.Account.Capabilities caps = mock(com.stripe.model.Account.Capabilities.class);
            when(caps.getCardPayments()).thenReturn("active");
            when(mockAcct.getCapabilities()).thenReturn(caps);
            acctStatic.when(() -> com.stripe.model.Account.retrieve(any(String.class))).thenReturn(mockAcct);

            PaymentIntent mockPi = mock(PaymentIntent.class);
            when(mockPi.getId()).thenReturn("pi_test_new");
            when(mockPi.getClientSecret()).thenReturn("pi_secret");
            piStatic.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class))).thenReturn(mockPi);

            // buildBid() (via stubCommonRepositories) est en EUR ; aucune préférence
            // "courante" n'entre plus en jeu — il n'y a même plus de resolver à stuber.
            PaymentResponse resp = service.createEscrow(buildRequest(), "uid-sender");

            assertThat(resp.getStatus()).isEqualTo("PENDING");
        }
    }

    // ── TravelerNotEligibleForPaymentException for all ineligible states ──────

    @Test
    void throws_TravelerNotEligible_when_status_PENDING_ONBOARDING() {
        UserEntity traveler = buildTraveler("acct_traveler", StripeAccountStatus.PENDING_ONBOARDING);
        stubCommonRepositories(traveler);

        Throwable thrown = catchThrowable(() -> service.createEscrow(buildRequest(), "uid-sender"));

        assertThat(thrown).isInstanceOf(TravelerNotEligibleForPaymentException.class);
        assertThat(((TravelerNotEligibleForPaymentException) thrown).getTravelerId()).isEqualTo(travelerId);
    }

    @Test
    void throws_TravelerNotEligible_when_status_REJECTED() {
        UserEntity traveler = buildTraveler("acct_traveler", StripeAccountStatus.REJECTED);
        stubCommonRepositories(traveler);

        Throwable thrown = catchThrowable(() -> service.createEscrow(buildRequest(), "uid-sender"));

        assertThat(thrown).isInstanceOf(TravelerNotEligibleForPaymentException.class);
        assertThat(((TravelerNotEligibleForPaymentException) thrown).getTravelerId()).isEqualTo(travelerId);
    }

    @Test
    void throws_TravelerNotEligible_when_status_NOT_CREATED() {
        UserEntity traveler = buildTraveler(null, StripeAccountStatus.NOT_CREATED);
        stubCommonRepositories(traveler);

        Throwable thrown = catchThrowable(() -> service.createEscrow(buildRequest(), "uid-sender"));

        assertThat(thrown).isInstanceOf(TravelerNotEligibleForPaymentException.class);
        assertThat(((TravelerNotEligibleForPaymentException) thrown).getTravelerId()).isEqualTo(travelerId);
    }

    @Test
    void throws_TravelerNotEligible_when_stripeAccountId_null_but_status_ONBOARDING_COMPLETE() {
        // Edge case: status says complete but stripeAccountId is null — must still reject
        UserEntity traveler = buildTraveler(null, StripeAccountStatus.ONBOARDING_COMPLETE);
        stubCommonRepositories(traveler);

        Throwable thrown = catchThrowable(() -> service.createEscrow(buildRequest(), "uid-sender"));

        assertThat(thrown).isInstanceOf(TravelerNotEligibleForPaymentException.class);
        assertThat(((TravelerNotEligibleForPaymentException) thrown).getTravelerId()).isEqualTo(travelerId);
    }

    @Test
    void throws_TravelerNotEligible_when_stripeAccountId_blank_but_status_ONBOARDING_COMPLETE() {
        UserEntity traveler = buildTraveler("   ", StripeAccountStatus.ONBOARDING_COMPLETE);
        stubCommonRepositories(traveler);

        Throwable thrown = catchThrowable(() -> service.createEscrow(buildRequest(), "uid-sender"));

        assertThat(thrown).isInstanceOf(TravelerNotEligibleForPaymentException.class);
        assertThat(((TravelerNotEligibleForPaymentException) thrown).getTravelerId()).isEqualTo(travelerId);
    }

    @Test
    void throws_TravelerNotEligible_when_status_DISABLED() {
        UserEntity traveler = buildTraveler("acct_traveler", StripeAccountStatus.DISABLED);
        stubCommonRepositories(traveler);

        Throwable thrown = catchThrowable(() -> service.createEscrow(buildRequest(), "uid-sender"));

        assertThat(thrown).isInstanceOf(TravelerNotEligibleForPaymentException.class);
        assertThat(((TravelerNotEligibleForPaymentException) thrown).getTravelerId()).isEqualTo(travelerId);
    }
}
