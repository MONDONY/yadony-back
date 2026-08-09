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
import com.yadony.api.payments.currency.CurrencyMatchGuard;
import com.yadony.api.settings.UserBusinessPrefsEntity;
import com.yadony.api.settings.UserBusinessPrefsRepository;
import com.stripe.model.PaymentIntent;
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
    @Mock UserBusinessPrefsRepository userBusinessPrefsRepository;

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
                PaymentServiceTestFactory.stubbedContacts(),
                userBusinessPrefsRepository, new CurrencyMatchGuard()
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

    private UserBusinessPrefsEntity prefsWithCurrency(String currency) {
        UserBusinessPrefsEntity prefs = new UserBusinessPrefsEntity();
        prefs.setUserId(senderId);
        prefs.setCurrencyCode(currency);
        return prefs;
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
        when(userBusinessPrefsRepository.findById(senderId))
                .thenReturn(Optional.of(prefsWithCurrency("CAD")));
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
    void success_zeroDecimalCurrency_keepsCommissionMetadataAlignedWithPersistedAmount() {
        UserEntity sender = buildSender();
        UserEntity traveler = buildTraveler("acct_traveler_123", StripeAccountStatus.ONBOARDING_COMPLETE);
        AnnouncementEntity announcement = buildAnnouncement();
        announcement.setPricePerKg(new BigDecimal("5.25"));

        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(buildBid("XOF")));
        when(userBusinessPrefsRepository.findById(senderId))
                .thenReturn(Optional.of(prefsWithCurrency("XOF")));
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.empty());
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(announcement));
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
            when(paymentIntent.getId()).thenReturn("pi_xof");
            when(paymentIntent.getClientSecret()).thenReturn("pi_xof_secret");
            paymentIntentStatic.when(() -> PaymentIntent.create(paramsCaptor.capture()))
                    .thenReturn(paymentIntent);

            PaymentResponse response = service.createEscrow(buildRequest("CAD"), "uid-sender");

            PaymentIntentCreateParams params = paramsCaptor.getValue();
            assertThat(params.getAmount()).isEqualTo(29L);
            assertThat(params.getCurrency()).isEqualTo("xof");
            assertThat(params.getMetadata())
                    .containsEntry("commission_amount", "3")
                    .containsEntry("commission_minor", "3");
            assertThat(response.getAmount()).isEqualByComparingTo("29");
            assertThat(response.getCommissionAmount()).isEqualByComparingTo("3");

            ArgumentCaptor<PaymentEntity> paymentCaptor = ArgumentCaptor.forClass(PaymentEntity.class);
            verify(paymentRepository).save(paymentCaptor.capture());
            assertThat(paymentCaptor.getValue().getAmount()).isEqualByComparingTo("29");
            assertThat(paymentCaptor.getValue().getCommissionAmount()).isEqualByComparingTo("3");
        }
    }

    @Test
    void currencyMismatchFailsBeforeIdempotencyCommissionPromoAndPersistence() {
        UserEntity sender = buildSender();
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(buildBid("EUR")));
        when(userBusinessPrefsRepository.findById(senderId))
                .thenReturn(Optional.of(prefsWithCurrency("CAD")));

        Throwable thrown = catchThrowable(
                () -> service.createEscrow(buildRequest("EUR"), "uid-sender"));

        assertThat(thrown).isInstanceOf(com.yadony.api.common.YadonyBusinessException.class);
        assertThat(((com.yadony.api.common.YadonyBusinessException) thrown).getErrorCode())
                .isEqualTo("currency-mismatch");
        verifyNoInteractions(paymentRepository, announcementRepository, auditService);
        verify(bidRepository, never()).save(any());
        verifyNoInteractions(commissionRateResolver);
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
