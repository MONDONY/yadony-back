package com.yadony.api.billing;

import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("StripeBillingService — gardes avant appel à Stripe")
class StripeBillingServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock ProSubscriptionRepository repository;

    private BillingProperties configured() {
        return new BillingProperties(false, 60, 5, "price_m", "price_y",
                "https://yadony.com/pro/ok", "https://yadony.com/pro/ko",
                "https://yadony.com/pro/parametres/abonnement");
    }

    private BillingProperties unconfigured() {
        return new BillingProperties(false, 60, 5, null, null,
                "https://yadony.com/pro/ok", "https://yadony.com/pro/ko",
                "https://yadony.com/pro/parametres/abonnement");
    }

    private ProSubscriptionEntity subscription(ProSubscriptionStatus status, String customerId) {
        ProSubscriptionEntity sub = new ProSubscriptionEntity();
        sub.setUserId(USER_ID);
        sub.setStatus(status);
        sub.setSource(ProSubscriptionSource.STRIPE);
        sub.setStripeCustomerId(customerId);
        return sub;
    }

    @Test
    @DisplayName("sans Price configuré, la souscription échoue en 503 plutôt qu'au démarrage")
    void unconfiguredPricesFailCleanly() {
        StripeBillingService service = new StripeBillingService(repository, unconfigured());

        assertThatThrownBy(() -> service.createCheckoutSession(USER_ID, BillingCycle.MONTHLY))
                .isInstanceOf(YadonyBusinessException.class)
                .hasMessageContaining("billing");
    }

    @Test
    @DisplayName("un abonnement déjà actif refuse une seconde souscription")
    void alreadyActiveIsRejected() {
        when(repository.findByUserId(USER_ID))
                .thenReturn(Optional.of(subscription(ProSubscriptionStatus.ACTIVE, "cus_1")));
        StripeBillingService service = new StripeBillingService(repository, configured());

        assertThatThrownBy(() -> service.createCheckoutSession(USER_ID, BillingCycle.MONTHLY))
                .isInstanceOf(YadonyBusinessException.class);
    }

    @Test
    @DisplayName("un compte en grâce historique peut souscrire : c'est le but")
    void legacyGraceCanSubscribe() {
        when(repository.findByUserId(USER_ID))
                .thenReturn(Optional.of(subscription(ProSubscriptionStatus.LEGACY_GRACE, null)));
        StripeBillingService service = new StripeBillingService(repository, configured());

        // L'appel réel à Stripe échouera faute de clé d'API en test : on vérifie
        // seulement que la garde métier ne bloque pas ce cas, contrairement au
        // précédent. Toute exception levée ici ne doit pas être un 409 métier.
        assertThatThrownBy(() -> service.createCheckoutSession(USER_ID, BillingCycle.MONTHLY))
                .isNotInstanceOf(YadonyBusinessException.class);
    }

    @Test
    @DisplayName("sans client Stripe connu, l'accès au portail est refusé")
    void portalWithoutCustomerIsRejected() {
        when(repository.findByUserId(USER_ID))
                .thenReturn(Optional.of(subscription(ProSubscriptionStatus.LEGACY_GRACE, null)));
        StripeBillingService service = new StripeBillingService(repository, configured());

        assertThatThrownBy(() -> service.createPortalSession(USER_ID))
                .isInstanceOf(YadonyBusinessException.class);
    }

    @Test
    @DisplayName("sans abonnement du tout, l'accès au portail est refusé")
    void portalWithoutSubscriptionIsRejected() {
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        StripeBillingService service = new StripeBillingService(repository, configured());

        assertThatThrownBy(() -> service.createPortalSession(USER_ID))
                .isInstanceOf(YadonyBusinessException.class);
    }

    @Test
    @DisplayName("nouveau client : la session Checkout créée porte l'utilisateur en client_reference_id")
    void checkoutSessionHappyPathCarriesUserIdAsClientReference() {
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        StripeBillingService service = new StripeBillingService(repository, configured());

        com.stripe.model.checkout.Session fakeSession = mock(com.stripe.model.checkout.Session.class);
        when(fakeSession.getId()).thenReturn("cs_test_123");
        when(fakeSession.getUrl()).thenReturn("https://checkout.stripe.com/pay/cs_test_123");

        try (MockedStatic<com.stripe.model.checkout.Session> mocked =
                     Mockito.mockStatic(com.stripe.model.checkout.Session.class)) {
            ArgumentCaptor<com.stripe.param.checkout.SessionCreateParams> captor =
                    ArgumentCaptor.forClass(com.stripe.param.checkout.SessionCreateParams.class);
            mocked.when(() -> com.stripe.model.checkout.Session.create(captor.capture()))
                    .thenReturn(fakeSession);

            String url = service.createCheckoutSession(USER_ID, BillingCycle.MONTHLY);

            assertThat(url).isEqualTo("https://checkout.stripe.com/pay/cs_test_123");
            com.stripe.param.checkout.SessionCreateParams captured = captor.getValue();
            // C'est ce champ que ProBillingStripeWebhookHandler relit pour rattacher
            // l'abonnement au bon compte : mal rempli, l'abonnement se rattacherait
            // au mauvais utilisateur ou à personne.
            assertThat(captured.getClientReferenceId()).isEqualTo(USER_ID.toString());
            assertThat(captured.getCustomer()).isNull();
        }
    }

    @Test
    @DisplayName("réabonnement : le client Stripe existant est réutilisé, pas recréé")
    void checkoutSessionReusesExistingStripeCustomer() {
        when(repository.findByUserId(USER_ID))
                .thenReturn(Optional.of(subscription(ProSubscriptionStatus.EXPIRED, "cus_existing")));
        StripeBillingService service = new StripeBillingService(repository, configured());

        com.stripe.model.checkout.Session fakeSession = mock(com.stripe.model.checkout.Session.class);
        when(fakeSession.getUrl()).thenReturn("https://checkout.stripe.com/pay/cs_test_456");

        try (MockedStatic<com.stripe.model.checkout.Session> mocked =
                     Mockito.mockStatic(com.stripe.model.checkout.Session.class)) {
            ArgumentCaptor<com.stripe.param.checkout.SessionCreateParams> captor =
                    ArgumentCaptor.forClass(com.stripe.param.checkout.SessionCreateParams.class);
            mocked.when(() -> com.stripe.model.checkout.Session.create(captor.capture()))
                    .thenReturn(fakeSession);

            service.createCheckoutSession(USER_ID, BillingCycle.YEARLY);

            // Sans cela, un second client Stripe serait créé au réabonnement,
            // perdant l'historique de facturation du premier.
            assertThat(captor.getValue().getCustomer()).isEqualTo("cus_existing");
        }
    }

    @Test
    @DisplayName("session Customer Portal créée : l'URL Stripe est retournée")
    void portalSessionHappyPathReturnsUrl() {
        when(repository.findByUserId(USER_ID))
                .thenReturn(Optional.of(subscription(ProSubscriptionStatus.ACTIVE, "cus_1")));
        StripeBillingService service = new StripeBillingService(repository, configured());

        com.stripe.model.billingportal.Session fakeSession =
                mock(com.stripe.model.billingportal.Session.class);
        when(fakeSession.getUrl()).thenReturn("https://billing.stripe.com/session/bps_test_1");

        try (MockedStatic<com.stripe.model.billingportal.Session> mocked =
                     Mockito.mockStatic(com.stripe.model.billingportal.Session.class)) {
            mocked.when(() -> com.stripe.model.billingportal.Session.create(any(
                            com.stripe.param.billingportal.SessionCreateParams.class)))
                    .thenReturn(fakeSession);

            String url = service.createPortalSession(USER_ID);

            assertThat(url).isEqualTo("https://billing.stripe.com/session/bps_test_1");
        }
    }
}
