# Story wallet-refund-mobile-money, lot 2 (Backend)

**Date :** 2026-09-18
**Status :** ✅ Complète

Spec et plan : dépôt `docs-claude`, plan `.superpowers/sdd/plan-lot2/` (briefs et rapports des tâches 1 à 6, ce fichier clôt la tâche 6).

## Résumé

Le remboursement automatique du solde wallet (`WalletSelfRefundService`) sait désormais rembourser une recharge mobile money par le rail pawaPay, avec un repli sur versement quand l'opérateur ne propose pas le remboursement, et retient un frais réel (Stripe ou pawaPay) sur toute recharge jamais entamée. Le contrat HTTP expose ces frais, le net réellement versé, le rail et la destination masquée, de façon strictement additive.

## Fichiers créés

- `src/main/java/com/yadony/api/payments/wallet/fees/WalletRefundFeeCalculator.java` : calcul pur du frais retenu sur une recharge remboursée, nul si elle a été partiellement dépensée, sinon frais réel du rail d'origine plafonné au restant.
- `src/main/java/com/yadony/api/payments/wallet/fees/StripeFeeSource.java` : frais Stripe réel lu sur `PaymentIntent.latest_charge.balance_transaction.fee` (cache Caffeine 1 h par `paymentIntentId|currency`, jamais de `null` mis en cache), avec repli configuré (`percent`/`fixed`) si illisible.
- `src/main/java/com/yadony/api/payments/wallet/fees/PawapayFeeTable.java` : barème pawaPay (`deposit-percent` + `refund-percent`) par opérateur, avec repli sur un défaut configuré.
- `src/main/java/com/yadony/api/payments/wallet/WalletRefundRail.java` : dérive le rail (`STRIPE`/`PAWAPAY`) et l'identifiant de dépôt pawaPay depuis un `paymentRef` (`"pawapay:" + depositId`).
- `src/main/java/com/yadony/api/payments/wallet/WalletRefundRailIssuer.java` : interface d'émission par rail (`issue`, `reconcile`), implémentée par le Stripe historique (resté dans `WalletSelfRefundService`) et par pawaPay.
- `src/main/java/com/yadony/api/payments/wallet/WalletPawapayRefundIssuer.java` : émetteur pawaPay : remboursement partiel du dépôt si l'opérateur le supporte, sinon versement direct ; repli sur versement si le remboursement échoue ; transition partagée idempotente (`applyPawapayOutcome`) et garde d'âge de 60 s avant tout envoi.
- `src/main/java/com/yadony/api/payments/wallet/WalletRefundOutcomeListener.java` : trois entrées `AFTER_COMMIT` + `REQUIRES_NEW` : lance l'appel réseau après le commit du lien (`onInitiationRequested`), règle l'item sur une issue finale (`onCompleted`/`onFailed`), toujours demande verrouillée avant item.
- `src/main/java/com/yadony/api/payments/wallet/WalletPawapayRefundInitiationEvent.java` : événement `(itemId, operationId)` publié dans la transaction qui pose le lien, jamais avant.
- Tests créés : `WalletRefundFeeCalculatorTest`, `StripeFeeSourceTest`, `PawapayFeeTableTest`, `WalletRefundRailTest`, `WalletPawapayRefundIssuerTest`, `WalletRefundOutcomeListenerTest`, `WalletRefundRequestSummaryResponseTest`.

## Fichiers modifiés

