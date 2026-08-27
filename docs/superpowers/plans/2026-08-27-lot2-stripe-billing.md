# Lot 2 — Stripe Billing — Plan d'implémentation

> **Pour les agents :** SOUS-COMPÉTENCE REQUISE — utiliser `superpowers:subagent-driven-development` pour exécuter ce plan tâche par tâche. Les étapes utilisent la syntaxe case à cocher (`- [ ]`).

**Objectif :** rendre l'abonnement PRO réellement payable — souscription par Stripe Checkout, gestion par le Customer Portal, cycle de vie piloté par les webhooks.

**Architecture :** le lot 1 a posé `ProSubscriptionEntity` et sa machine à états. Ce lot y branche Stripe : un contrôleur `billing/` crée les sessions Checkout et Portal, et un `StripeWebhookHandler` traduit les événements Stripe en transitions de la machine à états existante. L'idempotence est déjà assurée par l'infrastructure `stripe_event_inbox` du dépôt : **aucune table supplémentaire**.

**Stack :** Spring Boot 3.4, Java 21, stripe-java 33.3.0, PostgreSQL 16, JUnit 5, Mockito, AssertJ.

## Contraintes globales

- Package racine : `com.yadony.api`. Le lot 1 a livré le package `billing/` : ne pas dupliquer ce qui existe.
- Erreurs : `YadonyBusinessException` (RFC 7807 via `GlobalExceptionHandler`). Jamais de String ni de Map brute.
- Vérification de signature Stripe obligatoire sur tout webhook.
- Ownership : les endpoints opèrent toujours sur l'utilisateur authentifié. **Aucun `userId` accepté en paramètre client.**
- Le principal Spring Security est le `firebaseUid` (String), pas un `UserEntity`.
- Tests : Spring Boot 3.4 → `@MockitoBean`, jamais `@MockBean`. Pas de `@InjectMocks` : instanciation manuelle. AssertJ. `ReflectionTestUtils.setField(entity, "id", uuid)` pour un id hérité de `BaseEntity`.
- Profil de test : H2, `spring.flyway.enabled: false`, `ddl-auto: create`. Les migrations ne sont jamais exécutées par la suite, et les index partiels n'existent pas. Ne jamais asseoir une assertion sur une contrainte d'unicité de la base.
- Couverture minimale 90 %.
- **Jamais deux commandes Maven en parallèle** : corrompt `target/classes`. Un « Exit 134 » / SIGABRT avec 0 échec est un manque de mémoire JVM, pas une régression.
- Ne jamais modifier le code de production pour faire passer un test.

### Deux points du `CLAUDE.md` du dépôt à ignorer, vérifiés périmés

1. **`processed_stripe_events` n'existe plus.** Créée en `V48`, données migrées en `V84`, table droppée en `V86`. Zéro référence Java dans `src/`. Le `CLAUDE.md` la prescrit encore (règles NEVER 16, ALWAYS 12, checklist sécurité) : cette section n'est plus à jour. **Le seul mécanisme d'idempotence vivant est `stripe_event_inbox`**, dont la clé primaire est l'identifiant d'événement Stripe.
2. `@Where` est indiqué pour le soft delete ; les entités récentes utilisent `@SQLRestriction`.

### L'infrastructure webhook existante, à réutiliser telle quelle

```
POST /billing/webhook (contrôleur, 3 lignes)
  └→ StripeWebhookIngestService.ingest(payload, sigHeader, source)
       vérifie la signature, refuse les doublons par clé primaire, insère dans stripe_event_inbox
         └→ StripeEventScheduler.poll()  (@Scheduled, déjà actif)
              └→ StripeEventProcessor.processOne()  (claimNext FOR UPDATE SKIP LOCKED, retry + DEAD_LETTER)
                   └→ StripeEventDispatcher.dispatch()
                        └→ premier StripeWebhookHandler dont supports(type) est vrai
```

Pour brancher de nouveaux événements, il suffit de créer un `@Component` implémentant `StripeWebhookHandler` : le dispatcher injecte `List<StripeWebhookHandler>` et découvre les beans automatiquement. **Rien à modifier dans `common/stripe/` hormis la source du secret** (Task 1).

**Contrainte forte :** `StripeEventDispatcher` retourne au **premier** handler qui accepte le type. Un type d'événement ne peut aller qu'à un seul handler. `PaymentStripeWebhookHandler` revendique déjà `payment_intent.*`, `charge.*`, `transfer.*`, `payout.*`, `account.*`, `setup_intent.succeeded`, `capability.updated`, `radar.*`. Les types `checkout.session.*`, `customer.subscription.*` et `invoice.*` sont **libres**.

---

## Structure des fichiers

**Créés dans `src/main/java/com/yadony/api/billing/` :**

| Fichier | Responsabilité |
|---|---|
| `StripeBillingService.java` | Création des sessions Checkout et Customer Portal |
| `BillingController.java` | `POST /billing/checkout-session`, `POST /billing/portal-session`, `GET /billing/subscription`, `POST /billing/webhook` |
| `ProBillingStripeWebhookHandler.java` | Traduit les événements Stripe en transitions de la machine à états |
| `dto/CheckoutSessionResponse.java` | `{ url }` |
| `dto/PortalSessionResponse.java` | `{ url }` |
| `dto/ProSubscriptionResponse.java` | Statut exposé au portail web |

**Modifiés :**

| Fichier | Changement |
|---|---|
| `common/stripe/StripeWebhookSource.java` | Ajout de `BILLING` |
| `common/stripe/StripeWebhookIngestService.java` | Sélection du secret : ternaire → `switch` exhaustif |
| `config/StripeConfig.java` | Bean `stripeBillingWebhookSecret` |
| `config/SecurityConfig.java` | `/billing/webhook` en `permitAll` |
| `billing/BillingProperties.java` | Identifiants des Price Stripe et URLs de retour |
| `billing/ProSubscriptionService.java` | `activateFromStripe`, `markCancelAtPeriodEnd` |
| `auth/UserService.java` | `upgradeToPro` cesse d'accorder le statut PRO |
| `src/main/resources/application.yml`, `-dev.yml`, `-prod.yml` | Secret webhook billing, Price IDs, URLs |

**Aucune migration.** Le schéma du lot 1 suffit.

---

## Prérequis humain, hors code

Avant que le lot soit utilisable en production, quelqu'un doit créer dans le dashboard Stripe :

- un **Product** `yadony-pro` avec deux **Price** récurrents : `4,99 EUR/mois` et `47,90 EUR/an` ;
- un **Customer Portal** configuré pour n'autoriser que la mise à jour du moyen de paiement et la résiliation (pas de changement de plan : il n'y a qu'un palier) ;
- un **endpoint webhook** pointant sur `/api/v1/billing/webhook`, abonné à `checkout.session.completed`, `customer.subscription.updated`, `customer.subscription.deleted`, `invoice.paid`, `invoice.payment_failed`.

Les identifiants obtenus alimentent `STRIPE_BILLING_PRICE_MONTHLY`, `STRIPE_BILLING_PRICE_YEARLY` et `STRIPE_WEBHOOK_BILLING_SECRET`.

Le code ne doit pas empêcher l'application de démarrer si ces valeurs manquent : elles ont une valeur par défaut vide, et c'est l'appel à `POST /billing/checkout-session` qui échoue proprement (Task 3).

---

## Task 1 : Acheminement du secret webhook billing

**Fichiers :**
- Modifier : `src/main/java/com/yadony/api/common/stripe/StripeWebhookSource.java`
- Modifier : `src/main/java/com/yadony/api/common/stripe/StripeWebhookIngestService.java`
- Modifier : `src/main/java/com/yadony/api/config/StripeConfig.java`
- Modifier : `src/main/java/com/yadony/api/config/SecurityConfig.java`
- Modifier : `src/main/resources/application.yml`, `application-dev.yml`, `application-prod.yml`
- Test : `src/test/java/com/yadony/api/common/stripe/StripeWebhookIngestServiceTest.java`

**Interfaces :**
- Consomme : `StripeEventInboxRepository`
- Produit : `StripeWebhookSource.BILLING`, bean `@Qualifier("stripeBillingWebhookSecret")`

### Le piège que cette tâche existe pour éviter

`StripeWebhookIngestService` sélectionne aujourd'hui le secret par un **ternaire** :

```java
String secret = source == StripeWebhookSource.KYC ? kycSecret : paymentsSecret;
```

Ajouter `BILLING` à l'enum sans toucher cette ligne ferait valider les signatures billing avec le **secret PAYMENTS**, silencieusement. Selon la configuration, soit tous les webhooks billing seraient rejetés en production, soit — si les deux secrets sont identiques via le repli `${STRIPE_WEBHOOK_SECRET}` — ils passeraient en dev et échoueraient en production seulement. Le ternaire devient donc un `switch` exhaustif.

