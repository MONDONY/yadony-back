# Mobile money sur la négociation d'un colis, lot 1 (Backend)

**Date :** 2026-09-10
**Status :** Complète
**Branche :** worktree `mobile-money-negociation-lot1` (base `18d3ca98`, HEAD `f28fb5e0`)
**Spec :** `docs-claude/docs/superpowers/specs/2026-09-10-mobile-money-negociation-colis-design.md`

## Résumé

Ouverture du rail mobile money pawaPay sur le fil de négociation d'une demande de colis
(zone CFA), en miroir du rail déjà existant sur les bids d'un trajet. Le paiement se fait au
dépôt à l'accord (comme la carte), keyé `negotiation_thread_id` plutôt que `bid_id` — sur un
colis le fil est scellé avant que le bid soit matérialisé, à l'inverse d'un trajet. Ce lot 1
pose tout le rail (statut, service jumeau, endpoints, listeners, scheduler, événements, audit)
sans l'exposer : `NegotiationService.travelerCanOffer` continue de fermer `MOBILE_MONEY`
(`case MOBILE_MONEY -> false`), donc aucun fil ne propose ce moyen de paiement tant que le
lot 2 (exposition + app) n'est pas fusionné. Déploiement sans effet visible côté utilisateurs.

## Fichiers créés

- `src/main/resources/db/migration/V253__negotiation_threads_mobile_money.sql` — étend la
  contrainte CHECK `chk_neg_thread_status` avec `AWAITING_DEPOSIT`, ajoute
  `negotiation_threads.deposit_expires_at`.
- `src/main/java/com/yadony/api/payments/mobilemoney/MobileMoneyNegotiationPaymentService.java`
  — jumeau de `MobileMoneyBidPaymentService`, keyé `negotiation_thread_id` : création du
  paiement (transaction de l'appelant), initiation du dépôt (transaction séparée, appel
  pawaPay), statut, libération (échéance/renoncement), remboursement d'un séquestre orphelin,
  confirmation du séquestre (claim atomique), notification d'échec. Réutilise sans changement
  la partie pawaPay commune (`PawapayProviderResolver`, `PawapaySubmissionService`,
  `PawapayOperationService`).
- `src/main/java/com/yadony/api/payments/mobilemoney/NegotiationMobileMoneyAdapter.java` —
  implémentation `payments/` de `NegotiationMobileMoneyPort`, délègue au service ci-dessus (3
  méthodes). Symétrique de `NegotiationEscrowAdapter` côté carte.
- `src/main/java/com/yadony/api/requests/NegotiationMobileMoneyPort.java` — port consommé par
  `requests/` : `createPendingDeposit`, `releasePendingDeposit`, `refundEscrowedDeposit`, les
  records `PendingDeposit` et l'énumération `ReleaseOutcome` (`CANCELLED`, `NOTHING_PENDING`,
  `DEPOSIT_OPEN`, `DEPOSIT_COMPLETED_NOT_APPLIED`).
- `src/main/java/com/yadony/api/requests/service/NegotiationDepositExpiryRunner.java` —
  scheduler minute (`yadony.pawapay.deadline-cron`) : fils `AWAITING_DEPOSIT` échus, un
  `REQUIRES_NEW` par fil, alerte dédupliquée si un dépôt `COMPLETED` n'a pas encore été
  appliqué.
- `src/main/java/com/yadony/api/requests/service/NegotiationDepositListener.java` — écoute
  `MobileMoneyNegotiationDepositConfirmedEvent`/`…FailedEvent` (`AFTER_COMMIT` + `REQUIRES_NEW`,
  règle 18 du projet), appelle `finalizeAfterMobileMoneyDeposit` / `revertMobileMoneyDeposit`.
- `src/main/java/com/yadony/api/requests/event/NegotiationDepositPendingEvent.java` /
  `NegotiationDepositRevertedEvent.java` — événements consommés par `notifications/`.
- `src/main/java/com/yadony/api/payments/events/MobileMoneyNegotiationDepositConfirmedEvent.java`
  / `MobileMoneyNegotiationDepositFailedEvent.java` — événements internes à `payments/`, publiés
  par `MobileMoneyNegotiationPaymentService`, consommés par `NegotiationDepositListener`.
