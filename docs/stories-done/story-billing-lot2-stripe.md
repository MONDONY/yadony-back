# Story — Lot 2 : Stripe Billing (Backend)

**Date :** 2026-08-27
**Status :** ✅ Complète

## Résumé

Le lot 1 avait posé `ProSubscriptionEntity` et sa machine à états sans aucun moyen de payer.
Ce lot y branche Stripe : souscription par Stripe Checkout, gestion (carte, résiliation) par le
Customer Portal, cycle de vie entièrement piloté par cinq types de webhooks. `POST
/auth/me/upgrade-to-pro` cesse d'accorder le statut PRO — il ne fait plus que mettre à jour le
profil professionnel (raison sociale, SIRET). L'accès PRO ne s'obtient désormais que par un
abonnement Stripe réellement payé.

## Fichiers créés

### `billing/`
- `StripeBillingService.java` — crée les sessions Stripe Checkout (mode `subscription`) et
  Customer Portal ; gardes métier avant tout appel réseau (Price non configuré, abonnement déjà
  actif, pas de client Stripe connu)
- `BillingController.java` — `POST /billing/checkout-session`, `POST /billing/portal-session`,
  `GET /billing/subscription`, `POST /billing/webhook`
- `ProBillingStripeWebhookHandler.java` — `@Component implements StripeWebhookHandler`, découvert
  automatiquement par `StripeEventDispatcher` ; traduit les cinq événements Stripe Billing en
  transitions de `ProSubscriptionService`
- `dto/CheckoutSessionResponse.java` — `{ url }`
- `dto/PortalSessionResponse.java` — `{ url }`
- `dto/ProSubscriptionResponse.java` — statut exposé au portail web (`active`, `status`, `source`,
  `billingCycle`, `currentPeriodEnd`, `cancelAtPeriodEnd`, `graceExpiresAt`)

### Tests
- `billing/StripeBillingServiceTest.java`, `BillingControllerIntegrationTest.java`,
  `ProSubscriptionServiceStripeTest.java`, `ProBillingStripeWebhookHandlerTest.java` (34 tests —
  chemins nominaux + toutes les branches défensives : UUID invalide, `billing_cycle` absent ou
  inconnu, JSON malformé, `period_end`/`subscription` absents, gardes anti-doublon, statuts
  `payment_status`, repli métadonnée abonnement)
- `common/stripe/StripeWebhookIngestServiceTest.java` — croisement des trois secrets (PAYMENTS,
  KYC, BILLING) dans les deux sens
- `auth/UserServiceUpgradeToProTest.java` — `upgradeToPro` ne donne plus le statut PRO
- Classes du lot 1 étendues sans affaiblissement d'assertion : `BillingPropertiesTest.java`
  (3 → 6 tests, dont `stripePricesConfigured()`/`priceFor(cycle)`), `LegacyProGraceListenerTest.java`,
  `ProSubscriptionSchedulerTest.java`, `UserServiceTest.java` (classe imbriquée `DowngradeProTests`,
  6 nouveaux cas sur la garde Stripe du downgrade manuel)

**Aucune migration Flyway.** Le schéma du lot 1 (`V231`, `V232`) suffit intégralement.

## Fichiers modifiés

- `src/main/java/com/yadony/api/common/stripe/StripeWebhookSource.java` — ajout de `BILLING`
- `src/main/java/com/yadony/api/common/stripe/StripeWebhookIngestService.java` — sélection du
  secret : **ternaire remplacé par un `switch` exhaustif sans `default`** ; constructeur passé de
  deux à trois secrets injectés (`@Qualifier`)
- `src/main/java/com/yadony/api/common/stripe/AdminAlertService.java` — nouveau code d'alerte
  `BILLING_CHECKOUT_UNRESOLVED_USER` dans `titleFor`
- `src/main/java/com/yadony/api/config/StripeConfig.java` — bean `stripeBillingWebhookSecret`
  (`@Value("${stripe.webhook.billing-secret:}")`)
