# Story — Lot 3 : octroi administrateur d'un accès PRO (Backend)

**Date :** 2026-08-27
**Status :** ✅ Complète

## Résumé

Suite directe de `story-billing-lot1-fondation.md` (machine à états `ProSubscriptionEntity`)
et `story-billing-lot2-stripe.md` (paiement Stripe). Ce lot ajoute le troisième et dernier
producteur de la source `ProSubscriptionSource.ADMIN_GRANT`, posée dès la migration V231 du
lot 1 mais restée sans code jusqu'ici : un administrateur peut offrir un accès PRO gratuit
(partenariat, geste commercial) et le révoquer, sans jamais toucher à Stripe. Ce document ne
répète pas ce que couvrent les deux lots précédents — voir leurs docs pour la fondation et le
paiement.

## Fichiers créés

- `src/main/java/com/yadony/api/admin/dto/ProGrantRequest.java` — motif de l'octroi
  (`@NotBlank`, `@Size(max = 500)`, aligné sur la colonne `admin_grant_reason`)
- `src/main/java/com/yadony/api/billing/dto/AdminProSubscriptionView.java` — état d'abonnement
  exposé à l'administration (`status`, `source`, `billingCycle`, `currentPeriodEnd`,
  `cancelAtPeriodEnd`, `graceExpiresAt`, `stripeSubscriptionId`, `grantedByAdminId`,
  `adminGrantReason`, `grantedAt`), fabrique statique `from(ProSubscriptionEntity)`
- `src/main/resources/db/migration/V233__pro_subscriptions_granted_at.sql` — colonne
  `granted_at TIMESTAMPTZ` nullable sur `pro_subscriptions`
- Tests : `ProSubscriptionServiceAdminGrantTest`, `ProSubscriptionServiceRevokeAdminGrantTest`,
  `AdminProGrantControllerIT`, `AdminUserDetailResponseTest` (le fichier n'existait pas)

## Fichiers modifiés

- `src/main/java/com/yadony/api/billing/ProSubscriptionService.java` — deux méthodes
  ajoutées : `grantByAdmin(UUID userId, UUID adminId, String reason)` et
  `revokeAdminGrant(UUID userId, UUID adminId)`
- `src/main/java/com/yadony/api/billing/ProSubscriptionEntity.java` — champ `grantedAt`
  (colonne `granted_at`)
- `src/main/java/com/yadony/api/admin/account/AdminPermission.java` — ajout de
  `USER_PRO_GRANT` (30 → 31 valeurs)
- `src/main/java/com/yadony/api/admin/account/AdminRole.java` — non modifié directement, mais
  reçoit `USER_PRO_GRANT` par héritage silencieux (voir « Logique métier critique »)
- `src/main/java/com/yadony/api/admin/AdminUserController.java` — deux endpoints
  (`grantPro`, `revokePro`), deux dépendances de constructeur ajoutées
  (`ProSubscriptionService`, `ProSubscriptionRepository`), helper `detail(...)` branché sur
  la nouvelle surcharge à trois arguments
- `src/main/java/com/yadony/api/admin/dto/AdminUserDetailResponse.java` — nouveau champ
  `proSubscription` en **dernière position** du record ; la surcharge à deux arguments
  `from(UserEntity, Contact)` a été **supprimée** en revue finale (code mort, plus aucun
  appelant après le branchement de `detail(...)` sur la version à trois arguments)
- `src/test/java/com/yadony/api/admin/account/AdminPermissionsLotBTest.java` et
  `AdminPermissionsLotDTest.java` — sentinelles d'effectif mises à jour de 30 à 31 (régression
  de la tâche 2, révélée seulement par la suite complète de la tâche 3, voir « Pièges »)
- `src/test/java/com/yadony/api/admin/account/AdminRolePermissionsTest.java` — ajout de
  `onlyAdminsHaveProGrant`, qui rend explicite la décision sur les rôles

**Aucune migration au sens du plan initial** — V233 a été ajoutée en cours de route pendant
la vague de correctifs finale (voir plus bas), le plan prévoyait initialement zéro migration.

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble du flux

