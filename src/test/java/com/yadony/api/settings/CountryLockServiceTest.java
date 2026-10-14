package com.yadony.api.settings;

import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.StripeReleaseIntentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Le verrou pays porte sur un compte Stripe Connect engagé (son pays est immuable chez
 * Stripe) : un compte encore en attente d'onboarding, sans aucun mouvement, ne verrouille pas.
 */
@ExtendWith(MockitoExtension.class)
class CountryLockServiceTest {

    @Mock UserRepository userRepository;
    @Mock StripeReleaseIntentRepository releaseIntentRepository;
    @Mock PaymentRepository paymentRepository;
    @InjectMocks CountryLockService service;

    private final UUID userId = UUID.randomUUID();

    private UserEntity user(String accountId, StripeAccountStatus status) {
        UserEntity user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", userId);
        user.setStripeAccountId(accountId);
        user.setStripeAccountStatus(status);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        lenient().when(releaseIntentRepository.existsByDestination(accountId)).thenReturn(false);
        lenient().when(paymentRepository.existsCardPaymentForTraveler(userId)).thenReturn(false);
        return user;
    }

    @Test
    void isLocked_noUserFound_returnsFalse() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThat(service.isLocked(userId)).isFalse();
        assertThat(service.detachablePendingStripeAccountId(userId)).isEmpty();
    }

    @Test
    @DisplayName("Sans compte Stripe : pays libre, rien a detacher")
    void noStripeAccount_isFree() {
        user(null, StripeAccountStatus.NOT_CREATED);

        assertThat(service.isLocked(userId)).isFalse();
        assertThat(service.detachablePendingStripeAccountId(userId)).isEmpty();
    }

    @Test
    void blankStripeAccount_isFree() {
        user("", StripeAccountStatus.NOT_CREATED);

        assertThat(service.isLocked(userId)).isFalse();
        assertThat(service.detachablePendingStripeAccountId(userId)).isEmpty();
    }

    @Test
    @DisplayName("Compte en attente d'onboarding sans mouvement : pays libre, compte detachable")
    void pendingWithoutMovement_isFreeAndDetachable() {
        user("acct_pending", StripeAccountStatus.PENDING_ONBOARDING);

        assertThat(service.isLocked(userId)).isFalse();
        assertThat(service.detachablePendingStripeAccountId(userId)).contains("acct_pending");
    }

    @Test
    @DisplayName("Onboarding termine : pays verrouille")
    void onboardingComplete_locks() {
        user("acct_done", StripeAccountStatus.ONBOARDING_COMPLETE);

        assertThat(service.isLocked(userId)).isTrue();
        assertThat(service.detachablePendingStripeAccountId(userId)).isEmpty();
    }

    @Test
    @DisplayName("Compte desactive ou rejete par Stripe : pays verrouille par prudence")
    void disabledOrRejected_locks() {
        user("acct_disabled", StripeAccountStatus.DISABLED);
        assertThat(service.isLocked(userId)).isTrue();

        user("acct_rejected", StripeAccountStatus.REJECTED);
        assertThat(service.isLocked(userId)).isTrue();
    }

    @Test
    @DisplayName("Identifiant present avec un statut NOT_CREATED (etat incoherent) : verrouille")
    void inconsistentNotCreatedWithAccount_locks() {
        user("acct_odd", StripeAccountStatus.NOT_CREATED);

        assertThat(service.isLocked(userId)).isTrue();
    }

    @Test
    @DisplayName("En attente mais onboarding deja termine une fois : verrouille")
    void pendingWithOnboardingCompletedAt_locks() {
        UserEntity user = user("acct_back_to_pending", StripeAccountStatus.PENDING_ONBOARDING);
        user.setStripeOnboardingCompletedAt(Instant.now());

        assertThat(service.isLocked(userId)).isTrue();
        assertThat(service.detachablePendingStripeAccountId(userId)).isEmpty();
    }

    @Test
    @DisplayName("En attente mais un versement carte a vise le compte : verrouille")
    void pendingWithTransfer_locks() {
        user("acct_paid", StripeAccountStatus.PENDING_ONBOARDING);
        when(releaseIntentRepository.existsByDestination("acct_paid")).thenReturn(true);

        assertThat(service.isLocked(userId)).isTrue();
    }

    @Test
    @DisplayName("En attente mais un paiement carte a l'utilisateur pour voyageur : verrouille")
    void pendingWithCardPayment_locks() {
        user("acct_card", StripeAccountStatus.PENDING_ONBOARDING);
        when(paymentRepository.existsCardPaymentForTraveler(userId)).thenReturn(true);

        assertThat(service.isLocked(userId)).isTrue();
        assertThat(service.detachablePendingStripeAccountId(userId)).isEmpty();
    }
}
