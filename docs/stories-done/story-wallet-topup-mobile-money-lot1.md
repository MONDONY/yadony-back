# Story wallet-recharge-mobile-money, lot 1 (Backend)

**Date :** 2026-09-17
**Status :** ✅ Complète

Spec et plan : dépôt `docs-claude`, `docs/specs/2026-09-17-wallet-recharge-mobile-money-design.md`, `docs/plans/2026-09-17-wallet-recharge-mobile-money-back-lot1.md`.

## Résumé

Le portefeuille peut désormais être rechargé par mobile money (pawaPay), en plus de la carte Stripe déjà existante. La recharge réutilise le même rail pawaPay que le paiement de colis et le remboursement (`PawapayOperationEntity`, poller de réconciliation, callback), distingué par un nouveau champ `purpose` (`BID_PAYMENT` / `WALLET_TOPUP` / `WALLET_REFUND`) : une opération de recharge n'est liée à aucun paiement de colis, seulement à un utilisateur. La devise créditée est celle de l'opérateur du numéro payeur, jamais celle du portefeuille ni celle demandée par le client. Le crédit n'intervient qu'à la confirmation du dépôt par pawaPay (callback), jamais à l'initiation.

## Fichiers créés

- `src/main/resources/db/migration/V260__wallet_topup_mobile_money.sql` : colonnes `purpose` et `user_id` sur `pawapay_operations`, trois `CHECK` (`chk_pawapay_ops_purpose`, `chk_pawapay_ops_purpose_payment`, `chk_pawapay_ops_wallet_user`), un index simple (`idx_pawapay_ops_user_kind_status`) et un index unique partiel (`uq_pawapay_ops_live_wallet_topup`, filet anti-doublon), plus les colonnes de remboursement wallet du lot 2 (une seule migration pour les deux lots, V260 n'étant encore ni fusionnée ni déployée).
- `src/main/java/com/yadony/api/payments/pawapay/PawapayOperationPurpose.java` : énumération `BID_PAYMENT` / `WALLET_TOPUP` / `WALLET_REFUND`.
- `src/main/java/com/yadony/api/payments/wallet/WalletMobileMoneyTopupService.java` : cœur métier de la recharge, `initiate` / `status` / `providers`.
- `src/main/java/com/yadony/api/payments/wallet/WalletTopupOutcomeListener.java` : écoute la confirmation ou l'échec du dépôt pawaPay et crédite le portefeuille (ou journalise l'échec).
- `src/main/java/com/yadony/api/payments/wallet/WalletAmountText.java` : rendu d'un montant lisible pour l'utilisateur (« 10 000 F CFA »), distinct du format brut pawaPay.
- `src/main/java/com/yadony/api/payments/wallet/dto/WalletTopupStatusResponse.java` : DTO de statut, avec `statusOf(PawapayOperationStatus)` qui ramène les huit statuts pawaPay aux trois seuls que l'app distingue (`PENDING`, `CONFIRMED`, `FAILED`).
- Tests : `WalletMobileMoneyTopupServiceTest`, `WalletAmountTextTest`, `WalletTopupOutcomeListenerTest`, `WalletMobileMoneyTopupIT` (IT PostgreSQL end-to-end du parcours complet).

## Fichiers modifiés