**Octroi :**
1. `POST /admin/users/{userId}/pro-grant` avec `{ "reason": "..." }` →
   `AdminUserController.grantPro` résout d'abord `userRepository.findById(userId)` et lève un
   404 **avant tout appel au service** (voir « Pièges »).
2. `ProSubscriptionService.grantByAdmin(userId, adminId, reason)` recycle la ligne
   `pro_subscriptions` existante ou en crée une (`findByUserId(...).orElseGet(new)`), refuse
   en 409 (`active-stripe-subscription`) si un abonnement Stripe encore vivant couvre déjà
   l'utilisateur, sinon pose `status = ACTIVE`, `source = ADMIN_GRANT`, `grantedByAdminId`,
   `adminGrantReason`, `grantedAt = Instant.now()`, purge les résidus d'un cycle précédent,
   sauvegarde, puis synchronise l'accès (`ProAccessSynchronizer.sync(userId, true)`) et
   journalise `BILLING_ADMIN_GRANTED`.
3. Le contrôleur recharge l'utilisateur et retourne `AdminUserDetailResponse` à jour
   (`isProAccount = true`, `proSubscription` rempli).

**Révocation :**
1. `DELETE /admin/users/{userId}/pro-grant` → `AdminUserController.revokePro` délègue
   entièrement à `ProSubscriptionService.revokeAdminGrant(userId, adminId)`.
2. `revokeAdminGrant` charge la ligne (404 `no-subscription` si absente), refuse en 409
   (`not-an-admin-grant`) si `source != ADMIN_GRANT`, refuse en 409 (`already-revoked`) si le
   statut ne donne déjà plus l'accès, sinon passe `status = CANCELED`, synchronise l'accès à
   `false` et journalise `BILLING_ADMIN_GRANT_REVOKED` avec l'administrateur comme acteur.
3. Le contrôleur recharge l'utilisateur et retourne `AdminUserDetailResponse` à jour.

### Points d'entrée API

- `POST /admin/users/{userId}/pro-grant` — corps `{ "reason": string }` obligatoire (422 si
  vide ou > 500 caractères), `hasRole('ADMIN') and hasAuthority('USER_PRO_GRANT')`
- `DELETE /admin/users/{userId}/pro-grant` — aucun corps, même permission

### Entités JPA impliquées

- `ProSubscriptionEntity` → table `pro_subscriptions`, colonnes touchées par ce lot :
  `granted_by_admin_id`, `admin_grant_reason` (posées par la migration V231 du lot 1, restées
  sans producteur jusqu'à ce lot) et `granted_at` (V233, ajoutée par ce lot). Toujours au plus
  une ligne vivante par `user_id` — voir point 8 ci-dessous.

### Logique métier critique

**1. Trois gardes symétriques contre la désynchronisation avec Stripe.** Le dépôt en porte
désormais trois, à comprendre comme un même principe appliqué trois fois :
- `UserService.downgradePro` (lot 2) refuse qu'un utilisateur renonce à un abonnement payant
  encore ouvert via `DELETE /auth/me/upgrade-to-pro`.
- `ProSubscriptionService.revokeAdminGrant` (ce lot) refuse qu'un administrateur ferme une
  ligne dont la source est `STRIPE`.
- `ProSubscriptionService.grantByAdmin` (ce lot) refuse d'offrir un accès à un utilisateur
  dont l'abonnement Stripe est encore vivant (`source == STRIPE && status.grantsProAccess()`).

La troisième n'était pas dans le plan initial : elle a été trouvée en revue finale (bloquant
B1 du journal). Sans elle, un abonné Stripe `ACTIVE` recevant un octroi administrateur voyait
sa ligne recyclée et purgée — `stripeSubscriptionId` mis à `null` — pendant que Stripe
continuait de le prélever normalement (l'octroi n'annule rien côté Stripe), **et** perdait
tout accès au Customer Portal, puisque `StripeBillingService.createPortalSession` exige un
`stripeCustomerId` non nul pour fonctionner. Un utilisateur dans cet état ne pouvait alors
plus résilier lui-même. Principe commun aux trois gardes : **toute écriture qui change la
source d'un abonnement doit d'abord vérifier qu'elle ne rend pas orphelin un abonnement
Stripe vivant.**

