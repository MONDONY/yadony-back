package com.yadony.api.billing;

import com.yadony.api.auth.UserProStatusChangedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LegacyProGraceListenerTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock ProSubscriptionRepository repository;
    @Mock ProSubscriptionService subscriptionService;

    private LegacyProGraceListener listener() {
        BillingProperties props = new BillingProperties(false, 60, 5, null, null, null, null, null, null);
        return new LegacyProGraceListener(repository, subscriptionService, props);
    }

    @Test
    @DisplayName("un PRO sans abonnement reçoit une grâce")
    void opensGraceForOrphanPro() {
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        listener().onUserProStatusChanged(new UserProStatusChangedEvent(USER_ID, true));

        verify(subscriptionService).openLegacyGrace(USER_ID, 60);
    }

    @Test
    @DisplayName("un PRO déjà couvert par un abonnement ouvert n'en reçoit pas un second")
    void ignoresUserWithActiveSubscription() {
        ProSubscriptionEntity active = new ProSubscriptionEntity();
        active.setStatus(ProSubscriptionStatus.ACTIVE);
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.of(active));

        listener().onUserProStatusChanged(new UserProStatusChangedEvent(USER_ID, true));

        verify(subscriptionService, never()).openLegacyGrace(any(), anyInt());
    }

    @Test
    @DisplayName("un PRO dont l'abonnement est fermé reçoit une nouvelle grâce")
    void reopensGraceForClosedSubscription() {
        ProSubscriptionEntity expired = new ProSubscriptionEntity();
        expired.setStatus(ProSubscriptionStatus.EXPIRED);
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.of(expired));

        listener().onUserProStatusChanged(new UserProStatusChangedEvent(USER_ID, true));

        // Sans cela, un utilisateur expiré repassant par l'upgrade gratuit
        // resterait PRO indéfiniment avec un abonnement fermé.
        verify(subscriptionService).openLegacyGrace(USER_ID, 60);
    }

    @Test
    @DisplayName("une perte d'accès ferme l'abonnement encore ouvert")
    void closesOpenSubscriptionOnDowngrade() {
        ProSubscriptionEntity active = new ProSubscriptionEntity();
        active.setStatus(ProSubscriptionStatus.ACTIVE);
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.of(active));

        listener().onUserProStatusChanged(new UserProStatusChangedEvent(USER_ID, false));

        // Sans cela, DELETE /auth/me/upgrade-to-pro met is_pro_account=false
        // mais laisse la ligne pro_subscriptions ouverte : grantsProAccess()
        // resterait vrai alors que le drapeau est faux.
        verify(subscriptionService).cancel(active);
    }

    @Test
    @DisplayName("une perte d'accès sur un abonnement déjà fermé ne fait rien")
    void ignoresDowngradeWhenSubscriptionAlreadyClosed() {
        ProSubscriptionEntity expired = new ProSubscriptionEntity();
        expired.setStatus(ProSubscriptionStatus.EXPIRED);
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.of(expired));

        listener().onUserProStatusChanged(new UserProStatusChangedEvent(USER_ID, false));

        verify(subscriptionService, never()).cancel(any());
    }
}