- [ ] **Étape 1 : Écrire le test**

Le test signe un payload comme le fait Stripe, puis vérifie que chaque source n'accepte que son propre secret. Aucun mock statique n'est nécessaire.

`src/test/java/com/yadony/api/common/stripe/StripeWebhookIngestServiceTest.java` :

```java
package com.yadony.api.common.stripe;

import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("StripeWebhookIngestService — chaque source valide avec son propre secret")
class StripeWebhookIngestServiceTest {

    private static final String PAYMENTS_SECRET = "whsec_payments_aaaaaaaaaaaaaaaa";
    private static final String KYC_SECRET = "whsec_kyc_bbbbbbbbbbbbbbbbbbbb";
    private static final String BILLING_SECRET = "whsec_billing_cccccccccccccccc";

    @Mock StripeEventInboxRepository repo;

    private StripeWebhookIngestService service() {
        return new StripeWebhookIngestService(repo, PAYMENTS_SECRET, KYC_SECRET, BILLING_SECRET);
    }

    /** Reproduit l'en-tête Stripe-Signature : t=<ts>,v1=<HMAC-SHA256(ts + "." + payload, secret)>. */
    private static String signature(String payload, String secret) {
        long timestamp = Instant.now().getEpochSecond();
        String signedPayload = timestamp + "." + payload;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return "t=" + timestamp + ",v1=" + hex;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String payload(String eventId, String type) {
        return "{\"id\":\"" + eventId + "\",\"object\":\"event\",\"type\":\"" + type
                + "\",\"data\":{\"object\":{}}}";
    }

    @Test
    @DisplayName("un événement billing signé avec le secret billing est accepté et rangé sous BILLING")
    void billingEventAcceptedWithBillingSecret() {
        String body = payload("evt_billing_1", "invoice.paid");
        when(repo.existsById("evt_billing_1")).thenReturn(false);

        service().ingest(body, signature(body, BILLING_SECRET), StripeWebhookSource.BILLING);

        ArgumentCaptor<StripeEventInbox> captor = ArgumentCaptor.forClass(StripeEventInbox.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getSource()).isEqualTo(StripeWebhookSource.BILLING);
        assertThat(captor.getValue().getEventType()).isEqualTo("invoice.paid");
    }

    @Test
    @DisplayName("un événement billing signé avec le secret payments est refusé")
    void billingEventRejectedWithPaymentsSecret() {
        String body = payload("evt_billing_2", "invoice.paid");

        assertThatThrownBy(() ->
                service().ingest(body, signature(body, PAYMENTS_SECRET), StripeWebhookSource.BILLING))
                .isInstanceOf(YadonyBusinessException.class)
                .hasMessageContaining("Signature");

        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("un événement payments signé avec le secret billing est refusé")
    void paymentsEventRejectedWithBillingSecret() {
        String body = payload("evt_pay_1", "payment_intent.succeeded");

        assertThatThrownBy(() ->
                service().ingest(body, signature(body, BILLING_SECRET), StripeWebhookSource.PAYMENTS))
                .isInstanceOf(YadonyBusinessException.class);

        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("un événement KYC signé avec le secret KYC reste accepté")
    void kycEventStillWorks() {
        String body = payload("evt_kyc_1", "identity.verification_session.verified");
        when(repo.existsById("evt_kyc_1")).thenReturn(false);

        service().ingest(body, signature(body, KYC_SECRET), StripeWebhookSource.KYC);

        verify(repo).save(any(StripeEventInbox.class));
    }

    @Test
    @DisplayName("un événement déjà présent dans l'inbox n'est pas réinséré")
    void duplicateIsSkipped() {
        String body = payload("evt_dup", "invoice.paid");
        when(repo.existsById("evt_dup")).thenReturn(true);

        service().ingest(body, signature(body, BILLING_SECRET), StripeWebhookSource.BILLING);

        verify(repo, never()).save(any());
    }
}
```

- [ ] **Étape 2 : Lancer le test et vérifier qu'il échoue**

Commande : `./mvnw test -Dtest=StripeWebhookIngestServiceTest`
Attendu : ÉCHEC de compilation — `StripeWebhookSource.BILLING` n'existe pas et le constructeur n'a que trois paramètres.

- [ ] **Étape 3 : Ajouter la valeur `BILLING`**

`src/main/java/com/yadony/api/common/stripe/StripeWebhookSource.java` :

```java
package com.yadony.api.common.stripe;

public enum StripeWebhookSource { PAYMENTS, KYC, BILLING }
```

La colonne `stripe_event_inbox.source` est un `VARCHAR(16)` sans contrainte `CHECK` : `BILLING` y tient sans migration.

- [ ] **Étape 4 : Remplacer le ternaire par un switch exhaustif**

Dans `src/main/java/com/yadony/api/common/stripe/StripeWebhookIngestService.java`, ajouter le champ, le paramètre de constructeur, et remplacer la sélection du secret :

```java
    private final StripeEventInboxRepository repo;
    private final String paymentsSecret;
    private final String kycSecret;
    private final String billingSecret;

    public StripeWebhookIngestService(
            StripeEventInboxRepository repo,
            @Qualifier("stripePaymentsWebhookSecret") String paymentsSecret,
            @Qualifier("stripeKycWebhookSecret") String kycSecret,
            @Qualifier("stripeBillingWebhookSecret") String billingSecret) {
        this.repo = repo;
        this.paymentsSecret = paymentsSecret;
        this.kycSecret = kycSecret;
        this.billingSecret = billingSecret;
    }
```

Et, dans `ingest`, à la place du ternaire :

```java
        // switch exhaustif, et non un ternaire : une nouvelle source ajoutée à
        // l'enum sans être traitée ici validerait sa signature avec le secret
        // d'une autre source, silencieusement.
        String secret = switch (source) {
            case PAYMENTS -> paymentsSecret;
            case KYC -> kycSecret;
            case BILLING -> billingSecret;
        };
```

Le `switch` sur enum sans `default` fait échouer la compilation si une valeur est ajoutée sans être traitée : c'est exactement la protection recherchée. **Ne pas ajouter de branche `default`.**

- [ ] **Étape 5 : Déclarer le bean du secret**

Dans `src/main/java/com/yadony/api/config/StripeConfig.java`, auprès des autres :

```java
    @Value("${stripe.webhook.billing-secret:}")
    private String billingWebhookSecret;
```

et

```java
    @Bean("stripeBillingWebhookSecret")
    public String stripeBillingWebhookSecret() {
        return billingWebhookSecret;
    }
```

- [ ] **Étape 6 : Configurer les trois profils**

`src/main/resources/application.yml`, dans le bloc `stripe.webhook` existant :

```yaml
    billing-secret: ${STRIPE_WEBHOOK_BILLING_SECRET:${STRIPE_WEBHOOK_SECRET:}}
```

`src/main/resources/application-dev.yml`, même bloc :

```yaml
    billing-secret: ${STRIPE_WEBHOOK_BILLING_SECRET:${STRIPE_WEBHOOK_SECRET:whsec_local}}
```

`src/main/resources/application-prod.yml`, même bloc — **sans repli**, pour qu'une absence de configuration se voie au démarrage plutôt qu'à la première signature refusée :

```yaml
    billing-secret: ${STRIPE_WEBHOOK_BILLING_SECRET}
```

- [ ] **Étape 7 : Ouvrir l'endpoint webhook**

Dans `src/main/java/com/yadony/api/config/SecurityConfig.java`, ajouter `"/billing/webhook"` dans la liste `permitAll`, juste après `"/payments/stripe/webhook"`. La signature est vérifiée par `StripeWebhookIngestService` ; l'endpoint doit rester public car Stripe n'envoie pas de jeton Firebase.

- [ ] **Étape 8 : Relancer le test**

Commande : `./mvnw test -Dtest=StripeWebhookIngestServiceTest`
Attendu : SUCCÈS, 5 tests.

- [ ] **Étape 9 : Commit**

```bash
git add src/main/java/com/yadony/api/common/stripe src/main/java/com/yadony/api/config src/main/resources/application.yml src/main/resources/application-dev.yml src/main/resources/application-prod.yml src/test/java/com/yadony/api/common/stripe/StripeWebhookIngestServiceTest.java
git commit -m "feat(billing): source webhook BILLING avec son propre secret"
```

---

## Task 2 : Transitions Stripe sur la machine à états

**Fichiers :**
- Modifier : `src/main/java/com/yadony/api/billing/ProSubscriptionService.java`
- Test : `src/test/java/com/yadony/api/billing/ProSubscriptionServiceStripeTest.java`

