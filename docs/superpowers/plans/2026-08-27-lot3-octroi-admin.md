# Lot 3 — Octroi administrateur d'un accès PRO — Plan d'implémentation

> **Pour les agents :** SOUS-COMPÉTENCE REQUISE — utiliser `superpowers:subagent-driven-development` pour exécuter ce plan tâche par tâche. Les étapes utilisent la syntaxe case à cocher (`- [ ]`).

**Objectif :** permettre à un administrateur d'offrir un accès PRO gratuit — partenariat, geste commercial — et de le révoquer, indépendamment de Stripe.

**Architecture :** tout est déjà en place côté schéma et domaine. La migration `V231` a créé `granted_by_admin_id` et `admin_grant_reason`, et l'enum `ProSubscriptionSource.ADMIN_GRANT` existe — **sans aucun producteur à ce jour**. Ce lot ajoute une méthode `grantByAdmin` à la machine à états existante et deux endpoints administrateur. `cancel()` sert déjà la révocation : son javadoc la mentionne explicitement.

**Stack :** Spring Boot 3.4, Java 21, PostgreSQL 16, JUnit 5, Mockito, AssertJ.

**Aucune migration.** Les colonnes existent depuis `V231`.

## Contraintes globales

- Package racine : `com.yadony.api`. Les lots 1 et 2 ont livré le package `billing/` : ne rien y dupliquer.
- Erreurs : `YadonyBusinessException` → `ProblemDetail` RFC 7807. Jamais de String ni de Map brute.
- `audit_log` est immuable (trigger PostgreSQL) : jamais d'UPDATE ni de DELETE.
- Soft delete uniquement.
- Tests : `@MockitoBean` et non `@MockBean`. Pas de `@InjectMocks` : instanciation manuelle. AssertJ. `ReflectionTestUtils.setField(entity, "id", uuid)` pour un id hérité de `BaseEntity`.
- Profil de test : H2, `spring.flyway.enabled: false`, `ddl-auto: create`. Les migrations ne sont jamais exécutées et **les index partiels n'existent pas** : ne jamais asseoir une assertion sur une contrainte d'unicité de la base.
- Couverture minimale 90 %, appréciée au niveau du package.
- **Jamais deux commandes Maven en parallèle** : corrompt `target/classes`. Vérifier avec `pgrep -f plexus.classworlds.launcher.Launcher` avant d'en lancer une. Un « Exit 134 » ou SIGABRT avec 0 échec est un manque de mémoire JVM, pas une régression.
- **`target/jacoco.exec` est cumulatif.** Un pourcentage relevé après un run ciblé agrège les runs antérieurs. Pour mesurer proprement : supprimer le fichier, puis lancer `test` et `jacoco:report` **dans une seule commande**.
- Ne jamais modifier le code de production pour faire passer un test.

### Cinq pièges propres au package `admin/`, vérifiés dans le code

**1. Le principal administrateur n'est pas le `firebaseUid`.** Pour un utilisateur normal, `authentication.getName()` donne le `firebaseUid` — c'est ce que fait `BillingController.currentUserId`. Pour un administrateur, `FirebaseTokenFilter` pose un `AdminPrincipal` (record : `adminId`, `email`, `role`, `mustChangePassword`, `firebaseUid`). **Ne pas réutiliser le patron des endpoints utilisateurs.**

Il n'existe pas de helper partagé : chaque contrôleur admin duplique une méthode privée. La version canonique est dans `AdminUserController` :

```java
    private UUID adminId(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof AdminPrincipal principal) {
            return principal.adminId();
        }
        throw new YadonyBusinessException(HttpStatus.FORBIDDEN,
                "admin-principal-required", "Admin Principal Required",
                "Authentification administrateur requise");
    }
```

Comme les endpoints de ce lot vivent dans `AdminUserController`, ce helper y est **déjà présent** : le réutiliser, ne pas en créer un second.

**2. Une `@PreAuthorize` de méthode remplace celle de classe, elle ne s'y ajoute pas.** D'où la forme `hasRole('ADMIN') and hasAuthority('X')` répétée sur chaque méthode du dépôt. L'oublier ouvrirait l'endpoint à tout administrateur, quelle que soit sa permission.

