package com.yadony.api.billing;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * La règle d'éligibilité à l'essai, testée seule.
 *
 * <p>Elle vit ici plutôt que dans {@code StripeBillingService} parce que deux appelants la
 * partagent : le Checkout et {@code GET /billing/subscription}. Une divergence entre les
 * deux ferait annoncer au portail un essai que Stripe refuserait — d'où une classe unique,
 * et des tests qui portent sur elle.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProTrialPolicy — qui a droit à l'essai gratuit")
class ProTrialPolicyTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock UserRepository userRepository;
    @Mock ProSubscriptionRepository subscriptionRepository;

    private BillingProperties withTrial(Integer days) {
        return new BillingProperties(false, 60, 5, "price_m", "price_y",
                "https://yadony.com/pro/ok", "https://yadony.com/pro/ko",
                "https://yadony.com/pro/parametres/abonnement", days);
    }

    private ProTrialPolicy policy(Integer trialDays) {
        return new ProTrialPolicy(userRepository, subscriptionRepository, withTrial(trialDays));
    }

    private void travelerWith(int trips) {
        UserEntity user = new UserEntity();
        user.setTotalTrips(trips);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
    }

    private ProSubscriptionEntity subscriptionWithCustomer(String customerId) {
        ProSubscriptionEntity sub = new ProSubscriptionEntity();
        sub.setUserId(USER_ID);
        sub.setStatus(ProSubscriptionStatus.EXPIRED);
        sub.setSource(ProSubscriptionSource.STRIPE);
        sub.setStripeCustomerId(customerId);
        return sub;
    }

    @Test
    @DisplayName("un trajet réalisé et jamais client Stripe : éligible")
    void completedTripAndNeverPaidIsEligible() {
        travelerWith(1);
        when(subscriptionRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        assertThat(policy(7).trialDaysFor(USER_ID)).isEqualTo(7L);
        assertThat(policy(7).isEligible(USER_ID)).isTrue();
    }

    @Test
    @DisplayName("aucun trajet réalisé : pas éligible")
    void noCompletedTripIsNotEligible() {
        travelerWith(0);

        assertThat(policy(7).trialDaysFor(USER_ID)).isNull();
    }

    /**
     * L'identifiant client Stripe survit volontairement à une résiliation. Sans cette
     * condition, résilier puis se réabonner rendrait l'essai renouvelable sans fin.
     */
    @Test
    @DisplayName("ancien client Stripe : pas éligible, même avec des trajets")
    void formerStripeCustomerIsNotEligible() {
        travelerWith(5);
        when(subscriptionRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(subscriptionWithCustomer("cus_123")));

        assertThat(policy(7).trialDaysFor(USER_ID)).isNull();
    }

    @Test
    @DisplayName("un identifiant client vide ne vaut pas un passé de client payant")
    void blankCustomerIdDoesNotCount() {
        travelerWith(1);
        when(subscriptionRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(subscriptionWithCustomer("   ")));

        assertThat(policy(7).trialDaysFor(USER_ID)).isEqualTo(7L);
    }

    @Test
    @DisplayName("essai désactivé : personne n'est éligible, et la base n'est même pas lue")
    void disabledTrialShortCircuits() {
        assertThat(policy(0).trialDaysFor(USER_ID)).isNull();
        assertThat(policy(null).trialDaysFor(USER_ID)).isNull();
    }

    @Test
    @DisplayName("utilisateur introuvable : pas éligible, jamais d'exception")
    void unknownUserIsNotEligible() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThat(policy(7).trialDaysFor(USER_ID)).isNull();
    }
}