**Interfaces :**
- Consomme : `ProSubscriptionRepository`, `ProAccessSynchronizer.sync(UUID, boolean)`, `AuditService.log(String, UUID, String, UUID, Map)`
- Produit :
  - `activateFromStripe(UUID userId, String customerId, String subscriptionId, BillingCycle cycle, Instant periodEnd)` → `ProSubscriptionEntity`
  - `markCancelAtPeriodEnd(ProSubscriptionEntity sub, boolean cancelAtPeriodEnd)` → `ProSubscriptionEntity`
  - `renew(ProSubscriptionEntity sub, Instant periodEnd)` → `ProSubscriptionEntity`

- [ ] **Étape 1 : Écrire le test**

`src/test/java/com/yadony/api/billing/ProSubscriptionServiceStripeTest.java` :

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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
                .thenAnswer(inv -> inv.getArgument(0));

        ProSubscriptionEntity result = service().activateFromStripe(
                USER_ID, CUSTOMER_ID, SUBSCRIPTION_ID, BillingCycle.MONTHLY, periodEnd);

        assertThat(result.getStatus()).isEqualTo(ProSubscriptionStatus.ACTIVE);
        assertThat(result.getSource()).isEqualTo(ProSubscriptionSource.STRIPE);
        assertThat(result.getStripeCustomerId()).isEqualTo(CUSTOMER_ID);
        assertThat(result.getStripeSubscriptionId()).isEqualTo(SUBSCRIPTION_ID);
        assertThat(result.getBillingCycle()).isEqualTo(BillingCycle.MONTHLY);
        assertThat(result.getCurrentPeriodEnd()).isEqualTo(periodEnd);
        verify(accessSynchronizer).sync(USER_ID, true);
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
    }
}
```

- [ ] **Étape 2 : Lancer le test et vérifier qu'il échoue**

Commande : `./mvnw test -Dtest=ProSubscriptionServiceStripeTest`
Attendu : ÉCHEC de compilation, les trois méthodes n'existent pas.

- [ ] **Étape 3 : Ajouter les trois méthodes**

Dans `src/main/java/com/yadony/api/billing/ProSubscriptionService.java`, après `openLegacyGrace` :

```java
    /**
     * Souscription payante confirmée par Stripe.
     *
     * <p>Recycle la ligne existante comme {@link #openLegacyGrace} : l'index
     * {@code uq_pro_subscriptions_user} n'autorise qu'un abonnement vivant par
     * utilisateur, statut fermé compris.
     *
     * <p>Purge les champs qui n'ont plus de sens une fois l'abonnement payé :
     * la grâce historique, un impayé antérieur, et une résiliation programmée
     * sur un cycle précédent.
     */
    @Transactional
    public ProSubscriptionEntity activateFromStripe(UUID userId,
                                                    String customerId,
                                                    String subscriptionId,
                                                    BillingCycle cycle,
                                                    Instant periodEnd) {
        ProSubscriptionEntity sub = repository.findByUserId(userId)
                .orElseGet(ProSubscriptionEntity::new);
        sub.setUserId(userId);
        sub.setStatus(ProSubscriptionStatus.ACTIVE);
        sub.setSource(ProSubscriptionSource.STRIPE);
        sub.setStripeCustomerId(customerId);
        sub.setStripeSubscriptionId(subscriptionId);
        sub.setBillingCycle(cycle);
        sub.setCurrentPeriodEnd(periodEnd);
        sub.setGraceExpiresAt(null);
        sub.setPastDueSince(null);
        sub.setCancelAtPeriodEnd(false);
        ProSubscriptionEntity saved = repository.save(sub);

        accessSynchronizer.sync(userId, true);
        auditService.log(AUDIT_ENTITY_TYPE, saved.getId(), "BILLING_SUBSCRIPTION_ACTIVATED", userId,
                Map.of("cycle", cycle.name(), "stripeSubscriptionId", subscriptionId));

        log.info("Subscription {} activated from Stripe for user {} ({})",
                saved.getId(), userId, cycle);
        return saved;
    }

    /**
     * Résiliation programmée, ou son annulation, depuis le Customer Portal.
     * L'accès n'est pas coupé : la période en cours est déjà réglée. C'est
     * {@code ProSubscriptionScheduler.closeEndedCancellations} ou le webhook
     * {@code customer.subscription.deleted} qui fermera à l'échéance.
     */
    @Transactional
    public ProSubscriptionEntity markCancelAtPeriodEnd(ProSubscriptionEntity sub,
                                                       boolean cancelAtPeriodEnd) {
        sub.setCancelAtPeriodEnd(cancelAtPeriodEnd);
        ProSubscriptionEntity saved = repository.save(sub);
        log.info("Subscription {} cancelAtPeriodEnd set to {}", sub.getId(), cancelAtPeriodEnd);
        return saved;
    }

    /** Échéance encaissée : repousse la période et sort d'un éventuel impayé. */
    @Transactional
    public ProSubscriptionEntity renew(ProSubscriptionEntity sub, Instant periodEnd) {
        sub.setStatus(ProSubscriptionStatus.ACTIVE);
        sub.setPastDueSince(null);
        sub.setCurrentPeriodEnd(periodEnd);
        ProSubscriptionEntity saved = repository.save(sub);
        accessSynchronizer.sync(sub.getUserId(), true);
        log.info("Subscription {} renewed until {}", sub.getId(), periodEnd);
        return saved;
    }