**3. `AdminPermissionCoverageTest` fait rougir la suite** si une valeur d'`AdminPermission` n'est citée par aucune `@PreAuthorize`. Une nouvelle permission doit donc être créée **et câblée dans le même commit**.

**4. Doctrine d'audit du dépôt : le motif libre d'un administrateur ne va pas dans `audit_log`.** `AdminUserDeletionService` l'exclut délibérément, avec ce commentaire :

> Le payload ne contient que des données non-personnelles […]. La clé « reason » (motif libre) a été délibérément exclue : `audit_log` est immuable et ne peut être corrigé après coup — y écrire un texte libre saisi par un administrateur crée un risque réel d'y graver des données personnelles.

`AuditService` redacte automatiquement les clés PII connues, mais **pas** `reason`. Le motif est donc stocké dans la colonne `admin_grant_reason` — modifiable et soft-deletable — et **jamais** dans le payload d'audit.

**5. Convention de verbe HTTP.** Le dépôt utilise `POST /{userId}/delete` plutôt que `DELETE` **quand un corps est nécessaire**. Ici : `POST` pour l'octroi (il porte un motif), `DELETE` pour la révocation (aucun corps). Les deux formes existent déjà dans le dépôt.

---

## Structure des fichiers

**Créés :**

| Fichier | Responsabilité |
|---|---|
| `admin/dto/ProGrantRequest.java` | Motif de l'octroi, validé |
| `billing/dto/AdminProSubscriptionView.java` | État d'abonnement exposé à l'admin |

**Modifiés :**

| Fichier | Changement |
|---|---|
| `billing/ProSubscriptionService.java` | Ajout de `grantByAdmin(UUID userId, UUID adminId, String reason)` |
| `admin/account/AdminPermission.java` | Ajout de `USER_PRO_GRANT` |
| `admin/account/AdminRole.java` | Câblage de la permission sur les rôles concernés |
| `admin/AdminUserController.java` | Deux endpoints |
| `admin/dto/AdminUserDetailResponse.java` | Surcharge de `from` exposant l'abonnement |

**Aucune migration.**

---

## Task 1 : `grantByAdmin` sur la machine à états

**Fichiers :**
- Modifier : `src/main/java/com/yadony/api/billing/ProSubscriptionService.java`
- Test : `src/test/java/com/yadony/api/billing/ProSubscriptionServiceAdminGrantTest.java`

**Interfaces :**
- Consomme : `ProSubscriptionRepository`, `ProAccessSynchronizer.sync(UUID, boolean)`, `AuditService.log(String, UUID, String, UUID, Map)`
- Produit : `grantByAdmin(UUID userId, UUID adminId, String reason)` → `ProSubscriptionEntity`

### Trois exigences que le code doit respecter

**Recycler la ligne existante.** L'index `uq_pro_subscriptions_user` n'autorise qu'un abonnement vivant par utilisateur, **statuts fermés compris**. Même patron que `openLegacyGrace` et `activateFromStripe` : `findByUserId(...).orElseGet(ProSubscriptionEntity::new)`. Cet index n'existe pas sous H2 — vérification par lecture, aucun test ne peut la démontrer.

**Purger les champs Stripe.** `stripeCustomerId`, `stripeSubscriptionId`, `billingCycle`, `currentPeriodEnd`, plus `graceExpiresAt`, `pastDueSince` et `cancelAtPeriodEnd`. Sans cela, `findByStripeSubscriptionId` — indexée exprès pour les webhooks — ramènerait cette ligne au prochain événement Stripe et la ferait piloter par un abonnement qui n'est plus le sien.

**Enregistrer, puis synchroniser — dans cet ordre.** `LegacyProGraceListener` réagit à `UserProStatusChangedEvent(isPro=true)` et ouvre une grâce si aucun abonnement ne couvre déjà l'utilisateur. Si la synchronisation précédait l'enregistrement, l'octroi administrateur serait **écrasé par un `LEGACY_FREE`**. C'est l'ordre qu'`activateFromStripe` respecte déjà.

- [ ] **Étape 1 : Écrire le test**

`src/test/java/com/yadony/api/billing/ProSubscriptionServiceAdminGrantTest.java` :

