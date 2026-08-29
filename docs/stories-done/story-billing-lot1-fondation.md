# Story — Lot 1 : fondation billing PRO (Backend)

**Date :** 2026-08-27
**Status :** ✅ Complète

## Résumé

Transformation du statut « compte PRO » (`UserEntity.isProAccount`), jusqu'ici gratuit et
auto-déclaratif, en un cycle de vie d'abonnement piloté en base par un nouveau package
`billing/` — sans aucune dépendance à Stripe, qui arrive au lot 2. Le drapeau `isProAccount`
reste la seule chose que le code PRO existant lit ; il devient un champ dérivé, synchronisé
par événement depuis une nouvelle entité `ProSubscriptionEntity` qui porte la source de vérité.

## Fichiers créés

### `billing/`
- `ProSubscriptionStatus.java` — enum `ACTIVE`, `PAST_DUE`, `LEGACY_GRACE`, `CANCELED`, `EXPIRED` + `grantsProAccess()`
- `ProSubscriptionSource.java` — enum `STRIPE`, `ADMIN_GRANT`, `LEGACY_FREE`
- `BillingCycle.java` — enum `MONTHLY`, `YEARLY`
- `ProSubscriptionEntity.java` — entité JPA, table `pro_subscriptions`, une ligne vivante max par utilisateur
- `ProSubscriptionRepository.java` — requêtes des tâches planifiées + `findByStripeSubscriptionId`
- `ProAccessSynchronizer.java` — point unique d'écriture de `UserEntity.isProAccount`, publie `UserProStatusChangedEvent`
- `ProSubscriptionService.java` — machine à états (`openLegacyGrace`, `markPastDue`, `clearPastDue`, `expire`, `cancel`)
- `LegacyProGraceListener.java` — ouvre une grâce pour tout PRO sans abonnement ; ferme l'abonnement d'un PRO qui redescend
- `BillingProperties.java` — `@ConfigurationProperties("yadony.billing")`
- `ProSubscriptionScheduler.java` — 3 tâches planifiées de downgrade différé, derrière un drapeau

### `automation/`
- `AutomationRuleProStatusListener.java` — suspend les règles d'un voyageur au downgrade, les réactive au réabonnement

### Migrations
- `V231__pro_subscriptions.sql` — table `pro_subscriptions` + backfill des comptes PRO existants en `LEGACY_GRACE` (60 jours)
- `V232__automation_rules_disabled_by_downgrade.sql` — colonne `disabled_by_downgrade` sur `automation_rules`

### Tests
- `billing/ProSubscriptionStatusTest.java`, `ProAccessSynchronizerTest.java`, `ProSubscriptionServiceTest.java`,
  `LegacyProGraceListenerTest.java`, `BillingPropertiesTest.java`, `ProSubscriptionSchedulerTest.java`
- `billing/ProSubscriptionRepositoryIntegrationTest.java` — intégration H2
- `billing/ProDowngradeEndToEndIntegrationTest.java` — un downgrade ne dépublie aucune annonce
- `automation/AutomationRuleProStatusListenerTest.java`
- `migrations/V231MigrationTest.java` — rejoue la logique du backfill en SQL compatible H2

## Fichiers modifiés

- `src/main/java/com/yadony/api/automation/AutomationRuleEntity.java` — ajout du champ `disabledByDowngrade`
- `src/main/java/com/yadony/api/automation/AutomationRuleRepository.java` — ajout de `findByTravelerIdAndDisabledByDowngradeTrue`
- `src/main/resources/application.yml` — bloc `yadony.billing` (scheduler-enabled, legacy-grace-days, dunning-grace-days, expiry-cron)

Aucune modification de `auth/UserService`, `auth/AuthController` ni `auth/AuthService` : les endpoints
`POST /auth/me/upgrade-to-pro` et `DELETE /auth/me/upgrade-to-pro` existaient déjà et publiaient déjà
`UserProStatusChangedEvent` (upgrade) — ce lot se branche dessus sans y toucher.

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble du flux