```

- [ ] **Étape 4 : Relancer le test**

Commande : `./mvnw test -Dtest=ProSubscriptionServiceStripeTest`
Attendu : SUCCÈS, 5 tests.

- [ ] **Étape 5 : Vérifier la non-régression du lot 1**

Commande : `./mvnw test -Dtest='ProSubscriptionServiceTest,LegacyProGraceListenerTest,ProSubscriptionSchedulerTest'`
Attendu : SUCCÈS. Les méthodes du lot 1 ne doivent pas avoir changé de comportement.

- [ ] **Étape 6 : Commit**

```bash
git add src/main/java/com/yadony/api/billing/ProSubscriptionService.java src/test/java/com/yadony/api/billing/ProSubscriptionServiceStripeTest.java
git commit -m "feat(billing): transitions d'abonnement pilotées par Stripe"
```

---

## Task 3 : Handler des événements Stripe

**Fichiers :**
- Créer : `src/main/java/com/yadony/api/billing/ProBillingStripeWebhookHandler.java`
- Test : `src/test/java/com/yadony/api/billing/ProBillingStripeWebhookHandlerTest.java`

**Interfaces :**
- Consomme : `common/stripe/StripeWebhookHandler` (`boolean supports(String)`, `void handle(Event)`), `ProSubscriptionRepository.findByStripeSubscriptionId(String)`, `ProSubscriptionService`
- Produit : un `@Component` découvert automatiquement par `StripeEventDispatcher`

### Ce que le handler doit traiter

| Événement Stripe | Effet |
|---|---|
| `checkout.session.completed` | Crée ou réactive l'abonnement : `activateFromStripe`. Le `client_reference_id` porte l'identifiant utilisateur |
| `invoice.paid` | `renew` avec la nouvelle échéance |
| `invoice.payment_failed` | `markPastDue` — l'accès reste ouvert pendant les relances Stripe |
| `customer.subscription.updated` | Reporte `cancel_at_period_end` via `markCancelAtPeriodEnd` |
| `customer.subscription.deleted` | `cancel` |

Aucun de ces types n'est revendiqué par `PaymentStripeWebhookHandler` ni par `KycStripeWebhookHandler` : pas de conflit avec le routage au premier handler.

### Deux règles de robustesse à respecter

**Ne jamais lever d'exception pour une donnée absente.** `StripeEventProcessor` traite toute exception comme un échec à réessayer, avec sept relances puis mise en `DEAD_LETTER` et alerte administrateur. Un événement qu'on ne sait pas rattacher doit produire un `log.warn` et un `return`, comme le fait `KycStripeWebhookHandler`.

**Lire le JSON brut plutôt que l'objet désérialisé.** `event.getDataObjectDeserializer().getObject()` renvoie un `Optional` vide dès que la version d'API du compte Stripe diffère de celle du SDK — piège déjà documenté dans `PaymentStripeWebhookHandler.resolvePaymentIntent`. On lit donc `getRawJson()` avec Jackson.

- [ ] **Étape 1 : Écrire le test**

`src/test/java/com/yadony/api/billing/ProBillingStripeWebhookHandlerTest.java` :

```java
package com.yadony.api.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.model.Event;
import com.stripe.net.ApiResource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProBillingStripeWebhookHandler")
class ProBillingStripeWebhookHandlerTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String SUBSCRIPTION_ID = "sub_abc";

    @Mock ProSubscriptionRepository repository;
    @Mock ProSubscriptionService subscriptionService;

    private ProBillingStripeWebhookHandler handler() {
        return new ProBillingStripeWebhookHandler(repository, subscriptionService, new ObjectMapper());
    }

    /** Construit un Event Stripe à partir d'un JSON, comme le fait StripeEventDispatcher. */
    private static Event event(String type, String dataObjectJson) {
        String json = "{\"id\":\"evt_1\",\"object\":\"event\",\"type\":\"" + type
                + "\",\"data\":{\"object\":" + dataObjectJson + "}}";
        return ApiResource.GSON.fromJson(json, Event.class);
    }

    private ProSubscriptionEntity existingSubscription(ProSubscriptionStatus status) {
        ProSubscriptionEntity sub = new ProSubscriptionEntity();
        ReflectionTestUtils.setField(sub, "id", UUID.randomUUID());
        sub.setUserId(USER_ID);
        sub.setStatus(status);
        sub.setSource(ProSubscriptionSource.STRIPE);
        sub.setStripeSubscriptionId(SUBSCRIPTION_ID);
        return sub;
    }

    @Test
    @DisplayName("supports n'accepte que les cinq types attendus")
    void supportsOnlyBillingEvents() {
        ProBillingStripeWebhookHandler h = handler();
        assertThat(h.supports("checkout.session.completed")).isTrue();
        assertThat(h.supports("invoice.paid")).isTrue();
        assertThat(h.supports("invoice.payment_failed")).isTrue();
        assertThat(h.supports("customer.subscription.updated")).isTrue();
        assertThat(h.supports("customer.subscription.deleted")).isTrue();

        // Revendiqués par PaymentStripeWebhookHandler : ne jamais les intercepter.
        assertThat(h.supports("payment_intent.succeeded")).isFalse();
        assertThat(h.supports("charge.refunded")).isFalse();
    }

    @Test
    @DisplayName("checkout.session.completed active l'abonnement pour l'utilisateur référencé")
    void checkoutCompletedActivates() {
        String data = "{\"id\":\"cs_1\",\"client_reference_id\":\"" + USER_ID
                + "\",\"customer\":\"cus_1\",\"subscription\":\"" + SUBSCRIPTION_ID
                + "\",\"metadata\":{\"billing_cycle\":\"MONTHLY\"}}";

        handler().handle(event("checkout.session.completed", data));

        verify(subscriptionService).activateFromStripe(
                eq(USER_ID), eq("cus_1"), eq(SUBSCRIPTION_ID),
                eq(BillingCycle.MONTHLY), any(Instant.class));
    }

    @Test
    @DisplayName("checkout.session.completed sans client_reference_id est ignoré sans exception")
    void checkoutWithoutUserReferenceIsIgnored() {
        String data = "{\"id\":\"cs_2\",\"customer\":\"cus_1\",\"subscription\":\"sub_x\"}";

        handler().handle(event("checkout.session.completed", data));

        // Lever ici déclencherait sept relances puis une mise en DEAD_LETTER.
        verifyNoInteractions(subscriptionService);
    }

    @Test
    @DisplayName("invoice.paid renouvelle l'abonnement retrouvé")
    void invoicePaidRenews() {
        ProSubscriptionEntity sub = existingSubscription(ProSubscriptionStatus.PAST_DUE);
        when(repository.findByStripeSubscriptionId(SUBSCRIPTION_ID)).thenReturn(Optional.of(sub));
        String data = "{\"id\":\"in_1\",\"subscription\":\"" + SUBSCRIPTION_ID
                + "\",\"period_end\":1788000000}";

        handler().handle(event("invoice.paid", data));

        verify(subscriptionService).renew(eq(sub), any(Instant.class));
    }

    @Test
    @DisplayName("invoice.payment_failed passe en impayé sans couper l'accès")
    void invoiceFailedMarksPastDue() {
        ProSubscriptionEntity sub = existingSubscription(ProSubscriptionStatus.ACTIVE);
        when(repository.findByStripeSubscriptionId(SUBSCRIPTION_ID)).thenReturn(Optional.of(sub));
        String data = "{\"id\":\"in_2\",\"subscription\":\"" + SUBSCRIPTION_ID + "\"}";

        handler().handle(event("invoice.payment_failed", data));

        verify(subscriptionService).markPastDue(sub);
    }

    @Test
    @DisplayName("un abonnement inconnu est ignoré sans exception")
    void unknownSubscriptionIsIgnored() {
        when(repository.findByStripeSubscriptionId("sub_inconnu")).thenReturn(Optional.empty());
        String data = "{\"id\":\"in_3\",\"subscription\":\"sub_inconnu\"}";

        handler().handle(event("invoice.paid", data));

        verify(subscriptionService, never()).renew(any(), any());
    }

    @Test
    @DisplayName("customer.subscription.updated reporte la résiliation programmée")
    void subscriptionUpdatedReportsCancelAtPeriodEnd() {
        ProSubscriptionEntity sub = existingSubscription(ProSubscriptionStatus.ACTIVE);
        when(repository.findByStripeSubscriptionId(SUBSCRIPTION_ID)).thenReturn(Optional.of(sub));
        String data = "{\"id\":\"" + SUBSCRIPTION_ID + "\",\"cancel_at_period_end\":true}";

        handler().handle(event("customer.subscription.updated", data));

        verify(subscriptionService).markCancelAtPeriodEnd(sub, true);
    }

    @Test
    @DisplayName("customer.subscription.deleted ferme l'abonnement")
    void subscriptionDeletedCancels() {
        ProSubscriptionEntity sub = existingSubscription(ProSubscriptionStatus.ACTIVE);
        when(repository.findByStripeSubscriptionId(SUBSCRIPTION_ID)).thenReturn(Optional.of(sub));
        String data = "{\"id\":\"" + SUBSCRIPTION_ID + "\"}";

        handler().handle(event("customer.subscription.deleted", data));

        verify(subscriptionService).cancel(sub);
    }

    @Test
    @DisplayName("un abonnement déjà fermé n'est pas fermé une seconde fois")
    void alreadyClosedSubscriptionIsNotCancelledAgain() {
        ProSubscriptionEntity sub = existingSubscription(ProSubscriptionStatus.CANCELED);
        when(repository.findByStripeSubscriptionId(SUBSCRIPTION_ID)).thenReturn(Optional.of(sub));
        String data = "{\"id\":\"" + SUBSCRIPTION_ID + "\"}";

        handler().handle(event("customer.subscription.deleted", data));

        // Stripe rejoue ses événements : une seconde fermeture réécrirait audit_log.
        verify(subscriptionService, never()).cancel(any());
    }
}
```

- [ ] **Étape 2 : Lancer le test et vérifier qu'il échoue**

Commande : `./mvnw test -Dtest=ProBillingStripeWebhookHandlerTest`
Attendu : ÉCHEC de compilation, la classe n'existe pas.

- [ ] **Étape 3 : Écrire le handler**

`src/main/java/com/yadony/api/billing/ProBillingStripeWebhookHandler.java` :

```java
package com.yadony.api.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.model.Event;
import com.yadony.api.common.stripe.StripeWebhookHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Traduit les événements Stripe Billing en transitions de la machine à états
 * de l'abonnement PRO.
 *
 * <p>Découvert automatiquement par {@code StripeEventDispatcher}, qui injecte
 * la liste des {@link StripeWebhookHandler}. Aucun des types traités ici n'est
 * revendiqué par les handlers existants : le dispatcher s'arrête au premier
 * handler acceptant un type, un chevauchement en masquerait un.
 *
 * <p><b>Aucune méthode ne lève d'exception sur une donnée absente.</b>
 * {@code StripeEventProcessor} traite toute exception comme un échec à
 * réessayer : sept relances, puis mise en {@code DEAD_LETTER} et alerte
 * administrateur. Un événement inexploitable est journalisé et ignoré.
 */
@Component
public class ProBillingStripeWebhookHandler implements StripeWebhookHandler {

    private static final Logger log = LoggerFactory.getLogger(ProBillingStripeWebhookHandler.class);

    private static final Set<String> SUPPORTED = Set.of(
            "checkout.session.completed",
            "invoice.paid",
            "invoice.payment_failed",
            "customer.subscription.updated",
            "customer.subscription.deleted"
    );

    private final ProSubscriptionRepository repository;
    private final ProSubscriptionService subscriptionService;
    private final ObjectMapper objectMapper;

    public ProBillingStripeWebhookHandler(ProSubscriptionRepository repository,
                                          ProSubscriptionService subscriptionService,
                                          ObjectMapper objectMapper) {
        this.repository = repository;
        this.subscriptionService = subscriptionService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String eventType) {
        return SUPPORTED.contains(eventType);
    }

    @Override
    public void handle(Event event) {
        JsonNode data = readDataObject(event);
        if (data == null) {
            return;
        }
        switch (event.getType()) {
            case "checkout.session.completed" -> onCheckoutCompleted(data);
            case "invoice.paid" -> onInvoicePaid(data);
            case "invoice.payment_failed" -> onInvoiceFailed(data);
            case "customer.subscription.updated" -> onSubscriptionUpdated(data);
            case "customer.subscription.deleted" -> onSubscriptionDeleted(data);
            default -> log.warn("Unexpected billing event type {}", event.getType());
        }
    }

