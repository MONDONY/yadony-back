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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProSubscriptionService — transitions pilotées par Stripe")
class ProSubscriptionServiceStripeTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID SUB_ID = UUID.randomUUID();
    private static final String CUSTOMER_ID = "cus_test_123";
    private static final String SUBSCRIPTION_ID = "sub_test_456";

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
    @DisplayName("une souscription Stripe sur un compte neuf crée une ligne ACTIVE")
    void activatesFreshSubscription() {
        Instant periodEnd = Instant.now().plus(30, ChronoUnit.DAYS);
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        when(repository.save(any(ProSubscriptionEntity.class)))
                .thenAnswer(inv -> {
                    ProSubscriptionEntity entity = inv.getArgument(0);
                    ReflectionTestUtils.setField(entity, "id", SUB_ID);
                    return entity;
                });

        ProSubscriptionEntity result = service().activateFromStripe(
                USER_ID, CUSTOMER_ID, SUBSCRIPTION_ID, BillingCycle.MONTHLY, periodEnd);

        assertThat(result.getStatus()).isEqualTo(ProSubscriptionStatus.ACTIVE);
        assertThat(result.getSource()).isEqualTo(ProSubscriptionSource.STRIPE);
        assertThat(result.getStripeCustomerId()).isEqualTo(CUSTOMER_ID);
        assertThat(result.getStripeSubscriptionId()).isEqualTo(SUBSCRIPTION_ID);
        assertThat(result.getBillingCycle()).isEqualTo(BillingCycle.MONTHLY);
        assertThat(result.getCurrentPeriodEnd()).isEqualTo(periodEnd);
        verify(accessSynchronizer).sync(USER_ID, true);
        verify(auditService).log(eq("BILLING"), eq(SUB_ID), eq("BILLING_SUBSCRIPTION_ACTIVATED"),
                eq(USER_ID), anyMap());
    }

    @Test
    @DisplayName("une souscription Stripe recycle la ligne de grâce et purge ses résidus")
    void activationRecyclesLegacyGraceRow() {
        ProSubscriptionEntity legacy = subscription(ProSubscriptionStatus.LEGACY_GRACE,
                ProSubscriptionSource.LEGACY_FREE);
        legacy.setGraceExpiresAt(Instant.now().plus(10, ChronoUnit.DAYS));
        legacy.setPastDueSince(Instant.now().minus(2, ChronoUnit.DAYS));
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.of(legacy));
        when(repository.save(legacy)).thenReturn(legacy);

        ProSubscriptionEntity result = service().activateFromStripe(
                USER_ID, CUSTOMER_ID, SUBSCRIPTION_ID, BillingCycle.YEARLY,
                Instant.now().plus(365, ChronoUnit.DAYS));

        // Une seule ligne par utilisateur : uq_pro_subscriptions_user refuserait une insertion.
        assertThat(result.getId()).isEqualTo(SUB_ID);
        assertThat(result.getSource()).isEqualTo(ProSubscriptionSource.STRIPE);
        assertThat(result.getGraceExpiresAt())
                .as("la grâce n'a plus lieu d'être une fois l'abonnement payé")
                .isNull();
        assertThat(result.getPastDueSince()).isNull();
        verify(accessSynchronizer).sync(USER_ID, true);
    }

    @Test
    @DisplayName("une réactivation après résiliation efface le drapeau de résiliation")
    void activationClearsCancelAtPeriodEnd() {
        ProSubscriptionEntity canceled = subscription(ProSubscriptionStatus.CANCELED,
                ProSubscriptionSource.STRIPE);
        canceled.setCancelAtPeriodEnd(true);
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.of(canceled));
        when(repository.save(canceled)).thenReturn(canceled);

        ProSubscriptionEntity result = service().activateFromStripe(
                USER_ID, CUSTOMER_ID, SUBSCRIPTION_ID, BillingCycle.MONTHLY,
                Instant.now().plus(30, ChronoUnit.DAYS));

        assertThat(result.getStatus()).isEqualTo(ProSubscriptionStatus.ACTIVE);
        assertThat(result.isCancelAtPeriodEnd()).isFalse();
        verify(accessSynchronizer).sync(USER_ID, true);
    }

    @Test
    @DisplayName("l'activation depuis Stripe purge les traces d'un octroi administrateur antérieur")
    void activationPurgesAdminGrantTrace() {
        UUID adminId = UUID.randomUUID();
        ProSubscriptionEntity adminGranted = subscription(ProSubscriptionStatus.ACTIVE,
                ProSubscriptionSource.ADMIN_GRANT);
        adminGranted.setGrantedByAdminId(adminId);
        adminGranted.setAdminGrantReason("Promotion winter 2026");
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.of(adminGranted));
        when(repository.save(adminGranted)).thenAnswer(inv -> {
            ProSubscriptionEntity entity = inv.getArgument(0);
            ReflectionTestUtils.setField(entity, "id", SUB_ID);
            return entity;
        });

        ProSubscriptionEntity result = service().activateFromStripe(
                USER_ID, CUSTOMER_ID, SUBSCRIPTION_ID, BillingCycle.MONTHLY,
                Instant.now().plus(30, ChronoUnit.DAYS));

        assertThat(result.getSource()).isEqualTo(ProSubscriptionSource.STRIPE);
        assertThat(result.getGrantedByAdminId())
                .as("la source change : les traces de l'octroi administratif ne doivent pas survivre")
                .isNull();
        assertThat(result.getAdminGrantReason()).isNull();
        verify(accessSynchronizer).sync(USER_ID, true);
    }

    @Test
    @DisplayName("markCancelAtPeriodEnd ne coupe pas l'accès : la période est payée")
    void cancelAtPeriodEndKeepsAccess() {
        ProSubscriptionEntity active = subscription(ProSubscriptionStatus.ACTIVE,
                ProSubscriptionSource.STRIPE);
        when(repository.save(active)).thenReturn(active);

        ProSubscriptionEntity result = service().markCancelAtPeriodEnd(active, true);

        assertThat(result.isCancelAtPeriodEnd()).isTrue();
        assertThat(result.getStatus())
                .as("l'accès court jusqu'à l'échéance déjà réglée")
                .isEqualTo(ProSubscriptionStatus.ACTIVE);
    }

    @Test
    @DisplayName("renew repousse l'échéance et sort d'un impayé")
    void renewClearsPastDue() {
        ProSubscriptionEntity pastDue = subscription(ProSubscriptionStatus.PAST_DUE,
                ProSubscriptionSource.STRIPE);
        pastDue.setPastDueSince(Instant.now().minus(3, ChronoUnit.DAYS));
        Instant newEnd = Instant.now().plus(30, ChronoUnit.DAYS);
        when(repository.save(pastDue)).thenReturn(pastDue);

        ProSubscriptionEntity result = service().renew(pastDue, newEnd);

        assertThat(result.getStatus()).isEqualTo(ProSubscriptionStatus.ACTIVE);
        assertThat(result.getPastDueSince()).isNull();
        assertThat(result.getCurrentPeriodEnd()).isEqualTo(newEnd);
        verify(accessSynchronizer).sync(USER_ID, true);
        // PAST_DUE donnait déjà l'accès : ce n'est pas une résurrection, pas d'audit dédié.
        verify(auditService, never()).log(any(), any(), eq("BILLING_SUBSCRIPTION_REACTIVATED"), any(), anyMap());
    }

    @Test
    @DisplayName("renew depuis ACTIVE prolonge simplement la période, sans audit de résurrection")
    void renewFromActiveJustExtendsPeriod() {
        ProSubscriptionEntity active = subscription(ProSubscriptionStatus.ACTIVE,
                ProSubscriptionSource.STRIPE);
        Instant newEnd = Instant.now().plus(30, ChronoUnit.DAYS);
        when(repository.save(active)).thenReturn(active);

        ProSubscriptionEntity result = service().renew(active, newEnd);

        assertThat(result.getStatus()).isEqualTo(ProSubscriptionStatus.ACTIVE);
        assertThat(result.getCurrentPeriodEnd()).isEqualTo(newEnd);
        verify(accessSynchronizer).sync(USER_ID, true);
        verify(auditService, never()).log(any(), any(), eq("BILLING_SUBSCRIPTION_REACTIVATED"), any(), anyMap());
    }

    @Test
    @DisplayName("renew depuis EXPIRED ressuscite l'abonnement et journalise BILLING_SUBSCRIPTION_REACTIVATED")
    void renewFromExpiredReactivatesAndAudits() {
        ProSubscriptionEntity expired = subscription(ProSubscriptionStatus.EXPIRED,
                ProSubscriptionSource.STRIPE);
        Instant newEnd = Instant.now().plus(30, ChronoUnit.DAYS);
        when(repository.save(expired)).thenReturn(expired);

        ProSubscriptionEntity result = service().renew(expired, newEnd);

        assertThat(result.getStatus())
                .as("un encaissement tardif de dunning doit pouvoir ressusciter un EXPIRED")
                .isEqualTo(ProSubscriptionStatus.ACTIVE);
        assertThat(result.getCurrentPeriodEnd()).isEqualTo(newEnd);
        verify(accessSynchronizer).sync(USER_ID, true);
        verify(auditService).log(eq("BILLING"), eq(SUB_ID), eq("BILLING_SUBSCRIPTION_REACTIVATED"),
                eq(USER_ID), anyMap());
    }

    @Test
    @DisplayName("renew refuse de ressusciter un abonnement CANCELED")
    void renewRefusesToResurrectCanceledSubscription() {
        ProSubscriptionEntity canceled = subscription(ProSubscriptionStatus.CANCELED,
                ProSubscriptionSource.STRIPE);
        Instant staleEnd = canceled.getCurrentPeriodEnd();
        Instant newEnd = Instant.now().plus(30, ChronoUnit.DAYS);

        ProSubscriptionEntity result = service().renew(canceled, newEnd);

        assertThat(result.getStatus())
                .as("une résiliation volontaire ne doit jamais être rouverte par un paiement tardif")
                .isEqualTo(ProSubscriptionStatus.CANCELED);
        assertThat(result.getCurrentPeriodEnd()).isEqualTo(staleEnd);
        verify(repository, never()).save(any());
        verify(accessSynchronizer, never()).sync(any(), anyBoolean());
        verify(auditService, never()).log(any(), any(), any(), any(), anyMap());
    }
}
