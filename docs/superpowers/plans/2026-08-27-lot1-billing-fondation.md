# Lot 1 — Fondation billing PRO — Plan d'implémentation

> **Pour les agents :** SOUS-COMPÉTENCE REQUISE — utiliser `superpowers:subagent-driven-development` (recommandé) ou `superpowers:executing-plans` pour exécuter ce plan tâche par tâche. Les étapes utilisent la syntaxe case à cocher (`- [ ]`).

**Objectif :** doter le statut PRO d'un cycle de vie d'abonnement piloté en base, avec effets réels du downgrade, sans aucune dépendance à Stripe.

**Architecture :** une entité `ProSubscriptionEntity` dans un nouveau package `billing/` porte l'état de l'abonnement. `UserEntity.isProAccount` reste le drapeau que tout le code PRO existant lit ; il devient un champ dérivé, synchronisé par `ProAccessSynchronizer` qui publie l'événement `UserProStatusChangedEvent` déjà existant. Les effets métier du downgrade sont portés par des listeners dans leur package d'origine (`automation/`), jamais par `billing/`.

**Stack :** Spring Boot 3.4, Java 21, PostgreSQL 16, Flyway, JUnit 5, Mockito, AssertJ.

## Contraintes globales

- Package racine : `com.yadony.api` (et non `com.dony.api`, renommage effectué).
- Toute entité étend `com.yadony.api.common.BaseEntity` (UUID, `createdAt`, `updatedAt`, `deletedAt`).
- Soft delete uniquement. Les entités récentes utilisent `@SQLRestriction("deleted_at IS NULL")`, pas `@Where` (legacy présent sur `UserEntity`).
- Jamais de suppression physique, jamais de modification d'une migration existante : toujours `V(n+1)`.
- `audit_log` est immuable (trigger PostgreSQL) : jamais d'UPDATE ni de DELETE.
- Cross-package : Spring Application Events uniquement pour la logique métier. L'injection de **repository** d'un autre package est tolérée (précédent : `admin/AdminUserDeletionService` injecte `auth/UserRepository`).
- Erreurs : `YadonyBusinessException` (RFC 7807 via `GlobalExceptionHandler`). Jamais de String ou Map brute.
- Conventions de migration : `pk_<table>`, `fk_<table>_<cible>`, `chk_<table>_<règle>`, `uq_<table>_<...>`, `idx_<table>_<colonnes>` ; `TIMESTAMPTZ` ; enums en `VARCHAR(n)` + CHECK ; `gen_random_uuid()`.
- `@EnableScheduling` est déjà présent sur `YadonyBackApplication` : ne rien ajouter.
- `@ConfigurationPropertiesScan` est déjà actif : un record `@ConfigurationProperties` est détecté automatiquement.
- Tests : Spring Boot 3.4 → utiliser `@MockitoBean`, jamais `@MockBean` (déprécié). Pas de `@InjectMocks` : instanciation manuelle du service. AssertJ. `ReflectionTestUtils.setField(entity, "id", uuid)` pour poser un id hérité de `BaseEntity` (pas de setter). Le `CLAUDE.md` du dépôt mentionne encore `@MockBean` : le code réel fait foi.
- Profil de test : `@ActiveProfiles("test")` sur tout test d'intégration.
- **Transactionalité des listeners de ce lot.** `CLAUDE.md` interdit `@EventListener` seul pour les *listeners de paiement*, au profit de `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Transactional(propagation = REQUIRES_NEW)`. Cette règle ne s'applique pas ici et les listeners du lot utilisent `@EventListener` simple, pour deux raisons : ils ne touchent aucun objet Stripe ni aucun montant, et la suspension des droits doit être **atomique** avec le changement de drapeau — un downgrade commité dont les automatisations resteraient actives parce qu'une transaction séparée a échoué serait précisément la faille que ce lot ferme. C'est aussi le choix du listener déjà en place, `matching/AnnouncementService.onUserProStatusChanged`.
- Un listener exécuté dans la transaction de l'appelant ne doit jamais laisser échapper d'exception : il ferait échouer la transaction appelante.
- En test d'intégration, le principal Spring Security est le **`firebaseUid` (String)**, pas un `UserEntity`.
- Couverture minimale 90 %. Ne jamais lancer deux commandes Maven en parallèle (corrompt `target/classes`).
- Commande de test : `./mvnw test`. Jamais `-DskipTests`.

---

## Structure des fichiers

**Nouveau package `src/main/java/com/yadony/api/billing/` :**

| Fichier | Responsabilité |
|---|---|
| `ProSubscriptionStatus.java` | Enum des états + règle d'accès PRO |
| `ProSubscriptionSource.java` | Enum d'origine du droit |
| `BillingCycle.java` | Enum mensuel / annuel |
| `ProSubscriptionEntity.java` | État de l'abonnement |
| `ProSubscriptionRepository.java` | Accès données + requêtes des tâches planifiées |
| `ProAccessSynchronizer.java` | Point unique de mise à jour de `isProAccount` |
| `ProSubscriptionService.java` | Machine à états et transitions |
| `LegacyProGraceListener.java` | Ouvre une grâce si un PRO apparaît sans abonnement |
| `BillingProperties.java` | Configuration typée |
| `ProSubscriptionScheduler.java` | Les trois tâches planifiées |

**Modifié :**

| Fichier | Changement |
|---|---|
| `src/main/java/com/yadony/api/automation/AutomationRuleEntity.java` | Ajout du champ `disabledByDowngrade` |
| `src/main/java/com/yadony/api/automation/AutomationRuleRepository.java` | Ajout de `findByTravelerIdAndDisabledByDowngradeTrue` |
| `src/main/java/com/yadony/api/automation/AutomationRuleProStatusListener.java` | **Créé** — désactive et réactive les règles |
| `src/main/resources/application.yml` | Bloc `yadony.billing` |

**Migrations :** `V231__pro_subscriptions.sql`, `V232__automation_rules_disabled_by_downgrade.sql`.

> Vérifier avant écriture que `V230__residence_address.sql` est toujours la dernière migration sur `origin/main`. Renuméroter sinon.

---

## Task 1 : Modèle de souscription et migration

**Fichiers :**
- Créer : `src/main/java/com/yadony/api/billing/ProSubscriptionStatus.java`
- Créer : `src/main/java/com/yadony/api/billing/ProSubscriptionSource.java`
- Créer : `src/main/java/com/yadony/api/billing/BillingCycle.java`
- Créer : `src/main/java/com/yadony/api/billing/ProSubscriptionEntity.java`
- Créer : `src/main/java/com/yadony/api/billing/ProSubscriptionRepository.java`
- Créer : `src/main/resources/db/migration/V231__pro_subscriptions.sql`
- Test : `src/test/java/com/yadony/api/billing/ProSubscriptionStatusTest.java`
- Test : `src/test/java/com/yadony/api/billing/ProSubscriptionRepositoryIntegrationTest.java`

**Interfaces :**
- Consomme : `com.yadony.api.common.BaseEntity`
- Produit : `ProSubscriptionStatus.grantsProAccess()` (booléen), `ProSubscriptionEntity` avec accesseurs, `ProSubscriptionRepository.findByUserId(UUID)`, `findByStripeSubscriptionId(String)`, `findByStatusAndGraceExpiresAtBefore(ProSubscriptionStatus, Instant)`, `findByStatusAndPastDueSinceBefore(ProSubscriptionStatus, Instant)`, `findByStatusAndCancelAtPeriodEndTrueAndCurrentPeriodEndBefore(ProSubscriptionStatus, Instant)`

- [ ] **Étape 1 : Écrire le test de la règle d'accès**

`src/test/java/com/yadony/api/billing/ProSubscriptionStatusTest.java` :

```java
package com.yadony.api.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProSubscriptionStatusTest {

    @Test
    @DisplayName("ACTIVE, PAST_DUE et LEGACY_GRACE ouvrent l'accès PRO")
    void grantingStatuses() {
        assertThat(ProSubscriptionStatus.ACTIVE.grantsProAccess()).isTrue();
        assertThat(ProSubscriptionStatus.PAST_DUE.grantsProAccess()).isTrue();
        assertThat(ProSubscriptionStatus.LEGACY_GRACE.grantsProAccess()).isTrue();
    }

    @Test
    @DisplayName("CANCELED et EXPIRED ferment l'accès PRO")
    void revokingStatuses() {
        assertThat(ProSubscriptionStatus.CANCELED.grantsProAccess()).isFalse();
        assertThat(ProSubscriptionStatus.EXPIRED.grantsProAccess()).isFalse();
    }
}
```

