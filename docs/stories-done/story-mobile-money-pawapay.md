# Rail mobile money via pawaPay (Backend)

**Date :** 2026-09-05
**Status :** Complète
**Branche :** `feature/pawapay-mobile-money` (base `2368963f`)
**Spec :** `docs-claude/docs/superpowers/specs/2026-09-04-pawapay-mobile-money-design.md`

## Résumé

Ajout d'un troisième rail de paiement, à côté de la carte (Stripe Connect) et des espèces : le mobile money (Orange Money, Wave, MTN...) via l'agrégateur pawaPay, pour les annonces en XOF et XAF. Le séquestre est interne : après le deposit de l'expéditeur, les fonds restent sur le solde pawaPay de yadony jusqu'à la confirmation de livraison, moment où un payout verse le net au voyageur. L'ancien package `payments/mobilemoney/` (Wave/Orange Money en stubs, jamais branché sur un vrai payeur) est supprimé et remplacé intégralement. Le rail carte n'est pas modifié : chaque nouveau chemin est gardé par `rail == PaymentRail.PAWAPAY`.

## Fichiers créés

### Socle technique `payments/pawapay/` (ne connaît ni bid ni paiement métier)

- `PawapayClient.java` : client HTTP v2 (deposits, payouts, refunds, statut, configuration active, soldes, clés publiques), Spring `RestClient`, Bearer token, timeouts 10 s / 30 s.
- `PawapayConfig.java` : bean `RestClient` et garde de démarrage (refuse de démarrer en prod si le rail est actif sans signature obligatoire).
- `PawapayProperties.java` : configuration `yadony.pawapay.*`.
- `PawapayOperationEntity.java` / `PawapayOperationKind.java` / `PawapayOperationStatus.java` : l'opération pawaPay et son cycle de vie, table `pawapay_operations`.
- `PawapayOperationRepository.java` : les deux transitions atomiques (`applyTransition`, `markSubmittedIfStillCreated`), sélection bornée et projetée pour le poller (`findOpenForReconciliation`, `dto/PawapayOpenOperation`).
- `PawapayOperationService.java` : création d'une opération (transaction propre, avant l'appel HTTP), application d'une transition, publication des deux événements du socle.
- `PawapaySubmissionService.java` : regroupe `submitDeposit` / `submitPayout` / `submitRefund` (création + appel HTTP + marquage du résultat).
- `PawapayCallbackController.java` : réception publique des callbacks (`/pawapay/callbacks/deposits|payouts|refunds`).
- `PawapayReturnController.java` : page de rebond Wave (`/pawapay/return/{bidId}`), redirige vers le deep link de l'app.
- `PawapaySignatureVerifier.java` / `PawapaySignatureException.java` : vérification RFC 9421 des callbacks (Content-Digest puis signature ECDSA/RSA).
- `PawapayPublicKeyStore.java` : cache des clés publiques pawaPay (rafraîchi une fois si `keyId` inconnu, jamais en boucle).
- `PawapayReconciliationPoller.java` : filet de sécurité, relit périodiquement le statut des opérations non finales.
- `PawapayBalanceMonitor.java` : moniteur horaire du solde du portefeuille pawaPay, alerte si sous seuil.
- `PawapayAmounts.java` : formatage des montants pour pawaPay (XOF/XAF sans décimale) ; l'arrondi est celui de `CurrencyAmount`, jamais une seconde arithmétique.
- `PawapayCountries.java` : conversion alpha-3 (pawaPay) vers alpha-2 (yadony).
- `PawapayProviders.java` : libellés lisibles des opérateurs, constante `REDIRECT_AUTH` (Wave).
- `PawapayProviderResolver.java` : ce que pawaPay sait faire d'un numéro pour un type d'opération (opérateur prédit → configuration active → devise → numéro normalisé → pays alpha-2), séquence unique partagée par l'activation du compte (PAYOUT) et l'initiation du dépôt (DEPOSIT) ; pawaPay indisponible → 502, numéro reconnu mais inexploitable → `UnsupportedNumberException` que chaque appelant traduit en son 422 avec ses libellés.
- `PawapayErrors.java` : les deux erreurs RFC 7807 partagées par tout le rail (`mobile-money-disabled`, `mobile-money-provider-unavailable`), définies une seule fois.
- `PawapayText.java` : bornage commun (64 caractères, largeur de `failure_code`) de toute valeur non authentifiée avant audit, journal ou message d'erreur.
- `admin/AdminAlertEscalator.java` : alerte administrateur dédupliquée par incident (ligne `admin_alerts` non résolue du même type), écrite dans sa propre transaction `REQUIRES_NEW`, garde intégrée sur `VARCHAR(60)` — l'unique implémentation, appelée par le poller, le moniteur de solde, le scheduler d'expiration, le versement et le remboursement.
- `dto/` : `PawapayDepositRequest`, `PawapayPayoutRequest`, `PawapayRefundRequest`, `PawapayInitiationResult`, `PawapayOperationSnapshot`, `PawapayProviderConfig`, `PawapayProviderPrediction`, `PawapayPublicKey`, `PawapayWalletBalance`.
- `events/PawapayOperationCompletedEvent.java`, `events/PawapayOperationFailedEvent.java` : les deux seuls événements que le socle publie vers le reste de l'application.

### Rail côté bid `payments/mobilemoney/` (réécriture intégrale)

- `MobileMoneyAccountController.java` / `MobileMoneyAccountService.java` : compte de versement du voyageur (`/payments/mobile-money/account`).
- `MobileMoneyBidPaymentService.java` : acceptation, initiation du deposit, séquestre, expiration, statut. Le fichier central du rail côté bid.
- `MobileMoneyBidPricing.java` : calcul du prix brut / commission d'un bid mobile money.
- `MobileMoneyDepositOutcomeListener.java`, `MobileMoneyPayoutOutcomeListener.java`, `MobileMoneyRefundOutcomeListener.java` : les six écouteurs (`onCompleted`/`onFailed` × 3) qui traduisent une transition d'opération pawaPay en effet métier.
- `MobileMoneyPayoutInitiator.java` : versement du net au voyageur, partagé par la livraison, le force-release admin et la relance admin.
- `MobileMoneyPaymentDeadlineScheduler.java` : annule les bids dont le délai de paiement (30 min par défaut) est dépassé.
- `dto/MobileMoneyAccountResponse.java`, `dto/MobileMoneyInitiateRequest.java`, `dto/MobileMoneyPaymentStatusResponse.java`.

### Ailleurs

- `payments/PaymentRail.java` : enum `STRIPE` / `PAWAPAY`.
- `payments/events/MobileMoneyPaymentConfirmedEvent.java`, `MobileMoneyDepositFailedEvent.java`, `MobileMoneyPaymentExpiredEvent.java`.
- `auth/MobileMoneyPayoutStatus.java` : enum `NOT_CONFIGURED` / `ACTIVE` / `DISABLED`, jumeau de `StripeAccountStatus`.
- `common/Msisdn.java` : normalisation (chiffres seuls, sans `+`) et masquage (`+221 •••• 67`) des numéros mobile money.
- `db/migration/V241__pawapay_operations.sql`, `V242__users_mobile_money.sql`, `V243__payments_rail.sql`, `V244__bids_mobile_money.sql`.
- 35 classes de test nouvelles (détail dans la section Tests).

## Fichiers supprimés