**2. L'acteur d'audit d'une révocation administrateur.** `ProSubscriptionService.close(...)`
(appelée par `cancel()` et `expire()`) journalise **l'utilisateur** comme acteur — correct
pour ses appelants système existants : le cron de dunning, le webhook
`customer.subscription.deleted`, ou le renoncement de l'utilisateur lui-même via
`downgradePro`. Le plan initial de ce lot prévoyait de réutiliser `cancel()` telle quelle pour
la révocation administrateur ; en revue finale (bloquant B2), on a constaté que cela produisait
une entrée `audit_log` journalisant la **cible** comme acteur, indiscernable d'un renoncement
volontaire de l'utilisateur — aucune trace de quel administrateur avait révoqué. D'où
`revokeAdminGrant`, une méthode dédiée qui journalise `BILLING_ADMIN_GRANT_REVOKED` avec
l'administrateur comme acteur. `audit_log` étant immuable, une entrée faussement attribuée
n'aurait jamais pu être corrigée après coup.

**3. Le motif libre n'entre jamais dans `audit_log`.** Doctrine du dépôt, déjà suivie par
`AdminUserDeletionService` : cette table est immuable, et un texte saisi par un administrateur
y graverait définitivement d'éventuelles données personnelles. Le motif vit dans la colonne
`admin_grant_reason` — modifiable, soft-deletable — et jamais dans le payload d'audit.
`AuditService` redacte automatiquement les clés PII connues, mais **pas** `reason` : c'est à
l'appelant de ne jamais la lui passer. `grantByAdmin` et `revokeAdminGrant` ne journalisent que
`Map.of("targetUserId", userId.toString())`.

**4. Une propriété de sécurité du dépôt, à connaître avant d'ajouter la prochaine
permission.** `AdminRole.permissions()` définit `SUPER_ADMIN` par `EnumSet.allOf` et `ADMIN`
par `EnumSet.complementOf(EnumSet.of(ADMIN_MANAGE))`. Toute nouvelle valeur d'`AdminPermission`
est donc accordée **automatiquement** à ces deux rôles, sans qu'aucune ligne de code ne
l'exprime — c'est ainsi que `USER_PRO_GRANT` leur revient sans jamais apparaître dans
`AdminRole`, exactement comme `USER_COMMISSION` avant elle. Le seul contrepoids est constitué
des tests-sentinelles `AdminPermissionsLotBTest` et `AdminPermissionsLotDTest`, qui assertent
un effectif exact (`AdminPermission.values()`) et échouent à chaque changement d'enum — ils
doivent être mis à jour en connaissance de cause à chaque ajout, jamais neutralisés ni
transformés en `>=`. Pour toute permission qu'on voudrait au contraire **exclure** de
`SUPER_ADMIN`/`ADMIN`, il faudra l'ajouter explicitement au `complementOf` (ou construire
`ADMIN` autrement) — l'oubli serait silencieux.

Leçon de process attachée : une modification de l'enum `AdminPermission` exige de relancer la
**suite complète**, jamais un run ciblé. La tâche 2 de ce lot a ajouté `USER_PRO_GRANT` et n'a
lancé que des classes ciblées (`AdminProGrantControllerIT`, `AdminUserControllerTest`,
`AdminRolePermissionsTest`) — toutes vertes. La régression (les deux sentinelles passées de 30
à 31 attendu) n'a été révélée qu'à la tâche 3, en lançant `./mvnw test` pour de tout autres
raisons.

**5. L'ordre d'écriture dans les créateurs de ligne.** `repository.save(...)` doit précéder
`accessSynchronizer.sync(...)`, dans `grantByAdmin` comme dans `openLegacyGrace` et
`activateFromStripe` (lots 1 et 2). `LegacyProGraceListener` écoute
`UserProStatusChangedEvent(isPro=true)` et ouvre une `LEGACY_GRACE` si aucun abonnement ne
couvre déjà l'utilisateur au moment où il réagit. Si `sync` était appelé avant `save`, le
listener ne verrait pas encore la ligne `ADMIN_GRANT` fraîchement écrite et écraserait
l'octroi par une grâce historique.