- [ ] **Étape 2 : Lancer le test et vérifier qu'il échoue**

Commande : `./mvnw test -Dtest=ProSubscriptionStatusTest`
Attendu : ÉCHEC de compilation, `ProSubscriptionStatus` n'existe pas.

- [ ] **Étape 3 : Créer les trois enums**

`ProSubscriptionStatus.java` :

```java
package com.yadony.api.billing;

/**
 * États d'un abonnement PRO.
 *
 * <p>CANCELED et EXPIRED ferment tous deux l'accès et sont traités
 * identiquement par le gating. Ils restent distincts pour l'analytique :
 * CANCELED = résiliation d'un abonnement payant (churn),
 * EXPIRED = droit non converti arrivé à échéance (grâce ou dunning).
 */
public enum ProSubscriptionStatus {

    ACTIVE,
    PAST_DUE,
    LEGACY_GRACE,
    CANCELED,
    EXPIRED;

    /** Vrai si ce statut doit se traduire par {@code UserEntity.isProAccount == true}. */
    public boolean grantsProAccess() {
        return this == ACTIVE || this == PAST_DUE || this == LEGACY_GRACE;
    }
}
```

`ProSubscriptionSource.java` :

```java
package com.yadony.api.billing;

/**
 * Origine du droit PRO.
 *
 * <p>LEGACY_FREE identifie la cohorte des comptes passés PRO gratuitement
 * avant l'introduction de l'abonnement payant : ni payants, ni offerts par
 * un administrateur. Nécessaire pour suivre leur taux de conversion.
 */
public enum ProSubscriptionSource {
    STRIPE,
    ADMIN_GRANT,
    LEGACY_FREE
}
```

`BillingCycle.java` :

```java
package com.yadony.api.billing;

public enum BillingCycle {
    MONTHLY,
    YEARLY
}
```

- [ ] **Étape 4 : Relancer le test**

Commande : `./mvnw test -Dtest=ProSubscriptionStatusTest`
Attendu : SUCCÈS.

- [ ] **Étape 5 : Créer l'entité**

`ProSubscriptionEntity.java` :

```java
package com.yadony.api.billing;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.util.UUID;

/**
 * Abonnement PRO d'un voyageur. Au plus une ligne vivante par utilisateur
 * (index unique partiel {@code uq_pro_subscriptions_user}).
 *
 * <p>Cette entité est la source de vérité ; {@code UserEntity.isProAccount}
 * en est la projection, maintenue par {@link ProAccessSynchronizer}.
 */
@Entity
@Table(name = "pro_subscriptions")
@SQLRestriction("deleted_at IS NULL")
public class ProSubscriptionEntity extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ProSubscriptionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private ProSubscriptionSource source;

    @Column(name = "stripe_customer_id", length = 255)
    private String stripeCustomerId;

    @Column(name = "stripe_subscription_id", length = 255)
    private String stripeSubscriptionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_cycle", length = 8)
    private BillingCycle billingCycle;

    @Column(name = "current_period_end")
    private Instant currentPeriodEnd;

    @Column(name = "cancel_at_period_end", nullable = false)
    private boolean cancelAtPeriodEnd = false;

    /** Échéance de la grâce accordée à la cohorte LEGACY_FREE. */
    @Column(name = "grace_expires_at")
    private Instant graceExpiresAt;

    /**
     * Horodatage d'entrée en PAST_DUE, remis à {@code null} à la sortie.
     * {@code updatedAt} ne conviendrait pas : toute écriture sur la ligne
     * le repousserait et le dunning ne finirait jamais.
     */
    @Column(name = "past_due_since")
    private Instant pastDueSince;

    @Column(name = "granted_by_admin_id")
    private UUID grantedByAdminId;

    @Column(name = "admin_grant_reason", length = 500)
    private String adminGrantReason;

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public ProSubscriptionStatus getStatus() { return status; }
    public void setStatus(ProSubscriptionStatus status) { this.status = status; }

    public ProSubscriptionSource getSource() { return source; }
    public void setSource(ProSubscriptionSource source) { this.source = source; }

    public String getStripeCustomerId() { return stripeCustomerId; }
    public void setStripeCustomerId(String stripeCustomerId) { this.stripeCustomerId = stripeCustomerId; }

    public String getStripeSubscriptionId() { return stripeSubscriptionId; }
    public void setStripeSubscriptionId(String stripeSubscriptionId) { this.stripeSubscriptionId = stripeSubscriptionId; }

    public BillingCycle getBillingCycle() { return billingCycle; }
    public void setBillingCycle(BillingCycle billingCycle) { this.billingCycle = billingCycle; }

    public Instant getCurrentPeriodEnd() { return currentPeriodEnd; }
    public void setCurrentPeriodEnd(Instant currentPeriodEnd) { this.currentPeriodEnd = currentPeriodEnd; }

    public boolean isCancelAtPeriodEnd() { return cancelAtPeriodEnd; }
    public void setCancelAtPeriodEnd(boolean cancelAtPeriodEnd) { this.cancelAtPeriodEnd = cancelAtPeriodEnd; }

    public Instant getGraceExpiresAt() { return graceExpiresAt; }
    public void setGraceExpiresAt(Instant graceExpiresAt) { this.graceExpiresAt = graceExpiresAt; }

    public Instant getPastDueSince() { return pastDueSince; }
    public void setPastDueSince(Instant pastDueSince) { this.pastDueSince = pastDueSince; }

    public UUID getGrantedByAdminId() { return grantedByAdminId; }
    public void setGrantedByAdminId(UUID grantedByAdminId) { this.grantedByAdminId = grantedByAdminId; }

    public String getAdminGrantReason() { return adminGrantReason; }
    public void setAdminGrantReason(String adminGrantReason) { this.adminGrantReason = adminGrantReason; }
}
```

- [ ] **Étape 6 : Créer le repository**

`ProSubscriptionRepository.java` :

```java
package com.yadony.api.billing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProSubscriptionRepository extends JpaRepository<ProSubscriptionEntity, UUID> {

    Optional<ProSubscriptionEntity> findByUserId(UUID userId);

    Optional<ProSubscriptionEntity> findByStripeSubscriptionId(String stripeSubscriptionId);

    /** Grâces historiques arrivées à échéance. */
    List<ProSubscriptionEntity> findByStatusAndGraceExpiresAtBefore(
            ProSubscriptionStatus status, Instant threshold);

    /** Impayés dont le dunning est épuisé. */
    List<ProSubscriptionEntity> findByStatusAndPastDueSinceBefore(
            ProSubscriptionStatus status, Instant threshold);

    /** Résiliations dont la période payée est écoulée. */
    List<ProSubscriptionEntity> findByStatusAndCancelAtPeriodEndTrueAndCurrentPeriodEndBefore(
            ProSubscriptionStatus status, Instant threshold);
}
```

- [ ] **Étape 7 : Écrire la migration**

`src/main/resources/db/migration/V231__pro_subscriptions.sql` :