```java
package com.yadony.api.billing;

import com.yadony.api.common.AuditService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
                .thenAnswer(inv -> inv.getArgument(0));

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
    @DisplayName("un octroi purge les traces Stripe de la ligne recyclée")
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
        // Un stripe_subscription_id survivant serait retrouvé par findByStripeSubscriptionId
        // au prochain webhook, et cette ligne serait pilotée par un abonnement étranger.
        assertThat(result.getStripeCustomerId()).isNull();
        assertThat(result.getStripeSubscriptionId()).isNull();
        assertThat(result.getBillingCycle()).isNull();
        assertThat(result.getCurrentPeriodEnd()).isNull();
        assertThat(result.getPastDueSince()).isNull();
        assertThat(result.isCancelAtPeriodEnd()).isFalse();
        assertThat(result.getGraceExpiresAt()).isNull();
    }

    @Test
    @DisplayName("l'octroi est journalisé au nom de l'administrateur, sans le motif libre")
    void grantIsAuditedWithoutFreeText() {
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        when(repository.save(any(ProSubscriptionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

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
                .thenAnswer(inv -> inv.getArgument(0));

        service().grantByAdmin(USER_ID, ADMIN_ID, REASON);

        // Une trace désignant la cible comme acteur ne pourra jamais être corrigée,
        // et l'administrateur responsable resterait introuvable.
        verify(auditService).log(any(), any(), any(), eq(ADMIN_ID), anyMap());
    }
}
```

- [ ] **Étape 2 : Lancer le test et vérifier qu'il échoue**

Commande : `./mvnw test -Dtest=ProSubscriptionServiceAdminGrantTest`
Attendu : ÉCHEC de compilation, `grantByAdmin` n'existe pas.

- [ ] **Étape 3 : Écrire la méthode**

Dans `src/main/java/com/yadony/api/billing/ProSubscriptionService.java`, après `activateFromStripe` :

```java
    /**
     * Accès PRO offert par un administrateur : partenariat, geste commercial.
     *
     * <p>Sans échéance — il court jusqu'à révocation explicite par un administrateur,
     * ou renoncement de l'utilisateur lui-même. Aucune tâche planifiée ne le ferme :
     * les trois requêtes de {@code ProSubscriptionScheduler} filtrent sur des statuts
     * ou des dates que cet octroi ne porte pas.
     *
     * <p>Recycle la ligne existante comme les autres créateurs : l'index
     * {@code uq_pro_subscriptions_user} n'autorise qu'un abonnement vivant par
     * utilisateur, statuts fermés compris.
     *
     * <p>Le motif n'entre pas dans {@code audit_log} : cette table est immuable, et un
     * texte libre saisi par un administrateur y graverait définitivement d'éventuelles
     * données personnelles. Il vit dans {@code admin_grant_reason}.
     */
    @Transactional
    public ProSubscriptionEntity grantByAdmin(UUID userId, UUID adminId, String reason) {
        ProSubscriptionEntity sub = repository.findByUserId(userId)
                .orElseGet(ProSubscriptionEntity::new);
        sub.setUserId(userId);
        sub.setStatus(ProSubscriptionStatus.ACTIVE);
        sub.setSource(ProSubscriptionSource.ADMIN_GRANT);
        sub.setGrantedByAdminId(adminId);
        sub.setAdminGrantReason(reason);
        // La source change : les traces d'un cycle Stripe précédent ne doivent pas
        // survivre. Un stripe_subscription_id résiduel serait retrouvé par
        // findByStripeSubscriptionId au prochain webhook, qui piloterait alors cette
        // ligne depuis un abonnement qui n'est plus le sien.
        sub.setStripeCustomerId(null);
        sub.setStripeSubscriptionId(null);
        sub.setBillingCycle(null);
        sub.setCurrentPeriodEnd(null);
        sub.setGraceExpiresAt(null);
        sub.setPastDueSince(null);
        sub.setCancelAtPeriodEnd(false);

        // Enregistrer AVANT de synchroniser : LegacyProGraceListener réagit à
        // l'événement et ouvrirait une LEGACY_GRACE si aucun abonnement ne couvrait
        // encore l'utilisateur — écrasant cet octroi.
        ProSubscriptionEntity saved = repository.save(sub);
        accessSynchronizer.sync(userId, true);

        auditService.log(AUDIT_ENTITY_TYPE, saved.getId(), "BILLING_ADMIN_GRANTED", adminId,
                Map.of("targetUserId", userId.toString()));

        log.info("PRO access granted to user {} by admin {}", userId, adminId);
        return saved;
    }
```