- `payments/wallet/WalletRefundAllocation.java` / `WalletRefundAllocator.java` : `RefundableTopup` gagne `original`, `fee`, `rail`, `provider` ; `WalletRefundAllocation` gagne `fees`/`net` agrégés ; `allocate(...)` prend en plus la carte opérateur par `paymentRef` et une `FeeSources`.
- `payments/wallet/WalletSelfRefundService.java` : cœur du lot, canal posé selon le rail (mélange refusé), item `amount` brut + `feeAmount`, cibles à net nul (après arrondi à l'unité mineure) exclues avant écriture, `issueStripeRefund` émet `amount − feeAmount`, `reconcile`/`resolveIfComplete` réécrits pour ne jamais tenir un verrou pendant un appel réseau (Stripe lu hors verrou pour toutes les demandes PROCESSING d'une même lecture, `EntityManager.refresh` après verrou), nouveaux points d'accès de contrat (`details`, `destinationMasked`, `refundFeesByTransactionId`).
- `payments/wallet/WalletRefundRequestItemEntity.java` / `WalletRefundRequestItemRepository.java` : colonnes `fee_amount`, `pawapay_refund_id`, `pawapay_payout_id` (portées par V260 du lot 1) ; requêtes de reprise étendues aux deux canaux automatiques et à la clause « aucun identifiant d'émission encore posé ».
- `payments/wallet/WalletRefundIssueRecoveryScheduler.java` : reprend `AUTOMATIC_STRIPE` **et** `AUTOMATIC_PAWAPAY`.
- `payments/wallet/WalletController.java` et les DTO `WalletCurrencyBalanceDto`, `WalletEligibleTopupResponse`, `WalletRefundRequestSummaryResponse`, `WalletTransactionDto` : champs additifs (`feeAmount`, `netAmount`, `rail`, `destinationMasked`).
- `auth/UserService.java` / `auth/dto/WalletSettlementDto.java` : `walletSettlement` et `settleWalletsForDeletion` exposent le rail réel (STRIPE/PAWAPAY/MANUAL selon les cibles), les frais, le net et la destination masquée par devise.
- `payments/pawapay/PawapaySubmissionService.java` : `createWalletRefund`/`createWalletPayout` (réservation sans appel réseau) et `initiate(op, ref)` (appel réseau après commit, renvoie `PawapayInitiationResult`).
- `payments/pawapay/PawapayOperationEntity.java`, `PawapayOperationRepository.java` : `getRelatedOperationId()`, requêtes par utilisateur/purpose/kind.
- `payments/pawapay/dto/PawapayProviderConfig.java` : composant `refund` (`Limits`) et `supportsRefund()`.
- `payments/pawapay/PawapayClient.java` : lit `operationTypes.REFUND` dans `activeConfiguration()`.
- `src/main/resources/application.yml` : `yadony.wallet.refund-fee.stripe-fallback` (percent/fixed) et `yadony.pawapay.fees` (default + surcharges par opérateur).

Tâche 6 (ce lot) : `src/test/java/com/yadony/api/payments/wallet/WalletRefundIT.java` (5 scénarios de bout en bout ajoutés, aucun fichier de production modifié) et ce fichier de story.

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble du flux