```sql
-- V231__pro_subscriptions.sql
-- Abonnement PRO : transforme le statut auto-déclaratif gratuit en abonnement
-- payant. users.is_pro_account n'est pas supprimé — il reste le drapeau lu par
-- tout le code PRO existant (matching, export fiscal, automatisations, quotas)
-- et devient une projection de cette table, maintenue par ProAccessSynchronizer.
CREATE TABLE pro_subscriptions (
    id                     UUID         NOT NULL DEFAULT gen_random_uuid(),
    user_id                UUID         NOT NULL,
    status                 VARCHAR(16)  NOT NULL,
    source                 VARCHAR(16)  NOT NULL,
    stripe_customer_id     VARCHAR(255),
    stripe_subscription_id VARCHAR(255),
    billing_cycle          VARCHAR(8),
    current_period_end     TIMESTAMPTZ,
    cancel_at_period_end   BOOLEAN      NOT NULL DEFAULT FALSE,
    grace_expires_at       TIMESTAMPTZ,
    past_due_since         TIMESTAMPTZ,
    granted_by_admin_id    UUID,
    admin_grant_reason     VARCHAR(500),
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ,
    deleted_at             TIMESTAMPTZ,
    CONSTRAINT pk_pro_subscriptions PRIMARY KEY (id),
    CONSTRAINT fk_pro_subscriptions_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT chk_pro_subscriptions_status
        CHECK (status IN ('ACTIVE', 'PAST_DUE', 'LEGACY_GRACE', 'CANCELED', 'EXPIRED')),
    CONSTRAINT chk_pro_subscriptions_source
        CHECK (source IN ('STRIPE', 'ADMIN_GRANT', 'LEGACY_FREE')),
    CONSTRAINT chk_pro_subscriptions_cycle
        CHECK (billing_cycle IS NULL OR billing_cycle IN ('MONTHLY', 'YEARLY'))
);

-- Un utilisateur n'a qu'un abonnement vivant à la fois. Index partiel car le
-- soft delete laisse les lignes en place.
CREATE UNIQUE INDEX uq_pro_subscriptions_user
    ON pro_subscriptions (user_id)
    WHERE deleted_at IS NULL;

-- Servent les trois tâches planifiées de ProSubscriptionScheduler.
CREATE INDEX idx_pro_subscriptions_status_grace
    ON pro_subscriptions (status, grace_expires_at);
CREATE INDEX idx_pro_subscriptions_status_past_due
    ON pro_subscriptions (status, past_due_since);
CREATE INDEX idx_pro_subscriptions_status_period_end
    ON pro_subscriptions (status, current_period_end);

-- Recherche par identifiant Stripe lors du traitement des webhooks (lot 2).
CREATE INDEX idx_pro_subscriptions_stripe_subscription
    ON pro_subscriptions (stripe_subscription_id);

COMMENT ON COLUMN pro_subscriptions.past_due_since IS
    'Entrée en PAST_DUE, remis à NULL à la sortie. updated_at ne conviendrait '
    'pas : toute écriture sur la ligne le repousserait et le dunning ne '
    'finirait jamais.';

-- Backfill : les comptes déjà PRO au moment du déploiement reçoivent 60 jours
-- de grâce. ATTENTION — les tâches planifiées qui downgradent à l'échéance sont
-- désactivées par défaut (yadony.billing.scheduler-enabled=false) et ne doivent
-- être activées qu'une fois le lot 2 déployé : sans lui, aucun de ces comptes
-- n'a de moyen de payer.
INSERT INTO pro_subscriptions (user_id, status, source, grace_expires_at)
SELECT id, 'LEGACY_GRACE', 'LEGACY_FREE', NOW() + INTERVAL '60 days'
FROM users
WHERE is_pro_account = TRUE
  AND deleted_at IS NULL;
```

- [ ] **Étape 8 : Écrire le test d'intégration du repository**

`src/test/java/com/yadony/api/billing/ProSubscriptionRepositoryIntegrationTest.java` :

```java
package com.yadony.api.billing;

import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.kyc.KycStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("ProSubscriptionRepository — requêtes des tâches planifiées")
class ProSubscriptionRepositoryIntegrationTest {

    @Autowired ProSubscriptionRepository repository;
    @Autowired UserRepository userRepository;

    private UUID userId;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        userRepository.deleteAll();

        UserEntity user = new UserEntity();
        user.setFirebaseUid("uid-billing-repo-001");
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(Set.of(Role.TRAVELER));
        user.setCountry("FR");
        userId = userRepository.save(user).getId();
    }

    private ProSubscriptionEntity persist(ProSubscriptionStatus status,
                                          ProSubscriptionSource source) {
        ProSubscriptionEntity sub = new ProSubscriptionEntity();
        sub.setUserId(userId);
        sub.setStatus(status);
        sub.setSource(source);
        return repository.save(sub);
    }

    @Test
    @DisplayName("une grâce échue est retournée, une grâce en cours ne l'est pas")
    void findsExpiredLegacyGraceOnly() {
        ProSubscriptionEntity expired = persist(ProSubscriptionStatus.LEGACY_GRACE,
                ProSubscriptionSource.LEGACY_FREE);
        expired.setGraceExpiresAt(Instant.now().minus(1, ChronoUnit.DAYS));
        repository.save(expired);

        var found = repository.findByStatusAndGraceExpiresAtBefore(
                ProSubscriptionStatus.LEGACY_GRACE, Instant.now());
        assertThat(found).extracting(ProSubscriptionEntity::getId).containsExactly(expired.getId());

        expired.setGraceExpiresAt(Instant.now().plus(10, ChronoUnit.DAYS));
        repository.save(expired);

        assertThat(repository.findByStatusAndGraceExpiresAtBefore(
                ProSubscriptionStatus.LEGACY_GRACE, Instant.now())).isEmpty();
    }

    @Test
    @DisplayName("un impayé au-delà du seuil de dunning est retourné")
    void findsExhaustedDunning() {
        ProSubscriptionEntity pastDue = persist(ProSubscriptionStatus.PAST_DUE,
                ProSubscriptionSource.STRIPE);
        pastDue.setPastDueSince(Instant.now().minus(6, ChronoUnit.DAYS));
        repository.save(pastDue);

        var found = repository.findByStatusAndPastDueSinceBefore(
                ProSubscriptionStatus.PAST_DUE, Instant.now().minus(5, ChronoUnit.DAYS));
        assertThat(found).hasSize(1);
    }

    @Test
    @DisplayName("une résiliation dont la période est écoulée est retournée")
    void findsEndedCancellation() {
        ProSubscriptionEntity active = persist(ProSubscriptionStatus.ACTIVE,
                ProSubscriptionSource.STRIPE);
        active.setCancelAtPeriodEnd(true);
        active.setCurrentPeriodEnd(Instant.now().minus(1, ChronoUnit.HOURS));
        repository.save(active);

        var found = repository.findByStatusAndCancelAtPeriodEndTrueAndCurrentPeriodEndBefore(
                ProSubscriptionStatus.ACTIVE, Instant.now());
        assertThat(found).hasSize(1);
    }

    @Test
    @DisplayName("findByUserId retrouve l'abonnement du voyageur")
    void findsByUserId() {
        persist(ProSubscriptionStatus.ACTIVE, ProSubscriptionSource.STRIPE);
        assertThat(repository.findByUserId(userId)).isPresent();
        assertThat(repository.findByUserId(UUID.randomUUID())).isEmpty();
    }
}
```

- [ ] **Étape 9 : Lancer les tests**

Commande : `./mvnw test -Dtest='ProSubscriptionStatusTest,ProSubscriptionRepositoryIntegrationTest'`
Attendu : SUCCÈS. Si Flyway refuse de démarrer parce qu'une migration a été sautée en dev, exporter `SPRING_FLYWAY_OUT_OF_ORDER=true`.

- [ ] **Étape 10 : Commit**

```bash
git add src/main/java/com/yadony/api/billing src/main/resources/db/migration/V231__pro_subscriptions.sql src/test/java/com/yadony/api/billing
git commit -m "feat(billing): modèle de souscription PRO et migration V231"
```

---

## Task 2 : Synchronisation du drapeau `isProAccount`

**Fichiers :**
- Créer : `src/main/java/com/yadony/api/billing/ProAccessSynchronizer.java`
- Test : `src/test/java/com/yadony/api/billing/ProAccessSynchronizerTest.java`

**Interfaces :**
- Consomme : `auth/UserRepository`, `auth/UserEntity.isProAccount()` / `setProAccount(boolean)` (attention au nom : `setProAccount`, pas `setIsProAccount`), `auth/UserProStatusChangedEvent` (record `(UUID userId, boolean isPro)`)
- Produit : `ProAccessSynchronizer.sync(UUID userId, boolean shouldHaveAccess)` → `void`

- [ ] **Étape 1 : Écrire le test**

`src/test/java/com/yadony/api/billing/ProAccessSynchronizerTest.java` :

