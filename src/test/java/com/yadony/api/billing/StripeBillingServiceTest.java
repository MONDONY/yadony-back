package com.yadony.api.billing;

import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("StripeBillingService — gardes avant appel à Stripe")
class StripeBillingServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock ProSubscriptionRepository repository;

    private BillingProperties configured() {
        return new BillingProperties(false, 60, 5, "price_m", "price_y",
                "https://pro.yadony.com/ok", "https://pro.yadony.com/ko",
                "https://pro.yadony.com/parametres/abonnement");
    }

    private BillingProperties unconfigured() {
        return new BillingProperties(false, 60, 5, null, null,
                "https://pro.yadony.com/ok", "https://pro.yadony.com/ko",
                "https://pro.yadony.com/parametres/abonnement");
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
}