    /**
     * Lit le JSON brut plutôt que l'objet désérialisé : {@code getObject()}
     * renvoie un Optional vide dès que la version d'API du compte Stripe
     * diffère de celle du SDK — piège déjà rencontré dans
     * {@code PaymentStripeWebhookHandler}.
     */
    private JsonNode readDataObject(Event event) {
        try {
            String rawJson = event.getDataObjectDeserializer().getRawJson();
            if (rawJson == null || rawJson.isBlank()) {
                log.warn("Billing event {} has no data object", event.getId());
                return null;
            }
            return objectMapper.readTree(rawJson);
        } catch (Exception e) {
            log.warn("Cannot parse billing event {}: {}", event.getId(), e.getMessage());
            return null;
        }
    }

    private void onCheckoutCompleted(JsonNode data) {
        String reference = text(data, "client_reference_id");
        String customerId = text(data, "customer");
        String subscriptionId = text(data, "subscription");

        if (reference == null || customerId == null || subscriptionId == null) {
            log.warn("Checkout session incomplete (reference={}, customer={}, subscription={})",
                    reference, customerId, subscriptionId);
            return;
        }

        UUID userId;
        try {
            userId = UUID.fromString(reference);
        } catch (IllegalArgumentException e) {
            log.warn("Checkout session carries a non-UUID client_reference_id: {}", reference);
            return;
        }

        BillingCycle cycle = readCycle(data);
        // L'échéance exacte arrivera par invoice.paid ; on pose une borne
        // provisoire pour que la ligne ne paraisse jamais expirée entre-temps.
        Instant provisionalEnd = Instant.now().plus(
                cycle == BillingCycle.YEARLY ? 366 : 32, ChronoUnit.DAYS);

        subscriptionService.activateFromStripe(userId, customerId, subscriptionId,
                cycle, provisionalEnd);
    }

    private BillingCycle readCycle(JsonNode data) {
        String raw = data.path("metadata").path("billing_cycle").asText(null);
        if (raw == null) {
            log.warn("Checkout session without billing_cycle metadata — defaulting to MONTHLY");
            return BillingCycle.MONTHLY;
        }
        try {
            return BillingCycle.valueOf(raw);
        } catch (IllegalArgumentException e) {
            log.warn("Unknown billing_cycle metadata '{}' — defaulting to MONTHLY", raw);
            return BillingCycle.MONTHLY;
        }
    }

    private void onInvoicePaid(JsonNode data) {
        find(text(data, "subscription")).ifPresent(sub -> {
            long periodEnd = data.path("period_end").asLong(0L);
            Instant end = periodEnd > 0
                    ? Instant.ofEpochSecond(periodEnd)
                    : Instant.now().plus(32, ChronoUnit.DAYS);
            subscriptionService.renew(sub, end);
        });
    }

    private void onInvoiceFailed(JsonNode data) {
        find(text(data, "subscription")).ifPresent(sub -> {
            if (sub.getStatus() == ProSubscriptionStatus.PAST_DUE) {
                return;
            }
            subscriptionService.markPastDue(sub);
        });
    }

    private void onSubscriptionUpdated(JsonNode data) {
        find(text(data, "id")).ifPresent(sub -> {
            boolean cancelAtPeriodEnd = data.path("cancel_at_period_end").asBoolean(false);
            if (sub.isCancelAtPeriodEnd() == cancelAtPeriodEnd) {
                return;
            }
            subscriptionService.markCancelAtPeriodEnd(sub, cancelAtPeriodEnd);
        });
    }

    private void onSubscriptionDeleted(JsonNode data) {
        find(text(data, "id")).ifPresent(sub -> {
            // Stripe rejoue ses événements : sans cette garde, une seconde
            // réception réécrirait une entrée audit_log avec un statut
            // précédent égal au statut cible.
            if (!sub.getStatus().grantsProAccess()) {
                return;
            }
            subscriptionService.cancel(sub);
        });
    }