```java
package com.yadony.api.billing;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserProStatusChangedEvent;
import com.yadony.api.auth.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProAccessSynchronizerTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock UserRepository userRepository;
    @Mock ApplicationEventPublisher eventPublisher;

    private ProAccessSynchronizer synchronizer() {
        return new ProAccessSynchronizer(userRepository, eventPublisher);
    }

    private UserEntity user(boolean pro) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", USER_ID);
        u.setProAccount(pro);
        return u;
    }

    @Test
    @DisplayName("ouvrir l'accès pose le drapeau et publie l'événement")
    void grantsAccess() {
        UserEntity u = user(false);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));

        synchronizer().sync(USER_ID, true);

        assertThat(u.isProAccount()).isTrue();
        verify(userRepository).save(u);

        ArgumentCaptor<UserProStatusChangedEvent> captor =
                ArgumentCaptor.forClass(UserProStatusChangedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(USER_ID);
        assertThat(captor.getValue().isPro()).isTrue();
    }

    @Test
    @DisplayName("fermer l'accès retire le drapeau et publie l'événement")
    void revokesAccess() {
        UserEntity u = user(true);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));

        synchronizer().sync(USER_ID, false);

        assertThat(u.isProAccount()).isFalse();
        verify(eventPublisher).publishEvent(any(UserProStatusChangedEvent.class));
    }

    @Test
    @DisplayName("aucun événement si le drapeau est déjà dans l'état voulu")
    void noEventWhenAlreadyInTargetState() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(true)));

        synchronizer().sync(USER_ID, true);

        verify(userRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(UserProStatusChangedEvent.class));
    }

    @Test
    @DisplayName("utilisateur inconnu : aucune exception, aucun événement")
    void unknownUserIsIgnored() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        synchronizer().sync(USER_ID, true);

        verify(eventPublisher, never()).publishEvent(any(UserProStatusChangedEvent.class));
    }
}
```

- [ ] **Étape 2 : Lancer le test et vérifier qu'il échoue**

Commande : `./mvnw test -Dtest=ProAccessSynchronizerTest`
Attendu : ÉCHEC de compilation, `ProAccessSynchronizer` n'existe pas.

- [ ] **Étape 3 : Écrire l'implémentation**

`src/main/java/com/yadony/api/billing/ProAccessSynchronizer.java` :

```java
package com.yadony.api.billing;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserProStatusChangedEvent;
import com.yadony.api.auth.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Point unique de synchronisation entre l'état d'abonnement et
 * {@code UserEntity.isProAccount}, drapeau que tout le code PRO existant lit
 * (matching, export fiscal, automatisations, quotas de brouillons).
 *
 * <p>Aucun autre composant de {@code billing/} ne doit écrire ce drapeau.
 */
@Component
public class ProAccessSynchronizer {

    private static final Logger log = LoggerFactory.getLogger(ProAccessSynchronizer.class);

    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    public ProAccessSynchronizer(UserRepository userRepository,
                                 ApplicationEventPublisher eventPublisher) {
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Aligne le drapeau PRO de l'utilisateur sur {@code shouldHaveAccess}.
     *
     * <p>L'événement n'est publié que si le drapeau change réellement : les
     * listeners qu'il déclenche (annonces, automatisations) sont coûteux et
     * ne doivent pas tourner pour une transition sans effet — par exemple un
     * PAST_DUE qui revient ACTIVE, où l'accès n'a jamais été interrompu.
     */
    public void sync(UUID userId, boolean shouldHaveAccess) {
        UserEntity user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            log.warn("Cannot sync PRO access for unknown user {}", userId);
            return;
        }
        if (user.isProAccount() == shouldHaveAccess) {
            return;
        }
        user.setProAccount(shouldHaveAccess);
        userRepository.save(user);
        eventPublisher.publishEvent(new UserProStatusChangedEvent(userId, shouldHaveAccess));
        log.info("PRO access for user {} set to {}", userId, shouldHaveAccess);
    }
}
```

- [ ] **Étape 4 : Relancer le test**

Commande : `./mvnw test -Dtest=ProAccessSynchronizerTest`
Attendu : SUCCÈS, 4 tests.

- [ ] **Étape 5 : Commit**

```bash
git add src/main/java/com/yadony/api/billing/ProAccessSynchronizer.java src/test/java/com/yadony/api/billing/ProAccessSynchronizerTest.java
git commit -m "feat(billing): synchronise isProAccount depuis l'état d'abonnement"
```

---

## Task 3 : Machine à états de l'abonnement

**Fichiers :**
- Créer : `src/main/java/com/yadony/api/billing/ProSubscriptionService.java`
- Test : `src/test/java/com/yadony/api/billing/ProSubscriptionServiceTest.java`

**Interfaces :**
- Consomme : `ProSubscriptionRepository`, `ProAccessSynchronizer.sync(UUID, boolean)`, `common/AuditService.log(String entityType, UUID entityId, String action, UUID actorId, Map<String,Object> payload)`
- Produit :
  - `openLegacyGrace(UUID userId, int graceDays)` → `ProSubscriptionEntity`
  - `markPastDue(ProSubscriptionEntity sub)` → `ProSubscriptionEntity`
  - `clearPastDue(ProSubscriptionEntity sub)` → `ProSubscriptionEntity`
  - `expire(ProSubscriptionEntity sub)` → `ProSubscriptionEntity`
  - `cancel(ProSubscriptionEntity sub)` → `ProSubscriptionEntity`

- [ ] **Étape 1 : Écrire le test**

`src/test/java/com/yadony/api/billing/ProSubscriptionServiceTest.java` :

```java
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
    @DisplayName("clearPastDue efface l'horodatage et rétablit ACTIVE")
    void clearsPastDue() {
        ProSubscriptionEntity sub = subscription(ProSubscriptionStatus.PAST_DUE,
                ProSubscriptionSource.STRIPE);
        sub.setPastDueSince(Instant.now().minus(2, ChronoUnit.DAYS));
        when(repository.save(sub)).thenReturn(sub);

        ProSubscriptionEntity result = service().clearPastDue(sub);

        assertThat(result.getStatus()).isEqualTo(ProSubscriptionStatus.ACTIVE);
        assertThat(result.getPastDueSince()).isNull();
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
```

- [ ] **Étape 2 : Lancer le test et vérifier qu'il échoue**

Commande : `./mvnw test -Dtest=ProSubscriptionServiceTest`
Attendu : ÉCHEC de compilation, `ProSubscriptionService` n'existe pas.

- [ ] **Étape 3 : Écrire l'implémentation**

`src/main/java/com/yadony/api/billing/ProSubscriptionService.java` :

