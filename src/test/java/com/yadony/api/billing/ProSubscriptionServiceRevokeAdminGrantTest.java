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

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Révocation d'un accès offert par un administrateur.
 *
 * <p>Doit produire une trace d'audit strictement distincte d'un renoncement par
 * l'utilisateur lui-même : l'acteur journalisé est l'administrateur qui révoque, pas
 * la cible, sur le modèle exact de {@code grantByAdmin}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProSubscriptionService.revokeAdminGrant — révocation d'un accès offert")
class ProSubscriptionServiceRevokeAdminGrantTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ADMIN_ID = UUID.randomUUID();
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
    @DisplayName("révoque un accès offert et journalise l'administrateur comme acteur")
    void revokesAdminGrantAndAuditsTheAdminAsActor() {
        ProSubscriptionEntity granted = subscription(ProSubscriptionStatus.ACTIVE,
                ProSubscriptionSource.ADMIN_GRANT);
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.of(granted));
        when(repository.save(granted)).thenReturn(granted);

        ProSubscriptionEntity result = service().revokeAdminGrant(USER_ID, ADMIN_ID);

        assertThat(result.getStatus()).isEqualTo(ProSubscriptionStatus.CANCELED);
        verify(accessSynchronizer).sync(USER_ID, false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("BILLING"), eq(SUB_ID), eq("BILLING_ADMIN_GRANT_REVOKED"),
                eq(ADMIN_ID), payload.capture());
        assertThat(payload.getValue()).containsEntry("targetUserId", USER_ID.toString());
    }

    @Test
    @DisplayName("404 quand la cible n'a aucun abonnement")
    void throws404WhenNoSubscription() {
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().revokeAdminGrant(USER_ID, ADMIN_ID))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(ex -> assertThat(((YadonyBusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));

        verify(repository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("409 quand l'abonnement n'est pas un octroi administrateur")
    void throws409WhenNotAnAdminGrant() {
        ProSubscriptionEntity stripeSub = subscription(ProSubscriptionStatus.ACTIVE,
                ProSubscriptionSource.STRIPE);
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.of(stripeSub));

        assertThatThrownBy(() -> service().revokeAdminGrant(USER_ID, ADMIN_ID))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(ex -> assertThat(((YadonyBusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));

        verify(repository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("409 quand l'octroi est déjà révoqué — pas de double entrée CANCELED->CANCELED")
    void throws409WhenAlreadyRevoked() {
        ProSubscriptionEntity alreadyCanceled = subscription(ProSubscriptionStatus.CANCELED,
                ProSubscriptionSource.ADMIN_GRANT);
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.of(alreadyCanceled));

        assertThatThrownBy(() -> service().revokeAdminGrant(USER_ID, ADMIN_ID))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(ex -> assertThat(((YadonyBusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));

        verify(repository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }
}