**6. Un octroi administrateur n'a pas d'échéance**, et aucune tâche planifiée ne le ferme.
Les trois requêtes de `ProSubscriptionRepository` consommées par `ProSubscriptionScheduler`
sont `findByStatusAndGraceExpiresAtBefore`, `findByStatusAndPastDueSinceBefore` et
`findByStatusAndCancelAtPeriodEndTrueAndCurrentPeriodEndBefore` — `grantByAdmin` ne pose
aucun de ces trois champs (`graceExpiresAt`, `pastDueSince`, `cancelAtPeriodEnd` sont tous
purgés à `null`/`false`). Un octroi court donc jusqu'à révocation explicite par un
administrateur, ou jusqu'au renoncement de l'utilisateur.

**7. `stripeCustomerId` est conservé, `stripeSubscriptionId` est purgé.** Distinction posée
en revue finale (correctif 6 du journal) : le plan initial prévoyait de purger les deux. Le
premier permet de réutiliser le même Customer Stripe si l'utilisateur se réabonne un jour, et
évite de fragmenter son historique de facturation ; le second **doit** disparaître, sinon
`ProSubscriptionRepository.findByStripeSubscriptionId` — indexée pour le dispatch des webhooks
du lot 2 — le retrouverait au prochain événement Stripe portant cet identifiant et ferait
piloter cette ligne (devenue `ADMIN_GRANT`) par un abonnement qui n'est plus le sien. Même
règle dans `openLegacyGrace` (lot 1). `activateFromStripe`, lui, n'a jamais purgé
`stripeCustomerId` : il l'écrase systématiquement avec l'identifiant reçu du webhook —
aucun changement n'y était nécessaire.