- [ ] **Étape 4 : Relancer le test**

Commande : `./mvnw test -Dtest=ProSubscriptionServiceAdminGrantTest`
Attendu : SUCCÈS, 4 tests.

- [ ] **Étape 5 : Vérifier la non-régression du package**

Commande : `./mvnw test -Dtest='ProSubscriptionServiceTest,ProSubscriptionServiceStripeTest,LegacyProGraceListenerTest,ProSubscriptionSchedulerTest'`
Attendu : SUCCÈS. Aucune méthode existante ne devait changer.

- [ ] **Étape 6 : Commit**

```bash
git add src/main/java/com/yadony/api/billing/ProSubscriptionService.java src/test/java/com/yadony/api/billing/ProSubscriptionServiceAdminGrantTest.java
git commit -m "feat(billing): accès PRO offert par un administrateur"
```

---

## Task 2 : Endpoints administrateur

**Fichiers :**
- Créer : `src/main/java/com/yadony/api/admin/dto/ProGrantRequest.java`
- Modifier : `src/main/java/com/yadony/api/admin/account/AdminPermission.java`
- Modifier : `src/main/java/com/yadony/api/admin/account/AdminRole.java`
- Modifier : `src/main/java/com/yadony/api/admin/AdminUserController.java`
- Test : `src/test/java/com/yadony/api/admin/AdminProGrantControllerIT.java`

**Interfaces :**
- Consomme : `ProSubscriptionService.grantByAdmin(UUID, UUID, String)` et `cancel(ProSubscriptionEntity)`, `ProSubscriptionRepository.findByUserId(UUID)`
- Produit : `POST /admin/users/{userId}/pro-grant`, `DELETE /admin/users/{userId}/pro-grant`

### Le garde-fou de la révocation

La révocation doit être **refusée en 409** si la ligne n'a pas `source = ADMIN_GRANT`. Un administrateur ne doit pas pouvoir fermer un abonnement Stripe payant depuis cet endpoint : la base et Stripe se désynchroniseraient, l'utilisateur perdant son accès tout en restant débité. C'est exactement le défaut corrigé au lot 2 sur `DELETE /auth/me/upgrade-to-pro`.

- [ ] **Étape 1 : Créer le DTO de requête**

`src/main/java/com/yadony/api/admin/dto/ProGrantRequest.java` :

```java
package com.yadony.api.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Motif d'un accès PRO offert par un administrateur.
 *
 * <p>Obligatoire : un accès gratuit est un geste commercial qui doit rester explicable.
 * Borné à 500 caractères, ce qui correspond à la colonne {@code admin_grant_reason}.
 */
public record ProGrantRequest(
        @NotBlank(message = "Le motif est obligatoire")
        @Size(max = 500, message = "Le motif ne peut pas dépasser 500 caractères")
        String reason
) {}
```

- [ ] **Étape 2 : Ajouter la permission et la câbler**

Dans `src/main/java/com/yadony/api/admin/account/AdminPermission.java`, ajouter `USER_PRO_GRANT` auprès de `USER_COMMISSION` — même famille : un geste commercial ciblant un utilisateur.

Puis, dans `src/main/java/com/yadony/api/admin/account/AdminRole.java`, attribuer cette permission **aux mêmes rôles que `USER_COMMISSION`**. Lire le fichier pour identifier lesquels : offrir un accès PRO gratuit et modifier un taux de commission sont deux gestes commerciaux de même portée.

> `AdminPermissionCoverageTest` fait échouer la suite si une permission n'est citée par aucune `@PreAuthorize`. Elle doit donc être créée, câblée sur les rôles **et** portée par les endpoints de l'étape suivante dans le même commit.

- [ ] **Étape 3 : Écrire le test d'intégration**

`src/test/java/com/yadony/api/admin/AdminProGrantControllerIT.java` :