1. L'utilisateur ouvre l'écran portefeuille : `GET /wallet/balance` rejoue le ledger (`WalletSelfRefundService.allocation`), calcule pour chaque recharge encore remboursable son frais (`WalletRefundFeeCalculator.feeFor`, nul si elle a été partiellement dépensée) et agrège `fees`/`net` sur l'allocation.
2. `POST /wallet/refund-requests` (`WalletSelfRefundService.request`) sélectionne les recharges ciblées, exclut celles dont le net arrondi serait nul ou négatif, refuse un mélange de rails dans une même demande, pose le canal (`AUTOMATIC_STRIPE` ou `AUTOMATIC_PAWAPAY`) et écrit la demande + ses items (`amount` = brut, `feeAmount` = frais retenu), dans une seule transaction.
3. Au commit, `WalletRefundIssueListener` (AFTER_COMMIT) appelle `issuePendingItems`, qui boucle par rail. Stripe : `Refund.create` pour `amount − feeAmount`. PawaPay : `WalletPawapayRefundIssuer.issue` réserve une opération (REFUND si l'opérateur le supporte, sinon PAYOUT direct), pose son identifiant sur l'item **avant** tout appel réseau, passe l'item PROCESSING, publie `WalletPawapayRefundInitiationEvent`.
4. Au commit de cette réservation, `WalletRefundOutcomeListener.onInitiationRequested` (AFTER_COMMIT + REQUIRES_NEW) envoie réellement l'opération à pawaPay. Un rejet synchrone (`REJECTED`) est traité comme une issue finale en échec : un rejet de remboursement retombe sur un versement (`fallbackToPayout`, même mécanisme réservation → commit → appel réseau) ; un rejet de versement passe l'item FAILED avec une alerte.
5. pawaPay confirme par callback (comme pour la recharge et le paiement de colis) : `PawapayOperationService.apply` publie `PawapayOperationCompletedEvent`/`PawapayOperationFailedEvent` dans la même transaction que la transition. `WalletRefundOutcomeListener.onCompleted`/`onFailed` verrouille la demande puis l'item, applique l'issue (idempotente) et appelle `resolveIfComplete`.
6. `resolveIfComplete`/`resolveLocked` : si tous les items sont terminaux, débite le wallet du **brut** des items REFUNDED (`SELF_REFUND_OUT`), passe la demande REFUNDED (aucun item FAILED) ou FAILED (au moins un), et ouvre un ticket enfant `MANUAL_ADMIN` sur les items FAILED (repris de la story lot 1 côté Stripe, désormais valable aussi pour pawaPay).
7. `GET /wallet/refund-requests` et `listEligibleTopups` réconcilient au passage : les statuts Stripe des demandes PROCESSING sont lus en une seule passe **avant** tout verrou (`stripeStatusesOutsideLock`), pawaPay ne fait aucun appel réseau (le statut LOCAL de l'opération liée, tenu à jour par le poller, suffit).
8. La suppression de compte (`UserService.settleWalletsForDeletion`) ouvre une demande par devise avec solde positif, sur le rail qui lui correspond ; `walletSettlement` (lecture seule, appelée avant tout par `checkDeletionEligibility`) prévisualise ces montants, y compris pour un compte qui a un solde EUR (Stripe) et un solde XOF (pawaPay) simultanément : deux demandes indépendantes, jamais mélangées.

### Points d'entrée API

Inchangés dans leur route, étendus dans leur contrat (`@PreAuthorize("isAuthenticated()")`) :

- `GET /api/v1/wallet/balance` : `refundFeeAmount`, `refundNetAmount` en plus par devise.
- `GET /api/v1/wallet/refund-eligible-topups` : `feeAmount` par recharge éligible.
- `POST /api/v1/wallet/refund-requests`, `GET /api/v1/wallet/refund-requests` : `feeAmount`, `netAmount`, `rail`, `destinationMasked` sur chaque demande.
- `GET /api/v1/wallet/transactions` : `feeAmount`/`netAmount` sur un `SELF_REFUND_OUT` apparié sans ambiguïté à sa demande (sinon absents).
- `GET /api/v1/auth/deletion-eligibility` (via `walletSettlement`) : `rail`, `feeAmount`, `netAmount`, `destinationMasked` par devise à solde positif.

### Entités JPA impliquées

- `WalletRefundRequestItemEntity` → `wallet_refund_request_items` (colonnes du lot 1) : `fee_amount NUMERIC(10,2) NOT NULL DEFAULT 0`, `pawapay_refund_id`, `pawapay_payout_id`. Index unique partiel `uq_wallet_refund_request_items_pi_active` : un seul item **actif** (PENDING ou PROCESSING) par `payment_intent_id`, ce qui autorise plusieurs items terminaux (FAILED, REFUNDED) historiques pour la même recharge sans jamais permettre deux tentatives simultanées.
- `PawapayOperationEntity` (inchangée dans son schéma ce lot) : une opération REFUND ou PAYOUT de `purpose = WALLET_REFUND` porte `userId` (le propriétaire du wallet, pas forcément le déposant si le rôle a changé) et, pour un REFUND, `relatedOperationId` = le dépôt d'origine.

### Logique métier critique

- **Brut débité, net émis.** L'item porte le montant brut de la recharge (`amount`) et le frais retenu (`feeAmount`) séparément. Le rail externe (Stripe, pawaPay) ne voit jamais que `amount − feeAmount` ; le wallet, à la résolution, débite `amount` (le brut) en `SELF_REFUND_OUT`. Le frais n'est donc jamais un mouvement de wallet, seulement un écart entre ce qui est demandé et ce qui part réellement chez le prestataire.
- **Aucun frais sur une recharge entamée.** `WalletRefundFeeCalculator.feeFor` renvoie zéro dès que `topup.original() != topup.remaining()` : rembourser un reliquat après dépense ne pénalise jamais l'utilisateur d'un frais qu'il n'aurait payé qu'en partie.
- **Cibles à net nul exclues après arrondi, pas avant.** Le filtre compare `remaining` arrondi à l'unité mineure de la devise (down) au frais, lui-même déjà à cette échelle : comparer les montants non arrondis (2 décimales internes même pour le XOF) laissait passer des cibles dont l'arrondi ramenait le net réel à zéro (bug du tour 1 de la tâche 3, corrigé avant la fin du lot).
- **Un seul rail par demande.** Une sélection mélangeant une cible Stripe et une cible pawaPay lève `IllegalStateException("wallet-refund-mixed-rails")` avant toute écriture : chaque rail a son propre émetteur, une demande à moitié Stripe moitié pawaPay n'a pas de sens opérationnel.
- **`supportsRefund` et repli payout.** Un opérateur sans `operationTypes.REFUND` dans sa configuration active (ou `CLOSED`) ne reçoit jamais de tentative de remboursement partiel : le versement part directement, au numéro d'origine du dépôt. Un remboursement en échec (rejeté à l'initiation ou FAILED après acceptation) retombe sur le même mécanisme de versement : jamais de tentative de remboursement en boucle.
- **Cache des frais Stripe et repli configuré.** `StripeFeeSource` met en cache 1 h le frais réel lu chez Stripe (clé `paymentIntentId|currency`, jamais de `null` en cache) ; si Stripe est injoignable ou si la transaction de solde est dans une autre devise que celle demandée, le frais est calculé par un taux configuré (`percent` + `fixed`, défaut 3,15 % + 0,25). Le rail pawaPay a son propre barème (`PawapayFeeTable`, défaut 1 % dépôt + 1 % remboursement) avec surcharges par opérateur.
- **Lien item ↔ opération posé avant l'appel réseau, garde d'âge de 60 s.** L'émission pawaPay réserve l'opération et pose son identifiant sur l'item, PROCESSING, dans la même transaction que la demande, puis publie l'événement d'initiation. L'appel réseau ne part qu'au commit, dans une transaction séparée qui ne peut plus annuler le lien. Avant d'envoyer, l'opération est relue : si elle n'est plus CREATED ou si elle a plus de 60 s, l'envoi est abandonné et laissé au poller. Cette limite dérive du délai de réconciliation pawaPay (2 min) : un envoi tardif pourrait être accepté par pawaPay *après* que le poller a déjà déclenché un repli, provoquant un double mouvement.
- **Ordre des verrous demande → item, partout.** `issuePendingItems`, `WalletRefundOutcomeListener.settle`, `reconcile` verrouillent systématiquement la demande avant l'item qu'ils modifient. Le sens inverse (item puis demande) existait dans les webhooks Stripe avant le tour 1 de la tâche 5 et pouvait provoquer un interblocage Postgres avec une réconciliation concurrente sur le même item : corrigé en lisant l'item sans verrou pour trancher le statut Stripe (appel réseau compris), puis en verrouillant la demande, puis en relisant l'item (`EntityManager.refresh`) avant toute écriture.
- **Lectures Stripe hors verrou.** `listForUser` lit les statuts Stripe de **toutes** les demandes PROCESSING d'un utilisateur en une seule passe avant le premier verrou : une transaction Spring garde son `FOR UPDATE` jusqu'au commit, donc un verrou pris sur une première demande resterait tenu pendant l'appel réseau Stripe d'une seconde demande de la même lecture, bloquant potentiellement le webhook `charge.refunded` de la première.
- **`resolveIfComplete` verrou + refresh contre le double débit.** Un item peut être chargé PROCESSING dans le contexte de persistance d'une transaction, puis résolu et committé par un traitement concurrent (webhook, écouteur pawaPay) avant que cette même transaction n'appelle `resolveIfComplete`. Sans `EntityManager.refresh` après le `findByIdForUpdate`, l'entité du cache de premier niveau restait PROCESSING et un second débit partait. `resolveIfComplete` verrouille la demande puis la rafraîchit avant de statuer ; test de régression dans `WalletRefundIT` (neutraliser le `refresh` fait échouer le test avec un solde insuffisant, preuve du second débit).

### Events Spring publiés / écoutés

- `WalletPawapayRefundInitiationEvent(itemId, operationId)` : publié par `WalletPawapayRefundIssuer.emit` dans la transaction qui pose le lien item/opération. Écouté par `WalletRefundOutcomeListener.onInitiationRequested` (AFTER_COMMIT + REQUIRES_NEW) : envoie l'opération à pawaPay, traite un rejet synchrone comme une issue finale.
- `PawapayOperationCompletedEvent` / `PawapayOperationFailedEvent` (génériques, existants) : filtrés par `WalletRefundOutcomeListener` sur `purpose == WALLET_REFUND && kind in (REFUND, PAYOUT)` ; règlent l'item et déclenchent `resolveIfComplete`.

### Pièges et points d'attention

- **Brut débité / net émis.** Ne jamais confondre `item.getAmount()` (ce qui sort du wallet à la résolution) et `item.getAmount() − item.getFeeAmount()` (ce qui part réellement chez Stripe ou pawaPay). Toute nouvelle lecture de montant dans ce domaine doit choisir explicitement laquelle des deux elle veut.
- **Un seul remboursement actif par dépôt.** L'index partiel `uq_wallet_refund_request_items_pi_active` (statuts PENDING/PROCESSING) est le vrai filet contre un double remboursement du même `payment_intent_id` ; toute nouvelle voie de création d'item doit passer par un chemin qui respecte cet index (ne jamais insérer un item actif en dehors de `request()`/`openChildForFailedItems`/la reprise planifiée).
- **`supportsRefund` et repli payout.** La configuration active pawaPay est lue à chaque émission (`client.activeConfiguration()`, pas de cache côté wallet) : un changement d'opérateur (ex. l'ajout du type REFUND) prend effet à la prochaine demande sans redéploiement.
- **Cache des frais Stripe et repli configuré.** Le cache Caffeine est en mémoire locale (pas partagé entre instances) et expire à 1 h : un remboursement demandé juste après un changement de frais réel chez Stripe pour la même transaction peut encore lire l'ancienne valeur pendant jusqu'à 1 h. Sans conséquence pratique (le frais d'une transaction de solde donnée ne change pas après coup), mais à savoir si le calcul semble jamais se rafraîchir.
- **Scheduler de reprise étendu au canal pawaPay.** `WalletRefundIssueRecoveryScheduler` reprend maintenant `AUTOMATIC_STRIPE` **et** `AUTOMATIC_PAWAPAY` (items PENDING sans identifiant d'émission posé). Un item pawaPay déjà PROCESSING avec un identifiant posé n'est jamais repris par ce scheduler : c'est le poller pawaPay (statut LOCAL) et la réconciliation (`WalletRefundRailIssuer.reconcile`) qui couvrent ce cas.
- **Lien item ↔ opération posé avant l'appel réseau et garde d'âge de 60 s.** Toute future modification du timeout HTTP pawaPay (`PawapayConfig`, actuellement 10 s connexion + 30 s lecture) ou du délai de réconciliation du poller (`PawapayReconciliationPoller.CREATED_TIMEOUT`, 2 min) doit revérifier que la marge de `WalletPawapayRefundIssuer.MAX_SEND_AGE` (60 s) reste suffisante : sinon un envoi tardif accepté par pawaPay après un repli déjà déclenché par le poller peut provoquer un double mouvement.
- **Ordre des verrous demande → item partout.** Toute nouvelle méthode qui touche à la fois une `WalletRefundRequestEntity` et l'un de ses items doit verrouiller la demande en premier ; l'inverse peut interbloquer avec un webhook Stripe ou une réconciliation concurrente sur le même item.
- **Lectures Stripe hors verrou.** Ne jamais insérer un appel réseau Stripe entre un `findByIdForUpdate` et le commit qui le libère : lire les statuts Stripe nécessaires **avant** tout verrou, pour toutes les demandes concernées par la même transaction (voir `stripeStatusesOutsideLock`).
- **`resolveIfComplete` verrou + refresh.** Toujours rafraîchir l'entité après un `findByIdForUpdate` avant de statuer sur son état si elle a pu être chargée plus tôt dans la même transaction (cache de premier niveau Hibernate) : sans ce refresh, un double débit devient possible dès qu'un traitement concurrent résout la demande entre-temps.

## Critères d'acceptation couverts

- [x] Une recharge mobile money jamais dépensée se rembourse par le rail pawaPay (remboursement partiel ou versement selon l'opérateur), avec un frais retenu et un net réellement soumis : `WalletRefundIT.pawapay_rechargeJamaisDepensee_remboursementRefundComplet_debiteLeBrutEtRetientLesFrais`, `WalletMobileMoneyTopupIT.remboursementMobileMoney_refundRejete_repliParVersement_debiteLeBrutAuCompleted`.
- [x] Une recharge partiellement dépensée ne porte aucun frais et ne rembourse que son reliquat : `WalletRefundIT.pawapay_rechargePartiellementDepensee_fraisNulsEtRemboursementDuReliquat`, `WalletRefundFeeCalculatorTest`.
- [x] Un opérateur sans remboursement partiel déclenche un versement direct ; un versement en échec ouvre un ticket enfant manuel avec alerte : `WalletRefundIT.pawapay_operateurSansRefund_versementDirectEnEchec_ouvreUnTicketEnfantAvecAlerte`, `WalletPawapayRefundIssuerTest`.
- [x] Une recharge Stripe jamais dépensée retient le frais réel (ou le repli configuré si illisible) et n'émet que le net : `WalletRefundIT.stripe_rechargeJamaisDepensee_fraisDeRepliRetenus_refundEmisPourLeNet`, `StripeFeeSourceTest`.
- [x] La suppression de compte règle indépendamment un solde EUR (Stripe) et un solde XOF (pawaPay), chacun sur son propre canal, et l'aperçu (`walletSettlement`) expose le rail, les frais et la destination masquée de chacun : `WalletRefundIT.suppressionDeCompte_soldesEurStripeEtXofPawapay_deuxCanauxSansMelange`, `UserServiceDeleteAccountTest`.
- [x] Un seul remboursement actif par recharge (index unique partiel) : `WalletRefundIT.indexPartiel_plusieursItemsParPaymentIntentMaisUnSeulActif` (préexistant, tâche 1).
- [x] Pas de double débit du wallet quand une demande est résolue par un traitement concurrent dans la même unité de travail : `WalletRefundIT.listForUser_demandeResolueParUnEcouteurDansLaMemeUniteDeTravail_unSeulDebit` (préexistant, tâche 5).
- [x] Contrat HTTP étendu de façon strictement additive (`feeAmount`, `netAmount`, `rail`, `destinationMasked`), aucun champ renommé ni supprimé : `WalletControllerIT`, `WalletRefundRequestSummaryResponseTest`.

## Tests

- `./mvnw test jacoco:report` (premier plan, un seul Maven) : **BUILD SUCCESS, Tests run: 5797, Failures: 0, Errors: 0, Skipped: 7**.
- Couverture globale (instructions) : **91 %** (branches 79 %). Par paquet du lot : `com.yadony.api.payments.wallet` 96 % (branches 86 %), `com.yadony.api.payments.wallet.fees` 99 % (branches 84 %), `com.yadony.api.payments.pawapay` 96 % (branches 87 %), `com.yadony.api.payments.wallet.dto` 94 %, `com.yadony.api.payments.pawapay.dto` 100 %.
- Tests ajoutés dans cette tâche (6) : `WalletRefundIT`, tous nouveaux, aucun scénario du brief n'existait déjà tel quel dans cette classe (les tâches 4 et 5 avaient ajouté des scénarios pawaPay/verrouillage dans `WalletMobileMoneyTopupIT` et dans `WalletRefundIT` mais pas les 5 du brief de la tâche 6) :
  - `pawapay_rechargeJamaisDepensee_remboursementRefundComplet_debiteLeBrutEtRetientLesFrais`
  - `pawapay_rechargePartiellementDepensee_fraisNulsEtRemboursementDuReliquat`
  - `pawapay_operateurSansRefund_versementDirectEnEchec_ouvreUnTicketEnfantAvecAlerte`
  - `stripe_rechargeJamaisDepensee_fraisDeRepliRetenus_refundEmisPourLeNet`
  - `suppressionDeCompte_soldesEurStripeEtXofPawapay_deuxCanauxSansMelange`
- Tests des tâches 1 à 5, non dupliqués ici : `WalletRefundFeeCalculatorTest`, `StripeFeeSourceTest`, `PawapayFeeTableTest`, `WalletRefundAllocatorTest`, `WalletRefundRailTest`, `WalletSelfRefundServiceTest`, `WalletPawapayRefundIssuerTest`, `WalletRefundOutcomeListenerTest`, `WalletRefundIssueRecoverySchedulerTest`, `WalletControllerIT`, `WalletRefundRequestSummaryResponseTest`, `UserServiceDeleteAccountTest`, `WalletMobileMoneyTopupIT`, ainsi que les 12 tests déjà présents dans `WalletRefundIT` avant cette tâche (parcours Stripe, reprise, suppression de compte à un seul rail, ticket enfant, double débit fermé, index unique partiel).

## Décisions techniques

- **Brut débité, net émis plutôt qu'un débit du net** (héritée des tâches 1 et 3) : garder le débit du wallet sur le montant brut de la recharge simplifie l'invariant du ledger (`balance == SUM(amount)`) et rend le frais visible uniquement comme un écart entre ce qui est demandé et ce qui sort réellement chez le prestataire, sans introduire une troisième catégorie de mouvement wallet.
- **Émission pawaPay en deux temps (réservation liée avant commit, appel réseau après)** plutôt qu'un appel réseau synchrone dans la transaction d'émission (héritée de la tâche 4) : un appel réseau sous le verrou de l'item aurait tenu la transaction ouverte pendant la latence pawaPay, en plus d'un risque de double émission si la transaction échouait après un appel déjà parti.
- **Garde d'âge de 60 s dérivée du délai de réconciliation du poller** (tâche 4, tour 1) plutôt qu'une constante arbitraire : documente explicitement le calcul (10 s connexion + 30 s lecture + marge, sous les 120 s du poller) pour qu'une future modification des timeouts pawaPay ou du poller soit consciente de ce couplage.
- **Un seul type `PawapayFeeTable`** faisant à la fois `@ConfigurationProperties` et porteur de `fee(...)` (tâche 2) : cohérent avec le seul autre `@ConfigurationProperties` record du package (`WalletTopupProperties`), évite une séparation en deux fichiers pour un composant aussi petit.
- **Aucune migration dans ce lot** : V260 (lot 1) porte déjà toutes les colonnes et l'index nécessaires au remboursement pawaPay ; les deux lots partagent volontairement cette migration car aucun des deux n'était encore fusionné ni déployé au moment de leur écriture.
- **Tâche 6 : nouveaux scénarios dans `WalletRefundIT` plutôt qu'une nouvelle classe** : cette classe porte déjà le décor `EmbeddedPostgres` + profil `e2e` nécessaire (verrous `FOR NO KEY UPDATE`, index unique partiel), et son stub `StripeFeeSource` à zéro n'interfère pas avec les nouveaux scénarios pawaPay (`PawapayClient` mocké séparément) ; seul le scénario du frais de repli Stripe réécrit le stub localement, sans affecter les autres tests de la classe.