- `src/main/java/com/yadony/api/config/SecurityConfig.java` — `/billing/webhook` ajouté au
  `permitAll` (signature vérifiée en aval par `StripeWebhookIngestService`, pas par un rôle)
- `src/main/java/com/yadony/api/billing/BillingProperties.java` — record étendu de 3 à 8
  composantes (`priceMonthly`, `priceYearly`, `successUrl`, `cancelUrl`, `portalReturnUrl`
  ajoutés), + `stripePricesConfigured()` et `priceFor(BillingCycle)`
- `src/main/java/com/yadony/api/billing/ProSubscriptionService.java` — trois méthodes ajoutées :
  `activateFromStripe`, `markCancelAtPeriodEnd`, `renew` (voir « Logique métier critique ») ;
  `clearPastDue` supprimée en fin de lot (plus d'appelant, `renew` fait strictement plus)
- `src/main/java/com/yadony/api/auth/UserService.java` — `upgradeToPro` ne pose plus
  `isProAccount` ni ne publie `UserProStatusChangedEvent` ; `downgradePro` refuse désormais
  l'opération en 409 quand un abonnement Stripe est encore ouvert (voir plus bas) ; nouveau
  paramètre de constructeur `ProSubscriptionRepository`
- `src/main/java/com/yadony/api/billing/LegacyProGraceListener.java` — javadoc de classe corrigée
  (elle affirmait encore que l'upgrade gratuit accorde le statut PRO)
- `src/main/resources/application.yml`, `application-dev.yml`, `application-prod.yml` — secret
  webhook billing (`stripe.webhook.billing-secret`), Price IDs (`yadony.billing.price-monthly`,
  `price-yearly`), URLs de retour (`success-url`, `cancel-url`, `portal-return-url`) ; **prod sans
  repli** sur les Price IDs et le secret webhook, pour qu'une configuration manquante échoue au
  démarrage plutôt qu'à la première requête
- `src/test/resources/application-test.yml` — `billing-secret: whsec_test_billing` ajouté au bloc
  `stripe.webhook` (trou laissé par la tâche 1, comblé en tâche 4 avant que le contrôleur webhook
  n'en ait besoin)

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble du flux

**Souscription (Stripe Checkout) :**
1. `POST /billing/checkout-session?cycle=MONTHLY|YEARLY` → `BillingController.createCheckoutSession`
   résout l'utilisateur authentifié (jamais un `userId` client) → `StripeBillingService.createCheckoutSession`.
2. Le service refuse (409) si une ligne `pro_subscriptions` `ACTIVE`/`PAST_DUE` existe déjà, et
   refuse (503 `billing-not-configured`) si les Price Stripe ne sont pas configurés.
3. Il crée une Stripe Checkout Session en mode `subscription`, avec `client_reference_id =
   userId` et, sur `subscription_data`, les métadonnées `billing_cycle` et **`user_id`** — cette
   dernière atterrira sur l'objet `Subscription`, pas sur la Session (voir pièges). Réutilise
   `stripeCustomerId` existant s'il y en a un.
4. L'utilisateur paie sur Stripe, redirigé vers `success-url`/`cancel-url` (`dony-pro`).
5. Stripe envoie `checkout.session.completed` → `POST /billing/webhook` (public) →
   `StripeWebhookIngestService.ingest` vérifie la signature avec le secret `BILLING`, insère dans
   `stripe_event_inbox` → `StripeEventScheduler`/`StripeEventProcessor` (infrastructure
   préexistante, inchangée) → `StripeEventDispatcher.dispatch` route par type vers
   `ProBillingStripeWebhookHandler`.
6. `onCheckoutCompleted` vérifie d'abord `payment_status` (paiement confirmé sinon `return`),
   résout l'utilisateur (`client_reference_id`, repli sur la métadonnée `user_id` de l'abonnement
   via `Subscription.retrieve`), lit `billing_cycle`, puis appelle
   `ProSubscriptionService.activateFromStripe(userId, customerId, subscriptionId, cycle,
   provisionalEnd)`.
7. `activateFromStripe` recycle la ligne `pro_subscriptions` existante (ou en crée une), passe
   `status = ACTIVE`, `source = STRIPE`, purge grâce/impayé/résiliation programmée/traces d'octroi
   admin, appelle `ProAccessSynchronizer.sync(userId, true)` (pose `isProAccount = true` si pas
   déjà vrai) et journalise `BILLING_SUBSCRIPTION_ACTIVATED`.

**Renouvellement et impayé :**
- `invoice.paid` → `onInvoicePaid` retrouve l'abonnement par `stripeSubscriptionId`, lit
  l'échéance de service (`lines.data[0].period.end`, repli sur `period_end` racine puis sur +32
  jours) → `ProSubscriptionService.renew` repousse `currentPeriodEnd`, repasse `ACTIVE`, sort d'un
  `PAST_DUE` éventuel.
- `invoice.payment_failed` → `onInvoiceFailed` → `markPastDue` : `status = PAST_DUE`, l'accès
  reste ouvert (`grantsProAccess()` inclut `PAST_DUE`) pendant que Stripe relance la carte.

**Résiliation :**
- `customer.subscription.updated` avec `cancel_at_period_end` → `markCancelAtPeriodEnd` : pose le
  drapeau, **ne coupe pas l'accès**, la période en cours est déjà payée.
- `customer.subscription.deleted` → `onSubscriptionDeleted` (garde anti-rejeu sur
  `grantsProAccess()`) → `cancel()` → `status = CANCELED`, `ProAccessSynchronizer.sync(userId,
  false)`, audit `BILLING_SUBSCRIPTION_CANCELED`.
- Résiliation utilisateur via **Customer Portal Stripe** (`POST /billing/portal-session`), jamais
  via `DELETE /auth/me/upgrade-to-pro` (voir « Logique métier critique »).

**Upgrade gratuit (résiduel) :**
`POST /auth/me/upgrade-to-pro` ne fait plus que `UserService.upgradeToPro` : valide et enregistre
`companyName`/`siret` sur `UserEntity`, **ne touche plus `isProAccount`**, ne publie plus
`UserProStatusChangedEvent`. Le seul chemin vers `isProAccount = true` est désormais Stripe (via
`ProAccessSynchronizer`), une grâce historique déjà ouverte par le lot 1, ou un octroi admin (lot 3,
hors périmètre).

### Points d'entrée API

- `POST /billing/checkout-session?cycle=MONTHLY|YEARLY` — utilisateur authentifié (tout rôle),
  crée une session Stripe Checkout et retourne son URL
- `POST /billing/portal-session` — utilisateur authentifié, requiert un `stripeCustomerId`
  existant (404 sinon)
- `GET /billing/subscription` — utilisateur authentifié, statut courant (`NONE` si aucune ligne)
- `POST /billing/webhook` — **public**, signature `Stripe-Signature` vérifiée avec le secret
  `BILLING`
- `POST /api/v1/auth/me/upgrade-to-pro` — comportement changé : met à jour uniquement le profil
  professionnel, n'accorde plus le statut PRO
- `DELETE /api/v1/auth/me/upgrade-to-pro` — comportement changé : refuse désormais en 409 si un
  abonnement Stripe payant est encore ouvert (voir plus bas)

### Entités JPA impliquées

Aucune nouvelle entité ni migration. `ProSubscriptionEntity` (table `pro_subscriptions`, posée au
lot 1) est désormais alimentée pleinement par Stripe : `stripeCustomerId`, `stripeSubscriptionId`,
`billingCycle`, `currentPeriodEnd`, `cancelAtPeriodEnd` prennent vie à `activateFromStripe`.
`grantedByAdminId`/`adminGrantReason` restent réservés au lot 3 (octroi admin) mais sont purgés à
chaque transition Stripe pour ne jamais survivre à un changement de source.

### Logique métier critique

- **`Invoice.subscription` n'existe plus dans l'API Stripe.** Retiré en `2025-03-31.basil` ; le
  SDK du projet (`stripe-java 33.3.0`) est épinglé sur `Stripe.API_VERSION = 2026-07-29.dahlia`, où
  le champ n'existe pas non plus (vérifié par `javap` sur le jar : seul `getParent()` existe). Le
  chemin réel est `parent.subscription_details.subscription`. `invoiceSubscriptionId(JsonNode)` lit
  d'abord cette forme, avec repli sur l'ancien champ racine `subscription` — la version d'API
  réellement utilisée dépend de la configuration du compte/endpoint webhook, pas du SDK embarqué.
  **C'est le défaut le plus grave du lot** : sans ce correctif, `invoice.paid` et
  `invoice.payment_failed` étaient des no-op silencieux — aucun renouvellement, aucun passage en
  `PAST_DUE`, donc aucun downgrade jamais déclenché par le dunning. 25 tests verts ne l'avaient pas
  vu, parce qu'ils fabriquaient eux-mêmes leurs payloads à l'ancienne forme
  (`{"subscription":"sub_…"}`). **Leçon générale à retenir** : un test qui construit lui-même la
  donnée d'entrée d'un système externe ne vérifie que sa cohérence avec sa propre hypothèse sur le
  format de cette donnée, jamais le format réel.
- **La métadonnée `user_id` est posée sur `subscription_data`, pas sur la Session.**
  `StripeBillingService.createCheckoutSession` écrit `subscriptionData.putMetadata("user_id", …)` :
  ce paramètre atterrit sur l'objet `Subscription` une fois créé, jamais ré-exposé tel quel sur la
  Session ni dans le payload webhook `checkout.session.completed`. Le repli de
  `resolveUserIdFromSubscriptionMetadata` appelle donc `Subscription.retrieve(subscriptionId)` pour
  la lire — même famille de piège que `Invoice.subscription`, découverte en vérifiant plutôt qu'en
  supposant.
- **`payment_status` absent est traité comme non confirmé.** `onCheckoutCompleted` n'active
  l'abonnement que si `payment_status` vaut `paid` ou `no_payment_required` ; absent ou toute autre
  valeur (notamment `unpaid`, fréquent avec le prélèvement SEPA où le mandat est posé avant
  l'encaissement effectif) → `return`, l'activation viendra du `invoice.paid` correspondant. Choix
  assumé : le pire cas d'attendre est un léger retard d'activation, le pire cas d'un défaut de
  prudence est un accès PRO gratuit non désiré.
- **Checkout payé sans utilisateur identifiable → alerte admin, jamais un simple `log.warn`.** Si
  ni `client_reference_id` ni la métadonnée `user_id` de l'abonnement ne permettent de résoudre un
  utilisateur, `AdminAlertService.raise("BILLING_CHECKOUT_UNRESOLVED_USER", …)` est déclenché avec
  le contexte (session, customer, subscription) : de l'argent a été encaissé sans service rendu, ce
  n'est jamais un cas à ignorer silencieusement.
- **`onInvoicePaid` lit `lines.data[0].period.end`, pas `period_end` racine.** Le `period_end`
  racine de la facture ferme la période d'**usage** déjà facturée (proche de l'instant présent à
  chaque renouvellement), pas la nouvelle période de **service**. Lire la racine aurait fait
  retomber `currentPeriodEnd` à ~maintenant à chaque renouvellement, et la tâche planifiée de
  fermeture des résiliations programmées (lot 1, `closeEndedCancellations`) aurait fermé
  l'abonnement dès son passage suivant — alors que la période venait justement d'être payée.
- **`renew()` refuse de ressusciter un `CANCELED`.** Une résiliation volontaire ne doit jamais être
  rouverte par un encaissement tardif (facture en retard, webhook rejoué). `ACTIVE`, `PAST_DUE` et
  `EXPIRED` restent éligibles — ressusciter un `EXPIRED` est au contraire le comportement voulu
  (dunning résolu tardivement). L'entrée d'audit `BILLING_SUBSCRIPTION_REACTIVATED` n'est écrite
  que quand le statut de départ ne donnait pas déjà l'accès (`EXPIRED`), pour ne pas polluer le
  renouvellement normal d'un `ACTIVE`/`PAST_DUE`.
- **`DELETE /auth/me/upgrade-to-pro` refuse en 409 quand l'abonnement est un Stripe payant encore
  ouvert.** Cet endpoint ne contacte jamais Stripe : le laisser fermer la ligne
  `pro_subscriptions` sans agir côté Stripe ferait perdre l'accès à l'utilisateur tout en le
  laissant débité 4,99 €/mois — et le `invoice.paid` suivant ressusciterait la ligne fermée en
  `ACTIVE`, faisant osciller l'état au rythme des webhooks. `UserService.downgradePro` consulte
  `ProSubscriptionRepository.findByUserId` : si `source == STRIPE` **et**
  `status.grantsProAccess() == true` (ACTIVE ou PAST_DUE en pratique), il lève
  `YadonyBusinessException(409, "active-stripe-subscription", …)` renvoyant vers le Customer
  Portal. Une grâce historique (`LEGACY_FREE`), un octroi admin (`ADMIN_GRANT`), un abonnement
  Stripe déjà fermé (`CANCELED`/`EXPIRED`), ou l'absence de toute ligne restent libres de renoncer
  par cette voie.
- **Aucune table d'idempotence supplémentaire.** Le `CLAUDE.md` du dépôt prescrit encore
  `processed_stripe_events` (créée en `V48`) : elle a été migrée dans `V84` et **droppée en `V86`**,
  zéro référence Java dans `src/`. Cette section du `CLAUDE.md` est périmée. Le seul mécanisme
  d'idempotence vivant est `stripe_event_inbox`, dont la clé primaire est l'identifiant d'événement
  Stripe (`existsById` avant insertion, dans `StripeWebhookIngestService.ingest`).

### Events Spring publiés / écoutés

- `UserProStatusChangedEvent` — toujours l'événement central du lot 1, mais son unique déclencheur
  côté PRO gratuit disparaît : `UserService.upgradeToPro` ne le publie plus. Il n'est plus publié
  que par `ProAccessSynchronizer.sync`, appelé depuis `ProSubscriptionService` (`activateFromStripe`,
  `renew`, `markPastDue`, `close`) et depuis `UserService.downgradePro` (chemin gratuit résiduel).
  Écouté, sans changement, par `LegacyProGraceListener` (billing/) et
  `AutomationRuleProStatusListener` (automation/), déjà documentés au lot 1.
- Aucun nouvel événement Spring introduit par ce lot : la traduction Stripe → machine à états passe
  entièrement par des appels de méthode directs (`ProBillingStripeWebhookHandler` →
  `ProSubscriptionService`), pas par des événements — cohérent avec le fait que le handler tourne
  déjà dans le contexte transactionnel de `StripeEventProcessor.processOne`.

### Pièges et points d'attention

- **Prérequis humain avant toute mise en production**, sans quoi rien ne fonctionne :
  1. un **Product** Stripe avec deux **Price** récurrents : `4,99 €/mois` et `47,90 €/an` ;
  2. un **Customer Portal** restreint à la mise à jour du moyen de paiement et à la résiliation
     (pas de changement de plan, il n'existe qu'un palier) ;
  3. un **endpoint webhook** Stripe pointant sur `/api/v1/billing/webhook`, abonné aux cinq types
     `checkout.session.completed`, `customer.subscription.updated`, `customer.subscription.deleted`,
     `invoice.paid`, `invoice.payment_failed`.
  Les identifiants obtenus alimentent `STRIPE_BILLING_PRICE_MONTHLY`, `STRIPE_BILLING_PRICE_YEARLY`
  et `STRIPE_WEBHOOK_BILLING_SECRET`. **L'application démarre quand même sans eux** (valeurs de
  repli vides) : c'est l'appel à `POST /billing/checkout-session` qui échoue proprement en 503
  (`billing-not-configured`), jamais un crash au démarrage.