- `src/main/java/com/yadony/api/payments/mobilemoney/dto/MobileMoneyNegotiationStatusResponse.java`
  — réponse de `GET .../mobile-money/status`.
- Tests : `NegotiationThreadsMobileMoneyMigrationTest`, `MobileMoneyNegotiationPaymentServiceTest`
  (647 lignes, le plus gros), `NegotiationDepositExpiryRunnerTest`, `NegotiationDepositListenerTest`,
  `NegotiationServiceMobileMoneyTest` (413 lignes), `NegotiationThreadRepositoryDepositTest`,
  `NegotiationThreadStatusTest`, `NegotiationControllerMobileMoneyIT`,
  `PaymentServiceCancelNegotiationEscrowTest`, `PawapayReturnControllerIT` (route thread).

## Fichiers modifiés

- `src/main/java/com/yadony/api/requests/service/NegotiationService.java` (+173 lignes) —
  nouvelles méthodes `prepareMobileMoneyDeposit`, `finalizeAfterMobileMoneyDeposit`,
  `revertMobileMoneyDeposit`, `cancelMobileMoneyDeposit`, `expireMobileMoneyDeposit`,
  `requireParticipantThread`, records `PreparedDeposit` / `DepositExpiryOutcome`. Injection de
  `NegotiationMobileMoneyPort`. `travelerCanOffer` et `computeAvailableMethods` **non touchés**
  côté ouverture (voir Pièges).
- `src/main/java/com/yadony/api/requests/controller/NegotiationController.java` (+36 lignes) —
  trois endpoints `POST .../mobile-money/initiate`, `GET .../mobile-money/status`,
  `POST .../mobile-money/cancel-deposit`.
- `src/main/java/com/yadony/api/requests/entity/NegotiationThreadEntity.java` /
  `NegotiationThreadStatus.java` — statut `AWAITING_DEPOSIT` (inclus dans `isActive()`), champ
  `depositExpiresAt`.
- `src/main/java/com/yadony/api/requests/event/NegotiationCancelledEvent.java` — porte
  l'information nécessaire au remboursement rail-aware.
- `src/main/java/com/yadony/api/requests/repository/NegotiationThreadRepository.java` —
  `findIdsAwaitingDepositExpiredBefore` (scheduler), `findPackageRequestIdById`,
  `findByNegotiationThreadIdForUpdate` côté paiement (voir plus bas).
- `src/main/java/com/yadony/api/payments/PaymentRepository.java` — `findByNegotiationThreadIdForUpdate`
  (verrou `PESSIMISTIC_WRITE`, jumeau de `findByBidIdForUpdate`).
- `src/main/java/com/yadony/api/payments/PaymentService.java` — `cancelNegotiationEscrow`
  bifurque sur `payment.getRail() == PaymentRail.PAWAPAY` : jamais Stripe sur ce rail, libère un
  `PENDING` ou rembourse un `ESCROW` via `MobileMoneyNegotiationPaymentService` (injection
  `@Autowired(required = false)` pour ne pas casser les constructeurs des tests existants).
- `src/main/java/com/yadony/api/payments/mobilemoney/MobileMoneyDepositOutcomeListener.java` /
  `MobileMoneyPayoutOutcomeListener.java` — aiguillage par portée du paiement (`bidId` non nul
  → service bid, `negotiationThreadId` non nul → service fil) ; le payout retrouve le bid via
  `thread.materializedBidId` quand `payment.bidId` est nul.
- `src/main/java/com/yadony/api/payments/pawapay/PawapayProperties.java` /
  `PawapayReturnController.java` — composant `deepLinkAwaitingThread`, route
  `/pawapay/return/thread/{threadId}` → deep link `yadony://negotiations/{id}/mobile-money/awaiting`.
- `src/main/java/com/yadony/api/notifications/NotificationTexts.java` /
  `RequestEventsListener.java` — `depositPendingSender/Traveler`, `depositReverted(reason)` et
  leurs deux listeners `AFTER_COMMIT`.