- `payments/pawapay/PawapayOperationEntity.java` : champs `purpose` (défaut `BID_PAYMENT`) et `userId`, tous deux `updatable = false` (immuables après création).
- `payments/pawapay/PawapayOperationRepository.java` : `existsByUserIdAndKindAndCurrencyAndPurposeAndStatusIn` (garde applicative du doublon) et `findByIdAndUserId` (lecture du statut avec propriété).
- `payments/pawapay/PawapayOperationService.java` : nouvelle surcharge de `create(kind, purpose, userId, paymentId, relatedOperationId, amount, currency, provider, country, msisdn)` ; l'ancienne signature délègue avec `BID_PAYMENT` / `userId = null`. Traduction de la violation de l'index `uq_pawapay_ops_live_wallet_topup` en 422 `topup-already-pending`, sur le même modèle que `uq_pawapay_ops_live_per_payment` (409).
- `payments/pawapay/events/PawapayOperationCompletedEvent.java` et `PawapayOperationFailedEvent.java` : champs `purpose` (position 3) et `userId` (position 5) ajoutés.
- `payments/pawapay/PawapaySubmissionService.java` : `submitWalletDeposit(userId, msisdn, provider, country, amount, currency, clientReference, successfulUrl, failedUrl)`, qui crée l'opération avec `purpose = WALLET_TOPUP` et sans `paymentId`.
- `payments/pawapay/PawapayErrors.java` : `walletTopupAlreadyPending()` (422 `topup-already-pending`), utilisée à la fois par la garde applicative et par la traduction de l'index unique.
- `payments/pawapay/PawapayProperties.java` : composant `deepLinkWallet`, lu depuis la variable d'environnement `PAWAPAY_DEEP_LINK_WALLET` (défaut `yadony://payments/wallet`).
- `payments/pawapay/PawapayReturnController.java` : route `GET /pawapay/return/wallet-topup`, qui renvoie systématiquement vers l'écran du portefeuille (302 vers `deepLinkWallet`, repli `yadony://` si vide) sans jamais refléter de paramètre de la requête dans `Location`.
- `payments/mobilemoney/dto/MobileMoneyProvidersResponse.java` : fabrique `from(PawapayProviderResolver.Catalogue)`, déplacée depuis `MobileMoneyAccountService.toProvidersResponse` pour que le versement et la recharge rendent exactement la même forme de catalogue.
- `payments/mobilemoney/MobileMoneyAccountService.java` : adapté pour appeler `MobileMoneyProvidersResponse.from` (l'ancienne méthode privée est supprimée).
- `payments/wallet/WalletTopupOrchestrator.java` : aiguillage `MOBILE_MONEY` vers `WalletMobileMoneyTopupService.initiate` (délégation nue, sans toucher au montant ni à la devise) ; `WAVE` et `ORANGE_MONEY` (anciens codes de rail) restent refusés en 422 `mobile-money-topup-retired`, avec commentaire explicite pour ne jamais les faire retomber sur pawaPay.
- `payments/wallet/WalletController.java` : trois points d'entrée ajoutés (`POST /wallet/topup` avec `paymentMethod = MOBILE_MONEY`, `POST /wallet/topup/providers`, `GET /wallet/topup/{topupId}/status`).
- `payments/wallet/dto/WalletTopupRequest.java` : champs `phoneNumber` et `provider` ; commentaire de `paymentMethod` mis à jour.
- `payments/wallet/dto/WalletTopupResponse.java` : fabriques `stripe(...)` / `mobileMoney(PawapayOperationEntity)`, champs mobile money ajoutés ; le constructeur public à deux arguments a été supprimé une fois `initiateStripe` passé par la fabrique (règle « pas de code mort »).
- `src/main/resources/application.yml` (et `src/test/resources/application-test.yml`) : `pawapay.deep-link-wallet`.
- `src/test/java/com/yadony/api/payments/PaymentListenerTransactionalContractTest.java` : `WalletTopupOutcomeListener.onCompleted`/`onFailed` ajoutés au filet qui vérifie que tout listener touchant à l'argent du rail pawaPay reste `AFTER_COMMIT` + `REQUIRES_NEW`.

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble du flux

1. L'app envoie `POST /wallet/topup` avec `paymentMethod = MOBILE_MONEY`, `amount`, `phoneNumber` (et `provider` en option).
2. `WalletTopupOrchestrator.initiate` délègue directement à `WalletMobileMoneyTopupService.initiate` sans toucher au montant ni à la devise.
3. Le service : coupe court si `pawapay.enabled = false` (422 `mobile-money-disabled`) ; normalise le numéro (`Msisdn.normalize`, 422 `topup-phone-required` si absent, 422 `mobile-money-invalid-phone` si invalide) ; résout l'opérateur via `PawapayProviderResolver.resolve` (422 `topup-phone-unsupported` si le numéro n'est pas exploitable) ; borne le montant dans la devise de l'opérateur (422 `topup-amount-out-of-range`) ; vérifie qu'aucune recharge non terminale n'existe déjà pour cet utilisateur et cette devise (422 `topup-already-pending`, garde applicative doublée par l'index unique partiel en base) ; soumet le dépôt à pawaPay via `PawapaySubmissionService.submitWalletDeposit` (`purpose = WALLET_TOPUP`, sans `paymentId`) ; audite `wallet_topup` / `MOBILE_MONEY_INITIATED` ; renvoie `topupId`, devise, opérateur, numéro masqué et `authorizationUrl` (Wave uniquement).
4. L'utilisateur valide sur son téléphone (code PIN) ou sur la page de l'opérateur (Wave).
5. pawaPay notifie par callback (`PawapayCallbackController`), qui appelle `PawapayOperationService.apply(...)` : la transition d'état est appliquée et un `PawapayOperationCompletedEvent` ou `PawapayOperationFailedEvent` est publié À L'INTÉRIEUR de la même transaction.
6. `WalletTopupOutcomeListener` écoute ces deux événements génériques, filtre sur `kind == DEPOSIT` et `purpose == WALLET_TOPUP` (les dépôts de paiement de colis restent traités par `MobileMoneyDepositOutcomeListener`), et ne réagit qu'`AFTER_COMMIT`, dans une transaction `REQUIRES_NEW`.
7. `onCompleted` crédite le portefeuille via `WalletService.credit(userId, currency, amount, TOP_UP, paymentRef = "pawapay:" + id, idempotencyKey = "pawapay-topup-" + id)` : idempotent, un rejeu du callback ne crédite jamais deux fois. `credit` appelle `getOrCreate` en interne, donc un solde dans une devise encore jamais vue par cet utilisateur est créé automatiquement. Puis audit `wallet_topup` / `MOBILE_MONEY_CONFIRMED` et notification utilisateur (« Recharge de 10 000 F CFA confirmée par Orange Money. »).
8. `onFailed` ne crédite rien, journalise et audite `wallet_topup` / `MOBILE_MONEY_FAILED`.
9. L'app poll `GET /wallet/topup/{topupId}/status` jusqu'à obtenir `CONFIRMED` (avec `walletBalance`) ou `FAILED`.
10. Si l'opérateur redirige (Wave), la page de retour `GET /pawapay/return/wallet-topup` renvoie systématiquement vers l'écran du portefeuille (deep link), qui relit le statut par l'étape 9 ; elle ne décide jamais rien elle-même.