```java
package com.yadony.api.billing;

import com.yadony.api.common.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/**
 * Machine à états de l'abonnement PRO.
 *
 * <p>Toute transition passe par ce service : il met à jour la ligne, aligne
 * l'accès via {@link ProAccessSynchronizer} et journalise les transitions
 * fermantes dans {@code audit_log}.
 */
@Service
public class ProSubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(ProSubscriptionService.class);

    private static final String AUDIT_ENTITY_TYPE = "BILLING";

    private final ProSubscriptionRepository repository;
    private final ProAccessSynchronizer accessSynchronizer;
    private final AuditService auditService;

    public ProSubscriptionService(ProSubscriptionRepository repository,
                                  ProAccessSynchronizer accessSynchronizer,
                                  AuditService auditService) {
        this.repository = repository;
        this.accessSynchronizer = accessSynchronizer;
        this.auditService = auditService;
    }

    /**
     * Ouvre une grâce pour un compte PRO gratuit historique.
     * Utilisé par le backfill de la migration V231 et par
     * {@link LegacyProGraceListener} pour les upgrades gratuits résiduels.
     *
     * <p>Réutilise la ligne existante si l'utilisateur en a déjà une :
     * l'index {@code uq_pro_subscriptions_user} n'autorise qu'un abonnement
     * vivant par utilisateur, statut fermé compris. Un utilisateur dont
     * l'abonnement est EXPIRED conserve donc sa ligne, qui est recyclée.
     */
    @Transactional
    public ProSubscriptionEntity openLegacyGrace(UUID userId, int graceDays) {
        ProSubscriptionEntity sub = repository.findByUserId(userId)
                .orElseGet(ProSubscriptionEntity::new);
        sub.setUserId(userId);
        sub.setStatus(ProSubscriptionStatus.LEGACY_GRACE);
        sub.setSource(ProSubscriptionSource.LEGACY_FREE);
        sub.setGraceExpiresAt(Instant.now().plus(graceDays, ChronoUnit.DAYS));
        // Nettoyage des résidus d'un cycle précédent : sans cela, un
        // past_due_since périmé ferait expirer la grâce dès le premier passage
        // du cron de dunning.
        sub.setPastDueSince(null);
        sub.setCancelAtPeriodEnd(false);
        ProSubscriptionEntity saved = repository.save(sub);
        accessSynchronizer.sync(userId, true);
        log.info("Legacy PRO grace opened for user {} until {}", userId, saved.getGraceExpiresAt());
        return saved;
    }

    /**
     * Entrée en impayé. L'accès reste ouvert : Stripe relance la carte
     * pendant plusieurs jours et couper immédiatement pénaliserait un
     * incident bancaire passager.
     */
    @Transactional
    public ProSubscriptionEntity markPastDue(ProSubscriptionEntity sub) {
        sub.setStatus(ProSubscriptionStatus.PAST_DUE);
        sub.setPastDueSince(Instant.now());
        ProSubscriptionEntity saved = repository.save(sub);
        accessSynchronizer.sync(sub.getUserId(), true);
        log.info("Subscription {} marked PAST_DUE", sub.getId());
        return saved;
    }

    /** Paiement finalement encaissé : retour à ACTIVE. */
    @Transactional
    public ProSubscriptionEntity clearPastDue(ProSubscriptionEntity sub) {
        sub.setStatus(ProSubscriptionStatus.ACTIVE);
        sub.setPastDueSince(null);
        ProSubscriptionEntity saved = repository.save(sub);
        accessSynchronizer.sync(sub.getUserId(), true);
        log.info("Subscription {} recovered to ACTIVE", sub.getId());
        return saved;
    }

    /** Droit non converti arrivé à échéance : grâce écoulée ou dunning épuisé. */
    @Transactional
    public ProSubscriptionEntity expire(ProSubscriptionEntity sub) {
        return close(sub, ProSubscriptionStatus.EXPIRED, "BILLING_SUBSCRIPTION_EXPIRED");
    }

    /** Résiliation d'un abonnement, ou révocation d'un octroi administrateur. */
    @Transactional
    public ProSubscriptionEntity cancel(ProSubscriptionEntity sub) {
        return close(sub, ProSubscriptionStatus.CANCELED, "BILLING_SUBSCRIPTION_CANCELED");
    }

    private ProSubscriptionEntity close(ProSubscriptionEntity sub,
                                        ProSubscriptionStatus target,
                                        String auditAction) {
        String previousStatus = sub.getStatus().name();
        sub.setStatus(target);
        sub.setPastDueSince(null);
        ProSubscriptionEntity saved = repository.save(sub);

        accessSynchronizer.sync(sub.getUserId(), false);
        auditService.log(AUDIT_ENTITY_TYPE, sub.getId(), auditAction, sub.getUserId(),
                Map.of("previousStatus", previousStatus, "source", sub.getSource().name()));

        log.info("Subscription {} closed: {} -> {}", sub.getId(), previousStatus, target);
        return saved;
    }
}
```

- [ ] **Étape 4 : Relancer le test**

Commande : `./mvnw test -Dtest=ProSubscriptionServiceTest`
Attendu : SUCCÈS, 7 tests.

- [ ] **Étape 5 : Commit**

```bash
git add src/main/java/com/yadony/api/billing/ProSubscriptionService.java src/test/java/com/yadony/api/billing/ProSubscriptionServiceTest.java
git commit -m "feat(billing): machine à états de l'abonnement PRO"
```

---

## Task 4 : Fermeture de la faille des upgrades gratuits résiduels

**Contexte.** Entre le déploiement du lot 1 et celui du lot 2, `POST /auth/me/upgrade-to-pro` accorde toujours le statut PRO gratuitement. Un utilisateur qui l'appelle après le backfill obtiendrait `isProAccount = true` **sans ligne dans `pro_subscriptions`** : aucune tâche planifiée ne le verrait jamais, et il resterait PRO gratuit à vie.

Ce listener ferme la faille : dès qu'un utilisateur devient PRO sans abonnement, une grâce lui est ouverte automatiquement.

**Fichiers :**
- Créer : `src/main/java/com/yadony/api/billing/LegacyProGraceListener.java`
- Test : `src/test/java/com/yadony/api/billing/LegacyProGraceListenerTest.java`

**Fichiers (suite) :**
- Créer : `src/main/java/com/yadony/api/billing/BillingProperties.java`

**Interfaces :**
- Consomme : `auth/UserProStatusChangedEvent`, `ProSubscriptionRepository.findByUserId(UUID)`, `ProSubscriptionService.openLegacyGrace(UUID, int)`
- Produit : `BillingProperties.schedulerEnabledOrDefault()`, `legacyGraceDaysOrDefault()`, `dunningGraceDaysOrDefault()` — également consommé par la Task 6

- [ ] **Étape 1 : Créer la configuration typée**

`BillingProperties` est créé ici parce que ce listener en est le premier consommateur. La Task 6 le réutilise.

`src/main/java/com/yadony/api/billing/BillingProperties.java` :

```java
package com.yadony.api.billing;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration de l'abonnement PRO.
 *
 * <p>Détecté automatiquement : {@code @ConfigurationPropertiesScan} est actif
 * sur {@code YadonyBackApplication}.
 */
@ConfigurationProperties(prefix = "yadony.billing")
public record BillingProperties(
        Boolean schedulerEnabled,
        Integer legacyGraceDays,
        Integer dunningGraceDays
) {

    /**
     * Interrupteur général des tâches planifiées de downgrade.
     *
     * <p>Faux par défaut, volontairement : déployer le lot 1 sans le lot 2
     * lancerait un compte à rebours d'expiration sur des comptes qui n'ont
     * encore aucun moyen de payer. À passer à vrai seulement une fois le
     * parcours de paiement vérifié en production.
     */
    public boolean schedulerEnabledOrDefault() {
        return schedulerEnabled != null && schedulerEnabled;
    }

    public int legacyGraceDaysOrDefault() {
        return legacyGraceDays != null ? legacyGraceDays : 60;
    }

    public int dunningGraceDaysOrDefault() {
        return dunningGraceDays != null ? dunningGraceDays : 5;
    }
}
```

- [ ] **Étape 2 : Écrire le test**

`src/test/java/com/yadony/api/billing/LegacyProGraceListenerTest.java` :

```java
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
        BillingProperties props = new BillingProperties(false, 60, 5);
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
    @DisplayName("une perte d'accès ne déclenche aucune grâce")
    void ignoresDowngrade() {
        listener().onUserProStatusChanged(new UserProStatusChangedEvent(USER_ID, false));

        verify(subscriptionService, never()).openLegacyGrace(any(), anyInt());
        verify(repository, never()).findByUserId(any());
    }
}
```

- [ ] **Étape 3 : Lancer le test et vérifier qu'il échoue**

Commande : `./mvnw test -Dtest=LegacyProGraceListenerTest`
Attendu : ÉCHEC de compilation, `LegacyProGraceListener` n'existe pas.

- [ ] **Étape 4 : Écrire l'implémentation**

`src/main/java/com/yadony/api/billing/LegacyProGraceListener.java` :

```java
package com.yadony.api.billing;

import com.yadony.api.auth.UserProStatusChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Garantit qu'aucun compte PRO n'existe sans ligne dans {@code pro_subscriptions}.
 *
 * <p>Tant que le lot 2 n'est pas déployé, {@code POST /auth/me/upgrade-to-pro}
 * accorde encore le statut PRO gratuitement. Sans ce listener, un tel compte
 * n'aurait aucun abonnement, échapperait aux tâches planifiées et resterait
 * PRO gratuit indéfiniment.
 *
 * <p>Aucun risque de boucle avec {@link ProAccessSynchronizer} : quand
 * celui-ci publie l'événement, la ligne d'abonnement existe déjà et le
 * listener ne fait rien.
 */
@Component
public class LegacyProGraceListener {

    private static final Logger log = LoggerFactory.getLogger(LegacyProGraceListener.class);

    private final ProSubscriptionRepository repository;
    private final ProSubscriptionService subscriptionService;
    private final BillingProperties properties;

    public LegacyProGraceListener(ProSubscriptionRepository repository,
                                  ProSubscriptionService subscriptionService,
                                  BillingProperties properties) {
        this.repository = repository;
        this.subscriptionService = subscriptionService;
        this.properties = properties;
    }

    @EventListener
    @Transactional
    public void onUserProStatusChanged(UserProStatusChangedEvent event) {
        if (!event.isPro()) {
            return;
        }
        // Tester la présence de la ligne ne suffit pas : elle est recyclée et
        // survit à un EXPIRED. Un utilisateur dont la grâce s'est éteinte et qui
        // repasserait par l'upgrade gratuit garderait sinon isProAccount = true
        // avec un abonnement fermé, hors de portée des tâches planifiées.
        boolean alreadyCovered = repository.findByUserId(event.userId())
                .map(sub -> sub.getStatus().grantsProAccess())
                .orElse(false);
        if (alreadyCovered) {
            return;
        }
        subscriptionService.openLegacyGrace(event.userId(), properties.legacyGraceDaysOrDefault());
        log.info("Orphan PRO user {} given a legacy grace period", event.userId());
    }
}
```