```java
package com.yadony.api.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.admin.dto.ProGrantRequest;
import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.billing.ProSubscriptionEntity;
import com.yadony.api.billing.ProSubscriptionRepository;
import com.yadony.api.billing.ProSubscriptionSource;
import com.yadony.api.billing.ProSubscriptionStatus;
import com.yadony.api.auth.KycStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("AdminProGrantControllerIT — /admin/users/{userId}/pro-grant")
class AdminProGrantControllerIT {

    private static final UUID ADMIN_ID = UUID.randomUUID();

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired ProSubscriptionRepository subscriptionRepository;

    @MockitoBean FirebaseContactService firebaseContact;

    private UUID userId;

    @BeforeEach
    void setUp() {
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();

        UserEntity user = new UserEntity();
        user.setFirebaseUid("uid-pro-grant-001");
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(Set.of(Role.TRAVELER));
        user.setCountry("FR");
        userId = userRepository.save(user).getId();

        org.mockito.Mockito.lenient().when(firebaseContact.getContact(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new FirebaseContactService.Contact("+33612000010", null));
    }

    /** Administrateur disposant de toutes les permissions de son rôle. */
    private UsernamePasswordAuthenticationToken adminAuth(AdminRole role) {
        AdminPrincipal principal =
                new AdminPrincipal(ADMIN_ID, "admin@yadony.test", role, false, "uid-admin-pro-grant");
        List<SimpleGrantedAuthority> authorities = new ArrayList<>(
                role.permissions().stream().map(p -> new SimpleGrantedAuthority(p.name())).toList());
        authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }

    /** Administrateur authentifié mais privé de la permission d'octroi. */
    private UsernamePasswordAuthenticationToken adminWithoutPermission() {
        AdminPrincipal principal =
                new AdminPrincipal(UUID.randomUUID(), "sans@yadony.test", AdminRole.ADMIN, false, "uid-sans");
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private ProSubscriptionEntity persist(ProSubscriptionStatus status, ProSubscriptionSource source) {
        ProSubscriptionEntity sub = new ProSubscriptionEntity();
        sub.setUserId(userId);
        sub.setStatus(status);
        sub.setSource(source);
        return subscriptionRepository.save(sub);
    }

    @Test
    @DisplayName("l'octroi rend le compte PRO et journalise l'administrateur")
    void grantMakesAccountPro() throws Exception {
        mockMvc.perform(post("/admin/users/{userId}/pro-grant", userId)
                        .with(authentication(adminAuth(AdminRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ProGrantRequest("Partenariat presse"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isProAccount").value(true));

        assertThat(userRepository.findById(userId).orElseThrow().isProAccount()).isTrue();
        ProSubscriptionEntity sub = subscriptionRepository.findByUserId(userId).orElseThrow();
        assertThat(sub.getSource()).isEqualTo(ProSubscriptionSource.ADMIN_GRANT);
        assertThat(sub.getGrantedByAdminId()).isEqualTo(ADMIN_ID);
        assertThat(sub.getAdminGrantReason()).isEqualTo("Partenariat presse");
    }

    @Test
    @DisplayName("un motif vide est refusé en 422")
    void blankReasonIsRejected() throws Exception {
        mockMvc.perform(post("/admin/users/{userId}/pro-grant", userId)
                        .with(authentication(adminAuth(AdminRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ProGrantRequest("   "))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("un administrateur sans la permission est refusé en 403")
    void withoutPermissionIsForbidden() throws Exception {
        mockMvc.perform(post("/admin/users/{userId}/pro-grant", userId)
                        .with(authentication(adminWithoutPermission()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ProGrantRequest("Test"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("la révocation d'un octroi administrateur ferme l'accès")
    void revokeClosesAdminGrant() throws Exception {
        persist(ProSubscriptionStatus.ACTIVE, ProSubscriptionSource.ADMIN_GRANT);
        UserEntity user = userRepository.findById(userId).orElseThrow();
        user.setProAccount(true);
        userRepository.save(user);

        mockMvc.perform(delete("/admin/users/{userId}/pro-grant", userId)
                        .with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isProAccount").value(false));

        assertThat(subscriptionRepository.findByUserId(userId).orElseThrow().getStatus())
                .isEqualTo(ProSubscriptionStatus.CANCELED);
    }

    @Test
    @DisplayName("révoquer un abonnement Stripe payant est refusé en 409")
    void revokingStripeSubscriptionIsRejected() throws Exception {
        persist(ProSubscriptionStatus.ACTIVE, ProSubscriptionSource.STRIPE);

        // Fermer une ligne Stripe ici désynchroniserait la base et Stripe : l'utilisateur
        // perdrait son accès tout en restant débité.
        mockMvc.perform(delete("/admin/users/{userId}/pro-grant", userId)
                        .with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not-an-admin-grant"));

        assertThat(subscriptionRepository.findByUserId(userId).orElseThrow().getStatus())
                .as("l'abonnement payant doit rester intact")
                .isEqualTo(ProSubscriptionStatus.ACTIVE);
    }

    @Test
    @DisplayName("révoquer sans aucun abonnement répond 404")
    void revokingWithoutSubscriptionReturns404() throws Exception {
        mockMvc.perform(delete("/admin/users/{userId}/pro-grant", userId)
                        .with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Étape 4 : Lancer le test et vérifier qu'il échoue**

Commande : `./mvnw test -Dtest=AdminProGrantControllerIT`
Attendu : ÉCHEC — les endpoints n'existent pas.

- [ ] **Étape 5 : Écrire les endpoints**

Dans `src/main/java/com/yadony/api/admin/AdminUserController.java`, ajouter les deux dépendances au constructeur (`ProSubscriptionService`, `ProSubscriptionRepository`) puis, auprès des autres endpoints de gestion utilisateur :

```java
    /**
     * Offre un accès PRO gratuit : partenariat, geste commercial.
     *
     * <p>{@code POST} et non {@code PUT} : un corps est nécessaire pour le motif, et
     * c'est la forme retenue par les endpoints admin du dépôt.
     */
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('USER_PRO_GRANT')")
    @PostMapping("/{userId}/pro-grant")
    public AdminUserDetailResponse grantPro(@PathVariable UUID userId,
                                            @Valid @RequestBody ProGrantRequest request,
                                            Authentication authentication) {
        proSubscriptionService.grantByAdmin(userId, adminId(authentication), request.reason());
        return detail(userRepository.findById(userId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "user-not-found", "Not Found", "Utilisateur introuvable")));
    }

    /**
     * Révoque un accès offert.
     *
     * <p>Refusé si l'abonnement n'est pas un octroi administrateur : fermer une ligne
     * Stripe ici désynchroniserait la base et Stripe, l'utilisateur perdant son accès
     * tout en restant débité. La résiliation d'un abonnement payant passe par le
     * Customer Portal.
     */
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('USER_PRO_GRANT')")
    @DeleteMapping("/{userId}/pro-grant")
    public AdminUserDetailResponse revokePro(@PathVariable UUID userId,
                                             Authentication authentication) {
        ProSubscriptionEntity sub = proSubscriptionRepository.findByUserId(userId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "no-subscription", "Not Found",
                        "Aucun abonnement PRO sur ce compte"));

        if (sub.getSource() != ProSubscriptionSource.ADMIN_GRANT) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT,
                    "not-an-admin-grant", "Not An Admin Grant",
                    "Cet abonnement n'est pas un accès offert : il se résilie depuis Stripe.");
        }

        proSubscriptionService.cancel(sub);
        return detail(userRepository.findById(userId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "user-not-found", "Not Found", "Utilisateur introuvable")));
    }