L'ancien package mobile money (jamais branché sur un vrai payeur) est retiré en totalité : `MobileMoneyGateway`, `MobileMoneyGatewayRegistry`, `WaveGateway`, `OrangeMoneyGateway`, `MobileMoneyLinkResult`, `MobileMoneyPaymentEntity`, `MobileMoneyPaymentRepository`, `MobileMoneyPaymentRequest`, `MobileMoneyPaymentService`, `MobileMoneyProperties`, `MobileMoneyWebhookController`, `MobileMoneyBidAcceptedListener`, `MobileMoneyCommissionListener`, `dto/MobileMoneyStatusResponse`, `events/BidPaidByMobileMoneyEvent`, et leurs 7 tests dédiés. La table `mobile_money_payments` (V112) reste en base, inerte : une migration existante n'est jamais modifiée ni supprimée, et aucune ligne n'y est plus jamais écrite.

## Fichiers modifiés

- `admin/AdminFinanceController.java` : `GET /admin/mobile-money-payments` bascule de l'ancien repository mort vers `PawapayOperationRepository`.
- `admin/AdminPaymentController.java` : bifurcation par rail sur `force-release` et `refund`, deux nouveaux endpoints de relance (`retry-payout`, `retry-refund`), filtre `method` réellement branché sur `rail` (avant, tout `method != STRIPE` renvoyait une page vide).
- `admin/dto/AdminMobileMoneyResponse.java`, `AdminPaymentDetailResponse.java`, `AdminPaymentListItemResponse.java` : exposent le rail et les identifiants d'opération pawaPay (jamais le numéro en clair).
- `auth/UserEntity.java` : sept champs `mobileMoney*`, méthode `hasActiveMobileMoney()`.
- `config/SecurityConfig.java` : `/pawapay/callbacks/**` et `/pawapay/return/**` publics ; l'ancien `/webhooks/mobile-money/**` retiré.
- `matching/AnnouncementSearchMapper.java`, `matching/AnnouncementService.java` : troisième argument `traveler.hasActiveMobileMoney()` sur `AnnouncementPaymentRails.availableFor`.
- `matching/AwaitingPaymentCleanupScheduler.java` : ignore désormais les bids `MOBILE_MONEY` (voir Pièges).
- `matching/BidEntity.java` : `mobile_money_phone` chiffré (`EncryptedStringConverter`).
- `matching/BidNegotiationService.java` : garde explicite fermant `MOBILE_MONEY` sur le fil de négociation de trajet.
- `matching/BidRepository.java` : `findByStatusAndPaymentMethodAndAwaitingPaymentExpiresAtBefore` (bids expirés, paginée).
- `matching/BidService.java` : `MOBILE_MONEY` accepté à la création d'un bid, sous conditions.
- `matching/dto/BidRequest.java` : les champs `phoneNumber`/`countryCode` existants sont réutilisés pour le mobile money.
- `notifications/NotificationCategory.java`, `NotificationDeeplink.java`, `NotificationDispatcher.java`, `NotificationPrefsService.java`, `NotificationTexts.java` : nouveaux types de notification, textes, catégories, deep links du rail.
- `payments/BidAcceptedEventListener.java` : ignore aussi `MOBILE_MONEY` (comme `CASH`), pour ne jamais tenter `PaymentIntent.retrieve(null)`.
- `payments/DeliveryEventListener.java` : bifurcation par rail juste après le claim atomique `markReleasedIfEscrow`.
- `payments/PaymentEntity.java` : `rail` (défaut `STRIPE`) ; `stripePaymentIntentId` devient nullable. Aucune colonne de référence vers `pawapay_operations` (les trois colonnes de confort de V243 ont été retirées par V245 : un seul lecteur, et toute la discipline « jamais de setter après un claim » n'existait que pour elles).
- `payments/PaymentRepository.java` : `findByBidIdForUpdate`, `markEscrowIfPending`, `markCancelledIfPending`, filtre `rail` sur la liste admin.
- `matching/AnnouncementEntity.java` : `reserveCapacity(weightKg)` / `releaseCapacity(weightKg)`, la condition combinée (capacité bornée ET poids présent) portée une seule fois ; `auth/UserEntity.java` : `canReceiveMobileMoney(currency)`, source unique de « compte actif ET bonne devise » pour les trois portails ; `payments/cash/PaymentMethod.java` : `isCardEscrow()`, prédicat qui remplace la liste écrite à la main des modes hors escrow carte.
- `payments/RefundProcessor.java` : branche `PAWAPAY` en tête de `processRefund`.
- `payments/cash/PaymentMethod.java` : valeur `MOBILE_MONEY`, `isMobileMoney()` devient `this == MOBILE_MONEY`.
- `payments/currency/AnnouncementPaymentRails.java`, `CurrencyPaymentRails.java` : XOF/XAF autorisent `MOBILE_MONEY`.
- `payments/events/PaymentReleasedEvent.java` : constructeur à 6 arguments (devise + drapeau `mobileMoney`), l'ancien à 4 arguments conservé pour le rail carte.
- `payments/mobilemoney/MobileMoneyPaymentController.java` : réécrit (`accept`, `initiate`, `status`).
- `requests/service/NegotiationService.java`, `PackageRequestSearchMapper.java`, `PackageRequestService.java` : `MOBILE_MONEY` explicitement fermé sur ces chemins (négociation, demandes de colis).
- `resources/application.yml` : bloc `yadony.pawapay` (12 clés), `spring.task.scheduling.pool.size: 4`.
- `test/.../PaymentListenerTransactionalContractTest.java` : les six méthodes d'écouteurs mobile money enregistrées dans `fullContractListeners()` (fait au fil des tâches 14, 16 et 17, vérifié à la tâche 19, rien à modifier).

## Comment ça fonctionne (pour la maintenance)

Sept flux composent le rail. Chacun est décrit du point d'entrée jusqu'à l'écriture finale en base.

### 1. Activation du compte de versement (voyageur)

`POST /payments/mobile-money/account` (`hasAnyRole('TRAVELER','SENDER')`) sans corps : le numéro n'est **jamais** reçu du client.

1. `MobileMoneyAccountController.activate` résout l'appelant depuis le contexte de sécurité (jamais un paramètre d'URL), délègue à `MobileMoneyAccountService.activate(userId)`.
2. Si `yadony.pawapay.enabled = false` : 422 `mobile-money-disabled`.
3. Verrou pessimiste sur la ligne `users` (`findByIdForUpdate`) pour empêcher deux activations concurrentes d'écrire deux fois.
4. Le numéro est relu chez Firebase (`FirebaseContactService`), jamais saisi : absent -> 422 `mobile-money-phone-required`.
5. `client.predictProvider(phone)` puis `client.activeConfiguration()` (deux appels pawaPay, chacun encadré : une panne réseau rend 502 `mobile-money-provider-unavailable`, jamais un 500, et ne bloque pas indéfiniment le verrou pris à l'étape 3).
6. Contrôles : opérateur reconnu, opérateur supporte le `PAYOUT`, devise du provider = devise active du voyageur (`ActiveCurrencyResolver`). Sinon 422 `mobile-money-account-unsupported`.
7. Écriture : `mobileMoneyStatus = ACTIVE`, numéro (chiffré) et masqué, provider, pays, devise, `verifiedAt = now`. Audit `MM_ACCOUNT_ACTIVATED` (payload : uniquement le numéro masqué).

`DELETE` (`disable`) : passe le statut à `DISABLED`, conserve le numéro et les métadonnées (réactivation en un geste), audit `MM_ACCOUNT_DISABLED`. Ne vérifie **pas** `yadony.pawapay.enabled` : un voyageur doit pouvoir se retirer même rail fermé. Un bid mobile money en cours n'est pas annulé par une désactivation : c'est la livraison (flux 6) qui échouera si le compte n'est plus actif au moment du versement.

### 2. Création du bid (expéditeur)

Chemin existant `BidService.createBid`, étendu. `MOBILE_MONEY` accepté seulement si `CurrencyPaymentRails.allowsCode(devise, MOBILE_MONEY)` (XOF/XAF uniquement) **et** le voyageur a un compte actif, sinon 422 `mobile-money-not-available`. Le numéro payeur (`BidRequest.phoneNumber`/`countryCode`, champs réutilisés, pas de nouveau champ `payerPhone`) est optionnel : absent, il est prédit depuis le téléphone Firebase de l'expéditeur ; fourni, il est validé par `predictProvider` (pays incompatible avec la devise de l'annonce -> 422). Snapshot chiffré dans `bids.mobile_money_phone` / `mobile_money_country_code`. Le bid est créé `PENDING`, **sans** `PaymentEntity` et **sans** réservation de capacité (la capacité n'est prélevée qu'à l'acceptation, flux 3). Le fil de négociation de trajet (`BidNegotiationService.propose`) refuse explicitement `MOBILE_MONEY` : ce rail n'est ouvert qu'aux bids classiques.

### 3. Acceptation (voyageur)

Contrairement à la conception initiale (un écouteur asynchrone sur `BidAcceptedEvent`), l'implémentation retenue est un **endpoint dédié synchrone**, sur le modèle du rail espèces (`CashCommissionService.acceptCashBid`) : `POST /bids/{bidId}/mobile-money/accept` (`ROLE_TRAVELER`) -> `MobileMoneyBidPaymentService.acceptBid`. Tout se passe dans une seule transaction, aucun listener asynchrone entre l'acceptation et la création du paiement.

1. `yadony.pawapay.enabled` vérifié en tout premier, avant tout verrou.
2. Verrous **paiement inexistant encore, donc bid puis annonce** : `findByIdForUpdate` sur le bid, puis sur l'annonce (ordre spécifique à cette méthode : il n'y a pas encore de paiement à verrouiller).
3. Contrôles : l'annonce appartient à l'appelant, le bid est bien `MOBILE_MONEY`, le bid est `PENDING` (une reprise sur un bid déjà `AWAITING_PAYMENT` avec un paiement `PAWAPAY` existant renvoie simplement le statut courant, idempotent).
4. Le voyageur a un compte actif **et sa devise correspond à celle de l'annonce** : ce contrôle de devise est revérifié ici alors qu'il l'était déjà à la création du bid, parce que le compte a pu être désactivé puis réactivé dans une autre devise entre-temps ; c'est le dernier portail avant que l'argent bouge.
5. Capacité suffisante (sauf annonce en poids libre), annonce toujours ouverte.
6. Prix calculé (`MobileMoneyBidPricing`). Le code promo est **racheté** (`CommissionRateResolver.resolve` puis `PromoService.redeem`) et le bon de parrainage **consommé** (`CommissionVoucherService.consume`) à ce point précis, après le calcul du prix et avant la création du paiement, à la même position que le rail espèces : sans ce rachat/cette consommation, un même code ou un même bon réduirait la commission d'un nombre illimité d'envois mobile money.
7. Capacité réservée sur l'annonce (identique à la réservation espèces/carte).
8. Bid -> `AWAITING_PAYMENT`, `awaitingPaymentExpiresAt = now + depositDeadlineMinutes` (30 par défaut). `PaymentEntity` créé : `rail = PAWAPAY`, `status = PENDING`, `stripePaymentIntentId = null`, `amount` = brut, `commissionAmount`.
9. Audit `MM_BID_ACCEPTED_AWAITING_PAYMENT` + `MM_PAYMENT_CREATED`. `BidAcceptedEvent(..., mobileMoney = true)` publié : ce drapeau existait déjà (hérité de l'ancien rail Wave/Orange) et suffit à faire router `NotificationDispatcher` vers le push « Payez votre envoi » plutôt que le push générique « Demande acceptée ! ». `BidAcceptedEventListener` (le listener Stripe) ignore ce bid explicitement (voir Pièges), tout comme il ignore `CASH`.

### 4. Initiation, callback et séquestre (expéditeur paie)

**Initiation** : `POST /bids/{bidId}/mobile-money/initiate` (`ROLE_SENDER`, corps optionnel `{"phoneNumber": "..."}`) -> `MobileMoneyBidPaymentService.initiateDeposit`.

1. `findByBidIdForUpdate` (verrou pessimiste `FOR NO KEY UPDATE`, jamais un `FOR UPDATE` natif : celui-ci bloquerait le `KEY SHARE` que prend l'INSERT de l'opération pawaPay depuis sa propre transaction).
2. Paiement doit être `PENDING` (sinon 409), bid `AWAITING_PAYMENT` et deadline non dépassée (sinon 422 `mobile-money-payment-expired`).
3. Une opération DEPOSIT déjà vivante pour ce paiement -> elle est simplement renvoyée, aucune nouvelle création (l'expéditeur a rouvert l'écran). Ce contrôle passe **avant** la vérification `enabled` : couper le rail ne doit empêcher que les nouveaux mouvements d'argent, jamais la relecture d'une opération déjà en vol.
4. `enabled` vérifié seulement ici.
5. Numéro payeur résolu (override du corps > numéro déjà snapshoté sur le bid > téléphone Firebase de l'expéditeur), puis `predictProvider` + `activeConfiguration` (encadrés, 502 sur panne pawaPay). Contrôles : provider supporte le `DEPOSIT`, devise correspond au paiement, montant dans les bornes, pays résolvable.
6. `PawapaySubmissionService.submitDeposit(...)` : `PawapayOperationService.create` insère la ligne `pawapay_operations` en `CREATED` **dans sa propre transaction** (`REQUIRES_NEW`, committée avant l'appel HTTP), puis l'appel pawaPay est fait. `SUBMIT_REJECTED` -> 422 `mobile-money-deposit-rejected` ; `ACCEPTED` -> 201 avec l'opération (`authorizationUrl` pour Wave, construite avec la page de rebond `/api/v1/pawapay/return/{bidId}`).
7. Audit `MM_DEPOSIT_INITIATED` dans une transaction **indépendante** : si l'étape 6 rend `SUBMIT_REJECTED` et que la méthode lève, la transaction ambiante est annulée, mais l'audit de la tentative (et la ligne `pawapay_operations`, déjà committée) doivent survivre.

**Callback** : `POST /pawapay/callbacks/deposits` (public). Le corps est reçu en `byte[]` brut. Si signature requise (prod) ou simplement présente : `PawapaySignatureVerifier.verify` (Content-Digest sur les octets exacts, puis signature RFC 9421). Puis extraction de l'id et du statut, `PawapayOperationService.apply(id, status, ...)` : un seul `UPDATE ... WHERE status NOT IN (finaux)` ; une ligne touchée publie `PawapayOperationCompletedEvent`/`FailedEvent` **dans la même transaction**. Réponse 200 systématique, même sur opération inconnue ou déjà finale (jamais 404 : pawaPay retenterait 15 min).

**Poller** : `PawapayReconciliationPoller`, toutes les 2 min par défaut, relit via `getStatus` toute opération ouverte depuis plus de 60 s (lot borné à 200, plus anciennes d'abord) et applique la même transition. Rattrape les callbacks perdus.

**Séquestre** : `MobileMoneyDepositOutcomeListener.onCompleted` (`AFTER_COMMIT` + `REQUIRES_NEW`) sur `PawapayOperationCompletedEvent(kind = DEPOSIT)` -> `MobileMoneyBidPaymentService.confirmEscrow` :

- `markEscrowIfPending(paymentId, now)` : `UPDATE payments SET status='ESCROW', captured_at=now WHERE status='PENDING'`. Le deposit qui a financé le séquestre se retrouve par `pawapay_operations.payment_id`, jamais par une colonne de `payments`.
- 0 ligne touchée, deux cas : déjà `ESCROW` (rejeu, sortie silencieuse) ; ou `CANCELLED` (la deadline est tombée pendant que l'expéditeur saisissait son code PIN) -> remboursement automatique immédiat du deposit (`refundAfterCancel`), audit `MM_DEPOSIT_AFTER_CANCEL_REFUNDED`.
- 1 ligne touchée : bid `AWAITING_PAYMENT -> ACCEPTED`, numéro de suivi/QR/tracking générés si absents, audit `MM_ESCROW`, `MobileMoneyPaymentConfirmedEvent` publié -> pushs (expéditeur « paiement confirmé », voyageur « préparez la remise »).

Un deposit `FAILED` (`onFailed`) laisse le paiement `PENDING`, notifie l'expéditeur (« paiement refusé, réessayez »), audit `MM_DEPOSIT_FAILED`. Il peut relancer `/initiate`.

### 5. Expiration du délai de paiement

`MobileMoneyPaymentDeadlineScheduler`, chaque minute (`yadony.pawapay.deadline-cron`), lot borné à 200 (plus anciens d'abord). Chaque bid traité dans sa propre transaction (`MobileMoneyBidPaymentService.expire`, `REQUIRES_NEW`).

Ordre des verrous : **paiement** (`findByBidIdForUpdate`) **puis bid** (`findByIdForUpdate`), jamais l'inverse (voir Pièges). Rendu un `ExpireOutcome` :

- Bid déjà sorti d'`AWAITING_PAYMENT`/`MOBILE_MONEY`, ou deadline non atteinte -> `IGNORED`.
- Paiement introuvable (état structurellement impossible, mais échec fermé sur le chemin de l'argent) -> `PAYMENT_MISSING`, rien n'est touché.
- Dernier deposit encore ouvert (ni final) -> `IGNORED`, on laisse le poller le mener à son terme.
- Dernier deposit `COMPLETED` mais paiement encore `PENDING` (en vol, `confirmEscrow` pas encore passé) -> `DEPOSIT_COMPLETED_NOT_APPLIED`, **rien n'est annulé**.
- `markCancelledIfPending(paymentId)` rend 0 (course perdue contre `confirmEscrow`) -> `IGNORED`, rien d'autre n'est fait (ni bid, ni annonce, ni audit, ni événement).
- Rend 1 : capacité restituée à l'annonce (même verrou, même condition combinée que la réservation de `acceptBid`), bid `CANCELLED`, audit `MM_PAYMENT_EXPIRED`, `MobileMoneyPaymentExpiredEvent` publié -> pushs aux deux parties. Retourne `CANCELLED`.

Le scheduler agit sur la valeur rendue **après** le retour de `expire()`, donc hors de tout verrou : `CANCELLED` -> éviction du cache `announcements-search` ; `PAYMENT_MISSING`/`DEPOSIT_COMPLETED_NOT_APPLIED` -> alerte administrateur dédupliquée par bid (`MM_EXP_NO_PAYMENT_<bidId>` / `MM_EXP_DEPOSIT_DONE_<bidId>`).

### 6. Livraison et versement (voyageur payé)

`DeliveryConfirmedEvent` -> `DeliveryEventListener.handleDeliveryConfirmed` (`AFTER_COMMIT` + `REQUIRES_NEW`). Le claim atomique `markReleasedIfEscrow` (partagé par les deux rails) est tenté **en premier** ; la bifurcation par rail vient **après**, jamais avant.

`payment.getRail() == PAWAPAY` -> `releaseMobileMoney` : net = `amount - commissionAmount + travelerVoucherTopUp(...)`, arrondi (`PawapayAmounts.round`), formule volontairement recopiée à l'identique de `releaseV2` plutôt que factorisée (le rail carte n'est jamais touché). Puis `MobileMoneyPayoutInitiator.release(payment, bidId, travelerId, net, "delivery")` :

1. Le voyageur doit avoir un compte actif **dans la devise du paiement** : sinon alerte `PAWAPAY_PAYOUT_NO_ACCOUNT` puis exception (le claim est annulé par le rollback de la transaction ambiante, le paiement reste `ESCROW`).
2. Un payout déjà vivant ou abouti pour ce paiement (`findLive`) : il est **repris tel quel** (jamais un second `submitPayout`), alerte dédupliquée `MM_PAYOUT_ORPHAN_<paymentId>` via `AdminAlertEscalator`.
3. Sinon `submitPayout(...)`. `SUBMIT_REJECTED` -> alerte `PAWAPAY_PAYOUT_REJECTED` puis exception (claim annulé). `ACCEPTED` -> audit `ESCROW_RELEASED_MOBILE_MONEY` dans une transaction **indépendante** ; rien n'est écrit sur `payments` après le claim (voir Pièges).

Le paiement passe `RELEASED` dès que pawaPay **accepte** la soumission (même sémantique que le Transfer Stripe côté carte). La notification au voyageur (`PaymentReleasedEvent`, constructeur 6 arguments avec devise et `mobileMoney = true`) n'est publiée que plus tard, quand pawaPay **confirme** le payout : `MobileMoneyPayoutOutcomeListener.onCompleted` sur `PawapayOperationCompletedEvent(kind = PAYOUT)`. Un payout `FAILED` après acceptation (rare : les comptes/montants ont déjà été validés à la soumission) laisse le paiement `RELEASED` (la décision de payer a déjà été prise), alerte `PAWAPAY_PAYOUT_FAILED`, nécessite une relance admin.

Le force-release admin J+48 (`POST /admin/payments/{id}/force-release`) réutilise exactement ce même chemin (`MobileMoneyPayoutInitiator.release`) après son propre claim ; il ne mute plus jamais `payment` par setter, il relit l'entité (`entityManager.refresh(payment)`) avant de construire la réponse. La relance admin (`POST /admin/payments/{id}/mobile-money/retry-payout`) suit le même chemin, mais reprend le **montant de l'opération morte** plutôt que de le recalculer (un bon de parrainage consommé à la première tentative ne resservirait jamais, un recalcul sous-paierait le voyageur).

### 7. Remboursement

`RefundProcessor.processRefund` : en tête de méthode, `payment.getRail() == PAWAPAY` délègue à `refundMobileMoney` (le reste, Stripe, est inchangé).

- `PENDING` (jamais encaissé) -> `markCancelledIfPending`, aucun appel pawaPay ici. Si un deposit était encore en vol et aboutit ensuite, c'est le cas « deposit après annulation » du flux 4 qui rembourse.
- `ESCROW` -> claim `markRefundedIfEscrow` (même primitive que le rail carte), puis recherche du deposit `COMPLETED` d'origine (`findLatest`) et soumission du refund (ou réutilisation d'un refund déjà vivant via `findLive`, jamais resoumis). Audit dans une transaction indépendante. Deposit `COMPLETED` introuvable ou refus pawaPay -> alerte dédupliquée (`PAWAPAY_REFUND_NO_DEP_<paymentId>` / `PAWAPAY_REFUND_REJECTED_<paymentId>`) puis exception (claim annulé, paiement de nouveau `ESCROW`, remboursable plus tard).
- `RELEASED`/`REFUNDED`/`CANCELLED` -> aucune action, comme le rail carte.

L'endpoint admin `POST /admin/payments/{id}/refund` délègue **directement** à `RefundProcessor.processRefund` pour un paiement `PAWAPAY`, **avant** tout claim ambiant : brancher après un premier claim reproduirait un auto-interblocage (la transaction `REQUIRES_NEW` de `processRefund` tenterait de reverrouiller une ligne déjà tenue, non commitée, par la transaction ambiante).

Un second chemin de remboursement existe, volontairement non fusionné avec `RefundProcessor` : `MobileMoneyBidPaymentService.refundAfterCancel`, appelé uniquement depuis `confirmEscrow` pour le cas « deposit encaissé après annulation ». Une délégation à `RefundProcessor` a été tentée puis abandonnée : elle provoquerait le même auto-interblocage (la transaction ambiante de `confirmEscrow` détient déjà un verrou sur la même ligne `payments`). C'est de la dette assumée (voir Pièges).

## Points d'entrée API

| Endpoint | Rôle | Réponse |
|---|---|---|
| `GET /payments/mobile-money/account` | `SENDER`, `TRAVELER` | `MobileMoneyAccountResponse` |
| `POST /payments/mobile-money/account` | `SENDER`, `TRAVELER` | idem (activation, sans corps) |
| `DELETE /payments/mobile-money/account` | `SENDER`, `TRAVELER` | idem (désactivation) |
| `POST /bids/{bidId}/mobile-money/accept` | `TRAVELER` | `MobileMoneyPaymentStatusResponse` |
| `POST /bids/{bidId}/mobile-money/initiate` | `SENDER` | idem, 201, corps optionnel `{"phoneNumber": "..."}` |
| `GET /bids/{bidId}/mobile-money/status` | `SENDER`, `TRAVELER` | idem |
| `POST /pawapay/callbacks/deposits` | public (signature RFC 9421) | 200 systématique |
| `POST /pawapay/callbacks/payouts` | public (signature RFC 9421) | 200 systématique |
| `POST /pawapay/callbacks/refunds` | public (signature RFC 9421) | 200 systématique |
| `GET /pawapay/return/{bidId}` | public | 302 vers le deep link |

Endpoints admin modifiés ou ajoutés (tous sous `/admin`, garde `hasRole('ADMIN')` globale sur le contrôleur) :

| Endpoint | Autorité | Rôle dans le rail |
|---|---|---|
| `GET /admin/payments?method=PAWAPAY` | `PAYMENT_VIEW` | filtre désormais réellement sur `rail` |
| `GET /admin/mobile-money-payments` | `PAYMENT_VIEW` | liste des opérations `pawapay_operations`, plus récentes d'abord |
| `POST /admin/payments/{id}/force-release` | `PAYMENT_RELEASE` | bifurque vers `MobileMoneyPayoutInitiator.release` pour un paiement `PAWAPAY` |
| `POST /admin/payments/{id}/refund` | `PAYMENT_RELEASE` | délègue à `RefundProcessor` pour un paiement `PAWAPAY` |
| `POST /admin/payments/{id}/mobile-money/retry-payout` | `PAYMENT_RELEASE` | relance un versement mort |
| `POST /admin/payments/{id}/mobile-money/retry-refund` | `PAYMENT_RELEASE` | relance un remboursement mort |

## Entités JPA

- **`PawapayOperationEntity`** (table `pawapay_operations`, `V241`) : **n'étend pas `BaseEntity`**, choix délibéré, l'`id` est assigné (c'est l'identifiant envoyé à pawaPay) et non généré, et une opération ne se supprime jamais, même logiquement. `@Version` est nullable pour que Spring Data traite l'entité comme neuve malgré l'id déjà posé. Colonnes : `kind` (`DEPOSIT`/`PAYOUT`/`REFUND`), `status` (8 valeurs), `amount`, `currency`, `provider`, `country`, `msisdn` (chiffré) + `msisdn_masked`, `payment_id` (FK `payments`, nullable), `related_operation_id` (pour un REFUND, le DEPOSIT d'origine), `authorization_url`, `provider_transaction_id`, `failure_code`/`failure_message`, `raw_callback` (chiffré, voir Pièges), quatre horodatages, `version`. Index `idx_pawapay_ops_open` (partiel, statuts non finaux, pour le poller) et **`uq_pawapay_ops_live_per_payment`** UNIQUE `(payment_id, kind)` partiel `WHERE status NOT IN ('FAILED','SUBMIT_REJECTED') AND payment_id IS NOT NULL` : au plus une opération vivante ou aboutie par paiement et par type, le verrou base contre le double deposit/payout/refund.
- **`users.mobile_money_*`** (`V242`) : `mobile_money_status` (NOT NULL, DEFAULT `NOT_CONFIGURED`), `mobile_money_msisdn` (chiffré), `mobile_money_msisdn_masked`, `mobile_money_provider`, `mobile_money_country`, `mobile_money_currency`, `mobile_money_verified_at`. Enum Java `MobileMoneyPayoutStatus` (`auth/`), méthode `UserEntity.hasActiveMobileMoney()`, source unique de la condition, jamais recopiée ailleurs.
- **`payments.rail`** (`V243`) : `VARCHAR(10) NOT NULL DEFAULT 'STRIPE'` + CHECK. `stripe_payment_intent_id` devient nullable (contrainte UNIQUE conservée, PostgreSQL et H2 acceptent plusieurs NULL). Les trois colonnes de confort `pawapay_deposit_id`/`pawapay_payout_id`/`pawapay_refund_id` que V243 avait aussi posées sont **retirées par `V245`** (`V245PaymentsDropPawapayRefsMigrationTest`) : le seul lien qui fait autorité est `pawapay_operations.payment_id`, le détail admin le lit via `PawapayOperationService.findLatest`.
- **`bids.mobile_money_phone`** (`V244`, élargie de `VARCHAR(30)` à `VARCHAR(255)` et désormais chiffrée) + `bids.mobile_money_country_code` (type inchangé, mais sa valeur est vidée par la même migration partout où `mobile_money_phone` l'est). La deadline de paiement mobile money **réutilise** la colonne existante `bids.awaiting_payment_expires_at` : pas de nouvelle colonne `payment_deadline_at` comme envisagé initialement dans la conception.

## Logique métier critique

Chaque appel qui déplace de l'argent (deposit, payout, refund) est protégé par **deux verrous indépendants** : un en base yadony, un chez pawaPay.

| Risque | Verrou base yadony | Verrou pawaPay |
|---|---|---|
| Deux deposits pour un paiement | verrou pessimiste (`FOR NO KEY UPDATE`) sur `payments` + réutilisation de l'opération vivante + index unique partiel | l'UUID est persisté avant l'appel ; pawaPay refuse un id réutilisé |
| Deux payouts pour une livraison | claim `markReleasedIfEscrow` + index unique partiel + `findLive` avant toute soumission | idem, `payoutId` persisté avant l'appel |
| Deux refunds | claim `markRefundedIfEscrow` + index unique partiel | idem |
| Callback rejoué / callback et poller simultanés | `apply` = un seul `UPDATE ... WHERE status NOT IN (finaux)`, un seul gagnant | pawaPay retente 15 min, absorbé par l'état final |

`PawapayOperationService.apply` est le **point de transition unique**, partagé par le callback et le poller : jamais de départ depuis un état final, l'événement n'est publié que si une ligne a effectivement bougé.

Un payout (ou refund) déjà vivant retrouvé au moment de verser (claim précédent annulé après une soumission acceptée, événement rejoué, relance admin) est **toujours rattaché**, **jamais resoumis** : c'est `findLive` avant toute soumission qui fait cette distinction, doublé par l'index unique partiel en dernier recours.

Un deposit qui aboutit après l'annulation du bid (deadline dépassée pendant la saisie du PIN) est **remboursé automatiquement** : l'argent ne reste jamais en séquestre sans colis en face.

**Le lien qui fait foi est `pawapay_operations.payment_id`, jamais les colonnes `payments.pawapay_*_id`** : l'opération est écrite dans sa propre transaction (`REQUIRES_NEW`, committée avant l'appel HTTP), alors que la transition du paiement vit dans la transaction de l'appelant. Si le code échoue après une soumission acceptée, la transaction de l'appelant est annulée (le claim est défait) mais la ligne d'opération et l'appel HTTP, eux, ont eu lieu. Toute décision « ce paiement a-t-il déjà un payout ? » s'interroge sur `pawapay_operations`, jamais sur `payments`.

## Events Spring

| Événement | Champs | Publié par | Écouté par |
|---|---|---|---|
| `PawapayOperationCompletedEvent` | `operationId, kind, paymentId` | `PawapayOperationService.apply` (dans sa transaction) | `MobileMoneyDepositOutcomeListener`, `MobileMoneyPayoutOutcomeListener`, `MobileMoneyRefundOutcomeListener` (`onCompleted`, filtrent sur `kind`) |
| `PawapayOperationFailedEvent` | `operationId, kind, paymentId, failureCode, failureMessage` | idem | mêmes trois listeners, `onFailed` |
| `BidAcceptedEvent` | `bidId, senderId, travelerId, announcementId, mobileMoney` (champ préexistant, réutilisé) | `MobileMoneyBidPaymentService.acceptBid` | `NotificationDispatcher` (supprime le push générique si `mobileMoney`) |
| `MobileMoneyPaymentConfirmedEvent` | `bidId, senderId, travelerId, amount, currency` | `confirmEscrow` | `NotificationDispatcher` |
| `MobileMoneyDepositFailedEvent` | `bidId, senderId, failureCode` | `notifyDepositFailed` | `NotificationDispatcher` |
| `MobileMoneyPaymentExpiredEvent` | `bidId, senderId, travelerId` | `expire` (via le scheduler) | `NotificationDispatcher` |
| `PaymentReleasedEvent` | `bidId, travelerId, senderId, amount, currency, mobileMoney` (constructeur 6-arg neuf ; 4-arg legacy conservé, `currency="EUR"`, `mobileMoney=false`) | `DeliveryEventListener` (carte, direct) ou `MobileMoneyPayoutOutcomeListener.onCompleted` (mobile money, sur confirmation payout) | `NotificationDispatcher` |

Les six méthodes d'écouteurs mobile money (deux par événement générique du socle, trois écouteurs) sont enregistrées dans `PaymentListenerTransactionalContractTest.fullContractListeners()` : c'est le test qui garantit que chacune porte bien `@TransactionalEventListener(phase = AFTER_COMMIT)` **et** `@Transactional(propagation = REQUIRES_NEW)`. Tout nouveau listener touchant à l'argent de ce rail doit y être ajouté.

## Pièges et points d'attention

1. **Après un claim en masse (`@Modifying`) sur `payments`, ne jamais écrire sur l'entité gérée.** `PaymentEntity` n'a ni `@DynamicUpdate` ni `@Version` : un setter sur une entité chargée avant le claim la rend sale avec un statut périmé en mémoire, et le flush (souvent au commit) régénère un UPDATE de **toutes** les colonnes avec ces valeurs périmées, écrasant silencieusement le claim (`RELEASED` redevient `ESCROW`, un identifiant d'opération tout juste posé disparaît). Ce piège ne mord que si le setter est la **dernière** écriture avant le commit : un auto-flush déclenché par une requête ultérieure touchant le même espace de requête (par exemple une consommation de bon entre le claim et le setter) peut matérialiser l'écriture du setter **avant** l'UPDATE ciblé et masquer complètement le bug, ce qui l'a rendu d'autant plus difficile à détecter (le même code a été vu vert puis rouge selon l'ordre exact des lignes autour de lui). Deux seules façons sûres de continuer après un claim : ne rien écrire sur l'entité, ou la relire par `entityManager.refresh(payment)` avant de reconstruire une réponse — jamais un setter (`PaymentRepositoryMobileMoneyTest` fixe les deux). C'est aussi pourquoi `payments` ne porte plus aucune colonne que le rail devrait poser après un claim : les identifiants d'opération vivent dans `pawapay_operations`.
2. **Un bulk UPDATE JPQL n'incrémente pas `@Version`.** `applyTransition` et `markSubmittedIfStillCreated` sur `PawapayOperationEntity` sont des bulk UPDATE : Hibernate ne les fait jamais passer par le cycle de vie de l'entité, la colonne `version` n'y bouge donc jamais. La protection contre l'écrasement concurrent sur ces deux méthodes précises vient entièrement de leurs clauses `WHERE` (`status NOT IN (finaux)` / `status = CREATED`), pas du verrou optimiste : une écriture par entité classique passerait son contrôle de version même après qu'un callback ait fait avancer la ligne.
3. **L'autorité est `pawapay_operations.payment_id`.** Toute question « ce paiement a-t-il déjà un payout/refund/deposit ? » s'interroge sur `pawapay_operations` (`findLive`, `findLatest`), jamais sur `payments` — qui ne porte plus aucune référence d'opération depuis V245, précisément pour qu'aucune lecture ne puisse être en retard sur la réalité pawaPay.
4. **Rien de faillible après l'appel qui engage l'argent ; un audit qui doit survivre à un rollback a besoin de sa propre transaction.** Motif identique répété à trois endroits du rail (`initiateDeposit`, `MobileMoneyPayoutInitiator.release`, `RefundProcessor.refundEscrowedMobileMoney`) : la soumission pawaPay commite dans sa propre transaction (`REQUIRES_NEW`) avant l'appel HTTP, mais l'audit et le rattachement d'identifiant vivent, eux, dans la transaction de l'appelant. Si cette dernière échoue après une soumission acceptée, elle s'annule intégralement, y compris un audit qui y aurait été écrit, alors que l'argent a réellement bougé. Chaque site sensible enveloppe donc son audit dans un `TransactionTemplate` en `REQUIRES_NEW` propre (`independentAuditTransaction`), exécuté **avant** toute écriture ambiante ultérieure faillible.
5. **Une alerte dédupliquée sur un chemin qui lève est inerte.** Toute alerte de ce rail est dédupliquée en cherchant d'abord une ligne `admin_alerts` non résolue du même type avant d'en insérer une nouvelle. Si ce garde-fou (recherche + insertion + `raise`) reste dans la transaction ambiante d'une méthode dont l'appelant relance immédiatement une exception, la transaction entière (y compris la ligne de dédup fraîchement insérée) est annulée à chaque appel : la recherche suivante retrouve toujours zéro ligne, l'alerte repart à chaque tentative, et le mécanisme de déduplication ne sert jamais à rien alors qu'il semble présent dans le code. La règle du rail est portée par une seule classe : `AdminAlertEscalator.raiseOnce` écrit la ligne de dédup dans sa propre transaction `REQUIRES_NEW` (commitée avant de rendre la main) puis envoie l'alerte hors de toute connexion tenue — aucun appelant ne réimplémente ce bloc.
6. **L'index unique partiel n'existe pas sous H2 ; les migrations se testent avec `embedded-postgres`.** `uq_pawapay_ops_live_per_payment` est un index partiel PostgreSQL (clause `WHERE`) ; les tests unitaires et d'intégration de ce dépôt tournent sous H2 avec un schéma dérivé des entités, qui ne reproduit pas les index partiels. La seule preuve que la contrainte se déclenche réellement (pas seulement qu'elle existe dans le fichier SQL) est un test de migration utilisant `io.zonky.test:embedded-postgres` + Flyway réel, qui insère deux opérations vivantes concurrentes pour le même `(payment_id, kind)` et vérifie le nom de la contrainte dans l'exception PostgreSQL. Ce test existe déjà (`V241PawapayOperationsMigrationTest`) : le réutiliser comme modèle pour toute évolution de cet index.
7. **Le `Content-Digest` d'un callback porte sur les octets bruts ; une re-sérialisation casse toutes les signatures.** `PawapayCallbackController` reçoit le corps en `@RequestBody byte[]`, jamais un DTO Jackson désérialisé, précisément pour que `PawapaySignatureVerifier.verify` recalcule le digest sur les octets exacts reçus. Un futur refactor qui passerait par un objet désérialisé puis reformaté (même pour une raison anodine comme journaliser un id) romprait l'égalité octet à octet attendue et ferait échouer 100 % des callbacks en production avec un 401 d'apparence légitime, alors que les tests qui construisent leurs DTOs directement resteraient tous verts.
8. **`admin_alerts.type` est un `VARCHAR(60)` ; un préfixe suffixé par un UUID tient en 24 caractères.** Un UUID rend 36 caractères. Ce piège a déjà mordu deux fois pendant ce chantier (`MM_EXPIRE_PAYMENT_MISSING_` et `MM_EXPIRE_DEPOSIT_COMPLETED_`, respectivement 62 et 64 caractères une fois l'UUID ajouté) : l'INSERT lève une `DataIntegrityViolationException`, avalée par le `catch` générique du scheduler concerné, si bien que l'alerte n'est **jamais créée ni envoyée**, sans aucune erreur visible au-delà d'une ligne de log. Préfixes actuels du rail, tous vérifiés sous la barre : `PAWAPAY_BALANCE_LOW_` (+devise), `PAWAPAY_UNKNOWN_OP_` (55 avec l'UUID), `MM_PAYOUT_ORPHAN_` (53), `MM_EXP_NO_PAYMENT_` / `MM_EXP_DEPOSIT_DONE_` (54/56), `PAWAPAY_REFUND_NO_DEP_` / `PAWAPAY_REFUND_REJECTED_` (proches de la limite). `AdminAlertEscalator` refuse désormais bruyamment (`IllegalArgumentException`) tout type plus long que `TYPE_MAX_LENGTH` avant la moindre écriture ; tout nouveau préfixe suffixé par un UUID garde néanmoins son test unitaire assertant `(prefixe + UUID).length() <= AdminAlertEscalator.TYPE_MAX_LENGTH`.
9. **L'ordre des verrous est paiement, bid, annonce, uniforme sur tout le rail.** `acceptBid` (bid puis annonce, il n'existe pas encore de paiement), `initiateDeposit`, `confirmEscrow` et `expire` verrouillent tous dans le même ordre relatif dès qu'un paiement existe. Une version antérieure d'`expire()` inversait paiement et bid ; à la seconde précise où un deposit se confirme pendant que la deadline tombe sur le même bid, les deux transactions se seraient croisées en sens opposé, PostgreSQL aurait détecté l'interblocage et tué l'une des deux au bout d'une seconde. Perdre ce tirage est sans gravité pour `expire` (le tick suivant repasse) mais serait définitif pour `confirmEscrow` (son événement n'est publié qu'une fois, et le poller ne relit jamais une opération déjà finale).
10. **Le scheduler de nettoyage du rail carte ignore les bids mobile money.** `AwaitingPaymentCleanupScheduler` (préexistant) supprime physiquement les bids `AWAITING_PAYMENT` en carte au bout de 15 min et annule leur `PaymentIntent`. Un bid mobile money `AWAITING_PAYMENT` n'a pas de `PaymentIntent` et une deadline de 30 min par défaut sur la même colonne : le scheduler le saute explicitement (`if (paymentMethod == MOBILE_MONEY) continue;`). Son expiration est **entièrement** portée par `MobileMoneyPaymentDeadlineScheduler` (flux 5). Fusionner ou « simplifier » ces deux schedulers sans réintroduire cette garde soft-supprimerait un bid mobile money payé sur le mauvais horodatage, ou laisserait des bids mobile money bloqués si la garde disparaissait sans que son remplaçant soit branché.

Points d'attention additionnels, moins critiques mais réels :

- **`PaymentReleasedEvent.amount` n'a pas la même sémantique selon le rail** : le rail carte publie le **brut** payé par l'expéditeur (comportement historique, non corrigé ici), le rail mobile money publie le **net** réellement crédité au voyageur. Un futur agrégat qui lirait ce champ en pensant toujours lire un net surcompterait la commission sur chaque livraison carte.
- **Deux chemins de remboursement coexistent** (`RefundProcessor.refundMobileMoney` et `MobileMoneyBidPaymentService.refundAfterCancel`), volontairement non fusionnés (une délégation provoquerait un auto-interblocage, voir flux 7). Toute correction sur l'un doit être vérifiée sur l'autre.
- **Les numéros mobile money sont chiffrés à trois endroits distincts** (`pawapay_operations.msisdn`, `users.mobile_money_msisdn`, `bids.mobile_money_phone`), chacun avec sa colonne masquée pour l'affichage. Ne jamais journaliser ou exposer la forme non masquée. `AuditService.isSensitiveKey` (la denylist qui expurge les payloads d'audit) ne connaît pas la clé `msisdn` : aucun code n'écrit ce champ sous cette clé aujourd'hui, mais c'est une dette assumée, pas une garantie structurelle.
- **`raw_callback` est chiffré** (comme `msisdn`) parce que le JSON brut d'un callback pawaPay transporte le numéro de téléphone en clair dans `accountDetails.phoneNumber` : sans ce chiffrement, celui de la colonne voisine serait décoratif. Le poller réécrit cette colonne à chaque passage avec le snapshot de son propre `getStatus`, ce qui écrase le corps du callback **signé** reçu à l'origine : perte de traçabilité en cas de litige, dette assumée non corrigée dans ce chantier.

## Critères d'acceptation couverts

Décisions produit actées dans la spec (§1), chacune avec son point d'implémentation :

- [x] Agrégateur pawaPay API v2, callbacks signés RFC 9421 : `PawapayClient`, `PawapaySignatureVerifier`.
- [x] Trois rails en parallèle (carte si Connect, mobile money si compte actif, espèces toujours) : `AnnouncementPaymentRails.availableFor`.
- [x] Séquestre interne, libération au `DeliveryConfirmedEvent` uniquement (sauf force-release J+48) : `MobileMoneyBidPaymentService.confirmEscrow` + `DeliveryEventListener.releaseMobileMoney` + `AdminPaymentController.forceRelease`.
- [x] Paiement déclenché par l'expéditeur après acceptation, fenêtre de 30 min, jamais de push PIN automatique : `POST /bids/{bidId}/mobile-money/initiate`, `yadony.pawapay.deposit-deadline-minutes`.
- [x] Frais pawaPay absorbés par yadony, expéditeur paie le brut, voyageur reçoit son net exact : `MobileMoneyBidPricing`, `releaseMobileMoney` (net = brut - commission).
- [x] Numéro du voyageur = téléphone du compte yadony vérifié OTP, aucune saisie libre : `MobileMoneyAccountService.activate` (lecture Firebase uniquement).
- [x] Numéro payeur pré-rempli, modifiable : `resolvePayerMsisdn`, `MobileMoneyInitiateRequest.phoneNumber`.
- [x] Devises XOF et XAF uniquement, aucune conversion : `CurrencyPaymentRails`.
- [x] Remboursement = refund pawaPay sur le deposit d'origine : `RefundProcessor.refundEscrowedMobileMoney`.
- [x] Flux couverts = bids classiques uniquement, fil de négociation fermé : garde dans `BidNegotiationService.propose`.
- [x] Couverture pays portée par `active-configuration` pawaPay, Mali absent : aucune liste de pays codée en dur côté yadony, la disponibilité vient entièrement de `PawapayClient.activeConfiguration()`.

## Tests

- Suite complète : **4967 tests, 0 échec, 0 erreur, 7 ignorés, BUILD SUCCESS** (mesure du 2026-09-06 après la passe /simplify ; la première exécution des 40 commits de la branche ensemble comptait 4904 tests, sans régression croisée entre tâches).
- Couverture JaCoCo (instructions), packages du rail : `com.yadony.api.payments.pawapay` **96,7 %**, `.pawapay.dto` **100 %**, `.pawapay.events` **100 %**, `com.yadony.api.payments.mobilemoney` **96,1 %**, `.mobilemoney.dto` **100 %**. Tous au-dessus du seuil de 90 %.
- Couverture globale du dépôt : **90,1 %** d'instructions (91,8 % de lignes), contre 89 % à la fin du chantier. La passe /simplify a couvert les branches d'erreur que le rail introduisait sans jamais les exercer (réparation admin `repairDepositCompletedNotApplied`, `GET /admin/payments/{id}`, code `mobile-money-refund-failed`, les quatre algorithmes du vérifieur de signature, catch génériques du moniteur de solde et du poller). Les packages encore sous le seuil (`admin.metrics`, `messaging`, `common`…) sont du code legacy non touché par ce chantier, pratique déjà établie de ce dépôt (`docs/stories-done/refunds-idempotence-noshow-bidirectionnel.md`).
- 35 classes de test nouvelles, notamment : `PawapayClientTest`, `PawapaySignatureVerifierTest`, `PawapayPublicKeyStoreTest`, `PawapayOperationServiceTest`, `PawapayOperationServiceConcurrencyIT` (course callback/poller, deux `initiate` concurrents), `PawapayOperationRepositoryTest`, `PawapayCallbackControllerIT`, `PawapayReturnControllerIT`, `PawapayReconciliationPollerTest`, `PawapayBalanceMonitorTest`, `PawapayConfigGuardTest` (garde de démarrage prod), `V241PawapayOperationsMigrationTest`, `V244BidsMobileMoneyMigrationTest` (sur `embedded-postgres` réel), `MobileMoneyAccountServiceTest`/`ControllerIT`, `MobileMoneyBidPaymentServiceTest`/`EscrowTest`/`ExpireTest`, `MobileMoneyPayoutInitiatorTest`, les trois `*OutcomeListenerTest`, `MobileMoneyPaymentDeadlineSchedulerTest`, `PaymentRepositoryMobileMoneyTest` (le test qui prouve empiriquement le piège n°1 des Pièges), `DeliveryEventListenerMobileMoneyTest`, `RefundProcessorMobileMoneyTest`, `AdminPaymentControllerMobileMoneyIT`, `MsisdnTest`, `UserEntityMobileMoneyTest`, `AwaitingPaymentCleanupSchedulerMobileMoneyTest`.
- 7 classes de test de l'ancien package supprimées avec le code qu'elles testaient.
- Non-régression vérifiée à plusieurs reprises pendant le chantier sur les suites existantes complètes des packages touchés (`matching`, `notifications`, `payments`, `admin`) : toujours vertes, jamais de test Stripe modifié pour faire passer du code mobile money.

## Décisions techniques

Écarts constatés entre la conception initiale (spec) et l'implémentation retenue, tous vérifiés dans le code :

- **Acceptation par endpoint dédié synchrone**, pas par écouteur asynchrone sur `BidAcceptedEvent` comme envisagé : suit le précédent du rail espèces (`accept-with-commission`), tout se passe dans une seule transaction, élimine une classe entière de problèmes de séquencement asynchrone entre l'acceptation et la création du paiement.
- **Champs `bids` réutilisés** plutôt que créés : `BidRequest.phoneNumber`/`countryCode` (pas de nouveau `payerPhone`), `bids.awaiting_payment_expires_at` (pas de nouveau `payment_deadline_at`).
- **`raw_callback` chiffré** (`EncryptedStringConverter`), pas seulement `TEXT` : le JSON transporte le numéro de téléphone en clair dans les réponses pawaPay, laisser la colonne en clair aurait rendu décoratif le chiffrement de la colonne `msisdn` voisine.
- **`markSubmitted` transformé en UPDATE gardé** (`markSubmittedIfStillCreated`, `WHERE status = CREATED`) plutôt qu'un read-modify-write d'entité : sans cette garde, un callback pawaPay plus rapide que la réponse HTTP de notre propre appel d'initiation aurait été silencieusement écrasé.
- **Aucune référence d'opération pawaPay sur `payments`** (V245 retire les trois colonnes de confort de V243). Elles n'avaient qu'un lecteur (le détail admin) et imposaient, à chaque écriture après un claim en masse, des UPDATE ciblés dédiés (`attachPayoutId`/`attachRefundId`) pour ne jamais salir l'entité — un mécanisme qui a évité un bug sur chaque versement et chaque remboursement, mais que le prochain contributeur pouvait contourner par un simple setter. Sans colonne à poser, plus rien n'invite à écrire sur l'entité après un claim ; l'admin lit `pawapay_operations` (dernière opération de chaque type).
- **`entityManager.refresh(payment)` plutôt qu'une réconciliation manuelle par setters** dans les endpoints admin (force-release, retry-payout, retry-refund) : une réconciliation manuelle colonne par colonne s'est révélée incomplète une première fois (un horodatage réécrit avec une valeur postérieure de tout un aller-retour HTTP pawaPay) ; `refresh` rend le résultat correct quelle que soit la colonne, présente ou future.
- **Deux chemins de remboursement non fusionnés** (`RefundProcessor` et `refundAfterCancel`) : une délégation par appel direct provoquerait un auto-interblocage démontré (une transaction `REQUIRES_NEW` ne peut pas re-verrouiller une ligne déjà tenue, non commitée, par sa propre transaction appelante ; PostgreSQL ne détecte pas ce cas comme un interblocage, faute de cycle d'attente, et bloque indéfiniment). La convergence propre demanderait un événement + un écouteur `AFTER_COMMIT`, jugé hors périmètre de ce chantier.
- **Alertes dédupliquées par un type suffixé** (identifiant de paiement, de bid ou d'opération) plutôt qu'un type constant global : nécessaire dès qu'un même incident peut se reproduire indépendamment sur plusieurs paiements en parallèle.
- **Lot borné (200) et trié plus-ancien-d'abord** sur le poller et le scheduler d'expiration : un incident prolongé chez pawaPay ne doit jamais faire durer un seul passage des heures durant sur l'unique pool de scheduling (4 threads) partagé par les 28 tâches planifiées du dépôt.
- **Acteur de l'audit admin capturé via le `SecurityContext`** plutôt qu'un paramètre `Authentication` explicite sur les méthodes de contrôleur : évite de casser la compilation de tests unitaires existants qui appellent ces méthodes hors filtre Spring Security sur des paiements du rail carte, qui n'atteignent jamais le code mobile money concerné.
- **`PaymentReleasedEvent` garde son constructeur 4-arg historique** (devise `EUR` implicite, `mobileMoney=false`) : le rail carte n'a pas été retouché pour adopter la nouvelle forme, seul le rail mobile money utilise le constructeur 6-arg.