- `src/main/resources/application.yml` / `src/test/resources/application-test.yml` — clé de
  deep link du rail fil.

Détail exhaustif : `git diff --stat 18d3ca98..HEAD` (48 fichiers, +2668/-37).

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble du flux

1. Fil `AWAITING_PAYMENT` (offre acceptée par le voyageur, moyen non encore choisi).
   L'expéditeur appelle `POST /negotiations/{id}/mobile-money/initiate`.
2. `NegotiationController.initiateMobileMoney` appelle `NegotiationService.prepareMobileMoneyDeposit`
   (**transaction 1**) : vérifie propriété, statut, `MOBILE_MONEY` fournissable et accepté par
   la demande, voyageur apte (`canReceiveMobileMoney(currency)`), détails de livraison complets,
   garde de modération (`assertTravelerAnnouncementActive`), crée le paiement `PENDING` via le
   port (`payments/`), passe le fil en `AWAITING_DEPOSIT` avec une échéance
   (`yadony.pawapay.deposit-deadline-minutes`), publie `NegotiationDepositPendingEvent`. Idempotent :
   un fil déjà `AWAITING_DEPOSIT` valide est rendu tel quel (nouvel essai après coupure réseau).
3. Après commit, le contrôleur appelle `MobileMoneyNegotiationPaymentService.initiateDeposit`
   (**transaction 2**, jamais dans la transaction 1) : relit le paiement déjà commité, résout
   l'opérateur depuis le numéro (saisi ou Firebase), vérifie les limites, soumet le deposit à
   pawaPay (`PawapaySubmissionService`). Répond le statut, l'échéance, l'URL d'autorisation Wave
   le cas échéant.
4. Le rappel pawaPay (ou le poller de réconciliation) confirme le deposit :
   `MobileMoneyNegotiationPaymentService.confirmEscrow` claim atomique `PENDING → ESCROW`
   (`markEscrowIfPending`), audit, publie `MobileMoneyNegotiationDepositConfirmedEvent`.
5. `NegotiationDepositListener.onDepositConfirmed` (`AFTER_COMMIT` + `REQUIRES_NEW`) appelle
   `NegotiationService.finalizeAfterMobileMoneyDeposit` : si le fil est toujours
   `AWAITING_DEPOSIT`, réutilise `sealAcceptedThread` avec `paymentIntentId = null` et
   `paymentMethod = MOBILE_MONEY` (même code que la carte). `PackageRequestAcceptedEvent` part
   comme d'habitude ; `ThreadAcceptedBidListener` matérialise le bid `ACCEPTED`. Si le fil n'est
   plus scellable (rejeu, auto-rejet concurrent, annulation), le séquestre est orphelin :
   remboursement immédiat via le port, audit `DEPOSIT_ORPHANED`.
6. Livraison confirmée → `DeliveryEventListener` (inchangé) retrouve le paiement via le bid
   matérialisé, verse le net au voyageur si `rail == PAWAPAY`.

Issue non aboutie (échec pawaPay, expiration ou renoncement) : le fil revient à
`AWAITING_PAYMENT`, l'accord tient, les fils concurrents ne bougent pas.
`revertMobileMoneyDeposit` remet `depositExpiresAt = null`, publie
`NegotiationDepositRevertedEvent`.

### Points d'entrée API

Préfixe `/negotiations/{id}/mobile-money` :

- `POST /initiate` (`ROLE_SENDER`) — lance le dépôt. 201, corps optionnel
  `{ "phoneNumber": "..." }` pour un autre numéro payeur que celui de Firebase.
- `GET /status` (`ROLE_SENDER` ou `ROLE_TRAVELER`, tout participant du fil via
  `requireParticipantThread`) — statut du paiement, échéance, vue de l'opération pawaPay
  (`OperationView`).
- `POST /cancel-deposit` (`ROLE_SENDER`) — 204, renonce au dépôt en cours ; 409
  `negotiation/deposit-in-flight` si un dépôt est déjà encaissé ou en vol côté pawaPay.

### Entités JPA impliquées

