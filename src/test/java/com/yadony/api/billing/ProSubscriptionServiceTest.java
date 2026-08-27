package com.yadony.api.billing;

import com.yadony.api.common.AuditService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProSubscriptionServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID SUB_ID = UUID.randomUUID();

    @Mock ProSubscriptionRepository repository;
    @Mock ProAccessSynchronizer accessSynchronizer;
    @Mock AuditService auditService;

    private ProSubscriptionService service() {
        return new ProSubscriptionService(repository, accessSynchronizer, auditService);
    }

    private ProSubscriptionEntity subscription(ProSubscriptionStatus status,
                                               ProSubscriptionSource source) {
        ProSubscriptionEntity sub = new ProSubscriptionEntity();
        ReflectionTestUtils.setField(sub, "id", SUB_ID);
        sub.setUserId(USER_ID);
        sub.setStatus(status);
        sub.setSource(source);
        return sub;
    }

    @Test
    @DisplayName("openLegacyGrace crée une grâce datée et ouvre l'accès")
    void opensLegacyGrace() {
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        when(repository.save(any(ProSubscriptionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Instant before = Instant.now();
        ProSubscriptionEntity result = service().openLegacyGrace(USER_ID, 60);

        assertThat(result.getStatus()).isEqualTo(ProSubscriptionStatus.LEGACY_GRACE);
        assertThat(result.getSource()).isEqualTo(ProSubscriptionSource.LEGACY_FREE);
        assertThat(result.getUserId()).isEqualTo(USER_ID);
        assertThat(result.getGraceExpiresAt())
                .isBetween(before.plus(59, ChronoUnit.DAYS), Instant.now().plus(61, ChronoUnit.DAYS));
        verify(accessSynchronizer).sync(USER_ID, true);
    }

    @Test
    @DisplayName("openLegacyGrace recycle la ligne existante et purge les résidus du cycle précédent")
    void reusesExistingRowAndClearsStaleFields() {
        ProSubscriptionEntity existing = subscription(ProSubscriptionStatus.EXPIRED,
                ProSubscriptionSource.STRIPE);
        existing.setPastDueSince(Instant.now().minus(30, ChronoUnit.DAYS));
        existing.setCancelAtPeriodEnd(true);
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(existing);

        ProSubscriptionEntity result = service().openLegacyGrace(USER_ID, 60);

        // Une seule ligne par utilisateur : uq_pro_subscriptions_user refuserait
        // une seconde insertion, statut fermé compris.
        assertThat(result.getId()).isEqualTo(SUB_ID);
        assertThat(result.getStatus()).isEqualTo(ProSubscriptionStatus.LEGACY_GRACE);
        assertThat(result.getPastDueSince())
                .as("un past_due_since périmé ferait expirer la grâce au premier cron de dunning")
                .isNull();
        assertThat(result.isCancelAtPeriodEnd()).isFalse();
    }

    @Test
    @DisplayName("openLegacyGrace purge les champs Stripe/admin d'un cycle Stripe précédent")
    void reusesExistingRowAndPurgesStaleStripeAndAdminFields() {
        ProSubscriptionEntity existing = subscription(ProSubscriptionStatus.CANCELED,
                ProSubscriptionSource.STRIPE);
        existing.setStripeCustomerId("cus_stale");
        existing.setStripeSubscriptionId("sub_stale");
        existing.setBillingCycle(BillingCycle.MONTHLY);
        existing.setCurrentPeriodEnd(Instant.now().minus(10, ChronoUnit.DAYS));
        existing.setGrantedByAdminId(UUID.randomUUID());
        existing.setAdminGrantReason("ancien octroi admin");
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(existing);

        ProSubscriptionEntity result = service().openLegacyGrace(USER_ID, 60);

        // Une ligne LEGACY_FREE ne doit porter aucun résidu d'un cycle Stripe ou
        // d'un octroi admin précédent : stripe_subscription_id est indexée pour
        // les webhooks du lot 2, qui ramèneraient sinon cette ligne périmée au
        // premier webhook reçu pour cet identifiant.
        assertThat(result.getStripeCustomerId()).isNull();
        assertThat(result.getStripeSubscriptionId()).isNull();
        assertThat(result.getBillingCycle()).isNull();
        assertThat(result.getCurrentPeriodEnd()).isNull();
        assertThat(result.getGrantedByAdminId()).isNull();
        assertThat(result.getAdminGrantReason()).isNull();
    }

    @Test
    @DisplayName("markPastDue horodate l'entrée en impayé sans couper l'accès")
    void marksPastDue() {
        ProSubscriptionEntity sub = subscription(ProSubscriptionStatus.ACTIVE,
                ProSubscriptionSource.STRIPE);
        when(repository.save(sub)).thenReturn(sub);

        ProSubscriptionEntity result = service().markPastDue(sub);

        assertThat(result.getStatus()).isEqualTo(ProSubscriptionStatus.PAST_DUE);
        assertThat(result.getPastDueSince()).isNotNull();
        // L'accès reste ouvert pendant les relances Stripe.
        verify(accessSynchronizer).sync(USER_ID, true);
    }

    @Test
    @DisplayName("expire ferme l'accès et journalise")
    void expires() {
        ProSubscriptionEntity sub = subscription(ProSubscriptionStatus.LEGACY_GRACE,
                ProSubscriptionSource.LEGACY_FREE);
        when(repository.save(sub)).thenReturn(sub);

        ProSubscriptionEntity result = service().expire(sub);

        assertThat(result.getStatus()).isEqualTo(ProSubscriptionStatus.EXPIRED);
        verify(accessSynchronizer).sync(USER_ID, false);
        verify(auditService).log(eq("BILLING"), eq(SUB_ID),
                eq("BILLING_SUBSCRIPTION_EXPIRED"), eq(USER_ID), anyMap());
    }

    @Test
    @DisplayName("cancel ferme l'accès et journalise")
    void cancels() {
        ProSubscriptionEntity sub = subscription(ProSubscriptionStatus.ACTIVE,
                ProSubscriptionSource.STRIPE);
        when(repository.save(sub)).thenReturn(sub);

        ProSubscriptionEntity result = service().cancel(sub);

        assertThat(result.getStatus()).isEqualTo(ProSubscriptionStatus.CANCELED);
        verify(accessSynchronizer).sync(USER_ID, false);
        verify(auditService).log(eq("BILLING"), eq(SUB_ID),
                eq("BILLING_SUBSCRIPTION_CANCELED"), eq(USER_ID), anyMap());
    }

    @Test
    @DisplayName("le payload d'audit porte le statut précédent et la source")
    void auditPayloadCarriesContext() {
        ProSubscriptionEntity sub = subscription(ProSubscriptionStatus.PAST_DUE,
                ProSubscriptionSource.STRIPE);
        when(repository.save(sub)).thenReturn(sub);

        service().expire(sub);

        verify(auditService).log(eq("BILLING"), eq(SUB_ID), eq("BILLING_SUBSCRIPTION_EXPIRED"),
                eq(USER_ID), eq(Map.of("previousStatus", "PAST_DUE", "source", "STRIPE")));
    }
}