- [ ] **Étape 5 : Relancer le test**

Commande : `./mvnw test -Dtest=LegacyProGraceListenerTest`
Attendu : SUCCÈS, 4 tests.

- [ ] **Étape 6 : Commit**

```bash
git add src/main/java/com/yadony/api/billing/LegacyProGraceListener.java src/main/java/com/yadony/api/billing/BillingProperties.java src/test/java/com/yadony/api/billing/LegacyProGraceListenerTest.java
git commit -m "feat(billing): ouvre une grâce pour tout compte PRO sans abonnement"
```

---

## Task 5 : Effets du downgrade sur les automatisations

**Contexte.** Les règles d'automatisation s'exécutent côté serveur (`AutomationBidListener`, `CapacityWatchScheduler`). Sans désactivation explicite, un voyageur ayant perdu l'accès PRO continuerait d'en bénéficier : PRO gratuit de fait.

Les règles sont désactivées sans être supprimées, et **marquées** afin qu'un réabonnement ne réactive que celles-là, jamais celles que le voyageur avait lui-même désactivées.

**Fichiers :**
- Modifier : `src/main/java/com/yadony/api/automation/AutomationRuleEntity.java`
- Modifier : `src/main/java/com/yadony/api/automation/AutomationRuleRepository.java`
- Créer : `src/main/java/com/yadony/api/automation/AutomationRuleProStatusListener.java`
- Créer : `src/main/resources/db/migration/V232__automation_rules_disabled_by_downgrade.sql`
- Test : `src/test/java/com/yadony/api/automation/AutomationRuleProStatusListenerTest.java`

**Interfaces :**
- Consomme : `auth/UserProStatusChangedEvent`, `AutomationRuleRepository.findByTravelerIdOrderByCreatedAtAsc(UUID)`
- Produit : `AutomationRuleEntity.isDisabledByDowngrade()` / `setDisabledByDowngrade(boolean)`, `AutomationRuleRepository.findByTravelerIdAndDisabledByDowngradeTrue(UUID)`

- [ ] **Étape 1 : Écrire la migration**

`src/main/resources/db/migration/V232__automation_rules_disabled_by_downgrade.sql` :

```sql
-- V232__automation_rules_disabled_by_downgrade.sql
-- Distingue une règle désactivée par la perte du statut PRO d'une règle que le
-- voyageur a lui-même désactivée. Sans ce marqueur, un réabonnement
-- réactiverait en masse des règles volontairement éteintes.
ALTER TABLE automation_rules
    ADD COLUMN disabled_by_downgrade BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_automation_rules_disabled_by_downgrade
    ON automation_rules (traveler_id)
    WHERE disabled_by_downgrade = TRUE;
```

- [ ] **Étape 2 : Ajouter le champ à l'entité**

Dans `src/main/java/com/yadony/api/automation/AutomationRuleEntity.java`, après le champ `enabled` :

```java
    /**
     * Vrai si cette règle a été éteinte par la perte du statut PRO, et non par
     * le voyageur. Seules ces règles sont rallumées au réabonnement.
     */
    @Column(name = "disabled_by_downgrade", nullable = false)
    private boolean disabledByDowngrade = false;
```

Et les accesseurs, auprès de ceux de `enabled` :

```java
    public boolean isDisabledByDowngrade() { return disabledByDowngrade; }
    public void setDisabledByDowngrade(boolean disabledByDowngrade) { this.disabledByDowngrade = disabledByDowngrade; }
```

- [ ] **Étape 3 : Ajouter la requête au repository**

Dans `src/main/java/com/yadony/api/automation/AutomationRuleRepository.java`, ajouter :

```java
    List<AutomationRuleEntity> findByTravelerIdAndDisabledByDowngradeTrue(UUID travelerId);
```

- [ ] **Étape 4 : Écrire le test**

`src/test/java/com/yadony/api/automation/AutomationRuleProStatusListenerTest.java` :

```java
package com.yadony.api.automation;

import com.yadony.api.auth.UserProStatusChangedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutomationRuleProStatusListenerTest {

    private static final UUID TRAVELER_ID = UUID.randomUUID();

    @Mock AutomationRuleRepository ruleRepository;

    private AutomationRuleProStatusListener listener() {
        return new AutomationRuleProStatusListener(ruleRepository);
    }

    private AutomationRuleEntity rule(boolean enabled, boolean disabledByDowngrade) {
        AutomationRuleEntity r = new AutomationRuleEntity();
        r.setTravelerId(TRAVELER_ID);
        r.setEnabled(enabled);
        r.setDisabledByDowngrade(disabledByDowngrade);
        return r;
    }

    @Test
    @DisplayName("le downgrade éteint les règles actives et les marque")
    void downgradeDisablesActiveRules() {
        AutomationRuleEntity active = rule(true, false);
        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(TRAVELER_ID))
                .thenReturn(List.of(active));

        listener().onUserProStatusChanged(new UserProStatusChangedEvent(TRAVELER_ID, false));

        assertThat(active.isEnabled()).isFalse();
        assertThat(active.isDisabledByDowngrade()).isTrue();
    }

    @Test
    @DisplayName("le downgrade ne marque pas une règle déjà éteinte par le voyageur")
    void downgradeLeavesUserDisabledRulesUnmarked() {
        AutomationRuleEntity userDisabled = rule(false, false);
        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(TRAVELER_ID))
                .thenReturn(List.of(userDisabled));

        listener().onUserProStatusChanged(new UserProStatusChangedEvent(TRAVELER_ID, false));

        assertThat(userDisabled.isEnabled()).isFalse();
        assertThat(userDisabled.isDisabledByDowngrade())
                .as("une règle éteinte volontairement ne doit pas être rallumée au réabonnement")
                .isFalse();
    }

    @Test
    @DisplayName("le réabonnement rallume uniquement les règles marquées")
    void upgradeRestoresOnlyMarkedRules() {
        AutomationRuleEntity marked = rule(false, true);
        when(ruleRepository.findByTravelerIdAndDisabledByDowngradeTrue(TRAVELER_ID))
                .thenReturn(List.of(marked));

        listener().onUserProStatusChanged(new UserProStatusChangedEvent(TRAVELER_ID, true));

        assertThat(marked.isEnabled()).isTrue();
        assertThat(marked.isDisabledByDowngrade()).isFalse();
    }
}
```

- [ ] **Étape 5 : Lancer le test et vérifier qu'il échoue**

Commande : `./mvnw test -Dtest=AutomationRuleProStatusListenerTest`
Attendu : ÉCHEC de compilation, `AutomationRuleProStatusListener` n'existe pas.

- [ ] **Étape 6 : Écrire le listener**

`src/main/java/com/yadony/api/automation/AutomationRuleProStatusListener.java` :