- `NegotiationThreadEntity` → table `negotiation_threads` : nouveau champ `deposit_expires_at`
  (nullable, non nul seulement en `AWAITING_DEPOSIT`). Pas de colonne téléphone ajoutée (voir
  Pièges).
- `PaymentEntity` → table `payments` : une ligne `negotiation_thread_id` non nul, `bid_id` nul,
  `rail = PAWAPAY`. Cycle `PENDING` (créée) → `ESCROW` (deposit confirmé) → `RELEASED` (payout)
  ou `FAILED`/`CANCELLED` (échec, expiration) ou `REFUNDED` (dépôt tardif après annulation).
  Contrainte `UNIQUE(negotiation_thread_id)` réutilisée (recyclage d'une ligne `CANCELLED` à
  la bascule carte → mobile money).
- `PawapayOperationEntity` (existant, inchangé) : lien vers l'opération pawaPay, `payment_id`
  générique, référence client `thread-{id}`.

### Events Spring publiés / écoutés

| Événement | Publié par | Écouté par |
|---|---|---|
| `MobileMoneyNegotiationDepositConfirmedEvent` | `MobileMoneyNegotiationPaymentService.confirmEscrow` | `NegotiationDepositListener.onDepositConfirmed` → `finalizeAfterMobileMoneyDeposit` |
| `MobileMoneyNegotiationDepositFailedEvent` | `MobileMoneyNegotiationPaymentService.notifyDepositFailed` | `NegotiationDepositListener.onDepositFailed` → `revertMobileMoneyDeposit` |
| `NegotiationDepositPendingEvent` | `NegotiationService.prepareMobileMoneyDeposit` | `RequestEventsListener.onNegotiationDepositPending` (notification expéditeur + voyageur) |
| `NegotiationDepositRevertedEvent` | `NegotiationService.revertMobileMoneyDeposit` | `RequestEventsListener.onNegotiationDepositReverted` (notification expéditeur seul) |

`MobileMoneyDepositOutcomeListener` et `MobileMoneyPayoutOutcomeListener` (existants, rail bid)
sont rendus « thread-aware » : aiguillage par portée du paiement (`bidId` non nul → chemin bid
existant, `negotiationThreadId` non nul → chemin fil ci-dessus). Communication inter-packages
`payments/` ↔ `requests/` exclusivement par événements Spring, comme l'exige `CLAUDE.md`.

### Logique métier critique

- **Frontière transactionnelle pawaPay** : `prepareMobileMoneyDeposit` (fil + paiement PENDING)
  et `initiateDeposit` (appel pawaPay) sont deux transactions séparées, deux appels HTTP
  distincts côté contrôleur. Jamais d'appel réseau pawaPay dans la transaction qui change le
  statut du fil — même règle que le rail bid.
