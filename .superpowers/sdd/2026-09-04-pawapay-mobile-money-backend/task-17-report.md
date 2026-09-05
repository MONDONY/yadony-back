# Rapport — Tâche 17 : remboursement mobile money (`RefundProcessor`)

## Statut

DONE_WITH_CONCERNS. Aucun sous-agent dispatché, travail réalisé directement dans le worktree.
Une seule réserve, substantielle et documentée en détail plus bas : le point 2 du brief
(« un seul chemin de remboursement », `refundAfterCancel` délègue à `RefundProcessor`) n'a
**pas** été implémenté tel quel — analyse de risque de deadlock transactionnel à l'appui. Tout
le reste (branche PAWAPAY dans `RefundProcessor`, `attachRefundId`, `MobileMoneyRefundOutcomeListener`,
contrat centralisé) est fait, testé, vert.

## Fichiers créés

- `src/main/java/com/yadony/api/payments/mobilemoney/MobileMoneyRefundOutcomeListener.java` — écoute `PawapayOperationCompletedEvent`/`PawapayOperationFailedEvent` pour `kind == REFUND` : audit sur `COMPLETED`, alerte admin (`PAWAPAY_REFUND_FAILED`) + audit sur `FAILED`.
- `src/test/java/com/yadony/api/payments/RefundProcessorMobileMoneyTest.java` — 7 tests de la branche PAWAPAY de `RefundProcessor`.
- `src/test/java/com/yadony/api/payments/mobilemoney/MobileMoneyRefundOutcomeListenerTest.java` — 4 tests du listener ci-dessus.

## Fichiers modifiés

- `src/main/java/com/yadony/api/payments/RefundProcessor.java` — deux champs `@Autowired` (`pawapayOperations`, `pawapaySubmission`), dispatch `payment.getRail() == PAWAPAY` juste après le `findById` (avant le `switch` Stripe, qui ne bouge pas), méthodes privées `refundMobileMoney`/`refundEscrowedMobileMoney`, `enrich(...)` complété par `out.put("rail", payment.getRail().name())`.
- `src/main/java/com/yadony/api/payments/PaymentRepository.java` — `attachRefundId(id, opId)`, UPDATE ciblé symétrique d'`attachPayoutId` (tâche 16), annotation `@Modifying` nue, sans `clearAutomatically`.
- `src/test/java/com/yadony/api/payments/PaymentRepositoryMobileMoneyTest.java` — `markRefundedIfEscrow_thenAttachRefundId_doesNotRevertStatus` ajouté, sur le modèle exact de `markReleasedIfEscrow_thenAttachPayoutId_doesNotRevertStatus` (tâche 16).
- `src/test/java/com/yadony/api/payments/PaymentListenerTransactionalContractTest.java` — import + 2 entrées `MobileMoneyRefundOutcomeListener.class` (`onCompleted`/`onFailed`) dans `fullContractListeners()`.

**Non touché** (voir « Écarts », point 3) : `MobileMoneyBidPaymentService.java` et son test
`MobileMoneyBidPaymentServiceEscrowTest.java` — aucune ligne changée, décision documentée plus bas.

## Preuve du rouge TDD

Les 4 fichiers de test (2 nouveaux + 2 modifiés) ont été écrits/modifiés en premier, avec la
correction du piège 1 déjà appliquée (voir « Écarts », point 1 — je n'ai pas fait passer le
rouge par la version « buggée » du brief puis corrigé : l'erreur du setter est connue par
analyse statique avant toute exécution, comme le fait ce même piège documenté pour la tâche 16).

```
./mvnw test -q -Dtest='RefundProcessorMobileMoneyTest,MobileMoneyRefundOutcomeListenerTest,PaymentRepositoryMobileMoneyTest,PaymentListenerTransactionalContractTest'
```