### Points d'entrée API

Tous sous `@PreAuthorize("isAuthenticated()")` (classe `WalletController`, tout utilisateur connecté, SENDER ou TRAVELER) :

- `POST /api/v1/wallet/topup` : initie une recharge, `paymentMethod` = `STRIPE` ou `MOBILE_MONEY` (`WAVE`/`ORANGE_MONEY` refusés en 422, anciens codes retirés). Inchangé dans sa route, étendu dans son comportement.
- `POST /api/v1/wallet/topup/providers` : catalogue des opérateurs mobile money utilisables pour un numéro, avec l'opérateur détecté et le numéro masqué. Corps facultatif (`MobileMoneyProvidersRequest`, `phoneNumber` optionnel) : un corps absent et un numéro vide rendent le même 422 `topup-phone-required`, alignement volontaire sur le point d'entrée jumeau `MobileMoneyProvidersController`.
- `GET /api/v1/wallet/topup/{topupId}/status` : statut d'une recharge (`PENDING` / `CONFIRMED` / `FAILED`), `walletBalance` renseigné uniquement sur `CONFIRMED`. Une recharge d'un autre utilisateur est **introuvable** (404 `topup-not-found`), jamais interdite (403) : un 403 confirmerait l'existence de l'identifiant à un tiers.

### Entités JPA impliquées

- `PawapayOperationEntity` → `pawapay_operations`. Nouveaux champs `purpose` (`PawapayOperationPurpose`, `updatable = false`) et `userId` (`updatable = false`, nullable pour les opérations de paiement de colis). `payment_id` et `purpose = BID_PAYMENT` sont liés en base par `chk_pawapay_ops_purpose_payment` (équivalence stricte : l'un si et seulement si l'autre) ; `chk_pawapay_ops_wallet_user` impose `user_id` non nul pour tout `purpose` différent de `BID_PAYMENT`.
- Aucune nouvelle entité de recharge dédiée : la recharge mobile money vit entièrement dans `PawapayOperationEntity`, comme le paiement de colis et le remboursement.

### Logique métier critique