```java
package com.yadony.api.automation;

import com.yadony.api.auth.UserProStatusChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Aligne les règles d'automatisation sur le statut PRO du voyageur.
 *
 * <p>Les règles s'exécutent côté serveur et ne s'arrêteraient pas d'elles-mêmes :
 * sans cette désactivation, un voyageur résilié continuerait de bénéficier du
 * moteur d'automatisations.
 *
 * <p>Les règles ne sont jamais supprimées, conformément à la règle du projet
 * interdisant les suppressions physiques, et parce qu'un réabonnement doit
 * restaurer une configuration immédiatement opérationnelle.
 */
@Component
public class AutomationRuleProStatusListener {

    private static final Logger log = LoggerFactory.getLogger(AutomationRuleProStatusListener.class);

    private final AutomationRuleRepository ruleRepository;

    public AutomationRuleProStatusListener(AutomationRuleRepository ruleRepository) {
        this.ruleRepository = ruleRepository;
    }

    @EventListener
    @Transactional
    public void onUserProStatusChanged(UserProStatusChangedEvent event) {
        if (event.isPro()) {
            restore(event.userId());
        } else {
            suspend(event.userId());
        }
    }

    private void suspend(java.util.UUID travelerId) {
        List<AutomationRuleEntity> rules = ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId);
        int suspended = 0;
        for (AutomationRuleEntity rule : rules) {
            if (rule.isEnabled()) {
                rule.setEnabled(false);
                rule.setDisabledByDowngrade(true);
                suspended++;
            }
        }
        if (suspended > 0) {
            ruleRepository.saveAll(rules);
            log.info("PRO access lost for traveler {} — {} automation rules suspended",
                    travelerId, suspended);
        }
    }

    private void restore(java.util.UUID travelerId) {
        List<AutomationRuleEntity> rules =
                ruleRepository.findByTravelerIdAndDisabledByDowngradeTrue(travelerId);
        if (rules.isEmpty()) {
            return;
        }
        for (AutomationRuleEntity rule : rules) {
            rule.setEnabled(true);
            rule.setDisabledByDowngrade(false);
        }
        ruleRepository.saveAll(rules);
        log.info("PRO access restored for traveler {} — {} automation rules re-enabled",
                travelerId, rules.size());
    }
}
```

- [ ] **Étape 7 : Relancer le test**

Commande : `./mvnw test -Dtest=AutomationRuleProStatusListenerTest`
Attendu : SUCCÈS, 3 tests.

- [ ] **Étape 8 : Écrire le test de bout en bout du downgrade**

Ce test couvre l'exigence du spec la plus facile à casser silencieusement : **un downgrade ne doit dépublier aucune annonce**. Une annonce retirée du marché parce qu'un abonnement a expiré casserait des engagements déjà pris envers des expéditeurs.

`src/test/java/com/yadony/api/billing/ProDowngradeEndToEndIntegrationTest.java` :

```java
package com.yadony.api.billing;

import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.automation.AutomationRuleEntity;
import com.yadony.api.automation.AutomationRuleRepository;
import com.yadony.api.kyc.KycStatus;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.TransportMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Downgrade PRO — effets de bout en bout")
class ProDowngradeEndToEndIntegrationTest {

    @Autowired ProSubscriptionService subscriptionService;
    @Autowired ProSubscriptionRepository subscriptionRepository;
    @Autowired UserRepository userRepository;
    @Autowired AutomationRuleRepository ruleRepository;
    @Autowired AnnouncementRepository announcementRepository;

    private UUID travelerId;

    @BeforeEach
    void setUp() {
        subscriptionRepository.deleteAll();
        ruleRepository.deleteAll();
        announcementRepository.deleteAll();
        userRepository.deleteAll();

        UserEntity traveler = new UserEntity();
        traveler.setFirebaseUid("uid-downgrade-e2e-001");
        traveler.setStatus(UserStatus.ACTIVE);
        traveler.setKycStatus(KycStatus.PENDING);
        traveler.setRoles(Set.of(Role.TRAVELER));
        traveler.setCountry("FR");
        traveler.setProAccount(true);
        travelerId = userRepository.save(traveler).getId();
    }

    private ProSubscriptionEntity activeSubscription() {
        ProSubscriptionEntity sub = new ProSubscriptionEntity();
        sub.setUserId(travelerId);
        sub.setStatus(ProSubscriptionStatus.ACTIVE);
        sub.setSource(ProSubscriptionSource.STRIPE);
        return subscriptionRepository.save(sub);
    }

    private AutomationRuleEntity enabledRule() {
        AutomationRuleEntity rule = new AutomationRuleEntity();
        rule.setTravelerId(travelerId);
        rule.setRuleType("preset");
        rule.setPresetRuleId("alert_capacity_free");
        rule.setName("Alerte capacité");
        rule.setEnabled(true);
        rule.setConditions(List.of(Map.of()));
        rule.setAction(Map.of());
        return ruleRepository.save(rule);
    }

    private AnnouncementEntity activeAnnouncement() {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(travelerId);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(7));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Paris CDG");
        a.setPickupLat(new BigDecimal("48.860000"));
        a.setPickupLng(new BigDecimal("2.350000"));
        a.setDeliveryAddressLabel("Dakar Centre");
        a.setDeliveryLat(new BigDecimal("14.693000"));
        a.setDeliveryLng(new BigDecimal("-17.447000"));
        a.setAvailableKg(new BigDecimal("10.00"));
        a.setTotalKg(new BigDecimal("10.00"));
        a.setPricePerKg(new BigDecimal("5.00"));
        a.setStatus(AnnouncementStatus.ACTIVE);
        a.setTravelerIsPro(true);
        return announcementRepository.save(a);
    }

    @Test
    @DisplayName("l'expiration retire le statut PRO, suspend les règles, mais ne dépublie pas les annonces")
    void expirationSuspendsRulesWithoutUnpublishingAnnouncements() {
        ProSubscriptionEntity sub = activeSubscription();
        AutomationRuleEntity rule = enabledRule();
        AnnouncementEntity announcement = activeAnnouncement();

        subscriptionService.expire(sub);

        assertThat(userRepository.findById(travelerId).orElseThrow().isProAccount())
                .as("le drapeau PRO doit tomber")
                .isFalse();

        assertThat(ruleRepository.findById(rule.getId()).orElseThrow())
                .satisfies(r -> {
                    assertThat(r.isEnabled()).as("la règle doit être suspendue").isFalse();
                    assertThat(r.isDisabledByDowngrade())
                            .as("la suspension doit être marquée pour un futur réabonnement")
                            .isTrue();
                });

        AnnouncementEntity reloaded = announcementRepository.findById(announcement.getId()).orElseThrow();
        assertThat(reloaded.getStatus())
                .as("un downgrade ne doit jamais dépublier une annonce : "
                        + "des expéditeurs peuvent déjà s'être engagés dessus")
                .isEqualTo(AnnouncementStatus.ACTIVE);
        assertThat(reloaded.isTravelerIsPro())
                .as("seul le badge PRO de l'annonce doit tomber")
                .isFalse();
    }

    @Test
    @DisplayName("le réabonnement rallume les règles suspendues par le downgrade")
    void resubscriptionRestoresSuspendedRules() {
        ProSubscriptionEntity sub = activeSubscription();
        AutomationRuleEntity rule = enabledRule();

        subscriptionService.expire(sub);
        assertThat(ruleRepository.findById(rule.getId()).orElseThrow().isEnabled()).isFalse();

        subscriptionService.openLegacyGrace(travelerId, 60);

        assertThat(ruleRepository.findById(rule.getId()).orElseThrow())
                .satisfies(r -> {
                    assertThat(r.isEnabled()).isTrue();
                    assertThat(r.isDisabledByDowngrade()).isFalse();
                });
    }
}
```

> **Piège de ce test.** Il n'est volontairement pas annoté `@Transactional` : `AnnouncementRepository.updateTravelerProStatus` est une requête `@Modifying` qui contourne le contexte de persistance, et un test transactionnel lirait des valeurs périmées. D'où le nettoyage explicite dans `@BeforeEach`.
>
> Le second test réabonne un utilisateur dont la ligne est déjà `EXPIRED`. Il passe parce que `openLegacyGrace` **recycle la ligne existante** au lieu d'en insérer une seconde, ce que l'index `uq_pro_subscriptions_user` refuserait : il porte sur `deleted_at IS NULL`, sans distinguer les statuts fermés. Un abonnement par utilisateur, pour toute sa vie.

- [ ] **Étape 9 : Lancer le test de bout en bout**

Commande : `./mvnw test -Dtest=ProDowngradeEndToEndIntegrationTest`
Attendu : SUCCÈS, 2 tests.

- [ ] **Étape 10 : Commit**

```bash
git add src/main/java/com/yadony/api/automation src/main/resources/db/migration/V232__automation_rules_disabled_by_downgrade.sql src/test/java/com/yadony/api/automation/AutomationRuleProStatusListenerTest.java src/test/java/com/yadony/api/billing/ProDowngradeEndToEndIntegrationTest.java
git commit -m "feat(automation): suspend les règles à la perte du statut PRO"
```

---

## Task 6 : Configuration et tâches planifiées

