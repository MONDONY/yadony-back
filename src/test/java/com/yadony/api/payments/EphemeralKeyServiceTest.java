package com.yadony.api.payments;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.config.StripeConnectProperties;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.dto.EphemeralKeyResponse;
import com.stripe.exception.ApiException;
import com.stripe.model.Customer;
import com.stripe.model.EphemeralKey;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.EphemeralKeyCreateParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Clé éphémère Stripe pour la PaymentSheet native (flutter_stripe) : POST /payments/me/ephemeral-key.
 * Doit réutiliser/creer le customer Stripe de l'utilisateur et créer la clé avec la version d'API
 * exacte demandée par le SDK mobile (sinon la sheet native rejette la clé côté client).
 */
@ExtendWith(MockitoExtension.class)
class EphemeralKeyServiceTest {

    @Mock UserRepository userRepository;
    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock StripeGateway stripeGateway;

    PaymentService service;

    private final UUID userId = UUID.randomUUID();

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
                stripeGateway,
                PaymentServiceTestFactory.stubbedContacts(),
                mock(com.yadony.api.settings.UserBusinessPrefsRepository.class),
                new com.yadony.api.payments.currency.CurrencyMatchGuard()
                );
    }

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

    private UserEntity buildUser(String stripeCustomerId) {
        UserEntity u = new UserEntity();
        setId(u, userId);
        u.setFirebaseUid("uid-user");
        u.setStripeCustomerId(stripeCustomerId);
        return u;
    }

    @Test
    void createEphemeralKey_existingCustomer_returnsSecretAndCustomerId() throws Exception {
        when(userRepository.findByFirebaseUid("uid-user"))
                .thenReturn(Optional.of(buildUser("cus_existing")));

        EphemeralKey mockKey = mock(EphemeralKey.class);
        when(mockKey.getSecret()).thenReturn("ek_test_secret");

        ArgumentCaptor<EphemeralKeyCreateParams> paramsCaptor =
                ArgumentCaptor.forClass(EphemeralKeyCreateParams.class);
        when(stripeGateway.createEphemeralKey(paramsCaptor.capture())).thenReturn(mockKey);

        EphemeralKeyResponse response = service.createEphemeralKey("uid-user", "2024-06-20");

        assertThat(response.ephemeralKeySecret()).isEqualTo("ek_test_secret");
        assertThat(response.customerId()).isEqualTo("cus_existing");
        assertThat(paramsCaptor.getValue().getCustomer()).isEqualTo("cus_existing");
        // stripe-java exige la version d'API du client mobile sur les params (pas RequestOptions)
        assertThat(paramsCaptor.getValue().getStripeVersion()).isEqualTo("2024-06-20");
        // Customer déjà existant → jamais recréé
        org.mockito.Mockito.verify(stripeGateway, org.mockito.Mockito.never())
                .createCustomer(any(CustomerCreateParams.class));
    }

    @Test
    void createEphemeralKey_noCustomer_createsAndPersistsCustomer_thenCreatesKey() throws Exception {
        UserEntity user = buildUser(null);
        when(userRepository.findByFirebaseUid("uid-user")).thenReturn(Optional.of(user));

        Customer mockCustomer = mock(Customer.class);
        when(mockCustomer.getId()).thenReturn("cus_created");
        when(stripeGateway.createCustomer(any(CustomerCreateParams.class))).thenReturn(mockCustomer);

        EphemeralKey mockKey = mock(EphemeralKey.class);
        when(mockKey.getSecret()).thenReturn("ek_test_secret");
        ArgumentCaptor<EphemeralKeyCreateParams> paramsCaptor =
                ArgumentCaptor.forClass(EphemeralKeyCreateParams.class);
        when(stripeGateway.createEphemeralKey(paramsCaptor.capture())).thenReturn(mockKey);

        EphemeralKeyResponse response = service.createEphemeralKey("uid-user", "2024-06-20");

        assertThat(response.customerId()).isEqualTo("cus_created");
        assertThat(paramsCaptor.getValue().getCustomer()).isEqualTo("cus_created");
        assertThat(paramsCaptor.getValue().getStripeVersion()).isEqualTo("2024-06-20");
        assertThat(user.getStripeCustomerId()).isEqualTo("cus_created");
        org.mockito.Mockito.verify(userRepository).save(user);
    }

    @Test
    void createEphemeralKey_stripeError_throwsYadonyBusinessException502() throws Exception {
        when(userRepository.findByFirebaseUid("uid-user"))
                .thenReturn(Optional.of(buildUser("cus_existing")));
        when(stripeGateway.createEphemeralKey(any(EphemeralKeyCreateParams.class)))
                .thenThrow(new ApiException("stripe down", null, null, 500, null));

        Throwable thrown = catchThrowable(() -> service.createEphemeralKey("uid-user", "2024-06-20"));

        assertThat(thrown).isInstanceOf(YadonyBusinessException.class);
        YadonyBusinessException ex = (YadonyBusinessException) thrown;
        assertThat(ex.getErrorCode()).isEqualTo("ephemeral-key-creation-failed");
        assertThat(ex.getStatus()).isEqualTo(org.springframework.http.HttpStatus.BAD_GATEWAY);
    }

    @Test
    void createEphemeralKey_unknownUser_throwsUnauthorized() {
        when(userRepository.findByFirebaseUid("uid-unknown")).thenReturn(Optional.empty());

        Throwable thrown = catchThrowable(() -> service.createEphemeralKey("uid-unknown", "2024-06-20"));

        assertThat(thrown).isInstanceOf(YadonyBusinessException.class);
    }
}
