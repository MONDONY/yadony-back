# Compte PRO en SaaS payant — Découpage en lots

**Spec de référence :** `docs/superpowers/specs/2026-08-27-pro-saas-abonnement-design.md`
**Date :** 2026-08-27

Le spec couvre plusieurs sous-systèmes répartis sur 4 dépôts git distincts (`dony-back`, `dony-pro`, `dony_app`, `dony-admin`). Il ne peut pas être exécuté comme un plan unique : chaque lot ci-dessous livre du logiciel fonctionnel, testable et déployable seul, et fait l'objet de son propre plan détaillé.

---

## Vue d'ensemble

| Lot | Dépôt | Contenu | Dépend de | Plan |
|---|---|---|---|---|
| **1** | `dony-back` | Fondation : entité, migration V231 + backfill, machine à états, effets du downgrade, tâches planifiées | — | `2026-08-27-lot1-billing-fondation.md` |
| **2** | `dony-back` | Stripe Billing : Checkout, Customer Portal, webhooks | 1 | à écrire |
| **3** | `dony-back` + `dony-admin` | Octroi et révocation administrateur | 1 | à écrire |
| **4** | `dony-back` | Bonus de score PRO dans le matching | 1 | à écrire |
| **5** | `dony-pro` | Page de vente, gestion d'abonnement, bannières | 2 | à écrire |
| **6** | `dony_app` | Écran informatif et renvoi vers le web | 5 | à écrire |

```
Lot 1 ─┬─▶ Lot 2 ──▶ Lot 5 ──▶ Lot 6
       ├─▶ Lot 3
       └─▶ Lot 4
```

Les lots 3 et 4 sont indépendants l'un de l'autre et du chemin 2-5-6 : ils peuvent être menés en parallèle une fois le lot 1 mergé.

---

## Piège de déploiement à respecter absolument

Le lot 1 fait basculer tous les comptes PRO gratuits actuels en `LEGACY_GRACE` avec une échéance à 60 jours. Or **le moyen de payer n'existe qu'à partir du lot 2**.

Déployer le lot 1 seul avec ses tâches planifiées actives reviendrait à lancer un compte à rebours de downgrade sur des utilisateurs qui n'ont aucun moyen de s'abonner.

**Règle :** les trois tâches planifiées du lot 1 sont pilotées par le drapeau `yadony.billing.scheduler-enabled`, **désactivé par défaut**. Il n'est passé à `true` en production qu'une fois le lot 2 déployé et le parcours de paiement vérifié de bout en bout.

Le backfill lui-même est sans danger : il ne fait qu'inscrire une échéance en base, et 60 jours laissent une marge confortable pour livrer le lot 2.

---

## Découpage détaillé

### Lot 1 — Fondation billing (`dony-back`)

Modèle de souscription et cycle de vie complet, **sans aucune dépendance à Stripe**. Entièrement testable hors ligne.

- Enums `ProSubscriptionStatus`, `ProSubscriptionSource`, `BillingCycle`
- `ProSubscriptionEntity` + `ProSubscriptionRepository`
- Migration `V231__pro_subscriptions.sql` avec backfill des PRO existants
- `ProSubscriptionService` : machine à états et transitions
- `ProAccessSynchronizer` : met à jour `UserEntity.isProAccount` et publie `UserProStatusChangedEvent`
- `LegacyProGraceListener` : ferme la faille de la fenêtre lot 1 → lot 2 (voir plan)
- `AutomationRuleProStatusListener` dans `automation/` : désactive les règles au downgrade
- `BillingProperties` + trois tâches planifiées derrière le drapeau

**Livrable :** les comptes PRO ont un cycle de vie piloté, le downgrade coupe réellement les accès. Aucun changement visible pour l'utilisateur.

### Lot 2 — Stripe Billing (`dony-back`)

- `BillingController` : `POST /billing/checkout-session`, `POST /billing/portal-session`, `GET /billing/subscription`
- Webhook `POST /billing/webhook` branché sur `StripeWebhookIngestService` existant (nouvelle valeur `BILLING` dans `StripeWebhookSource`)
- Traitement asynchrone des événements via `StripeEventScheduler` existant
- `POST /auth/me/upgrade-to-pro` cesse d'accorder le statut PRO : il ne met plus à jour que le profil (raison sociale, SIRET)
- Activation du drapeau `scheduler-enabled` en production

**Livrable :** un voyageur peut réellement s'abonner et payer.

### Lot 3 — Octroi administrateur (`dony-back` + `dony-admin`)

- `POST` / `DELETE /admin/users/{userId}/pro-grant`, protégés par `@PreAuthorize("hasRole('ADMIN')")`
- Refus de révocation si `source != ADMIN_GRANT`
- Entrées `audit_log` sur octroi et révocation
- Exposition dans `AdminUserDetailResponse`
- Interface `dony-admin` : action « Offrir PRO » avec raison obligatoire

### Lot 4 — Bonus de matching (`dony-back`)

- Bonus additif de score pour les voyageurs PRO dans le classement de recherche
- Valeur configurable dans `application.yml`

Techniquement le plus petit lot. Il ne dépend du lot 1 que par cohérence produit : le boost ne doit pas être livré avant que l'abonnement ait un cycle de vie réel.

### Lot 5 — Portail `dony-pro`

- `/upgrade` devient la page de vente (formules mensuelle et annuelle)
- `/parametres/abonnement` : statut et accès au portail Stripe
- Bannières `PAST_DUE` et fin de grâce proche

### Lot 6 — Application mobile (`dony_app`)

- `upgrade_to_pro_screen.dart` devient informatif, renvoi vers le navigateur externe
- Bannière de statut

---

## Écarts assumés par rapport au spec

Deux points du spec sont corrigés ici, après lecture du code existant.

**1. Pas de table `billing_processed_events`.** Le spec prévoyait une table d'idempotence dédiée. Elle ferait doublon : `common/stripe/StripeWebhookIngestService` persiste déjà chaque événement dans `StripeEventInbox` (clé primaire = identifiant d'événement Stripe) et refuse les doublons, et `StripeEventScheduler` les traite en asynchrone avec relances. Le lot 2 réutilise cette infrastructure en ajoutant une valeur `BILLING` à `StripeWebhookSource`.

> **À vérifier au démarrage du lot 2 :** le `CLAUDE.md` du dépôt impose par ailleurs d'enregistrer tout événement Stripe dans une table `processed_stripe_events` **avant** de le traiter. Deux mécanismes d'idempotence semblent donc coexister (`StripeEventInbox` et `processed_stripe_events`). Déterminer lequel fait autorité pour un webhook neuf avant d'écrire le code du lot 2, plutôt que d'en introduire un troisième.

**2. Ajout du champ `past_due_since`.** Le spec décrit un downgrade « après 5 jours en `PAST_DUE` » sans prévoir de quoi mesurer ce délai. `updatedAt` ne convient pas : toute écriture sur la ligne le repousserait. Un horodatage dédié, posé à l'entrée en `PAST_DUE` et remis à `null` à la sortie, est nécessaire.
