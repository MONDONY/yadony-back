package com.yadony.api.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProSubscriptionSchedulerTest {

    @Mock ProSubscriptionRepository repository;
    @Mock ProSubscriptionService subscriptionService;

    private ProSubscriptionScheduler scheduler(boolean enabled) {
        return new ProSubscriptionScheduler(repository, subscriptionService,
                new BillingProperties(enabled, 60, 5, null, null, null, null, null, null));
    }

    private ProSubscriptionEntity subscription(ProSubscriptionStatus status) {
        ProSubscriptionEntity sub = new ProSubscriptionEntity();
        sub.setUserId(UUID.randomUUID());
        sub.setStatus(status);
        sub.setSource(ProSubscriptionSource.LEGACY_FREE);
        return sub;
    }

    @Test
    @DisplayName("drapeau désactivé : aucune lecture en base, aucun downgrade")
    void disabledSchedulerDoesNothing() {
        ProSubscriptionScheduler s = scheduler(false);

        s.expireLegacyGrace();
        s.expireExhaustedDunning();
        s.closeEndedCancellations();

        verifyNoInteractions(repository);
        verifyNoInteractions(subscriptionService);
    }

    @Test
    @DisplayName("grâce échue : expiration")
    void expiresLegacyGrace() {
        ProSubscriptionEntity sub = subscription(ProSubscriptionStatus.LEGACY_GRACE);
        when(repository.findByStatusAndGraceExpiresAtBefore(
                eq(ProSubscriptionStatus.LEGACY_GRACE), any(Instant.class)))
                .thenReturn(List.of(sub));

        scheduler(true).expireLegacyGrace();

        verify(subscriptionService).expire(sub);
    }

    @Test
    @DisplayName("dunning épuisé : expiration")
    void expiresExhaustedDunning() {
        ProSubscriptionEntity sub = subscription(ProSubscriptionStatus.PAST_DUE);
        when(repository.findByStatusAndPastDueSinceBefore(
                eq(ProSubscriptionStatus.PAST_DUE), any(Instant.class)))
                .thenReturn(List.of(sub));

        scheduler(true).expireExhaustedDunning();

        verify(subscriptionService).expire(sub);
    }

    @Test
    @DisplayName("période résiliée écoulée : annulation")
    void closesEndedCancellations() {
        ProSubscriptionEntity sub = subscription(ProSubscriptionStatus.ACTIVE);
        when(repository.findByStatusAndCancelAtPeriodEndTrueAndCurrentPeriodEndBefore(
                eq(ProSubscriptionStatus.ACTIVE), any(Instant.class)))
                .thenReturn(List.of(sub));

        scheduler(true).closeEndedCancellations();

        verify(subscriptionService).cancel(sub);
    }

    @Test
    @DisplayName("rien à traiter : aucun appel au service")
    void noWorkNoCalls() {
        when(repository.findByStatusAndGraceExpiresAtBefore(any(), any()))
                .thenReturn(List.of());

        scheduler(true).expireLegacyGrace();

        verify(subscriptionService, never()).expire(any());
    }
}
