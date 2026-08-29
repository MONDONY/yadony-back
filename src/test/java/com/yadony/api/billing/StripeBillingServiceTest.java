package com.yadony.api.billing;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
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
    @Mock UserRepository userRepository;

    /**
     * Le service reçoit une VRAIE ProTrialPolicy, pas un mock : la règle d'essai est
     * exactement celle que le portail lira, et un test qui mockerait la politique
     * prouverait seulement que le service appelle une méthode.
     */
    private StripeBillingService service(BillingProperties props) {
        return new StripeBillingService(repository, props,
                new ProTrialPolicy(userRepository, repository, props));
    }

    /** Voyageur ayant réalisé {@code trips} trajets, tel que la politique le lira. */
    private void travelerWith(int trips) {
        UserEntity user = new UserEntity();
        user.setTotalTrips(trips);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
    }

    private BillingProperties configured() {
        return new BillingProperties(false, 60, 5, "price_m", "price_y",
                "https://yadony.com/pro/ok", "https://yadony.com/pro/ko",
                "https://yadony.com/pro/parametres/abonnement", null);
    }

    private BillingProperties withTrial(int days) {
        return new BillingProperties(false, 60, 5, "price_m", "price_y",
                "https://yadony.com/pro/ok", "https://yadony.com/pro/ko",
                "https://yadony.com/pro/parametres/abonnement", days);
    }

    private BillingProperties unconfigured() {
        return new BillingProperties(false, 60, 5, null, null,
                "https://yadony.com/pro/ok", "https://yadony.com/pro/ko",
                "https://yadony.com/pro/parametres/abonnement", null);
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
        StripeBillingService service = service(unconfigured());

        assertThatThrownBy(() -> service.createCheckoutSession(USER_ID, BillingCycle.MONTHLY))
                .isInstanceOf(YadonyBusinessException.class)
                .hasMessageContaining("billing");
    }

    @Test
    @DisplayName("un abonnement déjà actif refuse une seconde souscription")
    void alreadyActiveIsRejected() {
        when(repository.findByUserId(USER_ID))
                .thenReturn(Optional.of(subscription(ProSubscriptionStatus.ACTIVE, "cus_1")));
        StripeBillingService service = service(configured());

        assertThatThrownBy(() -> service.createCheckoutSession(USER_ID, BillingCycle.MONTHLY))
                .isInstanceOf(YadonyBusinessException.class);
    }

    @Test
    @DisplayName("un compte en grâce historique peut souscrire : c'est le but")
    void legacyGraceCanSubscribe() {
        when(repository.findByUserId(USER_ID))
                .thenReturn(Optional.of(subscription(ProSubscriptionStatus.LEGACY_GRACE, null)));
        StripeBillingService service = service(configured());

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
        StripeBillingService service = service(configured());

        assertThatThrownBy(() -> service.createPortalSession(USER_ID))
                .isInstanceOf(YadonyBusinessException.class);
    }

    @Test
    @DisplayName("sans abonnement du tout, l'accès au portail est refusé")
    void portalWithoutSubscriptionIsRejected() {
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        StripeBillingService service = service(configured());

        assertThatThrownBy(() -> service.createPortalSession(USER_ID))
                .isInstanceOf(YadonyBusinessException.class);
    }

    @Test
    @DisplayName("nouveau client : la session Checkout créée porte l'utilisateur en client_reference_id")
    void checkoutSessionHappyPathCarriesUserIdAsClientReference() {
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        StripeBillingService service = service(configured());

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
        StripeBillingService service = service(configured());

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
        StripeBillingService service = service(configured());

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

    /**
     * Capture les paramètres de la session Checkout créée et les rend, pour assertion.
     * Le mock statique doit envelopper l'appel : {@code Session.create} est statique.
     */
    private com.stripe.param.checkout.SessionCreateParams captureSession(StripeBillingService service) {
        com.stripe.model.checkout.Session fakeSession = mock(com.stripe.model.checkout.Session.class);
        when(fakeSession.getUrl()).thenReturn("https://checkout.stripe.com/pay/cs_trial");

        try (MockedStatic<com.stripe.model.checkout.Session> mocked =
                     Mockito.mockStatic(com.stripe.model.checkout.Session.class)) {
            ArgumentCaptor<com.stripe.param.checkout.SessionCreateParams> captor =
                    ArgumentCaptor.forClass(com.stripe.param.checkout.SessionCreateParams.class);
            mocked.when(() -> com.stripe.model.checkout.Session.create(captor.capture()))
                    .thenReturn(fakeSession);
            service.createCheckoutSession(USER_ID, BillingCycle.MONTHLY);
            return captor.getValue();
        }
    }

    @Test
    @DisplayName("voyageur ayant déjà roulé : l'essai configuré est demandé à Stripe")
    void firstSubscriptionCarriesTheConfiguredTrial() {
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        travelerWith(1);

        com.stripe.param.checkout.SessionCreateParams params =
                captureSession(service(withTrial(14)));

        assertThat(params.getSubscriptionData().getTrialPeriodDays()).isEqualTo(14L);
        // Le repli du webhook quand client_reference_id manque : il doit survivre à l'essai.
        assertThat(params.getSubscriptionData().getMetadata())
                .containsEntry("user_id", USER_ID.toString());
    }

    @Test
    @DisplayName("sans essai configuré, le champ est omis plutôt qu'envoyé à zéro")
    void noTrialConfiguredOmitsTheField() {
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        com.stripe.param.checkout.SessionCreateParams params =
                captureSession(service(configured()));

        assertThat(params.getSubscriptionData().getTrialPeriodDays()).isNull();
    }

    /**
     * Le client Stripe est conservé après une résiliation : sa présence prouve que ce
     * compte a déjà eu un abonnement payant. Sans cette garde, résilier puis se réabonner
     * rendrait l'essai renouvelable indéfiniment — un mois gratuit à volonté.
     */
    @Test
    @DisplayName("réabonnement : l'essai n'est PAS réoffert à un ancien client Stripe")
    void trialIsNotOfferedTwiceToAReturningCustomer() {
        when(repository.findByUserId(USER_ID))
                .thenReturn(Optional.of(subscription(ProSubscriptionStatus.EXPIRED, "cus_existing")));
        travelerWith(3);

        com.stripe.param.checkout.SessionCreateParams params =
                captureSession(service(withTrial(14)));

        assertThat(params.getSubscriptionData().getTrialPeriodDays()).isNull();
        assertThat(params.getCustomer()).isEqualTo("cus_existing");
    }

    @Test
    @DisplayName("un accès offert par un admin ne consomme pas le droit à l'essai")
    void adminGrantedAccountKeepsItsTrialEntitlement() {
        ProSubscriptionEntity granted = new ProSubscriptionEntity();
        granted.setUserId(USER_ID);
        granted.setStatus(ProSubscriptionStatus.CANCELED);
        granted.setSource(ProSubscriptionSource.ADMIN_GRANT);
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.of(granted));
        travelerWith(1);

        com.stripe.param.checkout.SessionCreateParams params =
                captureSession(service(withTrial(14)));

        assertThat(params.getSubscriptionData().getTrialPeriodDays()).isEqualTo(14L);
    }

    /**
     * La condition demandée par le produit : l'essai récompense un usage réel, il n'est pas
     * une porte d'entrée. Sans trajet réalisé, le Checkout part quand même — l'utilisateur
     * peut devenir PRO en payant — mais sans période gratuite.
     */
    @Test
    @DisplayName("aucun trajet réalisé : pas d'essai, mais l'abonnement reste possible")
    void noCompletedTripMeansNoTrialButStillCheckout() {
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        travelerWith(0);

        com.stripe.param.checkout.SessionCreateParams params =
                captureSession(service(withTrial(7)));

        assertThat(params.getSubscriptionData().getTrialPeriodDays()).isNull();
        // La session est bien créée : refuser l'essai ne doit jamais refuser l'abonnement.
        assertThat(params.getClientReferenceId()).isEqualTo(USER_ID.toString());
    }

    @Test
    @DisplayName("utilisateur introuvable : pas d'essai, jamais d'exception")
    void unknownUserGetsNoTrial() {
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        com.stripe.param.checkout.SessionCreateParams params =
                captureSession(service(withTrial(7)));

        assertThat(params.getSubscriptionData().getTrialPeriodDays()).isNull();
    }
}