- **Claim atomique, jamais de mutation après un claim bulk** : `markEscrowIfPending`,
  `markCancelledIfPending`, `markRefundedIfEscrow` sont des `UPDATE ... WHERE status = X`
  retournant le nombre de lignes touchées ; après un claim réussi, l'entité relue n'est jamais
  ré-modifiée par un setter (le payload de l'audit et de l'événement vient des valeurs déjà
  connues, pas d'une relecture mutée).
- **Fenêtre séquestre-posé/fil-pas-encore-scellé** : voir Pièges, ruling `DEPOSIT_COMPLETED_NOT_APPLIED`.
- **Garde de modération** : `prepareMobileMoneyDeposit` appelle
  `assertTravelerAnnouncementActive` avant `createPendingDeposit`, comme la carte
  (`initiatePayment`) et l'espèce (`settleCommission`) — le trajet dédié du voyageur peut avoir
  été retiré par la modération entre l'accord de prix et ce dépôt.
- **Rail à blanc** : `NegotiationService.travelerCanOffer` ferme `MOBILE_MONEY`
  (`case MOBILE_MONEY -> false`, ligne 2207) et `PackageRequestService` (ligne 1069) passe
  `false` en dur à `AnnouncementPaymentRails.offerable(...)`. Aucun fil ne peut donc atteindre
  `AWAITING_DEPOSIT` en usage réel avant le lot 2 ; tout le rail est exercé uniquement par les
  tests (fils construits en base de test avec `MOBILE_MONEY` déjà dans `availablePaymentMethods`).

### Pièges et points d'attention

1. **Ordre des verrous** : demande (`package_requests`, `findByIdForUpdate`) puis fil
   (`negotiation_threads`), jamais l'inverse — cohérent avec le reste de `NegotiationService`.
2. **Claim bulk sans setter** : ne jamais réappliquer un setter sur une entité `Payment` après
   un `markXIfY` réussi ; toute donnée nécessaire à l'audit/l'événement doit être capturée avant
   ou tirée des valeurs déjà en main.
3. **Rail toujours à blanc** : ne pas s'étonner qu'aucun fil réel n'atteigne ce code — c'est
   voulu (lot 2 ouvre `travelerCanOffer` et `PackageRequestService`).
4. **Pas de colonnes téléphone sur le fil** : contrairement à la spec initiale
   (`mobile_money_phone`/`mobile_money_country_code` chiffrés sur `negotiation_threads`,
   comme sur `bids`), le numéro payeur n'est jamais persisté sur le fil. Il est résolu à chaque
   `initiateDeposit` (corps de la requête ou, à défaut, numéro Firebase de l'expéditeur) et ne
   vit que le temps de l'appel pawaPay. V253 ne contient donc que `deposit_expires_at` et
   l'extension de la contrainte CHECK.
5. **Textes de notification** : `NotificationCaps.BODY_MAX = 72` a forcé à raccourcir les corps
   de `depositPendingSender/Traveler` et `depositReverted` par rapport aux libellés du plan
   d'origine (sens conservé, formulation plus courte).
6. **`AWAITING_DEPOSIT` échu, avant le passage du balayage** : un `prepare` sur un fil
   `AWAITING_DEPOSIT` dont l'échéance est dépassée répond 409 (`thread/not-awaiting-payment`)
   tant que le scheduler minute n'est pas passé. Conforme au brief, laissé tel quel — l'app ou
   un lot suivant pourra vouloir un message dédié.
7. **`verifyNegotiationEscrow`** (garde synchrone du checkout carte) : sans objet sur ce rail,
   jamais appelée — le scellement vient exclusivement du rappel pawaPay ou du poller, jamais du
   checkout.

## Critères d'acceptation couverts (spec lot 1)

- [x] Nouveau statut `AWAITING_DEPOSIT` et échéance de dépôt (migration V253).
- [x] Données `payments` keyées fil (`negotiation_thread_id`, `bid_id` NULL, rail `PAWAPAY`).
- [x] Service jumeau `MobileMoneyNegotiationPaymentService` (création, initiation, statut,
      libération, remboursement, confirmation, échec).
- [x] Scellement par `sealAcceptedThread` avec `paymentIntentId = null`.
- [x] Événements `payments/` → `requests/` (confirmé, échoué) et `requests/` → `notifications/`
      (dépôt en attente, dépôt annulé/reverté).
- [x] Listeners thread-aware : payout (`bidId` nul → bid matérialisé), annulation
      (`cancelNegotiationEscrow` bifurque sur le rail).
- [x] Retour Wave côté fil (`/pawapay/return/thread/{threadId}`, deep link dédié).
- [x] Expiration par scheduler dédié (`NegotiationDepositExpiryRunner`, une transaction par fil).
- [x] Trois endpoints (`initiate`, `status`, `cancel-deposit`), erreurs RFC 7807.
- [x] Audit : `NEGOTIATION_DEPOSIT_PAYMENT_CREATED/INITIATED/CONFIRMED/FAILED/CANCELLED/REFUNDED`.
- [x] Notifications (dépôt en attente expéditeur/voyageur, dépôt reverté expéditeur).
- [x] Moyens fournissables (`travelerCanOffer`, `PackageRequestService`) volontairement **non**
      ouverts — lot 2.
- [x] Livraison et versement inchangés (déjà rail-aware par le lot pawaPay bid).

## Tests

- `./mvnw test jacoco:report` → **BUILD SUCCESS**, `Tests run: 5305, Failures: 0, Errors: 0,
  Skipped: 7` (suite complète du dépôt, pas seulement le lot).
- Couverture globale (lue sur `target/site/jacoco/jacoco.csv`, Σ LINE_COVERED /
  Σ (LINE_MISSED + LINE_COVERED)) : **92,41 %** (24304 lignes couvertes / 26301 exécutables).
- Couverture par classe neuve du lot :
  - `MobileMoneyNegotiationPaymentService` : **93,41 %** (170/182 lignes).
  - `NegotiationDepositExpiryRunner` : **100 %** (17/17 lignes).
  - `NegotiationDepositListener` : **100 %** (7/7 lignes).
  - `NegotiationController` (fichier entier, endpoints existants + les 3 nouveaux) : **93,94 %**
    (62/66 lignes).
  - `NegotiationMobileMoneyAdapter` : couvert par `NegotiationMobileMoneyAdapterTest`
    (délégation pure — voir Tour de correction 1 ci-dessous).
- Tests ajoutés (classes neuves ou étendues) : `NegotiationThreadsMobileMoneyMigrationTest`,
  `MobileMoneyNegotiationPaymentServiceTest`, `NegotiationDepositExpiryRunnerTest`,
  `NegotiationDepositListenerTest`, `NegotiationServiceMobileMoneyTest`,
  `NegotiationThreadRepositoryDepositTest`, `NegotiationThreadStatusTest`,
  `NegotiationControllerMobileMoneyIT`, `PaymentServiceCancelNegotiationEscrowTest`,
  `PawapayReturnControllerIT` (cas thread), `NegotiationMobileMoneyAdapterTest`, plus
  extensions de `MobileMoneyDepositOutcomeListenerTest`, `MobileMoneyPayoutOutcomeListenerTest`,
  `NotificationTextsTest`, `RequestEventsListenerTest`, `NotificationDispatcherTest`,
  `PawapayBalanceMonitorTest`, `PawapayConfigGuardTest`, `MobileMoneyAccountServiceTest`,
  `MobileMoneyBidPaymentServiceTest`, `NegotiationServicePublishingSuspensionTest`,
  `NegotiationServiceTest`.

### Tour de correction 1 : couverture de `NegotiationMobileMoneyAdapter`

Les trois méthodes de délégation (`createPendingDeposit`, `releasePendingDeposit`,
`refundEscrowedDeposit`) n'étaient exercées par aucun test passant par cette classe concrète
(`MobileMoneyNegotiationPaymentServiceTest` teste le service directement,
`NegotiationServiceMobileMoneyTest` mock l'interface `NegotiationMobileMoneyPort`). Ajout de
`NegotiationMobileMoneyAdapterTest` (`@ExtendWith(MockitoExtension.class)`, `@Mock`/`@InjectMocks`) :
trois tests de délégation pure, un par méthode, vérifiant l'argument transmis et la valeur
rendue. `Tests run: 3, Failures: 0, Errors: 0`.

## Décisions techniques

Rulings actés pendant l'implémentation (registre `progress.md` de la tâche SDD) :

1. **Séquestre posé, fil pas encore scellé → `DEPOSIT_COMPLETED_NOT_APPLIED` (pas
   `NOTHING_PENDING`)** : le plan initial faisait rendre `NOTHING_PENDING` par
   `releasePendingDeposit` pour tout statut de paiement différent de `PENDING`, y compris
   `ESCROW`. Conséquence si laissé tel quel : le balayage d'expiration ou un renoncement de
   l'expéditeur, arrivant pendant la fenêtre entre le claim `PENDING → ESCROW` (rappel pawaPay,
   déjà commité) et le scellement du fil (`finalizeAfterMobileMoneyDeposit`, encore en vol dans
   sa propre transaction asynchrone), aurait rétrogradé le fil à `AWAITING_PAYMENT` alors qu'un
   dépôt valide venait d'être encaissé — argent engagé, accord perdu côté fil. Décision :
   `ESCROW` retourne `DEPOSIT_COMPLETED_NOT_APPLIED`, le fil ne bouge pas, une alerte admin
   dédupliquée est levée (`NegotiationDepositExpiryRunner`), et le scellement arrive par la voie
   normale (`finalize` dès que sa transaction commite). Coût si le ruling était infondé : un fil
   pourrait rester coincé en `AWAITING_DEPOSIT` avec le séquestre posé jusqu'à intervention
   manuelle — jamais un remboursement à tort d'un dépôt valide.
2. **Garde de modération ajoutée dans `prepareMobileMoneyDeposit`** : le plan omettait l'appel à
   `assertTravelerAnnouncementActive` avant `createPendingDeposit`. Ajoutée par cohérence avec
   la carte (`initiatePayment`) et l'espèce (`settleCommission`), qui la portent déjà — sinon un
   dépôt aurait pu être posé sur un trajet dédié retiré par la modération entre l'accord de prix
   et le paiement.
3. **Pas de colonnes téléphone sur le fil** (`mobile_money_phone`/`mobile_money_country_code`
   prévues par la spec initiale, comme sur `bids`) : non ajoutées. Le numéro payeur est résolu à
   la volée à chaque `initiateDeposit` (corps de requête, sinon numéro Firebase) et ne persiste
   nulle part sur `negotiation_threads`. V253 se limite donc à `deposit_expires_at` et
   l'extension de la contrainte CHECK. Coût si à revoir : ajouter les deux colonnes et le
   chiffrement associé serait une migration V(n+1) isolée, sans impact sur le reste du rail.
4. **Textes de push raccourcis à 72 caractères** (`NotificationCaps.BODY_MAX`) : les corps de
   `depositPendingSender`, `depositPendingTraveler` et `depositReverted` dépassaient cette
   limite dans le libellé du plan d'origine. Raccourcis en conservant titre et sens ; asymétrie
   mineure notée et assumée entre les variantes `deposit-expired` / `sender-cancelled` de
   `depositReverted` (« L'accord tient » absent de l'une). Coût si à revoir : un texte moins
   explicite dans un cas, correction d'une ligne sans effet sur le reste du rail.
5. **Signatures réelles du code existant, vérifiées avant écriture** (pré-vol de la tâche SDD) :
   `PawapayProviderResolver.Resolved(provider, countryAlpha2, msisdn, config)`,
   `PawapayProviderConfig.Limits(min, max, authType, status)`,
   `PawapayOperationCompletedEvent(operationId, kind, paymentId)`,
   `com.yadony.api.admin.AdminAlertEscalator`, `UserEntity.setMobileMoneyStatus(MobileMoneyPayoutStatus.ACTIVE)`
   — le plan prévoyait de les vérifier avant usage, écart sans conséquence.
6. **Test de migration V253 par lecture de la contrainte, pas par INSERT** : `pg_get_constraintdef`
   vérifie que `AWAITING_DEPOSIT` est bien dans `chk_neg_thread_status` plutôt qu'un INSERT réel
   dans `negotiation_threads`, dont les FK vers `package_requests`/`users` auraient rendu
   l'INSERT nu impossible sans données satellites. Coût : un test un peu moins comportemental,
   la contrainte reste bien exercée.

## Vérifications de non-exposition (rail à blanc)

Les deux ancres de fermeture existent encore, code inchangé par ce lot :

- `src/main/java/com/yadony/api/requests/service/NegotiationService.java:2207` —
  `case MOBILE_MONEY -> false;` dans `travelerCanOffer`.
- `src/main/java/com/yadony/api/requests/service/PackageRequestService.java:1069` — troisième
  argument `false` codé en dur, passé à `AnnouncementPaymentRails.offerable(...)`, à la place du
  `travelerHasMobileMoney` réel prévu par la spec pour le lot 2.

(Le brief de cette tâche citait un identifiant littéral `travelerHasMobileMoney` dans
`PackageRequestService.java` : il n'existe pas sous ce nom dans cette version du code — c'est le
littéral `false` ligne 1069 qui joue ce rôle. Écart de rédaction du brief sans conséquence,
l'invariant produit — aucun fil ne peut fournir `MOBILE_MONEY` — est bien vérifié.)