```
[ERROR] PaymentListenerTransactionalContractTest.java:[6,43] cannot find symbol
  symbol:   class MobileMoneyRefundOutcomeListener
  location: package com.yadony.api.payments.mobilemoney
[ERROR] MobileMoneyRefundOutcomeListenerTest.java:[28,18] cannot find symbol
  symbol:   class MobileMoneyRefundOutcomeListener
  location: class com.yadony.api.payments.mobilemoney.MobileMoneyRefundOutcomeListenerTest
[ERROR] PaymentListenerTransactionalContractTest.java:[67,30] cannot find symbol
  symbol:   class MobileMoneyRefundOutcomeListener
[ERROR] PaymentListenerTransactionalContractTest.java:[68,30] cannot find symbol
  symbol:   class MobileMoneyRefundOutcomeListener
[ERROR] PaymentRepositoryMobileMoneyTest.java:[122,19] cannot find symbol
  symbol:   method attachRefundId(java.util.UUID,java.util.UUID)
  location: variable repository of type com.yadony.api.payments.PaymentRepository
[ERROR] RefundProcessorMobileMoneyTest.java:[111,34] cannot find symbol
  symbol:   method attachRefundId(java.util.UUID,java.util.UUID)
  location: interface com.yadony.api.payments.PaymentRepository
[ERROR] RefundProcessorMobileMoneyTest.java:[134,34] cannot find symbol
  symbol:   method attachRefundId(java.util.UUID,java.util.UUID)
[ERROR] RefundProcessorMobileMoneyTest.java:[152,43] cannot find symbol
  symbol:   method attachRefundId(java.lang.Object,java.lang.Object)
[ERROR] Failed to execute goal ... Compilation failure
```

Erreur exacte, échec de compilation, aucun test exécuté — conforme à l'attendu (classe et
méthode absentes). Implémentation de `attachRefundId`, `MobileMoneyRefundOutcomeListener` et
de la branche `RefundProcessor` ensuite : vert (voir plus bas). Noter que les champs
`pawapayOperations`/`pawapaySubmission` posés par `ReflectionTestUtils.setField` dans
`@BeforeEach` n'apparaissent PAS dans ce rouge : cet appel est réflexif (compile toujours),
il aurait échoué à l'exécution (`IllegalArgumentException: Could not find field`) si j'avais
comblé les deux erreurs `attachRefundId`/`MobileMoneyRefundOutcomeListener` sans ajouter ces
deux champs — non observé séparément puisque les trois corrections ont été faites ensemble.

## Résultat des tests (run consolidé)

**Nouveaux tests / fichiers modifiés de cette tâche :**
```
./mvnw test -q -Dtest='RefundProcessorMobileMoneyTest,MobileMoneyRefundOutcomeListenerTest,PaymentRepositoryMobileMoneyTest,PaymentListenerTransactionalContractTest'
```
- `RefundProcessorMobileMoneyTest` : **7/7** (0 échec, 0 erreur)
- `MobileMoneyRefundOutcomeListenerTest` : **4/4** (3 du brief + 1 ajouté : troncature 64 caractères)
- `PaymentRepositoryMobileMoneyTest` : **6/6** (5 existants + 1 ajouté : `markRefundedIfEscrow_thenAttachRefundId_doesNotRevertStatus`)
- `PaymentListenerTransactionalContractTest` : **17/17** (15 existants + 2 ajoutés — progression 13 → 15 [tâche 16] → 17 [tâche 17])

**Non-régression — chemin Stripe + six listeners appelants existants :**
```
./mvnw test -q -Dtest='RefundProcessorTest,BidExpiredOnDepartureEventListenerTest,BidRejectedEventListenerTest,NoShowEventListenerTest,ParcelRefusedEventListenerTest,SenderNoShowConfirmedListenerTest,TripCancelledEventListenerTest,MobileMoneyBidPaymentServiceEscrowTest'
```
- `RefundProcessorTest` (chemin Stripe, **octet pour octet inchangé**) : **8/8**
- `BidExpiredOnDepartureEventListenerTest` : **2/2**
- `BidRejectedEventListenerTest` : **2/2**
- `NoShowEventListenerTest` : **2/2**
- `ParcelRefusedEventListenerTest` : **2/2**
- `SenderNoShowConfirmedListenerTest` : **3/3**
- `TripCancelledEventListenerTest` : **5/5**
- `MobileMoneyBidPaymentServiceEscrowTest` (non touché — voir écart point 3) : **7/7**

