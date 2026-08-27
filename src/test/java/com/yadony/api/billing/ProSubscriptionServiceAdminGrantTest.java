package com.yadony.api.billing;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProSubscriptionService.grantByAdmin — accès PRO offert par un administrateur")
class ProSubscriptionServiceAdminGrantTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final UUID SUB_ID = UUID.randomUUID();
    private static final String REASON = "Partenariat presse, 6 mois";

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
    @DisplayName("un compte sans abonnement reçoit un accès offert, ouvert et sans échéance")
    void grantsToFreshAccount() {
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        when(repository.save(any(ProSubscriptionEntity.class)))
                .thenAnswer(inv -> {
                    ProSubscriptionEntity entity = inv.getArgument(0);
                    ReflectionTestUtils.setField(entity, "id", SUB_ID);
                    return entity;
                });

        ProSubscriptionEntity result = service().grantByAdmin(USER_ID, ADMIN_ID, REASON);

        assertThat(result.getStatus()).isEqualTo(ProSubscriptionStatus.ACTIVE);
        assertThat(result.getSource()).isEqualTo(ProSubscriptionSource.ADMIN_GRANT);
        assertThat(result.getGrantedByAdminId()).isEqualTo(ADMIN_ID);
        assertThat(result.getAdminGrantReason()).isEqualTo(REASON);
        assertThat(result.getCurrentPeriodEnd())
                .as("un octroi administrateur court jusqu'à révocation, sans échéance")
                .isNull();
        verify(accessSynchronizer).sync(USER_ID, true);
    }

    @Test
    @DisplayName("un octroi purge stripe_subscription_id mais conserve le client Stripe")
    void grantPurgesStripeTraces() {
        ProSubscriptionEntity expired = subscription(ProSubscriptionStatus.EXPIRED,
                ProSubscriptionSource.STRIPE);
        expired.setStripeCustomerId("cus_ancien");
        expired.setStripeSubscriptionId("sub_ancien");
        expired.setBillingCycle(BillingCycle.MONTHLY);
        expired.setCurrentPeriodEnd(Instant.now().minus(5, ChronoUnit.DAYS));
        expired.setPastDueSince(Instant.now().minus(30, ChronoUnit.DAYS));
        expired.setCancelAtPeriodEnd(true);
        expired.setGraceExpiresAt(Instant.now().minus(60, ChronoUnit.DAYS));
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.of(expired));
        when(repository.save(expired)).thenReturn(expired);

        ProSubscriptionEntity result = service().grantByAdmin(USER_ID, ADMIN_ID, REASON);

        // Une seule ligne vivante par utilisateur : uq_pro_subscriptions_user refuserait
        // une seconde insertion, statut fermé compris.
        assertThat(result.getId()).isEqualTo(SUB_ID);
        assertThat(result.getSource()).isEqualTo(ProSubscriptionSource.ADMIN_GRANT);
        // Le client Stripe est réutilisé en cas de réabonnement après annulation : il ne
        // doit pas être perdu au passage par un octroi administrateur.
        assertThat(result.getStripeCustomerId()).isEqualTo("cus_ancien");
        // Un stripe_subscription_id survivant serait retrouvé par findByStripeSubscriptionId
        // au prochain webhook, et cette ligne serait pilotée par un abonnement étranger.
        assertThat(result.getStripeSubscriptionId()).isNull();
        assertThat(result.getBillingCycle()).isNull();
        assertThat(result.getCurrentPeriodEnd()).isNull();
        assertThat(result.getPastDueSince()).isNull();
        assertThat(result.isCancelAtPeriodEnd()).isFalse();
        assertThat(result.getGraceExpiresAt()).isNull();
    }

    @Test
    @DisplayName("un octroi renseigne grantedAt")
    void grantSetsGrantedAt() {
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        when(repository.save(any(ProSubscriptionEntity.class)))
                .thenAnswer(inv -> {
                    ProSubscriptionEntity entity = inv.getArgument(0);
                    ReflectionTestUtils.setField(entity, "id", SUB_ID);
                    return entity;
                });

        Instant before = Instant.now();
        ProSubscriptionEntity result = service().grantByAdmin(USER_ID, ADMIN_ID, REASON);

        assertThat(result.getGrantedAt())
                .isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));
    }

    @Test
    @DisplayName("refuse d'offrir un accès quand un abonnement Stripe est encore vivant")
    void refusesGrantOverLiveStripeSubscription() {
        ProSubscriptionEntity liveStripe = subscription(ProSubscriptionStatus.ACTIVE,
                ProSubscriptionSource.STRIPE);
        liveStripe.setStripeCustomerId("cus_live");
        liveStripe.setStripeSubscriptionId("sub_live");
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.of(liveStripe));

        assertThatThrownBy(() -> service().grantByAdmin(USER_ID, ADMIN_ID, REASON))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(ex -> assertThat(((YadonyBusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));

        // Ni mutation ni trace d'audit : l'octroi est refusé avant tout effet de bord.
        verify(repository, never()).save(any());
        verify(accessSynchronizer, never()).sync(any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(auditService, never()).log(any(), any(), any(), any(), anyMap());
    }

    @Test
    @DisplayName("un octroi reste possible sur un abonnement Stripe déjà fermé")
    void grantAllowedOverClosedStripeSubscription() {
        ProSubscriptionEntity closedStripe = subscription(ProSubscriptionStatus.CANCELED,
                ProSubscriptionSource.STRIPE);
        closedStripe.setStripeCustomerId("cus_closed");
        closedStripe.setStripeSubscriptionId("sub_closed");
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.of(closedStripe));
        when(repository.save(closedStripe)).thenReturn(closedStripe);

        ProSubscriptionEntity result = service().grantByAdmin(USER_ID, ADMIN_ID, REASON);

        assertThat(result.getSource()).isEqualTo(ProSubscriptionSource.ADMIN_GRANT);
        assertThat(result.getStatus()).isEqualTo(ProSubscriptionStatus.ACTIVE);
    }

    @Test
    @DisplayName("l'octroi est journalisé au nom de l'administrateur, sans le motif libre")
    void grantIsAuditedWithoutFreeText() {
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        when(repository.save(any(ProSubscriptionEntity.class)))
                .thenAnswer(inv -> {
                    ProSubscriptionEntity entity = inv.getArgument(0);
                    ReflectionTestUtils.setField(entity, "id", SUB_ID);
                    return entity;
                });

        service().grantByAdmin(USER_ID, ADMIN_ID, REASON);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("BILLING"), any(UUID.class), eq("BILLING_ADMIN_GRANTED"),
                eq(ADMIN_ID), payload.capture());

        // audit_log est immuable : un motif libre saisi par un administrateur y graverait
        // définitivement d'éventuelles données personnelles. Il vit dans la colonne
        // admin_grant_reason, modifiable et soft-deletable.
        assertThat(payload.getValue().values())
                .as("le motif libre ne doit jamais entrer dans audit_log")
                .doesNotContain(REASON);
        assertThat(payload.getValue()).containsKey("targetUserId");
    }

    @Test
    @DisplayName("l'acteur journalisé est l'administrateur, jamais la cible")
    void auditActorIsTheAdmin() {
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        when(repository.save(any(ProSubscriptionEntity.class)))
                .thenAnswer(inv -> {
                    ProSubscriptionEntity entity = inv.getArgument(0);
                    ReflectionTestUtils.setField(entity, "id", SUB_ID);
                    return entity;
                });

        service().grantByAdmin(USER_ID, ADMIN_ID, REASON);

        // Une trace désignant la cible comme acteur ne pourra jamais être corrigée,
        // et l'administrateur responsable resterait introuvable.
        verify(auditService).log(any(), any(), any(), eq(ADMIN_ID), anyMap());
    }
}