    private Optional<ProSubscriptionEntity> find(String stripeSubscriptionId) {
        if (stripeSubscriptionId == null) {
            log.warn("Billing event without a subscription identifier");
            return Optional.empty();
        }
        Optional<ProSubscriptionEntity> found =
                repository.findByStripeSubscriptionId(stripeSubscriptionId);
        if (found.isEmpty()) {
            log.warn("No PRO subscription matches Stripe subscription {}", stripeSubscriptionId);
        }
        return found;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
```

- [ ] **Étape 4 : Relancer le test**

Commande : `./mvnw test -Dtest=ProBillingStripeWebhookHandlerTest`
Attendu : SUCCÈS, 9 tests.

- [ ] **Étape 5 : Commit**

```bash
git add src/main/java/com/yadony/api/billing/ProBillingStripeWebhookHandler.java src/test/java/com/yadony/api/billing/ProBillingStripeWebhookHandlerTest.java
git commit -m "feat(billing): traite les événements Stripe Billing de l'abonnement PRO"
```

---

## Task 4 : Endpoints de souscription et de gestion

**Fichiers :**
- Modifier : `src/main/java/com/yadony/api/billing/BillingProperties.java`
- Créer : `src/main/java/com/yadony/api/billing/StripeBillingService.java`
- Créer : `src/main/java/com/yadony/api/billing/BillingController.java`
- Créer : `src/main/java/com/yadony/api/billing/dto/CheckoutSessionResponse.java`
- Créer : `src/main/java/com/yadony/api/billing/dto/PortalSessionResponse.java`
- Créer : `src/main/java/com/yadony/api/billing/dto/ProSubscriptionResponse.java`
- Modifier : `src/main/resources/application.yml`, `-dev.yml`, `-prod.yml`
- Test : `src/test/java/com/yadony/api/billing/StripeBillingServiceTest.java`
- Test : `src/test/java/com/yadony/api/billing/BillingControllerIntegrationTest.java`

**Interfaces :**
- Consomme : `BillingProperties`, `ProSubscriptionRepository`, `auth/UserRepository`
- Produit : `POST /billing/checkout-session`, `POST /billing/portal-session`, `GET /billing/subscription`, `POST /billing/webhook`

### Piège de nommage

Deux classes portent le nom `Session` dans le SDK : `com.stripe.model.checkout.Session` et `com.stripe.model.billingportal.Session`, avec chacune leur `SessionCreateParams`. Dans un fichier qui utilise les deux, **utiliser les noms pleinement qualifiés** plutôt que des imports, sous peine de collision silencieuse.

- [ ] **Étape 1 : Étendre la configuration**

> **Cette étape casse trois classes de test du lot 1.** `BillingProperties` est un record : passer de 3 à 8 composantes invalide tous les appels `new BillingProperties(false, 60, 5)`. Trois classes les utilisent — `BillingPropertiesTest`, `LegacyProGraceListenerTest`, `ProSubscriptionSchedulerTest`. Il faut compléter leurs appels avec les cinq nouveaux arguments (`null` convient partout où la valeur n'est pas testée), **sans affaiblir leurs assertions**. `BillingPropertiesTest` doit en outre gagner un cas couvrant `stripePricesConfigured()` et `priceFor(cycle)`, sinon la nouvelle logique de repli part sans test — exactement le trou trouvé au lot 1 sur cette même classe.

Dans `src/main/java/com/yadony/api/billing/BillingProperties.java`, ajouter cinq composantes au record et leurs accesseurs de repli :

```java
@ConfigurationProperties(prefix = "yadony.billing")
public record BillingProperties(
        Boolean schedulerEnabled,
        Integer legacyGraceDays,
        Integer dunningGraceDays,
        String priceMonthly,
        String priceYearly,
        String successUrl,
        String cancelUrl,
        String portalReturnUrl
) {
```

en conservant les trois accesseurs existants et en ajoutant :

```java
    /**
     * Vrai si les identifiants de Price Stripe sont renseignés. Faux en
     * développement et en test, où le dashboard Stripe n'est pas configuré :
     * l'application doit démarrer quand même, et c'est l'appel à
     * {@code POST /billing/checkout-session} qui échoue proprement.
     */
    public boolean stripePricesConfigured() {
        return priceMonthly != null && !priceMonthly.isBlank()
                && priceYearly != null && !priceYearly.isBlank();
    }

    public String priceFor(BillingCycle cycle) {
        return cycle == BillingCycle.YEARLY ? priceYearly : priceMonthly;
    }
```

Dans `src/main/resources/application.yml`, bloc `yadony.billing` :

```yaml
    price-monthly: ${STRIPE_BILLING_PRICE_MONTHLY:}
    price-yearly: ${STRIPE_BILLING_PRICE_YEARLY:}
    success-url: ${YADONY_BILLING_SUCCESS_URL:https://pro.yadony.com/parametres/abonnement?success=1}
    cancel-url: ${YADONY_BILLING_CANCEL_URL:https://pro.yadony.com/upgrade?canceled=1}
    portal-return-url: ${YADONY_BILLING_PORTAL_RETURN_URL:https://pro.yadony.com/parametres/abonnement}
```

Reprendre les cinq mêmes clés dans `application-prod.yml`, **sans valeur de repli** pour `price-monthly` et `price-yearly`, afin qu'une configuration manquante se voie au démarrage.

- [ ] **Étape 2 : Écrire les DTO**

`src/main/java/com/yadony/api/billing/dto/CheckoutSessionResponse.java` :

```java
package com.yadony.api.billing.dto;

/** URL de redirection vers Stripe Checkout. */
public record CheckoutSessionResponse(String url) {}
```

`src/main/java/com/yadony/api/billing/dto/PortalSessionResponse.java` :

```java
package com.yadony.api.billing.dto;

/** URL de redirection vers le Customer Portal Stripe. */
public record PortalSessionResponse(String url) {}
```

`src/main/java/com/yadony/api/billing/dto/ProSubscriptionResponse.java` :

```java
package com.yadony.api.billing.dto;

import java.time.Instant;

/**
 * État d'abonnement exposé au portail web.
 *
 * <p>Ne porte aucun identifiant Stripe : le client n'en a pas besoin, et les
 * exposer élargirait la surface sans contrepartie.
 */
public record ProSubscriptionResponse(
        boolean active,
        String status,
        String source,
        String billingCycle,
        Instant currentPeriodEnd,
        boolean cancelAtPeriodEnd,
        Instant graceExpiresAt
) {}
```

- [ ] **Étape 3 : Écrire le test du service**

`src/test/java/com/yadony/api/billing/StripeBillingServiceTest.java` :

```java
package com.yadony.api.billing;

import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("StripeBillingService — gardes avant appel à Stripe")
class StripeBillingServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock ProSubscriptionRepository repository;

    private BillingProperties configured() {
        return new BillingProperties(false, 60, 5, "price_m", "price_y",
                "https://pro.yadony.com/ok", "https://pro.yadony.com/ko",
                "https://pro.yadony.com/parametres/abonnement");
    }

    private BillingProperties unconfigured() {
        return new BillingProperties(false, 60, 5, null, null,
                "https://pro.yadony.com/ok", "https://pro.yadony.com/ko",
                "https://pro.yadony.com/parametres/abonnement");
    }

    private ProSubscriptionEntity subscription(ProSubscriptionStatus status, String customerId) {
        ProSubscriptionEntity sub = new ProSubscriptionEntity();
        sub.setUserId(USER_ID);
        sub.setStatus(status);
        sub.setSource(ProSubscriptionSource.STRIPE);
        sub.setStripeCustomerId(customerId);
        return sub;
    }

    @Test
    @DisplayName("sans Price configuré, la souscription échoue en 503 plutôt qu'au démarrage")
    void unconfiguredPricesFailCleanly() {
        StripeBillingService service = new StripeBillingService(repository, unconfigured());

        assertThatThrownBy(() -> service.createCheckoutSession(USER_ID, BillingCycle.MONTHLY))
                .isInstanceOf(YadonyBusinessException.class)
                .hasMessageContaining("billing");
    }

    @Test
    @DisplayName("un abonnement déjà actif refuse une seconde souscription")
    void alreadyActiveIsRejected() {
        when(repository.findByUserId(USER_ID))
                .thenReturn(Optional.of(subscription(ProSubscriptionStatus.ACTIVE, "cus_1")));
        StripeBillingService service = new StripeBillingService(repository, configured());

        assertThatThrownBy(() -> service.createCheckoutSession(USER_ID, BillingCycle.MONTHLY))
                .isInstanceOf(YadonyBusinessException.class);
    }

    @Test
    @DisplayName("un compte en grâce historique peut souscrire : c'est le but")
    void legacyGraceCanSubscribe() {
        when(repository.findByUserId(USER_ID))
                .thenReturn(Optional.of(subscription(ProSubscriptionStatus.LEGACY_GRACE, null)));
        StripeBillingService service = new StripeBillingService(repository, configured());

        // L'appel réel à Stripe échouera faute de clé d'API en test : on vérifie
        // seulement que la garde métier ne bloque pas ce cas, contrairement au
        // précédent. Toute exception levée ici ne doit pas être un 409 métier.
        assertThatThrownBy(() -> service.createCheckoutSession(USER_ID, BillingCycle.MONTHLY))
                .isNotInstanceOf(YadonyBusinessException.class);
    }

    @Test
    @DisplayName("sans client Stripe connu, l'accès au portail est refusé")
    void portalWithoutCustomerIsRejected() {
        when(repository.findByUserId(USER_ID))
                .thenReturn(Optional.of(subscription(ProSubscriptionStatus.LEGACY_GRACE, null)));
        StripeBillingService service = new StripeBillingService(repository, configured());

        assertThatThrownBy(() -> service.createPortalSession(USER_ID))
                .isInstanceOf(YadonyBusinessException.class);
    }

    @Test
    @DisplayName("sans abonnement du tout, l'accès au portail est refusé")
    void portalWithoutSubscriptionIsRejected() {
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        StripeBillingService service = new StripeBillingService(repository, configured());

        assertThatThrownBy(() -> service.createPortalSession(USER_ID))
                .isInstanceOf(YadonyBusinessException.class);
    }
}
```

- [ ] **Étape 4 : Lancer le test et vérifier qu'il échoue**

Commande : `./mvnw test -Dtest=StripeBillingServiceTest`
Attendu : ÉCHEC de compilation, `StripeBillingService` n'existe pas.

- [ ] **Étape 5 : Écrire le service**

`src/main/java/com/yadony/api/billing/StripeBillingService.java` :

```java
package com.yadony.api.billing;

import com.stripe.exception.StripeException;
import com.yadony.api.common.YadonyBusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Création des sessions Stripe Checkout et Customer Portal.
 *
 * <p>La clé d'API est posée globalement par {@code StripeConfig.init()}, comme
 * ailleurs dans le projet : les appels statiques du SDK l'utilisent.
 */
@Service
public class StripeBillingService {

    private static final Logger log = LoggerFactory.getLogger(StripeBillingService.class);

    private final ProSubscriptionRepository repository;
    private final BillingProperties properties;

    public StripeBillingService(ProSubscriptionRepository repository,
                                BillingProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * Ouvre une session Checkout pour l'utilisateur.
     *
     * <p>L'identifiant utilisateur voyage dans {@code client_reference_id} :
     * c'est lui que {@link ProBillingStripeWebhookHandler} lira pour rattacher
     * l'abonnement au bon compte.
     */
    public String createCheckoutSession(UUID userId, BillingCycle cycle) {
        if (!properties.stripePricesConfigured()) {
            throw new YadonyBusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "billing-not-configured", "Billing Unavailable",
                    "L'abonnement PRO n'est pas encore disponible.");
        }

        repository.findByUserId(userId).ifPresent(sub -> {
            if (sub.getStatus() == ProSubscriptionStatus.ACTIVE
                    || sub.getStatus() == ProSubscriptionStatus.PAST_DUE) {
                throw new YadonyBusinessException(HttpStatus.CONFLICT,
                        "subscription-already-active", "Already Subscribed",
                        "Vous avez déjà un abonnement PRO en cours.");
            }
        });

        String existingCustomerId = repository.findByUserId(userId)
                .map(ProSubscriptionEntity::getStripeCustomerId)
                .orElse(null);

        try {
            com.stripe.param.checkout.SessionCreateParams.Builder params =
                    com.stripe.param.checkout.SessionCreateParams.builder()
                            .setMode(com.stripe.param.checkout.SessionCreateParams.Mode.SUBSCRIPTION)
                            .setSuccessUrl(properties.successUrl())
                            .setCancelUrl(properties.cancelUrl())
                            .setClientReferenceId(userId.toString())
                            .putMetadata("billing_cycle", cycle.name())
                            .setSubscriptionData(
                                    com.stripe.param.checkout.SessionCreateParams.SubscriptionData.builder()
                                            .putMetadata("billing_cycle", cycle.name())
                                            .putMetadata("user_id", userId.toString())
                                            .build())
                            .addLineItem(
                                    com.stripe.param.checkout.SessionCreateParams.LineItem.builder()
                                            .setPrice(properties.priceFor(cycle))
                                            .setQuantity(1L)
                                            .build());

            // Réutiliser le client Stripe existant évite d'en créer un second
            // au réabonnement, et conserve l'historique de facturation.
            if (existingCustomerId != null && !existingCustomerId.isBlank()) {
                params.setCustomer(existingCustomerId);
            }

            com.stripe.model.checkout.Session session =
                    com.stripe.model.checkout.Session.create(params.build());
            log.info("Checkout session {} created for user {} ({})",
                    session.getId(), userId, cycle);
            return session.getUrl();
        } catch (StripeException e) {
            log.error("Stripe refused the checkout session for user {}: {}", userId, e.getMessage());
            throw new IllegalStateException("Stripe checkout session creation failed", e);
        }
    }

    /** Ouvre une session Customer Portal pour gérer carte et résiliation. */
    public String createPortalSession(UUID userId) {
        String customerId = repository.findByUserId(userId)
                .map(ProSubscriptionEntity::getStripeCustomerId)
                .filter(id -> !id.isBlank())
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "no-stripe-customer", "No Billing Account",
                        "Aucun abonnement payant n'est rattaché à ce compte."));

        try {
            com.stripe.param.billingportal.SessionCreateParams params =
                    com.stripe.param.billingportal.SessionCreateParams.builder()
                            .setCustomer(customerId)
                            .setReturnUrl(properties.portalReturnUrl())
                            .build();

            com.stripe.model.billingportal.Session session =
                    com.stripe.model.billingportal.Session.create(params);
            log.info("Portal session created for user {}", userId);
            return session.getUrl();
        } catch (StripeException e) {
            log.error("Stripe refused the portal session for user {}: {}", userId, e.getMessage());
            throw new IllegalStateException("Stripe portal session creation failed", e);
        }
    }
}
```

- [ ] **Étape 6 : Relancer le test du service**

Commande : `./mvnw test -Dtest=StripeBillingServiceTest`
Attendu : SUCCÈS, 5 tests.

- [ ] **Étape 7 : Écrire le contrôleur**

`src/main/java/com/yadony/api/billing/BillingController.java` :

```java
package com.yadony.api.billing;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.billing.dto.CheckoutSessionResponse;
import com.yadony.api.billing.dto.PortalSessionResponse;
import com.yadony.api.billing.dto.ProSubscriptionResponse;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.stripe.StripeWebhookIngestService;
import com.yadony.api.common.stripe.StripeWebhookSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/billing")
public class BillingController {

    private final StripeBillingService stripeBillingService;
    private final ProSubscriptionRepository repository;
    private final UserRepository userRepository;
    private final StripeWebhookIngestService ingestService;

    public BillingController(StripeBillingService stripeBillingService,
                             ProSubscriptionRepository repository,
                             UserRepository userRepository,
                             StripeWebhookIngestService ingestService) {
        this.stripeBillingService = stripeBillingService;
        this.repository = repository;
        this.userRepository = userRepository;
        this.ingestService = ingestService;
    }

    @PostMapping("/checkout-session")
    public ResponseEntity<CheckoutSessionResponse> createCheckoutSession(
            Authentication authentication,
            @RequestParam(name = "cycle", defaultValue = "MONTHLY") BillingCycle cycle) {
        UUID userId = currentUserId(authentication);
        String url = stripeBillingService.createCheckoutSession(userId, cycle);
        return ResponseEntity.ok(new CheckoutSessionResponse(url));
    }

    @PostMapping("/portal-session")
    public ResponseEntity<PortalSessionResponse> createPortalSession(Authentication authentication) {
        UUID userId = currentUserId(authentication);
        String url = stripeBillingService.createPortalSession(userId);
        return ResponseEntity.ok(new PortalSessionResponse(url));
    }

    @GetMapping("/subscription")
    public ResponseEntity<ProSubscriptionResponse> getSubscription(Authentication authentication) {
        UUID userId = currentUserId(authentication);
        return repository.findByUserId(userId)
                .map(sub -> ResponseEntity.ok(new ProSubscriptionResponse(
                        sub.getStatus().grantsProAccess(),
                        sub.getStatus().name(),
                        sub.getSource().name(),
                        sub.getBillingCycle() == null ? null : sub.getBillingCycle().name(),
                        sub.getCurrentPeriodEnd(),
                        sub.isCancelAtPeriodEnd(),
                        sub.getGraceExpiresAt())))
                .orElseGet(() -> ResponseEntity.ok(new ProSubscriptionResponse(
                        false, "NONE", null, null, null, false, null)));
    }

    /** Endpoint public — la signature est vérifiée par StripeWebhookIngestService. */
    @PostMapping("/webhook")
    public ResponseEntity<Void> handleWebhook(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String sigHeader) {
        ingestService.ingest(payload, sigHeader, StripeWebhookSource.BILLING);
        return ResponseEntity.ok().build();
    }

    /**
     * Résout l'utilisateur courant depuis le principal Spring Security, qui est
     * le firebaseUid. Aucun identifiant utilisateur n'est jamais accepté du
     * client : ce serait laisser souscrire ou résilier pour autrui.
     */
    private UUID currentUserId(Authentication authentication) {
        String firebaseUid = authentication.getName();
        return userRepository.findByFirebaseUid(firebaseUid)
                .map(UserEntity::getId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.UNAUTHORIZED,
                        "unknown-user", "Unknown User", "Utilisateur introuvable"));
    }
}
```

> Vérifier la signature réelle de `UserRepository.findByFirebaseUid` avant d'écrire : si elle ne retourne pas un `Optional`, adapter l'appel au code réel plutôt que l'inverse.

- [ ] **Étape 8 : Écrire le test d'intégration du contrôleur**

`src/test/java/com/yadony/api/billing/BillingControllerIntegrationTest.java` :

```java
package com.yadony.api.billing;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.kyc.KycStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("BillingController")
class BillingControllerIntegrationTest {

    private static final String FIREBASE_UID = "uid-billing-ctrl-001";

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired ProSubscriptionRepository subscriptionRepository;

    @MockitoBean FirebaseContactService firebaseContact;

    private UUID userId;

    @BeforeEach
    void setUp() {
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();

        UserEntity user = new UserEntity();
        user.setFirebaseUid(FIREBASE_UID);
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(Set.of(Role.TRAVELER));
        user.setCountry("FR");
        userId = userRepository.save(user).getId();

        org.mockito.Mockito.lenient().when(firebaseContact.getContact(FIREBASE_UID))
                .thenReturn(new FirebaseContactService.Contact("+33612000009", null));
    }

    private UsernamePasswordAuthenticationToken authenticated() {
        return new UsernamePasswordAuthenticationToken(
                FIREBASE_UID, null, List.of(new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    @Test
    @DisplayName("sans abonnement, GET /billing/subscription répond NONE et inactif")
    void subscriptionAbsentReturnsNone() throws Exception {
        mockMvc.perform(get("/billing/subscription").with(authentication(authenticated())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.status").value("NONE"));
    }

    @Test
    @DisplayName("avec une grâce en cours, GET /billing/subscription l'expose comme active")
    void subscriptionInGraceIsActive() throws Exception {
        ProSubscriptionEntity sub = new ProSubscriptionEntity();
        sub.setUserId(userId);
        sub.setStatus(ProSubscriptionStatus.LEGACY_GRACE);
        sub.setSource(ProSubscriptionSource.LEGACY_FREE);
        sub.setGraceExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
        subscriptionRepository.save(sub);

        mockMvc.perform(get("/billing/subscription").with(authentication(authenticated())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.status").value("LEGACY_GRACE"))
                .andExpect(jsonPath("$.graceExpiresAt").exists());
    }

    @Test
    @DisplayName("sans authentification, les endpoints d'abonnement sont refusés")
    void endpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/billing/subscription"))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(post("/billing/portal-session"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("le webhook est public mais refuse une signature invalide")
    void webhookIsPublicButRejectsBadSignature() throws Exception {
        mockMvc.perform(post("/billing/webhook")
                        .header("Stripe-Signature", "t=1,v1=invalide")
                        .content("{\"id\":\"evt_x\",\"type\":\"invoice.paid\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("sans client Stripe rattaché, le portail répond 404")
    void portalWithoutCustomerReturns404() throws Exception {
        mockMvc.perform(post("/billing/portal-session").with(authentication(authenticated())))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Étape 9 : Lancer le test d'intégration**

Commande : `./mvnw test -Dtest=BillingControllerIntegrationTest`
Attendu : SUCCÈS, 5 tests.

Le test « signature invalide » atteste que l'endpoint est bien public — s'il était protégé, il répondrait 401 ou 403 avant d'atteindre la vérification de signature.

- [ ] **Étape 10 : Commit**

```bash
git add src/main/java/com/yadony/api/billing src/main/resources/application.yml src/main/resources/application-dev.yml src/main/resources/application-prod.yml src/test/java/com/yadony/api/billing
git commit -m "feat(billing): endpoints Checkout, Customer Portal et statut d'abonnement"
```

---

## Task 5 : L'upgrade gratuit cesse d'accorder le statut PRO

**Fichiers :**
- Modifier : `src/main/java/com/yadony/api/auth/UserService.java`
- Test : `src/test/java/com/yadony/api/auth/UserServiceUpgradeToProTest.java`
- Vérifier : `src/test/java/com/yadony/api/auth/AuthControllerUpgradeToProIntegrationTest.java`
- Vérifier : les scénarios Cucumber portant sur le passage en PRO

### Ce qui change et pourquoi

`POST /auth/me/upgrade-to-pro` accordait le statut PRO gratuitement. Il ne doit plus que **mettre à jour le profil professionnel** — raison sociale et SIRET. L'accès PRO ne s'obtient désormais que par Stripe Checkout.

`downgradePro` reste inchangé : un utilisateur doit pouvoir renoncer à son profil professionnel, et le lot 1 a branché la fermeture d'abonnement correspondante.

### Note d'ordre de déploiement

Entre ce lot et le lot 6, l'application mobile affiche encore un écran « Passer en compte PRO » qui appellera cet endpoint sans plus rien débloquer. C'est l'état final voulu — le paiement se fait sur le web — mais l'écran mobile devient trompeur dans l'intervalle. Deux options : livrer les lots 2 et 6 rapprochés, ou accepter la fenêtre. À signaler dans la documentation de story.

- [ ] **Étape 1 : Écrire le test**

`src/test/java/com/yadony/api/auth/UserServiceUpgradeToProTest.java` :

```java
package com.yadony.api.auth;

import com.yadony.api.auth.dto.UpgradeToProRequest;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService.upgradeToPro — ne donne plus le statut PRO")
class UserServiceUpgradeToProTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock UserRepository userRepository;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;

    private UserEntity user(boolean pro) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", USER_ID);
        u.setProAccount(pro);
        return u;
    }

    @Test
    @DisplayName("un compte standard renseigne son profil sans devenir PRO")
    void doesNotGrantProStatus() {
        UserEntity u = user(false);
        when(userRepository.save(u)).thenReturn(u);

        service().upgradeToPro(u, new UpgradeToProRequest("Yadony SARL", "12345678901234"));

        assertThat(u.isProAccount())
                .as("le statut PRO s'obtient désormais par abonnement payant")
                .isFalse();
        assertThat(u.getProCompanyName()).isEqualTo("Yadony SARL");
        verify(eventPublisher, never()).publishEvent(any(UserProStatusChangedEvent.class));
    }

    @Test
    @DisplayName("un compte déjà PRO garde son statut en mettant à jour son profil")
    void keepsExistingProStatus() {
        UserEntity u = user(true);
        when(userRepository.save(u)).thenReturn(u);

        service().upgradeToPro(u, new UpgradeToProRequest("Yadony SAS", null));

        assertThat(u.isProAccount())
                .as("un abonné payant ne doit pas perdre son accès en corrigeant sa raison sociale")
                .isTrue();
        assertThat(u.getProCompanyName()).isEqualTo("Yadony SAS");
    }

    @Test
    @DisplayName("un SIRET mal formé reste refusé")
    void invalidSiretStillRejected() {
        assertThatThrownBy(() ->
                service().upgradeToPro(user(false), new UpgradeToProRequest("X", "123")))
                .isInstanceOf(YadonyBusinessException.class);
    }
}
```

> Le constructeur réel de `UserService` prend davantage de dépendances que les trois mockées ici. Lire la classe et compléter la méthode `service()` en conséquence, en mockant ce qui est nécessaire. Ne pas modifier `UserService` pour simplifier son instanciation en test.

- [ ] **Étape 2 : Lancer le test et vérifier qu'il échoue**

Commande : `./mvnw test -Dtest=UserServiceUpgradeToProTest`
Attendu : ÉCHEC — `doesNotGrantProStatus` échoue puisque la méthode accorde encore le statut.

- [ ] **Étape 3 : Modifier `upgradeToPro`**

Dans `src/main/java/com/yadony/api/auth/UserService.java`, retirer l'attribution du statut et la publication d'événement, en conservant la validation du SIRET et la journalisation :

```java
    /**
     * Met à jour le profil professionnel : raison sociale et SIRET.
     *
     * <p>Cette méthode n'accorde plus le statut PRO — il s'obtient uniquement
     * par un abonnement payant, via Stripe Checkout depuis le portail web.
     * Le drapeau {@code isProAccount} est désormais piloté exclusivement par
     * {@code billing/ProAccessSynchronizer}.
     */
    @Transactional
    public UserEntity upgradeToPro(UserEntity user, UpgradeToProRequest request) {
        UUID userId = user.getId();

        if (request.siret() != null && !request.siret().isBlank()) {
            if (!request.siret().matches("\\d{14}")) {
                throw new YadonyBusinessException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "invalid-siret",
                        "Invalid SIRET",
                        "Le numéro SIRET doit contenir exactement 14 chiffres"
                );
            }
        }

        user.setProCompanyName(request.companyName());
        user.setProSiret(request.siret());
        UserEntity saved = userRepository.save(user);

        auditService.log("USER", userId, "USER_PRO_PROFILE_UPDATED", userId,
                Map.of("companyName", request.companyName() != null ? request.companyName() : "",
                        "siret", request.siret() != null ? request.siret() : ""));

        log.info("User {} PRO profile updated (companyName, siret)", userId);
        return saved;
    }
```

- [ ] **Étape 4 : Relancer le test**

Commande : `./mvnw test -Dtest=UserServiceUpgradeToProTest`
Attendu : SUCCÈS, 3 tests.

- [ ] **Étape 5 : Réparer les tests que ce changement casse**

`AuthControllerUpgradeToProIntegrationTest` affirme `isProAccount = true` après appel : cette assertion **doit être retournée**, pas supprimée. Elle documentait l'ancien comportement ; elle doit maintenant documenter le nouveau — le profil est mis à jour et le statut reste faux.

Chercher aussi les scénarios Cucumber concernés :

```bash
rtk proxy grep -rn "upgrade-to-pro\|compte PRO" src/test/resources/features/ src/test/java --include=*.feature --include=*Steps.java
```

Adapter leurs assertions au nouveau comportement, en préservant leur intention. Un scénario « passage en PRO puis retour au standard » qui n'assertait qu'un code HTTP 200 continue de passer sans modification.

- [ ] **Étape 6 : Lancer la suite complète**

Commande : `./mvnw test`
Attendu : SUCCÈS. C'est le seul moment où l'effet de ce changement sur l'ensemble du dépôt apparaît. Rapporter le résultat réel, y compris en cas d'échec, sans le contourner.

- [ ] **Étape 7 : Vérifier la couverture**

Commande : `./mvnw test jacoco:report`
Vérifier le package `com.yadony.api.billing` dans `target/site/jacoco/com.yadony.api.billing/index.html`, **et le pourcentage par classe** pour chaque classe créée dans ce lot. Un trou de couverture par classe a déjà échappé au lot 1 parce que seul le chiffre global avait été regardé.

- [ ] **Étape 8 : Commit**

```bash
git add src/main/java/com/yadony/api/auth/UserService.java src/test/java/com/yadony/api/auth
git commit -m "feat(billing): l'upgrade gratuit ne donne plus le statut PRO"
```

---

## Vérification de fin de lot

- [ ] `./mvnw test` passe intégralement
- [ ] Couverture du package `billing/` ≥ 90 %, vérifiée aussi par classe
- [ ] `/billing/webhook` figure bien dans la liste `permitAll` de `SecurityConfig`
- [ ] Le `switch` de `StripeWebhookIngestService` est exhaustif, sans branche `default`
- [ ] Aucune table d'idempotence n'a été ajoutée
- [ ] **Documentation de story** : `docs/stories-done/story-billing-lot2-stripe.md`, selon le gabarit du `CLAUDE.md`. Y consigner impérativement : les objets Stripe à créer dans le dashboard, la fenêtre entre lot 2 et lot 6 où l'écran mobile devient trompeur, et le fait que `processed_stripe_events` prescrit par le `CLAUDE.md` n'existe plus.

## Après ce lot, avant d'activer l'ordonnanceur

`yadony.billing.scheduler-enabled` peut passer à `true` **une fois ce lot déployé et le parcours de paiement vérifié sur un compte réel**. Deux dettes du lot 1 doivent être réglées avant cette activation, elles sont documentées dans `story-billing-lot1-fondation.md` :

1. `ProSubscriptionScheduler` enveloppe tout son lot dans une seule transaction : une ligne en échec annule tout le passage, et l'échec se reproduit chaque nuit. Passer à une transaction par ligne.
2. Le downgrade automatique n'écrit aucune entrée `audit_log` sous `entity_type = 'USER'`, contrairement au downgrade manuel : un administrateur consultant la piste d'audit d'un utilisateur ne verrait jamais un downgrade automatique.

## Hors périmètre

- Interface web `dony-pro` (lot 5) et application mobile (lot 6)
- Octroi administrateur (lot 3) et bonus de matching (lot 4)
- Changement de formule ou de cycle par une interface sur mesure : le Customer Portal Stripe suffit
- Preuve de bout en bout contre un vrai compte Stripe : à faire manuellement, avec la CLI Stripe, avant activation en production