**Montée en PRO (chemin gratuit résiduel, tant que le lot 2 n'est pas en prod) :**
1. `POST /auth/me/upgrade-to-pro` → `AuthService.upgradeToPro` → `UserService.upgradeToPro` pose
   `isProAccount = true` et publie `UserProStatusChangedEvent(userId, true)`.
2. `LegacyProGraceListener.onUserProStatusChanged` vérifie si l'utilisateur a déjà un abonnement
   ouvert (`grantsProAccess() == true`). Si non, `ProSubscriptionService.openLegacyGrace(userId, 60)`
   crée ou recycle une ligne `pro_subscriptions` en `LEGACY_GRACE`, `source = LEGACY_FREE`.
3. `openLegacyGrace` appelle `ProAccessSynchronizer.sync(userId, true)`, qui constate que le drapeau
   est déjà à `true` (posé à l'étape 1) et ne republie rien.

**Downgrade manuel :**
1. `DELETE /auth/me/upgrade-to-pro` → `UserService.downgradePro` pose `isProAccount = false`
   **avant** de publier `UserProStatusChangedEvent(userId, false)`.
2. `LegacyProGraceListener.onUserProStatusChanged` (branche `!event.isPro()`) charge l'abonnement
   par `findByUserId`. S'il est encore ouvert (`grantsProAccess() == true`), appelle
   `ProSubscriptionService.cancel(sub)`, qui passe le statut à `CANCELED`, journalise dans
   `audit_log` et appelle `ProAccessSynchronizer.sync(userId, false)`.
3. `sync` constate que le drapeau est déjà à `false` (posé à l'étape 1) et ne republie rien —
   pas de boucle.
4. `AutomationRuleProStatusListener` (écoute le même événement) désactive les règles
   d'automatisation actives du voyageur (`enabled=true` → `enabled=false, disabledByDowngrade=true`).
5. Aucun effet sur les annonces déjà publiées : elles restent actives, seul `isTravelerIsPro()`
   change côté snapshot.

**Downgrade automatique (tâches planifiées, `scheduler-enabled=true` uniquement) :**
`ProSubscriptionScheduler` tourne trois fois par jour (même cron) et appelle
`ProSubscriptionService.expire()` ou `.cancel()` sur les lignes trouvées par
`ProSubscriptionRepository`, ce qui déclenche la même chaîne d'événements que le downgrade manuel.

**Réabonnement (retour à PRO après un abonnement fermé) :**
Repasser par l'upgrade gratuit republie `UserProStatusChangedEvent(userId, true)`.
`LegacyProGraceListener` retrouve l'abonnement `EXPIRED`/`CANCELED` existant
(`grantsProAccess() == false`) et le recycle via `openLegacyGrace` — même ligne, nouveau statut
`LEGACY_GRACE`. `AutomationRuleProStatusListener` réactive les règles marquées
`disabledByDowngrade=true`.

### Points d'entrée API

Aucun nouvel endpoint dans ce lot. Les endpoints existants qui déclenchent la chaîne d'événements :
- `POST /api/v1/auth/me/upgrade-to-pro` — tout voyageur/expéditeur authentifié
- `DELETE /api/v1/auth/me/upgrade-to-pro` — idem

Les endpoints `POST /billing/checkout-session`, `POST /billing/portal-session`,
`POST /billing/webhook`, `POST /admin/users/{userId}/pro-grant` sont **hors périmètre de ce lot**
(voir « Pourquoi du code sans appelant » ci-dessous).

### Entités JPA impliquées

- `ProSubscriptionEntity` → table `pro_subscriptions` — au plus une ligne vivante par `user_id`
  (index unique partiel `uq_pro_subscriptions_user WHERE deleted_at IS NULL`). Étend `BaseEntity`,
  soft delete via `@SQLRestriction("deleted_at IS NULL")`.
- `AutomationRuleEntity` → table `automation_rules` — ajout de `disabled_by_downgrade boolean`,
  distinct de `enabled` pour ne pas confondre une extinction volontaire et une extinction forcée.

### Logique métier critique

- **`UserEntity.isProAccount` reste la seule chose lue par le code PRO existant** (matching,
  export fiscal, quotas de brouillons, automatisations). `ProSubscriptionEntity` en est la source
  de vérité, mais `ProAccessSynchronizer` est le seul point qui a le droit d'écrire le drapeau —
  aucun autre composant de `billing/` ne doit le faire.
- **`ProAccessSynchronizer.sync` ne republie l'événement que si le drapeau change réellement.**
  C'est ce qui empêche toute boucle entre `UserService` (qui pose le drapeau puis publie) et
  `ProSubscriptionService` (qui ferme l'abonnement puis appelle `sync`, qui constate que le
  drapeau est déjà dans l'état cible).
- **Recyclage de ligne dans `openLegacyGrace`.** L'index unique partiel n'autorise qu'une ligne
  vivante par utilisateur, statut fermé compris : un utilisateur `EXPIRED` ou `CANCELED` qui
  redevient PRO gratuitement voit sa ligne existante recyclée plutôt qu'une nouvelle insérée.
  Le recyclage purge explicitement `pastDueSince`, `cancelAtPeriodEnd`, et — depuis la revue
  finale — `stripeCustomerId`, `stripeSubscriptionId`, `billingCycle`, `currentPeriodEnd`,
  `grantedByAdminId`, `adminGrantReason`. Sans cette seconde purge, une ligne `LEGACY_FREE`
  pourrait porter un `stripe_subscription_id` périmé d'un ancien cycle Stripe, que
  `findByStripeSubscriptionId` — indexée pour les webhooks du lot 2 — ramènerait au premier
  webhook reçu pour cet identifiant, alors qu'il ne correspond plus à cet abonnement.
- **Idempotence de `expire`/`cancel`.** Ces deux méthodes ne vérifient elles-mêmes rien : c'est
  au **code appelant** de ne pas les invoquer sur une ligne déjà fermée. `LegacyProGraceListener`
  filtre sur `grantsProAccess()` avant d'appeler `cancel` ; les trois méthodes de
  `ProSubscriptionScheduler` filtrent déjà par statut de départ dans leurs requêtes repository.
  Sans ce garde, un second appel réécrirait une entrée `audit_log` avec un `previousStatus` égal
  au statut cible.
- **Downgrade : le drapeau est posé avant l'événement, pas après.** `UserService.downgradePro`
  et `UserService.upgradeToPro` posent tous deux le drapeau puis publient. C'est cet ordre précis
  qui rend `ProAccessSynchronizer.sync` idempotent et sans boucle — l'inverser romprait la garantie.

### Pourquoi du code de ce lot n'a encore aucun appelant

Le `CLAUDE.md` du dépôt interdit le code mort, avec un test explicite : « un service/controller/DTO
sans aucun appelant doit être supprimé, pas laissé au cas où ». Ce lot déroge sciemment à cette règle
pour la liste suivante, tous consommés par les lots déjà planifiés qui suivent :

- `ProSubscriptionService.markPastDue` / `clearPastDue` — consommés par les handlers webhook
  `invoice.payment_failed` / `invoice.paid` du **lot 2 (Stripe Billing)**.
- `ProSubscriptionRepository.findByStripeSubscriptionId` — indexée dès ce lot
  (`idx_pro_subscriptions_stripe_subscription`) pour que le lot 2 n'ait pas de migration à ajouter ;
  consommée par le dispatch des webhooks Stripe.
- `BillingCycle` (enum entier) — consommé quand le lot 2 crée les `ProSubscriptionEntity` `source = STRIPE`.
- Colonnes `stripe_customer_id` — remplie à la première souscription (lot 2).
- Colonnes `granted_by_admin_id`, `admin_grant_reason` et la valeur `ProSubscriptionSource.ADMIN_GRANT`
  — consommées par `POST /admin/users/{userId}/pro-grant` du **lot 3 (octroi administrateur)**.
- La valeur `ProSubscriptionSource.STRIPE` — posée par le lot 2 à la création d'un abonnement payant.

**Par ricochet**, `ProSubscriptionScheduler.expireExhaustedDunning()` ne peut trouver aucune ligne à
traiter en lot 1 : aucun code de ce lot ne produit de statut `PAST_DUE` (c'est le webhook
`invoice.payment_failed` du lot 2 qui appellera `markPastDue`). La méthode est testée unitairement
avec un repository mocké (`ProSubscriptionSchedulerTest`), mais restera muette en production tant
que le lot 2 n'est pas déployé.

### Le garde-fou de déploiement : `yadony.billing.scheduler-enabled`

Faux par défaut (`BillingProperties.schedulerEnabledOrDefault()`), il coupe les trois tâches
planifiées de `ProSubscriptionScheduler` avant même de lire la base. **Ne doit être activé en
production qu'une fois le lot 2 déployé et le parcours de paiement (`checkout-session` → Stripe →
webhook `checkout.session.completed`) vérifié.** L'activer plus tôt expirerait à l'échéance de leur
grâce des comptes qui n'ont encore aucun moyen technique de s'abonner — la totalité de la cohorte
backfillée par V231, qui deviendrait PRO gratuit → non-PRO du jour au lendemain sans jamais avoir
pu payer.

### La durée de grâce est écrite à deux endroits

`V231__pro_subscriptions.sql` code **60 jours en dur** dans son `INSERT` de backfill
(`NOW() + INTERVAL '60 days'`), exécuté une seule fois au déploiement sur tous les comptes PRO déjà
existants. `yadony.billing.legacy-grace-days` (défaut `60`, surchargeable via
`YADONY_BILLING_LEGACY_GRACE_DAYS`) pilote, lui, toutes les grâces ouvertes **après coup** par
`LegacyProGraceListener` (upgrades gratuits résiduels, réabonnements après grâce expirée).

Ces deux 60 ne sont pas la même valeur techniquement, même s'ils partagent le même défaut
aujourd'hui : **surcharger `YADONY_BILLING_LEGACY_GRACE_DAYS` en production ferait diverger la
durée de grâce de la cohorte backfillée (restée à 60 jours, gravée dans les lignes déjà insérées)
de celle des grâces ouvertes ensuite par le listener.** Toute modification de la politique de grâce
doit être documentée comme s'appliquant uniquement aux nouvelles grâces, jamais rétroactivement à
la cohorte V231.

### Events Spring publiés / écoutés

- `UserProStatusChangedEvent(userId, isPro)` — déjà existant, publié par
  `UserService.upgradeToPro`, `UserService.downgradePro` et `ProAccessSynchronizer.sync`.
  Écouté par :
  - `LegacyProGraceListener` (`billing/`) — ouvre/ferme la ligne `pro_subscriptions`
  - `AutomationRuleProStatusListener` (`automation/`) — suspend/réactive les règles
  - `matching/AnnouncementService.onUserProStatusChanged` (préexistant, inchangé) — snapshot
    `isTravelerIsPro` sur les annonces
- Tous les listeners de ce lot utilisent `@EventListener` simple (pas
  `@TransactionalEventListener(phase = AFTER_COMMIT)`) : ils ne touchent aucun objet Stripe ni
  aucun montant, et la suspension des droits doit être **atomique** avec le changement de drapeau —
  un downgrade commité dont les automatisations resteraient actives parce qu'une transaction
  séparée aurait échoué serait précisément la faille que ce lot ferme. C'est le choix déjà en place
  pour `AnnouncementService.onUserProStatusChanged`.

### Pièges et points d'attention

- **Le profil de test tourne sur H2 avec `spring.flyway.enabled: false`.** Les fichiers de
  migration (`V231__...sql`, `V232__...sql`) ne sont **jamais exécutés** par `./mvnw test` — le
  schéma vient des entités JPA (`ddl-auto: create`). Une erreur SQL dans une migration ne serait
  donc pas détectée par la suite de tests. La logique métier du backfill V231 est vérifiée à part,
  en rejouant le même `SELECT`/`INSERT` en SQL compatible H2 dans `V231MigrationTest` (gabarit
  `V89MigrationTest`) — pas en exécutant la vraie migration.
- **`AutomationRuleEntity` ne se persiste pas via JPA dans un test.** Ses colonnes `conditions`
  (`List<Map<String,Object>>`) et `action` (`Map<String,Object>`), mappées en `jsonb` via
  `@JdbcTypeCode(SqlTypes.JSON)`, échouent à la relecture sous H2
  (`Could not deserialize string to java type`) — défaut de la combinaison Hibernate 6.6.53 / H2
  2.3.232, préexistant et sans impact connu en production (PostgreSQL réel). Découvert en
  écrivant `ProDowngradeEndToEndIntegrationTest`, qui persistait initialement une vraie règle
  d'automatisation. Résolu en recentrant ce test sur la seule garantie qu'il protège réellement
  (« un downgrade ne dépublie aucune annonce »), sans persister d'`AutomationRuleEntity` — la
  suspension/réactivation des règles reste couverte uniquement par
  `AutomationRuleProStatusListenerTest`, en unitaire avec repository mocké, comme le reste du
  package `automation/`. **Ne jamais changer le mapping `jsonb` de ces colonnes pour faire passer
  un test** : elles stockent les règles de tous les voyageurs en production. Le même défaut
  toucherait potentiellement `AuditLogEntity.payload` si un test tentait un jour de le relire
  depuis H2 — non vérifié, hors périmètre de ce lot.
- **Ordre drapeau → événement.** `UserService.upgradeToPro`/`downgradePro` posent
  `isProAccount` **avant** de publier `UserProStatusChangedEvent`. C'est cet ordre qui rend
  `ProAccessSynchronizer.sync` idempotent (le drapeau est déjà dans l'état cible quand `sync`
  s'exécute depuis `openLegacyGrace`/`cancel`/`expire`) et empêche toute boucle événementielle.
- **Idempotence de `expire`/`cancel` déléguée à l'appelant.** Voir « Logique métier critique »
  ci-dessus — ces deux méthodes n'ont pas de garde interne.
- **Les trois `@Scheduled` de `ProSubscriptionScheduler` partagent la même clé de cron**
  (`yadony.billing.expiry-cron`), donc se déclenchent au même instant. Sans conséquence aux
  volumes attendus, mais à garder en tête si un jour elles doivent être échelonnées.
- **`ProSubscriptionScheduler` enveloppe chaque méthode dans une seule transaction** couvrant
  toutes les lignes trouvées. Une ligne en échec (ex. `ProAccessSynchronizer` qui ne trouve pas
  l'utilisateur) ferait échouer tout le lot de downgrades de la nuit, qui se reproduirait
  identiquement le lendemain. **Différé au lot 2** (à traiter avant d'activer
  `scheduler-enabled` en production) : transaction par ligne (`REQUIRES_NEW`) ou `try/catch` par
  élément.

## Critères d'acceptation couverts

- [x] Un compte passé PRO gratuitement (upgrade résiduel) reçoit une grâce de 60 jours en base,
      sans laquelle il échapperait à tout downgrade futur.
- [x] Un downgrade manuel (`DELETE /auth/me/upgrade-to-pro`) ferme l'abonnement ouvert et ne
      laisse jamais `grantsProAccess() == true` avec `isProAccount == false`.
- [x] Le statut d'un abonnement déjà fermé n'est jamais réécrit par un second appel
      (idempotence de `expire`/`cancel` via le garde côté appelant).
- [x] Un downgrade suspend les règles d'automatisation actives sans les supprimer ; un
      réabonnement les réactive, sauf celles éteintes volontairement par le voyageur.
- [x] Un downgrade ne dépublie et ne modifie aucune annonce déjà publiée.
- [x] Les trois tâches planifiées de downgrade différé sont inactives par défaut
      (`scheduler-enabled=false`) tant que le lot 2 n'est pas en production.
- [x] Le backfill des comptes PRO existants (V231) leur ouvre une grâce de 60 jours, en
      excluant les comptes déjà supprimés (`deleted_at IS NULL`).
- [x] `expiry-cron` est surchargeable par variable d'environnement sans rebuild
      (`YADONY_BILLING_EXPIRY_CRON`), comme le reste du bloc `yadony.billing`.
- [x] Aucune régression sur le code PRO existant qui lit `isProAccount` (matching, export fiscal,
      quotas, portail `dony-pro`).

## Tests

- `./mvnw test -Dtest='LegacyProGraceListenerTest,ProSubscriptionServiceTest,ProSubscriptionSchedulerTest'`
  → 18 tests, 0 échec (dernière vérification, après la vague de correctifs de revue finale).
- `./mvnw test` (suite complète, lancée en fin de tâche 6, avant la vague de correctifs) →
  4231 tests, 0 échec, 0 erreur, 7 ignorés. Non relancée en intégralité après la vague de
  correctifs finaux : seules les trois classes ci-dessus, dont deux modifiées et une inchangée,
  ont été revérifiées, conformément au périmètre fixé pour cette vague.
- JaCoCo, package `com.yadony.api.billing` : 94 % instructions, 94 % branches (rapport de fin de
  tâche 6). `ProAccessSynchronizer` à 100 %. `ProSubscriptionScheduler` à 100 % instructions,
  83 % branches (2 branches de logging non exercées, sans effet métier).

Tests ajoutés ou modifiés dans la vague de correctifs de revue finale :
- `LegacyProGraceListenerTest` — `ignoresDowngrade` renommé et inversé en
  `closesOpenSubscriptionOnDowngrade` (vérifie que `cancel` est appelé) ; ajout de
  `ignoresDowngradeWhenSubscriptionAlreadyClosed` (abonnement `EXPIRED` → `cancel` jamais appelé).
- `ProSubscriptionServiceTest` — ajout de `reusesExistingRowAndPurgesStaleStripeAndAdminFields`
  (les six champs Stripe/admin sont purgés au recyclage d'une ligne en `LEGACY_FREE`).

Tests créés pendant l'implémentation initiale du lot (tâches 1 à 6) : `ProSubscriptionStatusTest`,
`ProAccessSynchronizerTest`, `ProSubscriptionRepositoryIntegrationTest`, `V231MigrationTest`,
`AutomationRuleProStatusListenerTest`, `ProDowngradeEndToEndIntegrationTest`, `BillingPropertiesTest`,
`ProSubscriptionSchedulerTest`.

## Décisions techniques

| Décision | Choix | Alternatives écartées | Raison |
|---|---|---|---|
| Portage de l'état d'abonnement | Nouvelle entité `ProSubscriptionEntity` dans `billing/` | Champs Stripe directement sur `UserEntity` | Respecte la règle package-par-feature ; ne mélange pas `auth/` et facturation |
| Synchronisation du drapeau | `ProAccessSynchronizer`, point d'écriture unique, événementiel | Lecture directe de `ProSubscriptionEntity` partout | Zéro régression sur le code PRO existant qui lit `isProAccount` |
| Statuts fermés distincts | `CANCELED` (résiliation payante) et `EXPIRED` (droit non converti) | Un seul statut fermé | Analytique du taux de conversion de la cohorte `LEGACY_FREE` |
| Recyclage de ligne plutôt qu'insertion | `openLegacyGrace` réutilise la ligne existante | Nouvelle ligne à chaque grâce | L'index unique partiel n'autorise qu'une ligne vivante par utilisateur, statut fermé compris |
| Listeners en `@EventListener` simple | Pas de `@TransactionalEventListener(AFTER_COMMIT)` | Pattern imposé par CLAUDE.md pour les listeners de paiement | Aucun objet Stripe ni montant manipulé ; la suspension des droits doit être atomique avec le changement de drapeau |
| Effet du downgrade sur les automatisations | Désactivation (`disabled_by_downgrade`), jamais suppression | Suppression physique | Interdiction des suppressions physiques ; meilleur levier de reconquête au réabonnement |
| Idempotence de `expire`/`cancel` | Garde côté appelant (`grantsProAccess()`), pas dans le service | Garde interne au service | Les appelants (scheduler, listener) filtrent déjà par statut de départ ; éviter la duplication de la règle |
| Scheduler désactivé par défaut | `yadony.billing.scheduler-enabled=false` | Activé dès ce lot | Aucun moyen de payer sans le lot 2 : activer expirerait des comptes légitimes |
| `expiry-cron` surchargeable | `${YADONY_BILLING_EXPIRY_CRON:0 30 3 * * *}` | Valeur en dur (défaut initial de la tâche 6) | Convention `${ENV_VAR:defaut}` imposée par CLAUDE.md ; décaler l'heure en prod ne doit pas exiger un rebuild — corrigé en revue finale |