```

> Le helper `adminId(Authentication)` existe déjà dans cette classe : le réutiliser, ne pas en écrire un second. Vérifier les imports nécessaires et les ajouter.

- [ ] **Étape 6 : Relancer le test**

Commande : `./mvnw test -Dtest=AdminProGrantControllerIT`
Attendu : SUCCÈS, 6 tests.

- [ ] **Étape 7 : Vérifier la couverture des permissions**

Commande : `./mvnw test -Dtest=AdminPermissionCoverageTest`
Attendu : SUCCÈS. Ce test échoue si `USER_PRO_GRANT` n'est citée par aucune `@PreAuthorize`.

- [ ] **Étape 8 : Commit**

```bash
git add src/main/java/com/yadony/api/admin src/test/java/com/yadony/api/admin/AdminProGrantControllerIT.java
git commit -m "feat(admin): octroi et révocation d'un accès PRO offert"
```

---

## Task 3 : Exposer l'état d'abonnement à l'administrateur

**Fichiers :**
- Créer : `src/main/java/com/yadony/api/billing/dto/AdminProSubscriptionView.java`
- Modifier : `src/main/java/com/yadony/api/admin/dto/AdminUserDetailResponse.java`
- Modifier : `src/main/java/com/yadony/api/admin/AdminUserController.java`
- Test : `src/test/java/com/yadony/api/admin/dto/AdminUserDetailResponseTest.java`

### Pourquoi une surcharge et non un changement de signature

`AdminUserDetailResponse.from(UserEntity, Contact)` est appelée depuis neuf endroits dans `AdminUserController`, plus les tests. Changer sa signature ferait rayonner la modification sans bénéfice. On ajoute donc une **surcharge** à trois arguments, et seul l'appel de `detail(...)` bascule dessus.

- [ ] **Étape 1 : Créer le DTO d'état**

`src/main/java/com/yadony/api/billing/dto/AdminProSubscriptionView.java` :

```java
package com.yadony.api.billing.dto;

