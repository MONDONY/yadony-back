package com.yadony.api.payments;

import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.config.StripeConnectProperties;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.dto.CreatePaymentRequest;
import com.yadony.api.payments.dto.PaymentMethodResponse;
import com.stripe.exception.ApiException;
import com.stripe.model.Customer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentMethod;
import com.stripe.model.PaymentMethodCollection;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentIntentUpdateParams;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Support YadonyPaymentSheet (sheet de paiement custom) :
 * - customer Stripe attaché au PaymentIntent (cartes réutilisables) ;
 * - setup_future_usage=off_session par défaut, retirable via savePaymentMethod=false ;
 * - liste des cartes enregistrées du sender (jamais bloquante) ;
 * - PATCH du flag save sur un PaymentIntent non confirmé (ownership via metadata).
 *
 * L'escrow (capture MANUAL, absence de transfer_data/application_fee) reste couvert
 * par PaymentServiceOnBehalfOfTest — non dupliqué ici.
 */
@ExtendWith(MockitoExtension.class)
class PaymentSheetSupportTest {

    @Mock UserRepository userRepository;
    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;

    PaymentService service;

    private final UUID senderId   = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private final UUID bidId      = UUID.randomUUID();
    private final UUID annId      = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        StripeConnectProperties props = PaymentServiceTestFactory.defaultConnectProperties();
        service = new PaymentService(
                userRepository, bidRepository, mock(com.yadony.api.matching.BidGridItemRepository.class), announcementRepository,
                paymentRepository, auditService, eventPublisher,
                props,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                mock(com.yadony.api.common.stripe.AdminAlertService.class),
                PaymentServiceTestFactory.stubbedResolver(),
                mock(com.yadony.api.promo.PromoService.class),
                new StripeGatewayImpl(),
                PaymentServiceTestFactory.stubbedContacts(),
                mock(com.yadony.api.kyc.KycRepository.class)
                );
    }

    // ── Helpers (mêmes patterns que PaymentServiceOnBehalfOfTest) ────────────

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

    private UserEntity buildSender(String stripeCustomerId) {
        UserEntity u = new UserEntity();
        setId(u, senderId);
        u.setFirebaseUid("uid-sender");
        u.setStripeCustomerId(stripeCustomerId);
        return u;
    }

    private BidEntity buildBid() {
        BidEntity b = new BidEntity();
        setId(b, bidId);
        b.setAnnouncementId(annId);
        b.setSenderId(senderId);
        b.setWeightKg(BigDecimal.valueOf(5.0));
        b.setStatus(BidStatus.ACCEPTED);
        return b;
    }

    private AnnouncementEntity buildAnnouncement() {
        AnnouncementEntity ann = new AnnouncementEntity();
        setId(ann, annId);
        ann.setTravelerId(travelerId);
        ann.setPricePerKg(BigDecimal.valueOf(5.0));
        return ann;
    }

    private UserEntity buildTraveler() {
        UserEntity t = new UserEntity();
        setId(t, travelerId);
        t.setFirebaseUid("uid-traveler");
        t.setStripeAccountId("acct_traveler_123");
        t.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);
        return t;
    }

    private void stubCommonRepositories(UserEntity sender) {
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(buildBid()));
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.empty());
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(buildAnnouncement()));
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(buildTraveler()));
        when(paymentRepository.save(any())).thenAnswer(inv -> {
            PaymentEntity p = inv.getArgument(0);
            setId(p, UUID.randomUUID());
            return p;
        });
    }

    /** Stubbe Account.retrieve (capability card_payments active) + PaymentIntent.create. */
    private ArgumentCaptor<PaymentIntentCreateParams> stubStripeStatics(
            MockedStatic<com.stripe.model.Account> acctStatic, MockedStatic<PaymentIntent> piStatic) {
        com.stripe.model.Account mockAcct = mock(com.stripe.model.Account.class);
        com.stripe.model.Account.Capabilities caps = mock(com.stripe.model.Account.Capabilities.class);
        when(caps.getCardPayments()).thenReturn("active");
        when(mockAcct.getCapabilities()).thenReturn(caps);
        acctStatic.when(() -> com.stripe.model.Account.retrieve(any(String.class))).thenReturn(mockAcct);

        ArgumentCaptor<PaymentIntentCreateParams> captor =
                ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
        PaymentIntent mockPi = mock(PaymentIntent.class);
        when(mockPi.getId()).thenReturn("pi_test_new");
        when(mockPi.getClientSecret()).thenReturn("pi_secret");
        piStatic.when(() -> PaymentIntent.create(captor.capture())).thenReturn(mockPi);
        return captor;
    }

    private CreatePaymentRequest requestWithSave(Boolean save) {
        CreatePaymentRequest req = new CreatePaymentRequest();
        req.setBidId(bidId);
        req.setSavePaymentMethod(save);
        return req;
    }

    // ── Customer attaché au PaymentIntent ────────────────────────────────────

    @Test
    void createEscrow_attachesExistingCustomer_andSetsSetupFutureUsageByDefault() {
        stubCommonRepositories(buildSender("cus_existing"));

        try (MockedStatic<com.stripe.model.Account> acctStatic = mockStatic(com.stripe.model.Account.class);
             MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            var captor = stubStripeStatics(acctStatic, piStatic);

            service.createEscrow(requestWithSave(null), "uid-sender");

            PaymentIntentCreateParams params = captor.getValue();
            assertThat(params.getCustomer()).isEqualTo("cus_existing");
            assertThat(params.getSetupFutureUsage())
                    .isEqualTo(PaymentIntentCreateParams.SetupFutureUsage.OFF_SESSION);
            // Customer existant → jamais re-créé
            verify(userRepository, never()).save(any());
        }
    }

    @Test
    void createEscrow_createsAndPersistsCustomer_whenSenderHasNone() {
        UserEntity sender = buildSender(null);
        stubCommonRepositories(sender);

        try (MockedStatic<com.stripe.model.Account> acctStatic = mockStatic(com.stripe.model.Account.class);
             MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Customer> custStatic = mockStatic(Customer.class)) {
            var captor = stubStripeStatics(acctStatic, piStatic);
            Customer mockCustomer = mock(Customer.class);
            when(mockCustomer.getId()).thenReturn("cus_created");
            custStatic.when(() -> Customer.create(any(com.stripe.param.CustomerCreateParams.class)))
                    .thenReturn(mockCustomer);

            service.createEscrow(requestWithSave(null), "uid-sender");

            assertThat(captor.getValue().getCustomer()).isEqualTo("cus_created");
            assertThat(sender.getStripeCustomerId()).isEqualTo("cus_created");
            verify(userRepository).save(sender);
        }
    }

    @Test
    void createEscrow_exposesPaymentMethodTypes_forConditionalPayPalButton() {
        // Le SDK flutter_stripe n'expose pas payment_method_types via
        // retrievePaymentIntent : la sheet custom lit ce champ dans notre réponse.
        stubCommonRepositories(buildSender("cus_existing"));

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
            when(mockPi.getPaymentMethodTypes()).thenReturn(List.of("card", "paypal"));
            piStatic.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class))).thenReturn(mockPi);

            var response = service.createEscrow(requestWithSave(null), "uid-sender");

            assertThat(response.getPaymentMethodTypes()).containsExactly("card", "paypal");
        }
    }

    @Test
    void createEscrow_omitsSetupFutureUsage_whenSavePaymentMethodFalse() {
        stubCommonRepositories(buildSender("cus_existing"));

        try (MockedStatic<com.stripe.model.Account> acctStatic = mockStatic(com.stripe.model.Account.class);
             MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            var captor = stubStripeStatics(acctStatic, piStatic);

            service.createEscrow(requestWithSave(false), "uid-sender");

            assertThat(captor.getValue().getCustomer()).isEqualTo("cus_existing");
            assertThat(captor.getValue().getSetupFutureUsage()).isNull();
        }
    }

    // ── Liste des cartes enregistrées ─────────────────────────────────────────

    @Test
    void listSavedPaymentMethods_returnsEmpty_whenNoCustomer() {
        when(userRepository.findByFirebaseUid("uid-sender"))
                .thenReturn(Optional.of(buildSender(null)));

        List<PaymentMethodResponse> methods = service.listSavedPaymentMethods("uid-sender");

        assertThat(methods).isEmpty();
    }

    @Test
    void listSavedPaymentMethods_mapsCards() {
        when(userRepository.findByFirebaseUid("uid-sender"))
                .thenReturn(Optional.of(buildSender("cus_existing")));

        PaymentMethod pm = mock(PaymentMethod.class, RETURNS_DEEP_STUBS);
        when(pm.getId()).thenReturn("pm_1");
        when(pm.getCard().getBrand()).thenReturn("visa");
        when(pm.getCard().getLast4()).thenReturn("4242");
        when(pm.getCard().getExpMonth()).thenReturn(8L);
        when(pm.getCard().getExpYear()).thenReturn(2027L);

        PaymentMethodCollection collection = mock(PaymentMethodCollection.class);
        when(collection.getData()).thenReturn(List.of(pm));

        try (MockedStatic<PaymentMethod> pmStatic = mockStatic(PaymentMethod.class)) {
            pmStatic.when(() -> PaymentMethod.list(any(com.stripe.param.PaymentMethodListParams.class)))
                    .thenReturn(collection);

            List<PaymentMethodResponse> methods = service.listSavedPaymentMethods("uid-sender");

            assertThat(methods).hasSize(1);
            assertThat(methods.get(0).id()).isEqualTo("pm_1");
            assertThat(methods.get(0).brand()).isEqualTo("visa");
            assertThat(methods.get(0).last4()).isEqualTo("4242");
            assertThat(methods.get(0).expMonth()).isEqualTo(8);
            assertThat(methods.get(0).expYear()).isEqualTo(2027);
        }
    }

    @Test
    void listSavedPaymentMethods_returnsEmpty_onStripeError_neverThrows() {
        when(userRepository.findByFirebaseUid("uid-sender"))
                .thenReturn(Optional.of(buildSender("cus_existing")));

        try (MockedStatic<PaymentMethod> pmStatic = mockStatic(PaymentMethod.class)) {
            pmStatic.when(() -> PaymentMethod.list(any(com.stripe.param.PaymentMethodListParams.class)))
                    .thenThrow(new ApiException("stripe down", null, null, 500, null));

            List<PaymentMethodResponse> methods = service.listSavedPaymentMethods("uid-sender");

            assertThat(methods).isEmpty();
        }
    }

    // ── PATCH save-payment-method ─────────────────────────────────────────────

    private PaymentIntent mockOwnedIntent(String status) {
        PaymentIntent pi = mock(PaymentIntent.class);
        lenient().when(pi.getMetadata()).thenReturn(Map.of("sender_id", senderId.toString()));
        lenient().when(pi.getStatus()).thenReturn(status);
        return pi;
    }

    @Test
    void updateSavePaymentMethod_false_clearsSetupFutureUsage() throws Exception {
        when(userRepository.findByFirebaseUid("uid-sender"))
                .thenReturn(Optional.of(buildSender("cus_existing")));
        PaymentIntent pi = mockOwnedIntent("requires_payment_method");

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            piStatic.when(() -> PaymentIntent.retrieve("pi_1")).thenReturn(pi);
            ArgumentCaptor<PaymentIntentUpdateParams> updateCaptor =
                    ArgumentCaptor.forClass(PaymentIntentUpdateParams.class);
            when(pi.update(updateCaptor.capture())).thenReturn(pi);

            service.updateSavePaymentMethod("pi_1", false, "uid-sender");

            // save=false → setup_future_usage vidé (chaîne vide = clear côté Stripe)
            assertThat(updateCaptor.getValue().getExtraParams())
                    .containsEntry("setup_future_usage", "");
        }
    }

    @Test
    void updateSavePaymentMethod_true_setsOffSession() throws Exception {
        when(userRepository.findByFirebaseUid("uid-sender"))
                .thenReturn(Optional.of(buildSender("cus_existing")));
        PaymentIntent pi = mockOwnedIntent("requires_payment_method");

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            piStatic.when(() -> PaymentIntent.retrieve("pi_1")).thenReturn(pi);
            ArgumentCaptor<PaymentIntentUpdateParams> updateCaptor =
                    ArgumentCaptor.forClass(PaymentIntentUpdateParams.class);
            when(pi.update(updateCaptor.capture())).thenReturn(pi);

            service.updateSavePaymentMethod("pi_1", true, "uid-sender");

            assertThat(updateCaptor.getValue().getSetupFutureUsage())
                    .isEqualTo(PaymentIntentUpdateParams.SetupFutureUsage.OFF_SESSION);
        }
    }

    @Test
    void updateSavePaymentMethod_throws403_whenNotOwner() {
        UserEntity other = new UserEntity();
        setId(other, UUID.randomUUID());
        other.setFirebaseUid("uid-other");
        when(userRepository.findByFirebaseUid("uid-other")).thenReturn(Optional.of(other));
        PaymentIntent pi = mockOwnedIntent("requires_payment_method");

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            piStatic.when(() -> PaymentIntent.retrieve("pi_1")).thenReturn(pi);

            Throwable thrown = catchThrowable(
                    () -> service.updateSavePaymentMethod("pi_1", false, "uid-other"));

            assertThat(thrown).isInstanceOf(YadonyBusinessException.class);
            assertThat(((YadonyBusinessException) thrown).getErrorCode()).isEqualTo("forbidden");
        }
    }

    @Test
    void updateSavePaymentMethod_throws409_whenIntentAlreadyConfirmed() {
        when(userRepository.findByFirebaseUid("uid-sender"))
                .thenReturn(Optional.of(buildSender("cus_existing")));
        PaymentIntent pi = mockOwnedIntent("succeeded");

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            piStatic.when(() -> PaymentIntent.retrieve("pi_1")).thenReturn(pi);

            Throwable thrown = catchThrowable(
                    () -> service.updateSavePaymentMethod("pi_1", false, "uid-sender"));

            assertThat(thrown).isInstanceOf(YadonyBusinessException.class);
            assertThat(((YadonyBusinessException) thrown).getErrorCode()).isEqualTo("intent-not-editable");
        }
    }
}