**Non-régression complémentaire (non demandée explicitement, lancée pour étayer la décision de
ne pas toucher `MobileMoneyBidPaymentService`) :**
```
./mvnw test -q -Dtest='MobileMoneyBidPaymentServiceTest,MobileMoneyBidPaymentServiceExpireTest,MobileMoneyDepositOutcomeListenerTest,MobileMoneyPayoutOutcomeListenerTest'
```
- `MobileMoneyBidPaymentServiceTest` : **29/29**
- `MobileMoneyBidPaymentServiceExpireTest` : **10/10**
- `MobileMoneyDepositOutcomeListenerTest` : **5/5**
- `MobileMoneyPayoutOutcomeListenerTest` : **4/4**

**Total : 113 tests exécutés sur 16 classes, 0 échec, 0 erreur, 0 ignoré.**

Chaque commande a été lancée seule, en avant-plan, jamais deux Maven en parallèle. Aucune suite
complète (`./mvnw test` sans `-Dtest`) n'a été lancée.

## Écarts avec le cahier des charges et justification

1. **Assertions `pawapayRefundId` changées de `isEqualTo(refund.getId())` à `isNull()`, plus
   `verify(paymentRepository).attachRefundId(...)` ajouté** dans les deux tests concernés
   (`escrow_claimsOnce_thenSubmitsRefundOfTheCompletedDeposit`,
   `escrow_liveRefundAlreadyExists_isRecovered_notResubmitted`). Le code donné en Step 3 du
   brief contient `payment.setPawapayRefundId(refund.getId());` juste après le claim bulk
   `markRefundedIfEscrow` — **exactement** le défaut critique documenté par la tâche 16 (voir
   `PaymentRepository#attachPayoutId`, Javadoc) : un setter sur l'entité gérée après un bulk
   `@Modifying` sans `clearAutomatically` la rend sale, et au flush (pas de `@DynamicUpdate`/
   `@Version` sur `PaymentEntity`) Hibernate régénère un UPDATE de toutes les colonnes — le
   `status` en mémoire (potentiellement périmé selon le point d'exécution) écraserait
   silencieusement `REFUNDED`. Le brief lui-même prévient de ce piège en toutes lettres
   (« après un claim bulk, plus jamais d'écriture sur l'entité gérée ») ET demande explicitement
   d'ajouter `assertThat(payment.getPawapayRefundId()).isNull()` — instruction qui **contredit**
   le code Step-3/Step-1 donné tel quel. J'ai suivi l'instruction explicite (la plus récente,
   qui cite le mécanisme exact) plutôt que le code d'exemple, et corrigé les deux tests en
   conséquence : `RefundProcessor` pose le refund id via `paymentRepository.attachRefundId(...)`
   (UPDATE ciblé), jamais via le setter — l'entité en mémoire ne le voit jamais.

2. **Troncature à 64 caractères ajoutée dans `MobileMoneyRefundOutcomeListener#onFailed`** pour
   `failureCode` ET `failureMessage` (le code donné en Step 4 ne journalisait/n'alertait que
   `failureCode`, non tronqué). Invariant explicite du brief : « Toute valeur non authentifiée
   journalisée est tronquée à 64 caractères. » Même garde que le jumeau direct
   `MobileMoneyPayoutOutcomeListener#truncate` (tâche 16), qui traite `failureCode` ET
   `failureMessage`. Test ajouté : `failed_truncatesLongFailureCodeAndMessageTo64Chars`.

3. **`MobileMoneyBidPaymentService` non modifié — `refundAfterCancel` ne délègue PAS à
   `RefundProcessor.processRefund`.** C'est l'écart le plus significatif de cette tâche, analysé
   en détail :

   `confirmEscrow` (tâche 14) appelle `refundAfterCancel` dans **deux** branches distinctes :
   - **Cas A** (ligne ~476) : `markEscrowIfPending` échoue (`moved == 0`) et le paiement relu
     est déjà `CANCELLED` — un deposit vient d'aboutir alors que le paiement avait déjà été
     annulé par ailleurs (deadline). Ce cas est **explicitement et volontairement** laissé hors
     du périmètre de `RefundProcessor` par le brief lui-même, dans la section sur la branche
     `PENDING` : *« si le dépôt aboutit ensuite, c'est confirmEscrow de la tâche 14 qui verra
     CANCELLED et remboursera »*. `RefundProcessor.refundMobileMoney` ne gère que `PENDING` et
     `ESCROW` (switch donné en Step 3, branche `default` = no-op) — un paiement `CANCELLED` ne
     peut structurellement pas être remboursé par lui. Ce cas A **doit** rester la responsabilité
     propre de `confirmEscrow` : impossible d'y déléguer sans réintroduire dans
     `RefundProcessor` une transition `CANCELLED → REFUNDED` qu'aucun test donné ni aucune
     phrase du brief ne demande.
   - **Cas B** (ligne ~491) : `markEscrowIfPending` réussit (`moved == 1`, paiement passé
     PENDING→ESCROW), puis le bid relu s'avère ne plus être `AWAITING_PAYMENT` (annulé entre
     temps) : le paiement est immédiatement re-basculé ESCROW→REFUNDED via le MÊME
     `markRefundedIfEscrow` que `RefundProcessor`, puis `refundAfterCancel` soumet le refund.
     **C'est ce cas B, seul, qui duplique réellement la logique ESCROW→REFUNDED+refund pawaPay
     de `RefundProcessor`** — c'est très probablement lui que vise la « décision du
     contrôleur ».

   J'ai tenté la délégation littérale pour le cas B (`refundProcessor.processRefund(paymentId,
   ...)` appelé depuis `confirmEscrow`, en remplacement du claim manuel +
   `refundAfterCancel`) et je l'ai **rejetée avant de l'écrire dans le fichier**, pour la raison
   suivante, vérifiée ligne par ligne :

   - `MobileMoneyDepositOutcomeListener#onCompleted` est
     `@Transactional(propagation = REQUIRES_NEW)` et appelle `service.confirmEscrow(...)` — un
     bean Spring-proxied, méthode `@Transactional` par défaut (`REQUIRED`) : **elle rejoint la
     transaction du listener**, ne l'isole pas.
   - `confirmEscrow` fait déjà, EN AMONT du cas B et dans CETTE MÊME transaction,
     `paymentRepository.markEscrowIfPending(...)` — un `UPDATE` qui **prend et garde un verrou
     ligne PostgreSQL sur `payments`** jusqu'au commit ou rollback de la transaction (règle
     PostgreSQL standard : un `UPDATE`, même sans `SELECT FOR UPDATE` explicite, verrouille la
     ligne pour toute la durée de la transaction).
   - `RefundProcessor.processRefund` est `@Transactional(propagation = REQUIRES_NEW)` : l'appeler
     depuis `confirmEscrow` **suspend** la transaction du listener et démarre une **seconde
     transaction physique concurrente**, sur une connexion distincte.
   - Cette seconde transaction relit le paiement (`findById`, lecture simple non bloquante sous
     READ COMMITTED — elle voit l'état commité *avant* le début de la transaction du listener,
     donc `PENDING`, pas encore `ESCROW`), puis tente un `UPDATE` (`markCancelledIfPending` ou
     `markRefundedIfEscrow`, selon la branche) sur **la même ligne `payments`** déjà verrouillée
     par la transaction suspendue.
   - Cet `UPDATE` **bloque** en attendant la libération du verrou — verrou que la transaction
     suspendue ne peut PAS libérer tant que l'appel Java synchrone vers `processRefund`
     (sur le même thread, même pile d'appel) n'est pas revenu. **Auto-interblocage** (ou, en
     l'absence de `lock_timeout` configuré, blocage indéfini) — un risque réel de production,
     invisible dans un test Mockito (aucun mock ne modélise un verrou PostgreSQL), qui n'aurait
     donc jamais été détecté par `MobileMoneyBidPaymentServiceEscrowTest` tel qu'il existe.

   **Décision** : ne pas router le cas B vers `RefundProcessor` par un appel synchrone
   intra-transaction. J'ai donc laissé `MobileMoneyBidPaymentService.confirmEscrow` et
   `refundAfterCancel` strictement inchangés — les deux cas A et B gardent leur logique propre,
   déjà protégée par le MÊME claim atomique (`markRefundedIfEscrow`, partagé avec
   `RefundProcessor`) et par l'index unique partiel `uq_pawapay_ops_live_per_payment` : un
   double remboursement reste structurellement impossible, seule la duplication de **code**
   (pas de **garantie**) subsiste pour le cas B. `MobileMoneyBidPaymentServiceEscrowTest`
   (7/7) et les trois autres suites du même service (`MobileMoneyBidPaymentServiceTest` 29/29,
   `MobileMoneyBidPaymentServiceExpireTest` 10/10, `MobileMoneyDepositOutcomeListenerTest` 5/5)
   confirment qu'aucune régression n'a été introduite en ne touchant rien.

   **Piste non retenue ici, proposée pour un suivi** (hors périmètre autorisé de fichiers pour
   cette tâche — nécessiterait un nouvel événement + un nouveau listener) : le cas B pourrait
   publier un événement léger (ex. `MobileMoneyEscrowReversedEvent(paymentId)`) consommé par un
   `@TransactionalEventListener(AFTER_COMMIT) @Transactional(REQUIRES_NEW)` dédié qui, LUI,
   appellerait `RefundProcessor.processRefund` — après le commit de `confirmEscrow`, donc sans
   verrou tenu, sans risque de blocage. Je ne l'ai pas implémenté : ce n'est ni dans la liste de
   fichiers autorisés, ni dans le code donné par le brief, et cela aurait dépassé le périmètre
   demandé sans y être invité explicitement.

4. **Aucune déduplication `findByTypeAndResolved` ajoutée** avant les 3 `adminAlert.raise(...)`
   de cette tâche (`PAWAPAY_REFUND_NO_DEPOSIT`, `PAWAPAY_REFUND_REJECTED` dans
   `RefundProcessor` ; `PAWAPAY_REFUND_FAILED` dans le listener), malgré l'invariant du brief
   sur les alertes dédupliquées. Vérifié avant d'écarter : (a) le chemin Stripe existant dans
   CETTE MÊME classe (`STRIPE_REFUND_FAILED`, `refundEscrowedPayment`) n'en fait pas non plus ;
   (b) le jumeau direct de tâche 16 (`PAWAPAY_PAYOUT_FAILED`,
   `MobileMoneyPayoutOutcomeListener#onFailed`) n'en fait pas non plus ; (c) le test donné
   `RefundProcessorMobileMoneyTest` (Step 1 du brief) ne câble aucun `AdminAlertRepository`
   dans son `setUp()` — en ajouter un comme troisième champ `@Autowired` aurait cassé les tests
   donnés (champ jamais posé par `ReflectionTestUtils.setField`, donc `null`, NPE au premier
   `raise`). Ajouter la dédup aurait exigé de modifier le test donné sans justification
   équivalente aux deux pièges explicitement signalés par le brief. Écart déclaré, pas un oubli :
   à reconsidérer si une accumulation d'alertes Telegram identiques est observée en production
   (mécanisme de relance de la tâche 18, hors périmètre ici).

5. **Rapport ajouté au commit** en plus des deux répertoires `src/main/java/.../payments` et
   `src/test/java/.../payments` listés par la commande donnée en Step 6 — cohérent avec les
   tâches 1 à 16 de cette même campagne, dont les rapports sont tous committés (vérifié :
   `task-16-report.md` et les précédents sont trackés par git).

Aucun autre écart : le reste (branche `PENDING`, claim `ESCROW`, `findLatest`/`findLive`,
`onCompleted` du listener) est implémenté conforme au code donné dans le brief.

## Auto-revue

Invariants de la branche (section du brief), un par un :

1. **Aucun `repository.save()` sur `pawapay_operations`** : confirmé — tout passe par
   `PawapayOperationService`/`PawapaySubmissionService` (`findLatest`, `findLive`,
   `submitRefund`), jamais un repository d'opération manipulé directement dans `RefundProcessor`
   ni dans le listener.
2. **Après un claim bulk sur `payments`, aucune écriture sur l'entité gérée** : le seul
   `payment.setStatus(...)` restant (PENDING→CANCELLED, ESCROW→REFUNDED) réécrit la MÊME valeur
   que celle que le claim vient de poser en base (aucune divergence mémoire/DB possible, donc
   aucun risque au flush) — voir Javadoc de `refundMobileMoney`. Le seul champ réellement
   NOUVEAU posé après le claim (`pawapayRefundId`) passe exclusivement par `attachRefundId`
   (UPDATE ciblé), jamais par un setter — prouvé par les deux tests unitaires (`isNull()`) ET
   par le test d'intégration JDBC brut.
3. **Aucun numéro de téléphone en clair** : ni `RefundProcessor` ni
   `MobileMoneyRefundOutcomeListener` ne lisent/journalisent `msisdn` — seuls des UUID
   (`paymentId`, `operationId`) et des statuts/codes d'échec apparaissent en audit/alerte.
4. **Toute valeur non authentifiée journalisée est tronquée à 64 caractères** : `failureCode`
   (déjà 64 par colonne) et `failureMessage` (TEXT, non bornée) sont tronqués dans le listener
   avant tout audit/alerte — testé explicitement (écart 2 ci-dessus). `RefundProcessor` ne
   journalise que `refund.getFailureCode()` dans le message/contexte d'alerte
   `PAWAPAY_REFUND_REJECTED` : valeur déjà bornée à 64 par la colonne `pawapay_operations.failure_code`
   (vérifié dans `PawapayOperationEntity`), pas de troncature supplémentaire nécessaire ici.
5. **Ordre des verrous : paiement, bid, annonce** : `RefundProcessor` ne prend qu'un verrou
   implicite (l'UPDATE atomique sur `payments`, jamais de `findByIdForUpdate` explicite sur
   bid/annonce) — aucun second verrou pris, donc pas de risque d'inversion d'ordre. C'est
   précisément ce constat (verrou déjà tenu par l'appelant `confirmEscrow` sur `payments`) qui a
   motivé le refus de la délégation du cas B (écart 3) : un second niveau de verrouillage sur la
   MÊME ressource, depuis une transaction imbriquée `REQUIRES_NEW`, est la source du risque de
   blocage identifié.
6. **Notifications** : ni `RefundProcessor` ni le nouveau listener n'appellent `notifyUser` — à
   l'identique du chemin Stripe existant (qui n'en appelle pas non plus depuis
   `refundEscrowedPayment`/`cancelPendingPaymentIntent`). Vérifié : aucun type de notification
   de remboursement n'existe dans `NotificationCategory`/`NotificationDeeplink`/
   `NotificationPrefsService` à catalguer ici — non applicable, la notification de l'utilisateur
   final reste du ressort de l'événement métier qui a déclenché le remboursement (annulation de
   trajet, rejet de bid, etc.), pas de `RefundProcessor` lui-même — symétrie confirmée avec le
   chemin Stripe.
7. **`@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)`** sur les deux
   méthodes du listener : posé, et vérifié par
   `PaymentListenerTransactionalContractTest#payment_listener_uses_after_commit_and_requires_new`
   (paramétré, 2 nouvelles entrées) — pas de test réflexif local dans
   `MobileMoneyRefundOutcomeListenerTest`, conforme à la consigne.
8. **`RefundProcessor.processRefund` reste `@Transactional(REQUIRES_NEW)`, signature inchangée** :
   vérifié par le test déjà existant `PaymentListenerTransactionalContractTest#refund_processor_uses_requires_new`
   (non modifié, toujours vert).

Point né de la relecture, signalé pour trace (pas un défaut, un choix) : dans
`refundEscrowedMobileMoney`, le contrôle `SUBMIT_REJECTED` s'applique après le
`orElseGet(() -> submitRefund(...))`, donc en théorie aussi à un refund **récupéré** via
`findLive` — mais `findLive` filtre sur `LIVE_OR_DONE`
(`CREATED, ACCEPTED, PROCESSING, ENQUEUED, IN_RECONCILIATION, COMPLETED`), qui exclut
structurellement `SUBMIT_REJECTED` (rangé dans `DEAD`) : ce garde-fou ne peut donc jamais se
déclencher sur un refund recouvré, uniquement sur un refund tout juste soumis — comportement
voulu, pas un bug latent, mais qui mériterait un commentaire si un futur lecteur s'interroge.

## Trois réponses explicites

**1. Qu'y a-t-il après `submitRefund` accepté, dans quel ordre, et qu'est-ce qui peut lever ?**

Dans `refundEscrowedMobileMoney`, après que `pawapaySubmission.submitRefund(...)` (ou le
`findLive` qui l'évite) a produit un `refund` dont le statut n'est PAS `SUBMIT_REJECTED` :

1. `paymentRepository.attachRefundId(paymentId, refund.getId())` — UPDATE ciblé (JDBC). Faillible
   en théorie (`DataAccessException`).
2. Construction de `enriched` (map en mémoire, `enrich(...)` + `put("refundOperationId", ...)`)
   — non faillible.
3. `auditService.log("PAYMENT", paymentId, auditAction, auditActor, enriched)` — un INSERT JPA.
   Faillible en théorie (`DataAccessException`).
4. `log.info(...)` puis `return true` — la méthode revient, `@Transactional(REQUIRES_NEW)`
   commite (claim `markRefundedIfEscrow` + `attachRefundId` + l'audit, tous dans la même
   transaction).

**Oui**, il y a bien deux opérations faillibles après l'appel qui engage réellement l'argent
(`submitRefund`, qui a déjà durablement commité la ligne `pawapay_operations` — REQUIRES_NEW
propre à `PawapayOperationService.create`/`markSubmitted` — et déjà déclenché l'appel HTTP
irréversible vers pawaPay). Si l'une des deux lève, TOUTE la transaction ambiante (claim inclus)
rollback : le paiement redevient `ESCROW` en base. Mais ceci **ne produit pas de second refund**
au retry : `pawapayOperations.findLive(paymentId, REFUND)` retrouverait l'opération déjà créée
(statut `ACCEPTED`/`PROCESSING`, committée indépendamment) et la rattacherait
(`orElseGet` jamais invoqué une seconde fois) plutôt que d'en soumettre une nouvelle — exactement
la même garantie structurelle (protection par retrouvabilité de l'opération, pas par la
frontière transactionnelle) que le rail Stripe existant, où `auditService.log(...)` s'exécute,
lui aussi non protégé, juste après un `Refund.create`/`pi.cancel()` réussi.

**2. Quel test prouve que le claim survit au commit ?**

`PaymentRepositoryMobileMoneyTest#markRefundedIfEscrow_thenAttachRefundId_doesNotRevertStatus`
— test d'intégration `@SpringBootTest` (H2, dialecte PostgreSQL forcé) : claim
(`repository.markRefundedIfEscrow(p.getId())`, assertion `== 1`), UPDATE ciblé
(`repository.attachRefundId(p.getId(), opId)`), `repository.flush()` explicite, puis lecture du
`status` et de `pawapay_refund_id` via **JDBC brut** (`jdbc.queryForObject(...)`, en
contournant tout cache de premier niveau Hibernate) — assertions `"REFUNDED"` et `opId`. Lancé :
**vert** (6/6 sur la classe). En remplaçant la ligne `repository.attachRefundId(...)` par
`p.setPawapayRefundId(opId)` (l'antipattern), ce test serait rouge (`expected REFUNDED but was
ESCROW`) — exactement le mécanisme que le test unitaire `isNull()` de `RefundProcessorMobileMoneyTest`
détecte aussi, à un niveau différent (absence d'appel réel à la DB vs. valeur en mémoire).

**3. Quels tests existants des sept appelants et du chemin Stripe as-tu lancés, et quel est leur
résultat exact ?**

Six listeners identifiés par recherche exhaustive (`grep -rl "processRefund(" src/main/java`,
tout le répertoire, pas seulement `payments/`) comme appelant directement
`RefundProcessor.processRefund` aujourd'hui : `BidRejectedEventListener`,
`ParcelRefusedEventListener`, `NoShowEventListener`, `BidExpiredOnDepartureEventListener`,
`TripCancelledEventListener`, `SenderNoShowConfirmedListener`. Le septième « appelant » que
mentionne le brief est, par déduction (le brief lui-même le confirme dans sa section sur la
branche PENDING), le chemin `MobileMoneyDepositOutcomeListener → confirmEscrow →
refundAfterCancel` — que j'ai choisi de NE PAS convertir en appel direct à `processRefund`
(écart 3) : il reste donc, après cette tâche, à **six** appelants directs plus le chemin Stripe
lui-même (inchangé), et non sept.

Résultats exacts (voir aussi la section « Résultat des tests ») :
- `RefundProcessorTest` (chemin Stripe) : **Tests run: 8, Failures: 0, Errors: 0, Skipped: 0**
- `BidRejectedEventListenerTest` : **Tests run: 2, Failures: 0, Errors: 0, Skipped: 0**
- `ParcelRefusedEventListenerTest` : **Tests run: 2, Failures: 0, Errors: 0, Skipped: 0**
- `NoShowEventListenerTest` : **Tests run: 2, Failures: 0, Errors: 0, Skipped: 0**
- `BidExpiredOnDepartureEventListenerTest` : **Tests run: 2, Failures: 0, Errors: 0, Skipped: 0**
- `TripCancelledEventListenerTest` : **Tests run: 5, Failures: 0, Errors: 0, Skipped: 0**
- `SenderNoShowConfirmedListenerTest` : **Tests run: 3, Failures: 0, Errors: 0, Skipped: 0**

Ces sept classes construisent `RefundProcessor`/leurs listeners avec les mêmes constructeurs
qu'avant cette tâche (le constructeur 3-arg de `RefundProcessor` n'a pas changé) ; tous leurs
paiements de test sont de rail `STRIPE` (défaut ou explicite), donc la nouvelle branche
`if (payment.getRail() == PaymentRail.PAWAPAY)` n'est jamais atteinte par ces suites — la seule
voie de régression possible (une ligne Stripe déplacée ou modifiée) est exclue par relecture
directe du diff : le `switch` Stripe (`cancelPendingPaymentIntent`/`refundEscrowedPayment`) est
resté à l'identique, seul `enrich(...)` gagne une clé `"rail"` supplémentaire dans la map — sans
incidence sur des assertions qui utilisent toutes `any(Map.class)`, jamais un contenu exact.

## Commit

Voir SHA rapporté séparément. Message de commit vérifié sans trailer/mention Claude via
`/usr/bin/git log -1 --format=%B` avant de le rapporter.