import com.yadony.api.billing.ProSubscriptionEntity;

import java.time.Instant;
import java.util.UUID;

/**
 * État d'abonnement PRO exposé à l'administration.
 *
 * <p>Porte l'origine du droit — payant, offert, ou grâce historique — et, pour un
 * accès offert, qui l'a accordé et pourquoi. Contrairement au DTO exposé à
 * l'utilisateur, celui-ci montre les identifiants Stripe : un administrateur en a
 * besoin pour rapprocher une ligne d'un abonnement dans le dashboard.
 */
public record AdminProSubscriptionView(
        String status,
        String source,
        String billingCycle,
        Instant currentPeriodEnd,
        boolean cancelAtPeriodEnd,
        Instant graceExpiresAt,
        String stripeSubscriptionId,
        UUID grantedByAdminId,
        String adminGrantReason
) {
    public static AdminProSubscriptionView from(ProSubscriptionEntity sub) {
        if (sub == null) {
            return null;
        }
        return new AdminProSubscriptionView(
                sub.getStatus().name(),
                sub.getSource().name(),
                sub.getBillingCycle() == null ? null : sub.getBillingCycle().name(),
                sub.getCurrentPeriodEnd(),
                sub.isCancelAtPeriodEnd(),
                sub.getGraceExpiresAt(),
                sub.getStripeSubscriptionId(),
                sub.getGrantedByAdminId(),
                sub.getAdminGrantReason()
        );
    }
}
```

- [ ] **Étape 2 : Écrire le test**

Ajouter à `src/test/java/com/yadony/api/admin/dto/AdminUserDetailResponseTest.java` — ou créer le fichier s'il n'existe pas, en suivant le style de `AdminUserListItemResponseTest` :

```java
    @Test
    @DisplayName("sans abonnement, le champ proSubscription est nul")
    void withoutSubscription() {
        AdminUserDetailResponse response =
                AdminUserDetailResponse.from(sampleUser(), sampleContact(), null);

        assertThat(response.proSubscription()).isNull();
    }

    @Test
    @DisplayName("un accès offert expose l'administrateur et le motif")
    void adminGrantExposesGranter() {
        UUID adminId = UUID.randomUUID();
        ProSubscriptionEntity sub = new ProSubscriptionEntity();
        sub.setStatus(ProSubscriptionStatus.ACTIVE);
        sub.setSource(ProSubscriptionSource.ADMIN_GRANT);
        sub.setGrantedByAdminId(adminId);
        sub.setAdminGrantReason("Partenariat presse");

        AdminUserDetailResponse response =
                AdminUserDetailResponse.from(sampleUser(), sampleContact(), sub);

        assertThat(response.proSubscription().source()).isEqualTo("ADMIN_GRANT");
        assertThat(response.proSubscription().grantedByAdminId()).isEqualTo(adminId);
        assertThat(response.proSubscription().adminGrantReason()).isEqualTo("Partenariat presse");
    }

    @Test
    @DisplayName("la surcharge à deux arguments reste disponible et laisse le champ nul")
    void twoArgOverloadStillWorks() {
        // Neuf appels du contrôleur l'utilisent encore : elle ne doit pas disparaître.
        AdminUserDetailResponse response =
                AdminUserDetailResponse.from(sampleUser(), sampleContact());

        assertThat(response.proSubscription()).isNull();
    }