**8. Un seul abonnement vivant par utilisateur.** L'index partiel `uq_pro_subscriptions_user`
n'autorise qu'une ligne vivante par `user_id`, statuts fermés compris. Les trois créateurs
(`openLegacyGrace`, `activateFromStripe`, `grantByAdmin`) recyclent donc systématiquement la
ligne existante via `findByUserId(...).orElseGet(ProSubscriptionEntity::new)` plutôt que d'en
insérer une nouvelle. **Cet index n'existe pas sous le profil de test H2** (`ddl-auto:
create`, migrations non exécutées) : aucun test ne peut démontrer qu'il empêcherait une
double insertion. Vérifié par lecture du schéma, pas par un test.

### Events Spring publiés / écoutés

Aucun nouvel événement Spring. Ce lot réutilise `UserProStatusChangedEvent`, déjà central aux
lots 1 et 2 : `grantByAdmin` et `revokeAdminGrant` le publient indirectement via
`ProAccessSynchronizer.sync(userId, true|false)`, avec les mêmes écouteurs déjà documentés
(`LegacyProGraceListener`, `AutomationRuleProStatusListener`).

### Pièges et points d'attention

- **`grantPro` vérifie l'existence de la cible avant toute mutation.** Trouvé en revue finale
  (bloquant B3) : la première version appelait `grantByAdmin` avant de vérifier que
  `userId` correspondait à un utilisateur réel. Sur un utilisateur soft-deleted (la ligne
  `users` reste physiquement présente, seul `deleted_at` est posé), la FK passait quand même
  et laissait une ligne `pro_subscriptions` `ACTIVE` fantôme, gravée dans `audit_log`, sans
  qu'`isProAccount` ne bouge — et qu'aucune tâche planifiée ne balaierait jamais (voir point
  6). Sur un UUID complètement inconnu, l'ancien comportement remontait un 500 au lieu d'un
  404 propre. Le contrôleur résout désormais `userRepository.findById(userId)` en tête et
  lève le 404 avant tout appel au service. Un second `findById` après l'appel reste
  nécessaire pour construire la réponse : le premier objet chargé ne refléterait pas
  `isProAccount`, mis à jour par `ProAccessSynchronizer` dans un flux distinct.
- **`AdminUserDetailResponse.from(UserEntity, Contact)` (surcharge à deux arguments) a été
  supprimée**, et non conservée comme le prévoyait le plan initial. Le plan la justifiait par
  « neuf appelants » — c'était vrai au moment de la planification, mais périmé dès que
  `AdminUserController.detail(...)` a basculé sur la surcharge à trois arguments : à la fin
  du lot, plus aucun appelant en production ne l'utilisait. Elle a été supprimée avec son
  test associé en revue finale, comme code mort au sens de la règle du dépôt. Leçon générale :
  une justification de conception peut expirer à l'intérieur du lot même qui l'a écrite —
  vérifier par grep au moment de conclure, pas seulement au moment de planifier.
- **Le nouveau champ `proSubscription` est en dernière position** du record
  `AdminUserDetailResponse` (27 composantes) : l'ajouter au milieu aurait cassé silencieusement
  tout appel positionnel existant.
- **`AdminPermissionCoverageTest`** échoue si une valeur d'`AdminPermission` n'est citée par
  aucune `@PreAuthorize` du dépôt — garde-fou distinct des sentinelles d'effectif du point 4,
  qui vérifie la couverture plutôt que le compte.
- **Une `@PreAuthorize` de méthode remplace celle de classe, elle ne s'y ajoute pas.** D'où la
  forme répétée `hasRole('ADMIN') and hasAuthority('USER_PRO_GRANT')` sur `grantPro` et
  `revokePro` : l'omettre sur l'un des deux ouvrirait cet endpoint à tout administrateur, quelle
  que soit sa permission.
- **Convention de verbe HTTP du dépôt** : `POST /{userId}/pro-grant` (un corps est nécessaire
  pour le motif) et `DELETE /{userId}/pro-grant` (aucun corps) — même patron que
  `POST /{userId}/delete` pour la suppression de compte.
- **`AdminProSubscriptionView` expose les identifiants Stripe** (`stripeSubscriptionId`),
  contrairement au DTO exposé à l'utilisateur final (`ProSubscriptionResponse`, lot 2) : un
  administrateur en a besoin pour rapprocher une ligne d'un abonnement dans le dashboard
  Stripe. Ne pas dupliquer ce champ dans une réponse orientée utilisateur.

## Critères d'acceptation couverts

- [x] Un administrateur peut offrir un accès PRO gratuit avec un motif obligatoire —
      `POST /admin/users/{userId}/pro-grant`, 422 si motif vide ou > 500 caractères.
- [x] L'octroi rend le compte PRO immédiatement (`isProAccount = true`), sans échéance.
- [x] Un administrateur peut révoquer un accès offert —
      `DELETE /admin/users/{userId}/pro-grant`.
- [x] La révocation est journalisée avec l'administrateur comme acteur, jamais la cible.
- [x] Le motif libre n'apparaît jamais dans `audit_log`.
- [x] Un administrateur ne peut pas offrir un accès à un abonné Stripe payant vivant — 409
      `active-stripe-subscription`.
- [x] Un administrateur ne peut pas révoquer un abonnement Stripe payant depuis ces
      endpoints — 409 `not-an-admin-grant`.
- [x] Une double révocation est refusée — 409 `already-revoked`, pas de doublon dans
      `audit_log`.
- [x] Offrir un accès à un utilisateur inexistant ou soft-deleted répond 404 sans mutation.
- [x] L'état d'abonnement (statut, source, date d'octroi, motif, administrateur) est exposé
      dans `AdminUserDetailResponse`.
- [x] Seuls les administrateurs porteurs de `USER_PRO_GRANT` (`SUPER_ADMIN`, `ADMIN`) peuvent
      appeler ces endpoints ; `SUPPORT` en est exclu.
- [x] Aucune régression sur les gardes symétriques posées au lot 2
      (`DELETE /auth/me/upgrade-to-pro` refuse toujours en 409 un abonnement Stripe vivant).

## Tests

- `./mvnw test` (suite complète, dernier lancement en fin de vague de correctifs finale,
  commit `2e9e0597`) → **4329 tests, 0 échec, 0 erreur, 7 ignorés, BUILD SUCCESS**.
- Couverture JaCoCo, mesure consolidée (`jacoco.exec` supprimé puis `test` + `jacoco:report`
  en une seule commande) : package `com.yadony.api.billing` **97 % instructions / 90 %
  branches** ; package `com.yadony.api.billing.dto` **100 % / 100 %**.
- Tests créés : `ProSubscriptionServiceAdminGrantTest` (7 tests, dont les gardes de
  non-régression `refusesGrantOverLiveStripeSubscription` et
  `grantAllowedOverClosedStripeSubscription`), `ProSubscriptionServiceRevokeAdminGrantTest`
  (4 tests : révocation nominale + audit sur l'admin, 404, 409 hors `ADMIN_GRANT`, 409 double
  révocation), `AdminProGrantControllerIT` (8 tests d'intégration MockMvc), et
  `AdminUserDetailResponseTest` (3 tests, le fichier n'existait pas avant ce lot).
- Tests modifiés : `ProSubscriptionServiceTest` (purge Stripe adaptée : `stripeCustomerId`
  conservé), `ProSubscriptionServiceStripeTest`, `AdminUserControllerTest` (délégation à
  `revokeAdminGrant`, vérification du 404 avant appel au service),
  `AdminRolePermissionsTest` (`onlyAdminsHaveProGrant`), `AdminPermissionsLotBTest` et
  `AdminPermissionsLotDTest` (effectif 30 → 31, régression de la tâche 2 corrigée en tâche 3).
- Chaque garde ajoutée en revue finale est testée par `verify(..., never())` sur les mocks
  correspondants (`save`, `sync`, `auditService.log`) — la re-revue a jugé ces tests
  discriminants : ils échoueraient si la garde était retirée.

## Décisions techniques

| Décision | Choix | Alternatives écartées | Raison |
|---|---|---|---|
| Permission dédiée | `USER_PRO_GRANT`, nouvelle valeur | Réutiliser `USER_COMMISSION` | Offrir un accès gratuit et ajuster un taux de commission sont deux pouvoirs distincts ; les confondre donnerait le premier à quiconque détient le second |
| Rôles porteurs | `SUPER_ADMIN`, `ADMIN` (par héritage `allOf`/`complementOf`), `SUPPORT` exclu | Câblage explicite dans `AdminRole` | Même portée que `USER_COMMISSION`, déjà accordée aux mêmes rôles par le même mécanisme ; rendu explicite a posteriori par un test-sentinelle dédié |
| Révocation : méthode dédiée `revokeAdminGrant` | Nouvelle méthode journalisant l'admin comme acteur | Réutiliser `cancel()` tel quel (plan initial) | `cancel()`/`close()` journalisent l'utilisateur comme acteur, correct pour les appelants système mais indiscernable d'un renoncement volontaire pour une action admin |
| Garde sur `grantByAdmin` | Refus 409 si abonnement Stripe vivant | Aucune garde (plan initial) | Image miroir du garde-fou de révocation ; sans elle, un abonné Stripe recevant un octroi perdait l'accès au Customer Portal tout en restant prélevé |
| Purge de `stripeCustomerId` | Conservé dans les trois créateurs (`openLegacyGrace`, `grantByAdmin`) | Purgé comme les autres champs Stripe (plan initial) | Permet de réutiliser le Customer Stripe existant au réabonnement, sans fragmenter l'historique de facturation |
| Date d'octroi | Nouveau champ `grantedAt` + migration V233 | `createdAt`/`updatedAt` de `BaseEntity` | `createdAt` date la création de la ligne recyclée, pas l'octroi ; `updatedAt` est écrasé par toute écriture ultérieure — ni l'un ni l'autre ne convient |
| Surcharge à deux arguments d'`AdminUserDetailResponse.from` | Supprimée | Conservée (plan initial, justifiée par « neuf appelants ») | Devenue code mort dès que `detail(...)` a basculé sur la surcharge à trois arguments ; le dépôt interdit le code sans appelant |
| Vérification de l'existence de la cible | `findById` en tête de `grantPro`, avant toute mutation | Laisser `grantByAdmin` échouer plus tard (comportement initial livré, corrigé en revue finale) | Sans elle, un utilisateur soft-deleted recevait une ligne `pro_subscriptions` fantôme jamais balayée, et un UUID inconnu remontait un 500 au lieu d'un 404 |
