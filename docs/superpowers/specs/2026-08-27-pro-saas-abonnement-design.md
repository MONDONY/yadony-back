# Compte PRO en SaaS payant — Design

**Date :** 2026-08-27
**Statut :** Validé, prêt pour plan d'implémentation
**Périmètre :** `dony-back/` (majeur), `dony-pro/` (majeur), `dony_app/` (mineur), `dony-admin/` (mineur)

---

## 1. Contexte et problème

Le statut « compte PRO » (`UserEntity.isProAccount`) existe déjà et est fonctionnellement riche, mais **il est gratuit et auto-déclaratif** : n'importe quel voyageur appelle `POST /auth/me/upgrade-to-pro` avec un nom de société et un SIRET optionnels, et obtient immédiatement l'accès à :

- les statistiques et analytics voyageur (`matching/TravelerStatsController`, `ProAnalyticsService`) ;
- l'export fiscal PDF/CSV DAC7 (`payments/FiscalExportController`) ;
- le moteur d'automatisations (`automation/AutomationRuleController`, `AutomationHistoryController`) ;
- des quotas de brouillons d'annonces relevés (`maxDraftsPro` vs `maxDrafts` dans `matching/AnnouncementService`) ;
- le portail web dédié `dony-pro/` (Nuxt), un produit complet réservé aux comptes PRO via `middleware/pro-only.ts`.

Aucune infrastructure de facturation n'est rattachée à ce statut. Le package `com.yadony.api.subscriptions` porte un nom trompeur : il implémente la fonctionnalité « un expéditeur s'abonne aux annonces d'un voyageur » (notifications de nouveaux trajets), sans aucun lien avec la monétisation.

**Objectif :** transformer ce flag gratuit en véritable abonnement SaaS payant, sans régresser sur le code PRO déjà en production.

---

## 2. Décisions produit

| Sujet | Décision |
|---|---|
| Nombre de paliers | **Un seul** palier PRO payant |
| Prix mensuel | **4,99 €** |
| Prix annuel | **47,90 €** (équivalent à 2 mois offerts, remise ~20 %) |
| Canal de paiement | **Web uniquement** (`dony-pro`, Stripe Checkout) |
| Comptes PRO gratuits existants | Période de grâce de **60 jours**, puis downgrade automatique |
| Échec de paiement au renouvellement | Stripe Smart Retries, accès maintenu, downgrade après épuisement du dunning |
| Résiliation utilisateur | Accès conservé **jusqu'à la fin de la période déjà payée** |
| Accès PRO offert par un admin | **Oui**, dès la v1, indépendant de Stripe |
| Service « boost IA » v1 | **Mise en avant du voyageur PRO dans le ranking de matching** |

### Choix du canal de paiement web-only

Apple impose l'usage de son In-App Purchase (commission 30 %) dès qu'un achat débloque des fonctionnalités dans une app iOS. En cantonnant la souscription au web (`dony-pro`), on adopte le modèle « reader app » utilisé par Netflix ou Spotify : l'app mobile n'affiche que le statut et renvoie vers le web. Cela évite à la fois la commission et le risque de rejet App Store, et supprime le chantier StoreKit 2 / Play Billing.

---

## 3. Architecture retenue

**Nouveau package `com.yadony.api.billing`, portant une entité `ProSubscriptionEntity` dédiée. `UserEntity.isProAccount` est conservé comme champ dérivé, mis à jour par événement.**

### Alternatives écartées

- **Tout porter sur `UserEntity`** (ajouter les champs Stripe directement dessus) : plus rapide à coder, mais viole la règle package-par-feature du projet et mélange les responsabilités `auth/` et facturation.
- **Table de plans génériques avec matrice de fonctionnalités** : sur-ingénierie pour un palier unique. Écarté au titre du YAGNI ; réintroductible si un second palier apparaît.

### Pourquoi cette approche