- **Devise de l'opérateur, jamais celle du portefeuille** : contrairement à la recharge Stripe (qui recrédite la devise active du portefeuille via `ActiveCurrencyResolver`), la recharge mobile money crédite la devise que l'opérateur du numéro impose. Un numéro ivoirien crédite un solde XOF, créé au besoin.
- **Aucun crédit à l'initiation** : `initiate` ne fait que soumettre le dépôt et retourner son identité. Le crédit n'a lieu qu'au callback, jamais avant, même si l'appel à pawaPay est « accepté ».
- **Garde du doublon en deux temps** : la garde applicative (`existsByUserIdAndKindAndCurrencyAndPurposeAndStatusIn` sur les statuts `PawapayOperationStatus.OPEN`, donc non terminaux) est un chemin TOCTOU (deux requêtes concurrentes peuvent la franchir toutes les deux) ; le filet est l'index unique partiel `uq_pawapay_ops_live_wallet_topup (user_id, purpose, currency)` restreint aux statuts non terminaux, traduit en 422 `topup-already-pending` par `PawapayOperationService.create` au même endroit que la traduction de `uq_pawapay_ops_live_per_payment`. Les deux chemins rendent le même texte.
- **Numéro normalisé avant tout appel réseau** : `Msisdn.normalize` est appliqué en tête d'`initiate` et de `providers`, avant la résolution de l'opérateur : sans cela une saisie hors bornes partirait chez pawaPay et reviendrait en 502 alors que c'est une erreur utilisateur (422).
- **Bornes de montant dans la devise de l'opérateur**, avec un contrôle de décimales (`stripTrailingZeros().scale() > minorUnit`, XOF/XAF n'ont pas de centimes).
- **Statuts pawaPay ramenés à trois** : `COMPLETED → CONFIRMED`, `FAILED`/`SUBMIT_REJECTED → FAILED`, tout le reste (`CREATED`, `ACCEPTED`, `PROCESSING`, `ENQUEUED`, `IN_RECONCILIATION`) `→ PENDING`, par un switch **exhaustif sans `default`** : un futur statut pawaPay cassera la compilation plutôt que d'être classé silencieusement en attente.
- **Interrupteur d'urgence** `pawapay.enabled` contrôlé en tout premier dans `initiate` et `providers` : contrairement au paiement de colis, il n'y a rien d'idempotent à relire avant de couper le rail.

### Events Spring publiés / écoutés

- `PawapayOperationCompletedEvent` / `PawapayOperationFailedEvent` (existants, étendus des champs `purpose` et `userId`) : publiés par `PawapayOperationService.apply` à l'intérieur de la transaction qui applique la transition d'état. Écoutés par `WalletTopupOutcomeListener.onCompleted` / `onFailed`, filtrés sur `kind == DEPOSIT && purpose == WALLET_TOPUP`.

### Pièges et points d'attention

- **`WalletMobileMoneyTopupService.status()` et l'auto-invocation de `getOrCreate`** : `status()` est `@Transactional(readOnly = true)` ; `WalletService.getBalance` (sans annotation propre) rejoint cette même transaction et appelle `this.getOrCreate` en auto-invocation interne à la classe, donc le `@Transactional(propagation = NOT_SUPPORTED)` posé sur `getOrCreate` ne s'applique JAMAIS sur ce chemin (il passe par le proxy Spring, pas par un appel `this.`) et la transaction `readOnly` de `status()` n'est jamais suspendue. Conséquence réelle : entre le commit du callback pawaPay (opération `COMPLETED`) et le commit du crédit (transaction `REQUIRES_NEW` de `WalletTopupOutcomeListener`), un poll de `/status` peut rendre `CONFIRMED` avec le solde d'avant crédit, voire un compte créé dans une transaction `readOnly` non flushée si le solde dans cette devise n'existait pas encore ; fenêtre de quelques millisecondes, auto-corrigée au poll suivant.
- **Fenêtre entre `COMPLETED` et le crédit** : `PawapayOperationCompletedEvent` n'est publié qu'une seule fois par opération et le poller de réconciliation ne rebalaie que les opérations encore **ouvertes** ; si le crédit, l'audit ou la notification lèvent après le commit du callback, l'opération reste `COMPLETED` pour toujours sans que rien ne la rejoue. `onCompleted` entoure donc son corps d'un `try/catch RuntimeException` qui appelle `AdminAlertService.raise("WALLET_TOPUP_CREDIT_FAILED", ...)` PUIS repropage à l'identique (jamais avalée, la transaction `REQUIRES_NEW` doit rester annulée). `onFailed` a la garde inverse : elle journalise en `ERROR` et **avale** l'exception, parce qu'aucun argent n'a bougé et que repropager risquerait d'empêcher d'autres écouteurs `AFTER_COMMIT` du même événement de s'exécuter.
- **`clientReference = "wallet-topup-" + userId` n'est pas unique par recharge** : deux recharges successives du même utilisateur portent la même référence dans le tableau de bord pawaPay. Sans effet métier (la garde du doublon empêche deux dépôts vivants simultanés), mais à revoir si pawaPay l'exige unique un jour.
- **L'index `uq_pawapay_ops_live_wallet_topup` ne porte pas `kind`** : sans effet tant que `WALLET_TOPUP` n'existe qu'en `DEPOSIT`, mais une future opération `WALLET_TOPUP` d'un autre `kind` entrerait en collision avec une recharge en cours sur la même devise.
- **Poller pawaPay à couper dans les IT end-to-end** : le profil `e2e` hérite des vrais crons pawaPay d'`application.yml` ; sans `@DynamicPropertySource` qui les désactive, le poller de réconciliation interrogerait le `PawapayClient` doublé pendant le test.
- **`WalletAmountText` vit dans `payments.wallet`, pas dans `common`** : aucun formateur de montant lisible n'existait avant cette story (`PawapayAmounts.format` ne rend que le nombre brut pour pawaPay, sans séparateur ni symbole). Créé au plus près de son unique consommateur pour l'instant (le wallet) plutôt que dans `common`, en attendant un deuxième appelant hors du package.
- **Pas de `@Async` sur `WalletTopupOutcomeListener`**, contrairement à ses jumeaux du même rail (`MobileMoneyDepositOutcomeListener`, `MobileMoneyPayoutOutcomeListener`, `MobileMoneyRefundOutcomeListener`), qui portent `@Async` en plus de `AFTER_COMMIT` + `REQUIRES_NEW`. Choix délibéré du brief de tâche : la sûreté transactionnelle vient de `REQUIRES_NEW`, `@Async` n'y change rien ; écart de style à uniformiser si une revue future l'exige.
- **`WalletMobileMoneyTopupIT` ouvre un second contexte Spring e2e** (~40 secondes de CI supplémentaires), distinct de `WalletRefundIT` : ce parcours exige un `PawapayClient` doublé et le rail activé, que le décor de `WalletRefundIT` ne pose pas ; les greffer dessus aurait changé le contexte de ses onze tests de remboursement pour rien.