**Fichiers :**
- Créer : `src/main/java/com/yadony/api/billing/ProSubscriptionScheduler.java`
- Modifier : `src/main/resources/application.yml`
- Test : `src/test/java/com/yadony/api/billing/ProSubscriptionSchedulerTest.java`

**Interfaces :**
- Consomme : `ProSubscriptionRepository`, `ProSubscriptionService.expire(...)` / `cancel(...)`, `BillingProperties` (créé en Task 4)
- Produit : rien qui soit consommé par une autre tâche de ce lot

- [ ] **Étape 1 : Ajouter le bloc de configuration**

Dans `src/main/resources/application.yml`, à l'intérieur du bloc `yadony:` existant (par exemple après `escrow-timeout-hours`) :

```yaml
  billing:
    # Faux tant que le lot 2 (Stripe Checkout) n'est pas en production :
    # sans lui, les comptes en grâce n'ont aucun moyen de s'abonner.
    scheduler-enabled: ${YADONY_BILLING_SCHEDULER_ENABLED:false}
    legacy-grace-days: ${YADONY_BILLING_LEGACY_GRACE_DAYS:60}
    dunning-grace-days: ${YADONY_BILLING_DUNNING_GRACE_DAYS:5}
    expiry-cron: "0 30 3 * * *"
```

- [ ] **Étape 2 : Écrire le test du scheduler**

`src/test/java/com/yadony/api/billing/ProSubscriptionSchedulerTest.java` :

```java
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
                new BillingProperties(enabled, 60, 5));
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
```

- [ ] **Étape 3 : Lancer le test et vérifier qu'il échoue**

Commande : `./mvnw test -Dtest=ProSubscriptionSchedulerTest`
Attendu : ÉCHEC de compilation, `ProSubscriptionScheduler` n'existe pas.

- [ ] **Étape 4 : Écrire le scheduler**

`src/main/java/com/yadony/api/billing/ProSubscriptionScheduler.java` :

```java
package com.yadony.api.billing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Downgrades différés de l'abonnement PRO.
 *
 * <p>Les trois tâches sont sous le même interrupteur
 * {@code yadony.billing.scheduler-enabled}, faux par défaut : sans le parcours
 * de paiement du lot 2, elles expireraient des comptes qui n'ont aucun moyen
 * de s'abonner.
 *
 * <p>{@code @EnableScheduling} est déjà porté par {@code YadonyBackApplication}.
 */
@Component
public class ProSubscriptionScheduler {

    private static final Logger log = LoggerFactory.getLogger(ProSubscriptionScheduler.class);

    private final ProSubscriptionRepository repository;
    private final ProSubscriptionService subscriptionService;
    private final BillingProperties properties;

    public ProSubscriptionScheduler(ProSubscriptionRepository repository,
                                    ProSubscriptionService subscriptionService,
                                    BillingProperties properties) {
        this.repository = repository;
        this.subscriptionService = subscriptionService;
        this.properties = properties;
    }

    /** Grâces historiques arrivées à échéance. */
    @Scheduled(cron = "${yadony.billing.expiry-cron:0 30 3 * * *}", zone = "UTC")
    @Transactional
    public void expireLegacyGrace() {
        if (!properties.schedulerEnabledOrDefault()) {
            return;
        }
        List<ProSubscriptionEntity> expired = repository.findByStatusAndGraceExpiresAtBefore(
                ProSubscriptionStatus.LEGACY_GRACE, Instant.now());
        expired.forEach(subscriptionService::expire);
        if (!expired.isEmpty()) {
            log.info("Legacy PRO grace expired for {} subscriptions", expired.size());
        }
    }

    /** Impayés dont les relances Stripe n'ont rien donné. */
    @Scheduled(cron = "${yadony.billing.expiry-cron:0 30 3 * * *}", zone = "UTC")
    @Transactional
    public void expireExhaustedDunning() {
        if (!properties.schedulerEnabledOrDefault()) {
            return;
        }
        Instant threshold = Instant.now()
                .minus(properties.dunningGraceDaysOrDefault(), ChronoUnit.DAYS);
        List<ProSubscriptionEntity> exhausted = repository.findByStatusAndPastDueSinceBefore(
                ProSubscriptionStatus.PAST_DUE, threshold);
        exhausted.forEach(subscriptionService::expire);
        if (!exhausted.isEmpty()) {
            log.info("Dunning exhausted for {} subscriptions", exhausted.size());
        }
    }

    /**
     * Filet de sécurité : ferme les résiliations dont la période payée est
     * écoulée si le webhook {@code customer.subscription.deleted} a été manqué.
     */
    @Scheduled(cron = "${yadony.billing.expiry-cron:0 30 3 * * *}", zone = "UTC")
    @Transactional
    public void closeEndedCancellations() {
        if (!properties.schedulerEnabledOrDefault()) {
            return;
        }
        List<ProSubscriptionEntity> ended = repository
                .findByStatusAndCancelAtPeriodEndTrueAndCurrentPeriodEndBefore(
                        ProSubscriptionStatus.ACTIVE, Instant.now());
        ended.forEach(subscriptionService::cancel);
        if (!ended.isEmpty()) {
            log.info("Closed {} subscriptions whose paid period ended", ended.size());
        }
    }
}
```

- [ ] **Étape 5 : Relancer le test**

Commande : `./mvnw test -Dtest=ProSubscriptionSchedulerTest`
Attendu : SUCCÈS, 5 tests.

- [ ] **Étape 6 : Lancer toute la suite**

Commande : `./mvnw test`
Attendu : SUCCÈS complet. Aucun test existant ne doit casser — en particulier `AuthControllerUpgradeToProIntegrationTest`, qui exerce l'endpoint d'upgrade gratuit toujours en place.

> Si la suite s'arrête sur « Exit 134 » / SIGABRT avec 0 échec, c'est un manque de mémoire de la JVM, pas une régression. Ne jamais lancer `./mvnw compile` pendant que `./mvnw test` tourne.

- [ ] **Étape 7 : Vérifier la couverture**

Commande : `./mvnw test jacoco:report`
Ouvrir `target/site/jacoco/index.html` et vérifier que le package `com.yadony.api.billing` est à 90 % au minimum. Compléter les tests si nécessaire.

- [ ] **Étape 8 : Commit**

```bash
git add src/main/java/com/yadony/api/billing/BillingProperties.java src/main/java/com/yadony/api/billing/ProSubscriptionScheduler.java src/main/resources/application.yml src/test/java/com/yadony/api/billing/ProSubscriptionSchedulerTest.java
git commit -m "feat(billing): tâches planifiées de downgrade derrière un drapeau"
```

---

## Vérification de fin de lot

- [ ] `./mvnw test` passe intégralement
- [ ] Couverture du package `billing/` ≥ 90 % (`target/site/jacoco/index.html`)
- [ ] Le backfill V231 a bien créé une ligne par compte PRO existant :
  ```sql
  SELECT COUNT(*) FROM users WHERE is_pro_account = TRUE AND deleted_at IS NULL;
  SELECT COUNT(*) FROM pro_subscriptions WHERE status = 'LEGACY_GRACE';
  -- les deux comptes doivent être égaux
  ```
- [ ] `yadony.billing.scheduler-enabled` vaut bien `false` dans la configuration déployée
- [ ] Aucun changement visible pour l'utilisateur : l'endpoint `POST /auth/me/upgrade-to-pro` fonctionne toujours, les comptes PRO gardent leurs accès
- [ ] **Documentation de story rédigée** — obligatoire selon `CLAUDE.md` : créer `docs/stories-done/story-billing-lot1-fondation.md` en suivant le gabarit imposé (Résumé, Fichiers créés, Fichiers modifiés, Comment ça fonctionne, Points d'entrée API, Entités JPA, Logique métier critique, Events Spring, Pièges, Critères d'acceptation, Tests avec le pourcentage JaCoCo constaté, Décisions techniques). Ne la rédiger qu'une fois `./mvnw test` à zéro rouge.

## Ce que ce lot ne fait pas

- Aucun appel à Stripe, aucune possibilité de payer (lot 2)
- L'upgrade gratuit reste ouvert ; il est neutralisé par `LegacyProGraceListener`, qui ouvre une grâce plutôt qu'un accès illimité
- Les tâches planifiées sont inertes tant que le drapeau reste faux