Tout le code existant qui teste `user.isProAccount()` (matching, export fiscal, automations, quotas, `StripeV2AccountProvisioner`) **reste inchangé**. Le package `billing/` publie `UserProStatusChangedEvent` (événement qui existe déjà, publié aujourd'hui par `UserService.upgradeToPro()`), et un listener met à jour le flag booléen sur `UserEntity`. Zéro régression sur les fonctionnalités PRO déjà en production, et respect de la règle « cross-package = Spring Application Events uniquement ».

---

## 4. Modèle de données

### `ProSubscriptionEntity`

Extends `BaseEntity` (UUID, `createdAt`, `updatedAt`, `deletedAt`, soft delete). Relation 1-1 avec `UserEntity` via `userId`.

| Champ | Type | Description |
|---|---|---|
| `userId` | UUID | Unique, référence `users` |
| `status` | enum | `ACTIVE`, `PAST_DUE`, `LEGACY_GRACE`, `CANCELED`, `EXPIRED` |
| `source` | enum | `STRIPE`, `ADMIN_GRANT`, `LEGACY_FREE` |
| `stripeCustomerId` | String | Nullable si `source != STRIPE` |
| `stripeSubscriptionId` | String | Nullable si `source != STRIPE` |
| `billingCycle` | enum | `MONTHLY`, `YEARLY`. Nullable si `source != STRIPE` |
| `currentPeriodEnd` | Instant | Nullable (illimité pour `ADMIN_GRANT`) |
| `cancelAtPeriodEnd` | boolean | Défaut `false` |
| `graceExpiresAt` | Instant | Utilisé pour `LEGACY_GRACE` uniquement |
| `grantedByAdminId` | UUID | Nullable, renseigné si `source = ADMIN_GRANT` |
| `adminGrantReason` | String | Nullable, obligatoire si `source = ADMIN_GRANT` |

### Statuts et flag dérivé

`UserEntity.isProAccount` vaut `true` si et seulement si le statut appartient à `{ACTIVE, PAST_DUE, LEGACY_GRACE}`.

`EXPIRED` et `CANCELED` sont distingués pour l'analytics (churn payant vs abandon en fin de grâce) mais traités identiquement par le gating.

### Idempotence des webhooks

Table légère `billing_processed_events` (`stripe_event_id` unique, `processed_at`). Stripe pouvant réémettre un même événement, tout webhook déjà traité est ignoré.

### Migration Flyway `V231__pro_subscriptions.sql`

> `V230__residence_address.sql` est la dernière migration sur `origin/main` au 2026-08-27. Renuméroter si d'autres migrations sont mergées entre-temps.

1. Création de la table `pro_subscriptions` et de `billing_processed_events`.
2. **Backfill** : chaque utilisateur ayant `is_pro_account = true` reçoit une ligne
   `source = LEGACY_FREE`, `status = LEGACY_GRACE`, `graceExpiresAt = <date de migration> + 60 jours`.

La valeur `LEGACY_FREE` est introduite plutôt que de réutiliser `ADMIN_GRANT` ou `STRIPE` : elle décrit honnêtement l'origine du droit (upgrade gratuit historique) et permet de suivre le taux de conversion de cette cohorte.

---

## 5. Intégration Stripe Billing

Stripe Connect (déjà en place) gère les **payouts vers les voyageurs** : argent sortant. Stripe Billing gère ici l'**encaissement des abonnements** : argent entrant vers yadony. Ce sont deux ensembles d'objets distincts sur le même compte plateforme, à ne pas confondre lors de l'implémentation.

### Objets Stripe

- Un **Product** `yadony-pro`, avec deux **Price** : mensuel 4,99 € et annuel 47,90 €. Créés une fois dans le dashboard Stripe, leurs identifiants vivent en configuration (variables d'environnement), jamais en base.
- Un **Customer** par voyageur abonné, créé à la première souscription, conservé dans `stripeCustomerId` et réutilisé en cas de réabonnement après annulation.

### Parcours de souscription

`POST /billing/checkout-session` crée une **Stripe Checkout Session** en mode `subscription` pour l'utilisateur authentifié, et retourne l'URL de redirection. Retour vers `dony-pro/parametres/abonnement?success=1`.

### Gestion de l'abonnement

`POST /billing/portal-session` crée une session **Stripe Customer Portal**. Le portail est configuré pour n'autoriser que la mise à jour du moyen de paiement et la résiliation ; le changement de plan est désactivé puisqu'il n'existe qu'un palier. Cela évite de construire une interface de facturation sur mesure.

### Webhooks

Nouvel endpoint **public** `POST /billing/webhook` (à ajouter à la liste des endpoints publics de `SecurityConfig`, aux côtés de `/kyc/webhook`). La signature `Stripe-Signature` est vérifiée systématiquement ; toute requête non signée valablement retourne 400.

| Événement Stripe | Effet |
|---|---|
| `checkout.session.completed` | Crée ou réactive la souscription : `status = ACTIVE`, `source = STRIPE` |
| `invoice.paid` | Met à jour `currentPeriodEnd` ; repasse en `ACTIVE` si l'état était `PAST_DUE` |
| `invoice.payment_failed` | Passe en `PAST_DUE`. Pas de downgrade : Stripe Smart Retries prend le relais |
| `customer.subscription.updated` avec `cancel_at_period_end = true` | Positionne `cancelAtPeriodEnd`, statut inchangé jusqu'à l'échéance |
| `customer.subscription.deleted` | Passe en `CANCELED` |

---

## 6. Machine à états

```
LEGACY_GRACE ──(souscription via Checkout)──▶ ACTIVE
     │
     └──(graceExpiresAt dépassé, cron)──────▶ EXPIRED

ACTIVE ──(invoice.payment_failed)──▶ PAST_DUE
   ▲                                    │
   └──────(invoice.paid)────────────────┤
                                        │
        (dunning épuisé, cron)          ▼
                                     EXPIRED

ACTIVE ──(customer.subscription.deleted)──────────────────▶ CANCELED
ACTIVE ──(cancelAtPeriodEnd et currentPeriodEnd dépassé)──▶ CANCELED

ADMIN_GRANT : ACTIVE jusqu'à révocation manuelle ─────────▶ CANCELED
```

### Tâches planifiées

Trois vérifications quotidiennes dans `billing/`, via Spring `@Scheduled` (pattern déjà employé pour la libération forcée des paiements à J+48) :

1. **Expiration de la grâce historique** : `LEGACY_GRACE` dont `graceExpiresAt < now` passe à `EXPIRED`.
2. **Expiration du dunning** : `PAST_DUE` depuis plus de 5 jours (durée configurable) sans résolution passe à `EXPIRED`.
3. **Fin de période résiliée** : `ACTIVE` avec `cancelAtPeriodEnd = true` et `currentPeriodEnd < now` passe à `CANCELED`. Filet de sécurité si le webhook `customer.subscription.deleted` a été manqué.

Chaque transition vers `EXPIRED` ou `CANCELED` publie `UserProStatusChangedEvent`, dont le listener repasse `isProAccount` à `false`.

---

## 7. Boost dans le matching

Le service de scoring de `matching/` lit `traveler.isProAccount()` — lecture directe du flag, sans événement, conformément au précédent déjà en place dans `AnnouncementService` et `BidService`.

Un **bonus de score additif** est appliqué aux voyageurs PRO actifs dans le classement des résultats de recherche et de matching côté expéditeur. Le choix d'un bonus additif plutôt que d'un remplacement du score de pertinence est délibéré : la pertinence pour l'expéditeur ne doit pas être dégradée par la monétisation.

La valeur du bonus est une constante configurable dans `application.yml`. Pas de table de configuration : inutile tant qu'il n'existe qu'un seul palier.

> **Note de vocabulaire.** Ce service est présenté côté produit comme un « boost IA ». Techniquement, il s'agit d'une pondération dans l'algorithme de scoring existant, sans appel à un modèle de langage. Cette distinction est notée ici pour éviter toute confusion lors de l'implémentation ou des évolutions futures.

---

## 8. Accès PRO offert par un administrateur

| Endpoint | Effet |
|---|---|
| `POST /admin/users/{userId}/pro-grant` | Crée ou réactive une souscription `source = ADMIN_GRANT`, `status = ACTIVE`, sans `currentPeriodEnd` (illimitée jusqu'à révocation). Renseigne `grantedByAdminId` et `adminGrantReason` (obligatoire) |
| `DELETE /admin/users/{userId}/pro-grant` | Révoque : passe en `CANCELED` |

Les deux sont protégés par `@PreAuthorize("hasRole('ADMIN')")`.

**Garde-fou :** la révocation est refusée si `source != ADMIN_GRANT`. Un administrateur ne peut pas annuler un abonnement Stripe payant depuis cet endpoint, sous peine de désynchroniser la base et Stripe ; il doit passer par le dashboard Stripe ou le Customer Portal.

Octroi et révocation créent une entrée `audit_log` (action sensible, cohérent avec les autres actions administrateur).

Le statut, la source, la date d'octroi et la raison sont exposés dans `AdminUserDetailResponse`. Côté interface `dony-admin`, une action « Offrir PRO » est ajoutée à la fiche utilisateur, avec champ raison obligatoire.

---

## 9. Frontend `dony-pro` (Nuxt)

- **`/upgrade`** — la page existe déjà comme cible de redirection du middleware `pro-only`. Elle devient la page de vente : présentation des deux formules (mensuelle et annuelle), appel à l'action qui déclenche `POST /billing/checkout-session` puis redirige vers Stripe.
- **`/parametres/abonnement`** (nouveau, dans `app/features/parametres/`) — affiche le statut courant (`ACTIVE`, `PAST_DUE`, `LEGACY_GRACE` avec jours restants, résiliation programmée) et un bouton « Gérer mon abonnement » qui appelle `POST /billing/portal-session` et redirige vers le portail Stripe.
- **Bannière globale** lorsque le statut est `PAST_DUE`, ou `LEGACY_GRACE` à moins de 7 jours de l'expiration, incitant à régulariser. Cohérent avec les messages incitatifs déjà présents dans `AnnouncementService`.
- `middleware/pro-only.ts` reste inchangé : il continue de lire `isProAccount`, maintenu à jour par le listener.

---

## 10. Frontend `dony_app` (Flutter)

- `upgrade_to_pro_screen.dart` — le parcours d'upgrade gratuit actuel disparaît. Il est remplacé par un écran informatif renvoyant vers `dony-pro/upgrade`, ouvert dans le **navigateur externe** et non dans une webview (Stripe interdit ses parcours de paiement en webview, contrainte déjà rencontrée sur ce projet).
- Bannière de statut (`PAST_DUE`, `LEGACY_GRACE`) avec lien vers `dony-pro/parametres/abonnement`.
- `UserModel.isProAccount` reste un booléen. Toute la logique de statut fin demeure côté backend et `dony-pro`.

---

## 11. Erreurs, sécurité, tests

### Erreurs

Toutes les erreurs passent par `GlobalExceptionHandler` au format RFC 7807 `ProblemDetail`, comme partout dans le projet. Exemple : `POST /billing/checkout-session` sur un compte déjà `ACTIVE` retourne 409.

### Sécurité

- Vérification obligatoire de la signature Stripe sur `/billing/webhook`, même pattern que `/kyc/webhook`.
- Idempotence des webhooks via `billing_processed_events`.
- `checkout-session` et `portal-session` opèrent toujours sur l'utilisateur authentifié courant. Aucun `userId` accepté en paramètre client.
- Aucune clé Stripe en dur : variables d'environnement uniquement.

### Tests

**Backend** (couverture ≥ 90 %) :
- unitaires sur la machine à états de `ProSubscriptionService` (toutes les transitions) ;
- intégration MockMvc sur le webhook : signature valide, signature invalide, événement dupliqué ;
- intégration sur les trois tâches planifiées : grâce expirée, dunning expiré, fin de période résiliée ;
- intégration sur les endpoints d'octroi administrateur, dont le refus de révocation d'un abonnement Stripe.

**`dony-pro`** (couverture ≥ 90 %) :
- Vitest sur le composant de statut d'abonnement (chaque statut affiché) ;
- Playwright sur le parcours d'upgrade, avec Stripe simulé.

**`dony_app`** : l'écran d'upgrade devient purement informatif, donc moins de logique qu'auparavant. Test widget de l'écran et de la bannière de statut.

---

## 12. Hors périmètre v1

Backlog explicite, à ne pas implémenter dans ce lot :

- paliers multiples (PRO / PRO+) ;
- achat in-app mobile (StoreKit 2, Play Billing) ;
- assistant IA de tarification et de négociation ;
- recommandations IA sur l'activité, au-delà des statistiques brutes déjà disponibles ;
- interface sur mesure de changement de formule ou de cycle : le Customer Portal Stripe suffit en v1.