```

Écrire les fabriques `sampleUser()` et `sampleContact()` en s'alignant sur l'existant du fichier ou sur `AdminUserListItemResponseTest`.

- [ ] **Étape 3 : Lancer le test et vérifier qu'il échoue**

Commande : `./mvnw test -Dtest=AdminUserDetailResponseTest`
Attendu : ÉCHEC de compilation, le champ et la surcharge n'existent pas.

- [ ] **Étape 4 : Ajouter le champ et la surcharge**

Dans `src/main/java/com/yadony/api/admin/dto/AdminUserDetailResponse.java` :

1. Ajouter `AdminProSubscriptionView proSubscription` en **dernière** composante du record — l'ajouter au milieu casserait silencieusement tout appel positionnel existant.
2. Conserver `from(UserEntity, Contact)` en déléguant à la nouvelle surcharge avec `null`.
3. Ajouter `from(UserEntity, Contact, ProSubscriptionEntity)` qui remplit le champ via `AdminProSubscriptionView.from(sub)`.

- [ ] **Étape 5 : Brancher le contrôleur**

Dans `AdminUserController`, faire pointer le helper `detail(...)` sur la surcharge à trois arguments, en chargeant l'abonnement :

```java
    private AdminUserDetailResponse detail(UserEntity user) {
        return AdminUserDetailResponse.from(
                user,
                firebaseContact.getContact(user.getFirebaseUid()),
                proSubscriptionRepository.findByUserId(user.getId()).orElse(null));
    }
```

Les neuf appels existants à `detail(...)` en bénéficient sans modification.

- [ ] **Étape 6 : Relancer les tests**

Commande : `./mvnw test -Dtest='AdminUserDetailResponseTest,AdminProGrantControllerIT,AdminUserControllerTest'`
Attendu : SUCCÈS. `AdminUserControllerTest` ne devait pas casser — si c'est le cas, adapter ses attentes sans affaiblir ses assertions, et le signaler.

- [ ] **Étape 7 : Lancer la suite complète**

Commande : `./mvnw test`
Attendu : SUCCÈS. Rapporter le résultat réel, y compris en cas d'échec.

> Un rapport surefire périmé de `com.yadony.api.automation.JsonDebugTest` traîne dans `target/surefire-reports/`, source supprimée : ce n'est pas un échec.

- [ ] **Étape 8 : Vérifier la couverture**

Commande : `rm -f target/jacoco.exec && ./mvnw test jacoco:report`
Une seule commande, sans quoi le chiffre agrège les runs antérieurs. Vérifier le package `com.yadony.api.billing` et les classes créées.

- [ ] **Étape 9 : Commit**

```bash
git add src/main/java/com/yadony/api/billing/dto src/main/java/com/yadony/api/admin src/test/java/com/yadony/api/admin
git commit -m "feat(admin): expose l'état d'abonnement PRO dans la fiche utilisateur"
```

---

## Vérification de fin de lot

- [ ] `./mvnw test` passe intégralement
- [ ] Couverture du package `billing/` ≥ 90 %, mesurée proprement
- [ ] `AdminPermissionCoverageTest` passe : `USER_PRO_GRANT` est câblée
- [ ] Les deux endpoints portent `hasRole('ADMIN') and hasAuthority('USER_PRO_GRANT')` — une `@PreAuthorize` de méthode remplace celle de classe
- [ ] Le motif libre n'apparaît **nulle part** dans un payload `audit_log`
- [ ] Révoquer un abonnement Stripe est refusé en 409
- [ ] **Documentation de story** : `docs/stories-done/story-billing-lot3-admin-grant.md`, selon le gabarit du `CLAUDE.md`

## Hors périmètre

- Interface `dony-admin` : ce lot livre l'API, l'écran « Offrir PRO » viendra ensuite
- Durée limitée d'un octroi : un accès offert court jusqu'à révocation. Si le besoin d'une échéance apparaît, `currentPeriodEnd` et la tâche planifiée existent déjà pour la porter
- Bonus de matching (lot 4), portail web (lot 5), mobile (lot 6)