- **La fenêtre entre ce lot et le lot 6 (application mobile).** `dony_app` affiche encore un écran
  « Passer en compte PRO » qui appelle `POST /auth/me/upgrade-to-pro` sans plus rien débloquer.
  C'est l'état final voulu (le paiement se fait sur le web, modèle « reader app »), mais l'écran
  devient trompeur tant que le lot 6 (écran informatif renvoyant vers `dony-pro/upgrade`) n'est pas
  livré.
- **`target/jacoco.exec` est cumulatif.** Un pourcentage de couverture par classe relevé après un
  run ciblé agrège en réalité les runs précédents et ne mesure pas ce qu'on croit. Pour mesurer
  proprement : supprimer le fichier, puis lancer `test` et `jacoco:report` **dans une seule
  commande**. Piège découvert pendant ce lot, à retenir pour tout le projet.
- **JaCoCo n'attribue aucune couverture à `BillingController.handleWebhook`**, alors que son
  exécution est prouvée par les logs applicatifs du test (`webhookIsPublicButRejectsBadSignature`
  traverse bien `ingestService.ingest(...)` jusqu'à l'exception de signature attendue, confirmé par
  un run isolé où `StripeWebhookIngestService` ressort à 41 % d'instructions). C'est un défaut
  d'attribution du rapport HTML, pas un trou de test ni de code — à ne pas rouvrir dans une revue
  future sur la seule foi du pourcentage affiché.
- **`yadony.billing.scheduler-enabled` reste à `false`.** Les deux dettes documentées dans
  `story-billing-lot1-fondation.md` (transaction unique pour tout le lot de downgrades planifiés ;
  absence d'entrée `audit_log` pour un downgrade automatique) restent valables et doivent être
  réglées avant activation. S'y ajoute désormais une vérification manuelle du parcours de paiement
  contre un vrai compte Stripe, avec la CLI Stripe (`stripe trigger checkout.session.completed`,
  etc.) — jamais faite dans ce lot, exclue du scope automatisable.
- **`StripeEventDispatcher` route par type d'événement seul, sans consulter
  `StripeEventInbox.source`.** Préexistant à ce lot, mais ce lot est le **premier à introduire une
  troisième source** (`BILLING`, après `PAYMENTS` et `KYC`). Un endpoint Stripe mal configuré côté
  dashboard (ex. un événement `invoice.paid` envoyé sur l'endpoint billing pour un abonnement qui
  n'a rien à voir) ferait piloter la logique paiements par un webhook signé du secret billing :
  le secret par source garantit l'**authenticité** de l'événement, jamais son **cloisonnement**
  fonctionnel. À garder en tête si une prochaine source d'événements Stripe est ajoutée.

## Critères d'acceptation couverts

- [x] Un utilisateur peut souscrire à l'abonnement PRO mensuel ou annuel via Stripe Checkout —
      `POST /billing/checkout-session?cycle=...`, activation par `checkout.session.completed`.
- [x] Un renouvellement d'échéance repousse la date de fin de période sans interruption d'accès —
      `invoice.paid` → `renew` (échéance de service, pas d'usage).
- [x] Un échec de paiement place le compte en impayé sans couper l'accès, le temps des relances
      Stripe — `invoice.payment_failed` → `markPastDue`.
- [x] Une résiliation programmée depuis le Customer Portal laisse l'accès actif jusqu'à
      l'échéance déjà payée — `customer.subscription.updated` → `markCancelAtPeriodEnd`.
- [x] Une résiliation effective ferme l'abonnement et coupe l'accès —
      `customer.subscription.deleted` → `cancel`.
- [x] La gestion de l'abonnement (carte, résiliation) se fait exclusivement via le Customer Portal
      Stripe, jamais via une interface sur mesure — `POST /billing/portal-session`.
- [x] `POST /auth/me/upgrade-to-pro` n'accorde plus le statut PRO gratuitement — seul le profil
      professionnel est mis à jour.
- [x] `DELETE /auth/me/upgrade-to-pro` ne peut pas être utilisé pour résilier un abonnement payant
      sans passer par Stripe — refus en 409.
- [x] Toute donnée absente ou malformée dans un webhook est journalisée et ignorée, jamais levée en
      exception — pas de relance inutile ni de mise en `DEAD_LETTER` sur un webhook légitime mais
      incomplet.
- [x] Un paiement encaissé sans utilisateur rattachable déclenche une alerte administrateur,
      jamais un silence.
- [x] Aucune régression sur le code PRO existant lisant `isProAccount` (matching, export fiscal,
      quotas, portail `dony-pro`) — vérifié par la suite complète.
- [x] Aucune table d'idempotence supplémentaire créée — `stripe_event_inbox` (infrastructure
      préexistante) suffit.
- [x] Vérification de signature Stripe obligatoire sur `/billing/webhook`, avec son propre secret
      (`BILLING`), jamais celui de `PAYMENTS` ou `KYC`.

## Tests

- `./mvnw test` (suite complète, dernier lancement en fin de vague de correctifs finaux, commit
  `8d198649`) → **4304 tests, 0 échec, 0 erreur, 7 ignorés, BUILD SUCCESS**.
- Couverture JaCoCo du package `com.yadony.api.billing`, dernière mesure isolée disponible (fin de
  tâche 4, avant les vagues de correctifs de revue finale, run propre avec `jacoco.exec` vidé au
  préalable) : **97 % instructions / 93 % branches**, détail par classe :
  - `BillingProperties` — 100 % / 100 %
  - `StripeBillingService` — 93 % / 75 % (chemins nominaux `Session.create` couverts via
    `Mockito.mockStatic` ; reste non couvert : branches défensives jugées hors d'atteinte sans
    wrapper injectable autour du SDK Stripe)
  - `BillingController` — 84 % / 50 % (`handleWebhook` non attribué par JaCoCo malgré exécution
    prouvée — voir « Pièges » ; branche non couverte restante : `orElseThrow` de `currentUserId`
    sur un principal authentifié dont l'utilisateur aurait disparu de la base)
  - `ProBillingStripeWebhookHandler` — 100 % / 100 % (34 tests, toutes les branches défensives
    couvertes après un complément dédié)
  - `ProSubscriptionService` — couverture non isolément mesurée après les correctifs finaux
    (`renew`, `activateFromStripe`) ; 100 % des transitions exercées par
    `ProSubscriptionServiceStripeTest` (9 tests) et `ProSubscriptionServiceTest` (7 tests).

  **Note de transparence** : cette mesure par classe date de la fin de la tâche 4, avant les deux
  vagues de correctifs de revue finale (3 bloquants + 5 non bloquants) qui ont ajouté des tests sur
  `ProBillingStripeWebhookHandler` et `ProSubscriptionService`. Aucune commande Maven n'a été
  relancée pour produire cette documentation (contrainte de cette tâche de rédaction) : le nombre
  global (4304 tests, 0 échec) est vérifié sur le dernier commit, mais un rapport JaCoCo consolidé
  post-correctifs n'a pas été régénéré. À refaire (`rm target/jacoco.exec && ./mvnw test
  jacoco:report` en une seule commande, cf. piège documenté ci-dessus) avant de considérer la
  couverture du package `billing/` comme formellement vérifiée à 90 % sur l'état final du lot.

- Tests ajoutés ou modifiés dans ce lot : `StripeWebhookIngestServiceTest`,
  `ProSubscriptionServiceStripeTest`, `ProBillingStripeWebhookHandlerTest` (34 tests, dont 9 ajoutés
  lors des correctifs de revue finale), `StripeBillingServiceTest` (8 tests),
  `BillingControllerIntegrationTest` (9 tests), `UserServiceUpgradeToProTest`, `UserServiceTest`
  (classe imbriquée `DowngradeProTests`, 6 cas), `BillingPropertiesTest` (3 → 6),
  `LegacyProGraceListenerTest` et `ProSubscriptionSchedulerTest` (adaptations mécaniques au
  passage du record `BillingProperties` de 3 à 8 composantes, aucune assertion affaiblie).

## Décisions techniques

| Décision | Choix | Alternatives écartées | Raison |
|---|---|---|---|
| Sélection du secret webhook | `switch` exhaustif sans `default` | Ternaire étendu | Un `switch` sans branche par défaut fait échouer la compilation si une source est ajoutée sans être traitée ; le ternaire aurait validé silencieusement `BILLING` avec le secret `PAYMENTS` |
| Idempotence des webhooks billing | `stripe_event_inbox` existant, clé primaire = event id Stripe | Nouvelle table dédiée (prescrite par le design initial) | Infrastructure déjà en place et déjà testée ; en créer une seconde aurait dupliqué la logique sans bénéfice |
| Lecture des payloads webhook | JSON brut Jackson (`getRawJson()`) | Objet désérialisé du SDK (`getObject()`) | `getObject()` renvoie un `Optional` vide dès que la version d'API du compte diverge de celle du SDK — piège déjà rencontré côté paiements |
| Résolution de l'utilisateur au checkout | `client_reference_id`, repli sur la métadonnée `user_id` de l'abonnement via `Subscription.retrieve` | Métadonnée uniquement sur la Session | `subscription_data.metadata` n'est pas ré-exposée sur la Session ni le payload webhook — vérifié sur le SDK, pas supposé |
| Échéance de renouvellement | `lines.data[0].period.end` avec repli sur `period_end` racine | `period_end` racine seul (plan initial) | La racine ferme la période d'usage facturée, pas la période de service dont dépend l'accès — corrigé en revue finale |
| Résurrection d'un abonnement par `renew` | Refusée si `CANCELED`, autorisée si `EXPIRED` | Aucune garde (plan initial) | Une résiliation volontaire ne doit jamais être rouverte par un encaissement tardif ; un `EXPIRED` peut légitimement être rattrapé par un dunning résolu tard |
| Résiliation via `DELETE /auth/me/upgrade-to-pro` sur un Stripe actif | Refusée en 409, renvoi vers le Customer Portal | Fermer la ligne localement sans toucher Stripe (comportement initial livré, corrigé en revue finale) | Sans la garde, l'utilisateur perdait l'accès tout en restant débité, et le webhook suivant ressuscitait la ligne fermée |
| Paiement Checkout non confirmé (`payment_status`) | Absent ou non `paid`/`no_payment_required` → ignoré, activation différée à `invoice.paid` | Activer dès `checkout.session.completed` sans regarder `payment_status` (comportement initial, corrigé en revue finale) | Le prélèvement SEPA confirme la session avant l'encaissement effectif ; le pire cas d'attendre est un léger retard, le pire cas d'activer à tort est un accès gratuit |
| Checkout payé sans utilisateur résolvable | Alerte administrateur (`AdminAlertService.raise`) | `log.warn` seul (comportement initial, corrigé en revue finale) | De l'argent encaissé sans service rendu et sans trace exploitable justifie une alerte active, pas un silence dans les logs |
| Statut PRO de l'upgrade gratuit | Supprimé : `upgradeToPro` ne met plus à jour que le profil professionnel | Conserver l'upgrade gratuit en parallèle de Stripe | Décision produit du design : l'accès PRO ne s'obtient plus que par un abonnement payant, web-only |