## Critères d'acceptation couverts

- [x] Une recharge mobile money confirmée crédite le portefeuille dans la devise de l'opérateur (pas celle du portefeuille ni celle demandée par le client) : `WalletMobileMoneyTopupIT` (bout en bout, numéro ivoirien, crédit XOF), `WalletTopupOutcomeListenerTest`.
- [x] N'importe quel numéro mobile money supporté par pawaPay peut payer une recharge, avec détection automatique de l'opérateur ou choix explicite : `WalletMobileMoneyTopupServiceTest` (résolution, `provider` optionnel), `POST /wallet/topup/providers`.
- [x] Une seconde recharge est refusée tant qu'une première est en attente (même utilisateur, même devise) : garde applicative + index unique partiel, `PawapayOperationServiceTest.create_translatesTheWalletTopupUniqueIndexInto422`, `WalletRefundIT.create_uneSeuleRechargeVivanteParUtilisateurEtDevise`.
- [x] Un rejeu du callback pawaPay (ou de l'événement applicatif) ne crédite jamais deux fois : idempotence par clé `pawapay-topup-<id>`, exercée bout en bout dans `WalletMobileMoneyTopupIT` (premier filet : transition déjà appliquée ; second filet : appel direct à `WalletService.credit` avec la même clé).
- [x] Un dépôt qui échoue chez l'opérateur ne crédite rien et le rejette proprement à l'utilisateur : `WalletTopupOutcomeListenerTest` (`onFailed`), statut `FAILED` exposé par `GET /wallet/topup/{topupId}/status`.

## Tests

- `./mvnw test jacoco:report` : 5671 tests, 0 échec, 0 erreur, 7 ignorés préexistants, BUILD SUCCESS.

- Couverture lignes : 92,8 % globale, 94,4 % sur `payments.wallet`, 97,4 % sur `payments.pawapay` ; classes du lot : `WalletTopupOutcomeListener` 100 %, `WalletAmountText` 100 %, `PawapayOperationService` 100 %, `WalletTopupOrchestrator` 93,1 %, `WalletMobileMoneyTopupService` 90,0 %.

- Tests ajoutés ou modifiés dans ce lot : `PawapayOperationServiceTest` (2 nouveaux cas + traduction de l'index), `PawapaySubmissionServiceTest` (`submitWalletDeposit_createsWalletTopupOperationWithoutPayment`), `WalletMobileMoneyTopupServiceTest` (23 cas), `WalletAmountTextTest` (4 cas), `WalletTopupOutcomeListenerTest` (8 cas), `PaymentListenerTransactionalContractTest` (2 nouveaux, filet transactionnel), `WalletTopupCurrencyTest` (aiguillage de l'orchestrateur), `WalletControllerIT` (recharge sans numéro, catalogue à corps facultatif, statut d'une recharge inconnue ou d'un identifiant non UUID, authentification), `WalletMobileMoneyTopupIT` (parcours complet PostgreSQL, EmbeddedPostgres + vraies migrations Flyway jusqu'à V260), `PawapayReturnControllerIT` (route de rebond, non-injection dans `Location`), `MobileMoneyDepositOutcomeListenerTest` / `MobileMoneyPayoutOutcomeListenerTest` / `MobileMoneyRefundOutcomeListenerTest` (constructions d'événements mises à jour avec les nouveaux champs `purpose`/`userId`).

## Décisions techniques

- **Une seule migration (V260) pour les lots 1 et 2** : le lot 2 (remboursement wallet) ajoute des colonnes sur `wallet_refund_request_items` et modifie une contrainte existante ; comme aucune des deux migrations n'était encore fusionnée ni déployée au moment de l'implémentation, tout tient dans V260 plutôt que d'ouvrir une V261 pour un découpage qui n'aurait aucune valeur en production.
- **Statuts `OPEN` plutôt qu'une nouvelle constante `LIVE_OR_DONE_MINUS_COMPLETED`** pour la garde du doublon (Ruling R1 du registre de tâches) : le plan citait une constante inexistante ; `PawapayOperationStatus.OPEN` (déjà définie, non terminale) porte exactement la sémantique voulue (« encore vivante ») sans complexifier l'énumération.
- **`WalletAmountText` écrit plutôt qu'une extension de `PawapayAmounts` ou de `CurrencyAmount`** (Ruling R3) : `PawapayAmounts.format` sert un contrat externe (le format brut attendu par pawaPay) et n'a pas vocation à produire un texte affiché ; `CurrencyAmount` ne formate pas. Un petit formateur dédié, avec une seule règle d'arrondi (déléguée à `CurrencyAmount.of`) et un séparateur de milliers en espace insécable, évite de dupliquer cette règle dans chaque message utilisateur. Le symbole est répété sur les deux bornes d'un intervalle (« Entre 500 F CFA et 1 000 000 F CFA ») plutôt qu'une seule fois à la fin, pour éviter l'ambiguïté d'un montant sans devise dans un message multidevise.
- **Garde de doublon en deux couches (applicative + index unique partiel)** plutôt que la seule garde applicative prévue par le brief initial (Ruling R10) : la lecture puis l'écriture est en race condition par construction (TOCTOU) ; comme V260 n'était pas encore déployée, l'index a été ajouté dans la même migration plutôt que d'accepter une fenêtre de double recharge documentée comme acceptable.
- **URL de retour Wave sans identifiant d'opération** (Ruling R4, affinée) : contrairement au paiement de colis (`bidId` connu avant l'appel pawaPay), l'identifiant de la recharge n'existe qu'après la soumission, qui a justement besoin de cette URL. La page de rebond ne décide donc de rien : elle renvoie systématiquement vers l'écran du portefeuille, qui relit le statut par son propre identifiant via `GET /wallet/topup/{topupId}/status`.
- **`providers()` déplacé plutôt que dupliqué** (Ruling R2 et R6) : la même fabrique `MobileMoneyProvidersResponse.from` sert le versement (`MobileMoneyAccountService`) et la recharge, pour que les deux écrans ne puissent jamais diverger de forme sans qu'un changement de code le rende visible.
- **Pas d'endpoint ni de champ `EXPIRED`** malgré sa mention dans le contrat HTTP de la spec §2 : ce statut n'existe pas côté pawaPay, une recharge non validée finit toujours `FAILED`. Implémenté tel quel plutôt que d'introduire un état qui ne se produirait jamais.
- **Code d'erreur `topup-phone-unsupported` ajouté hors spec** (Ruling R7) : sans lui, un numéro reconnu mais inexploitable (pays non couvert, opérateur fermé) remontait en 500 via `UnsupportedNumberException` non attrapée. Un 422 dédié, avec un libellé par raison, est un moindre mal en attendant que le catalogue d'erreurs de l'app le référence.
