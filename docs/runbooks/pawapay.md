# Runbook pawaPay (rail mobile money)

**Périmètre :** exploitation et diagnostic du rail mobile money (Orange Money, Wave, MTN...) adossé à l'agrégateur pawaPay. Pour la mécanique interne détaillée (flux, événements, entités), voir `docs/stories-done/story-mobile-money-pawapay.md`.

**État à la date de rédaction (2026-09-05) :** rail implémenté et testé, `feature/pawapay-mobile-money` pas encore fusionnée, `yadony.pawapay.enabled` fermé partout. Rien de ce document ne s'applique tant que le rail n'est pas activé en environnement réel.

## Variables d'environnement

Toutes sous le préfixe `yadony.pawapay.*`, sans valeur par défaut sensible codée en dur (secrets par variable d'environnement uniquement) :

| Variable | Défaut | Rôle |
|---|---|---|
| `PAWAPAY_ENABLED` | `false` | Interrupteur général. Rail fermé : jamais proposé sur une annonce, activation de compte refusée (422), poller et moniteur de solde inactifs. Les bids déjà en cours continuent d'être traités (coupe l'entrée, pas la sortie). |
| `PAWAPAY_BASE_URL` | `https://api.sandbox.pawapay.io` | Basculer vers `https://api.pawapay.io` (ou équivalent prod) pour la mise en service réelle. |
| `PAWAPAY_API_TOKEN` | vide | Bearer token pawaPay. Jamais dans le code, jamais affiché dans un log ou un ticket. |
| `PAWAPAY_CALLBACK_SIGNATURES` | `false` | Vérification RFC 9421 obligatoire ou non. **Doit être `true` en production** : le démarrage refuse (`IllegalStateException` au boot) si le profil est `prod`, le rail actif et cette valeur à `false`. |
| `PAWAPAY_DEPOSIT_DEADLINE_MINUTES` | `30` | Fenêtre laissée à l'expéditeur pour payer après acceptation du bid. |
| `PAWAPAY_RETURN_BASE_URL` | `https://api.yadony.com` | Base de la page de rebond Wave (doit être en HTTPS, jamais un schéma applicatif : les PSP refusent une redirection vers un deep link). |
| `PAWAPAY_DEEP_LINK_AWAITING` | `yadony://bids/%s/mobile-money/awaiting` | Modèle du deep link vers l'écran d'attente app, `%s` = bidId. |
| `PAWAPAY_BALANCE_MIN_XOF` / `PAWAPAY_BALANCE_MIN_XAF` | `0` (désactivé) | Seuils d'alerte de solde bas, par devise. Voir section Préfinancement. |
| `PAWAPAY_POLL_CRON` | `0 */2 * * * *` | Fréquence du poller de réconciliation (toutes les 2 min). |
| `PAWAPAY_BALANCE_CRON` | `0 15 * * * *` | Fréquence du moniteur de solde (toutes les heures, à la 15e minute). |
| `PAWAPAY_DEADLINE_CRON` | `0 * * * * *` | Fréquence du scheduler d'expiration des paiements (toutes les minutes). |

## Avant d'activer le rail : vérifications sur le premier vrai callback de recette

Le vérifieur de signature (`PawapaySignatureVerifier`) et le contrôleur de callback ont été construits contre la spécification RFC 9421 telle que documentée, mais **jamais confrontés à un vrai callback pawaPay**. Les points suivants sont fail-closed (un désaccord se traduit par un 401 systématique, jamais un versement erroné) mais bloqueraient **tout** le rail s'ils ne correspondaient pas à ce que pawaPay envoie réellement. À vérifier dès le premier callback de recette, avant toute mise en service :

1. **L'URL de callback enregistrée chez pawaPay inclut-elle bien `/api/v1` ?** Le code signe `getRequestURI()`, qui inclut le context-path. Si l'URL enregistrée dans le dashboard pawaPay omet ce préfixe, la base de signature calculée par yadony ne correspondra jamais à celle signée par pawaPay : **toutes** les signatures échoueront.
2. **pawaPay signe-t-il `@authority` sans le port ?** nginx transmet `Host: api.yadony.com` sans port explicite. Une divergence ici casse aussi 100 % des signatures.
3. **pawaPay couvre-t-il `@target-uri`, `@scheme` ou `@query` dans sa liste de composants signés ?** Ces trois composants dérivés ne sont pas gérés par `PawapaySignatureVerifier`. S'ils sont couverts par pawaPay, 100 % des callbacks seront rejetés. L'échec est fail-closed et donc visible immédiatement (401 systématique dès le premier callback), pas une découverte tardive.
4. **pawaPay re-signe-t-il chaque réessai d'un même callback, ou rejoue-t-il le même message signé avec backoff ?** `MAX_SIGNATURE_AGE_SECONDS` est fixé en dur à 300 s. Si pawaPay rejoue le même message signé au-delà de 5 minutes sans le re-signer, ces réessais tardifs échoueront en 401. Si c'est le cas observé, il faut élargir la constante (actuellement non paramétrée en propriété).
5. **pawaPay signale-t-il une opération inconnue par une réponse `200` avec `status: NOT_FOUND`, ou par un `404` HTTP ?** Le poller de réconciliation ne considère une opération comme définitivement inconnue que sur ce premier cas ; tout le reste (4xx, 404, corps vide) est traité comme une erreur réseau retentable. Sur un vrai `404`, une opération resterait ouverte indéfiniment (sens sûr : aucun argent ne bouge, et une alerte Sentry ERROR remonte au bout d'une heure sans résolution, voir catalogue des alertes) mais ce cas précis n'a pas de test contre une vraie réponse pawaPay.
6. **Deux `POST /bids/{id}/mobile-money/initiate` concurrents sur le même paiement rendent-ils bien un 409, jamais un 500 ?** L'index unique base (`uq_pawapay_ops_live_per_payment`) est prouvé se déclencher sur PostgreSQL réel (test de migration), mais la traduction de l'exception PostgreSQL en 409 HTTP n'est testée que contre un dépôt mocké. À rejouer une fois sur un environnement PostgreSQL réel avant la mise en service.
7. **Le portefeuille pawaPay yadony est-il préfinancé ?** Voir section Préfinancement plus bas : sans solde, tout payout échoue (`SUBMIT_REJECTED`).

## Précondition de déploiement

**Le chiffrement de `pawapay_operations.raw_callback` n'est pas rétro-compatible.** `EncryptionService.decrypt` commence par un décodage Base64 ; toute ligne écrite avant l'ajout de ce chiffrement (donc en clair) ferait échouer la lecture. Sans effet en production puisque la migration `V241` n'y a jamais tourné avant ce chiffrement. **Mais toute base de développement locale où `V241` aurait déjà été appliquée avant cette version doit être purgée** (`docker compose down -v` puis remigrer) avant de reprendre le travail sur ce rail.

## Activation dans le dashboard pawaPay

1. Enregistrer les trois URLs de callback (avec le préfixe `/api/v1`, voir point 1 ci-dessus) :
   - `https://api.yadony.com/api/v1/pawapay/callbacks/deposits`
   - `https://api.yadony.com/api/v1/pawapay/callbacks/payouts`
   - `https://api.yadony.com/api/v1/pawapay/callbacks/refunds`
2. Activer **« Signed callbacks »** côté pawaPay en production, en cohérence avec `PAWAPAY_CALLBACK_SIGNATURES=true`.
3. Récupérer le token API et le poser en variable d'environnement (`PAWAPAY_API_TOKEN`), jamais en dur.

## Configuration nginx

- `proxy_set_header Host $host;` est **indispensable** sur les routes pawaPay : le contrôleur signe `@authority` à partir de l'en-tête `Host` reçu (avec repli sur `getServerName()`). Sans ce header transmis tel quel, la vérification de signature échoue systématiquement.
- Allowlist des IP sortantes pawaPay sur `/api/v1/pawapay/callbacks/*` (voir la documentation pawaPay pour la liste à jour). Défense en profondeur : la signature RFC 9421 reste la protection primaire.
- Sortir `/api/v1/pawapay/callbacks/*` et `/api/v1/pawapay/return/*` du rate-limit général (30 req/min) et **a fortiori** du rate-limit `/auth`/`/kyc` (5 req/min) : ce ne sont ni des routes d'authentification ni des routes utilisateur, un webhook agrégateur peut légitimement rappeler plusieurs fois en cas de doute réseau de son côté, et un blocage ferait perdre des confirmations de paiement réelles.

## États d'un paiement et d'une opération

### `PaymentEntity.status` (rail mobile money)

| Statut | Signification | Ce qui le fait avancer |
|---|---|---|
| `PENDING` | Bid accepté, paiement créé, deposit pas encore confirmé | deposit `COMPLETED` -> `ESCROW` ; deadline dépassée sans deposit vivant -> `CANCELLED` |
| `ESCROW` | Deposit confirmé, fonds sur le solde pawaPay de yadony | `DeliveryConfirmedEvent` (ou force-release admin) -> `RELEASED` ; remboursement -> `REFUNDED` |
| `RELEASED` | Payout soumis et **accepté** par pawaPay (pas nécessairement encore arrivé sur le téléphone du voyageur) | rien : c'est un état terminal côté yadony. La notification voyageur n'arrive qu'à la confirmation `COMPLETED` du payout |
| `CANCELLED` | Jamais encaissé, deadline dépassée ou bid annulé avant paiement | rien, terminal |
| `REFUNDED` | Remboursé à l'expéditeur (avant ou après séquestre) | rien, terminal |

### `PawapayOperationEntity.status` (une opération : un deposit, un payout ou un refund)

| Statut | Catégorie | Note |
|---|---|---|
| `CREATED` | ouvert | posé par yadony avant l'appel HTTP, avant toute réponse pawaPay |
| `ACCEPTED`, `PROCESSING`, `ENQUEUED`, `IN_RECONCILIATION` | ouvert | valeurs renvoyées par pawaPay, en cours de traitement de leur côté |
| `COMPLETED` | final | argent effectivement transféré |
| `FAILED` | final, mort | argent non transféré, une nouvelle opération du même type est possible |
| `SUBMIT_REJECTED` | final, mort | refusé dès la soumission (jamais parti chez pawaPay), une nouvelle opération est possible |

Ce qui fait avancer une opération : un callback pawaPay signé sur `/pawapay/callbacks/{deposits,payouts,refunds}`, ou le poller de réconciliation (`PawapayReconciliationPoller`, toutes les 2 min par défaut) qui relit `getStatus` pour toute opération ouverte depuis plus de 60 s. Les deux passent par le même point de transition unique (`PawapayOperationService.apply`) : un seul gagne en cas de course, jamais de retour en arrière depuis un état final.

## Catalogue des alertes

Toutes les alertes de ce rail sont visibles dans `admin_alerts` et relayées (Telegram/Sentry selon configuration) via `AdminAlertService`. Sauf mention contraire, elles sont **dédupliquées** : une alerte non résolue du même type bloque la création d'une nouvelle jusqu'à résolution.

| Type | Signification | Déclencheur | Geste de reprise |
|---|---|---|---|
| `PAWAPAY_BALANCE_LOW_<DEVISE>` | Solde du portefeuille pawaPay yadony sous le seuil configuré, pour cette devise | Moniteur horaire (`PawapayBalanceMonitor`) | Préfinancer le portefeuille (virement manuel, voir section suivante), puis marquer l'alerte résolue |
| `PAWAPAY_UNKNOWN_OP_<operationId>` | Une opération reste ouverte depuis plus d'une heure et pawaPay ne la reconnaît plus | Poller de réconciliation | Vérifier manuellement le statut réel côté dashboard pawaPay avec cet id ; corriger l'état yadony à la main si nécessaire |
| `PAWAPAY_ESCROW_CONFIRMATION_FAILED` | `confirmEscrow` a levé une exception après qu'un deposit soit passé `COMPLETED` (souvent une panne réseau) | `MobileMoneyDepositOutcomeListener.onCompleted`, **non dédupliquée** (déclencheur ponctuel) | Vérifier l'état du paiement cité dans l'alerte ; si un remboursement était en cours, vérifier son issue chez pawaPay avant toute action manuelle |
| `PAWAPAY_PAYOUT_NO_ACCOUNT` | À la livraison, le voyageur n'a pas de compte mobile money actif compatible (désactivé, ou changé de devise depuis l'acceptation) | `MobileMoneyPayoutInitiator.release`, à la livraison ou à un force-release | Contacter le voyageur pour qu'il réactive/reconfigure son compte, puis relancer via `retry-payout` (le paiement reste `ESCROW` en attendant) |
| `PAWAPAY_PAYOUT_REJECTED` | pawaPay a refusé la soumission du payout (solde yadony insuffisant, provider fermé...) | idem | Regarder `failureCode` dans le payload de l'alerte ; si solde insuffisant, préfinancer puis `retry-payout` |
| `PAWAPAY_PAYOUT_FAILED` | pawaPay avait accepté le payout puis l'a fait échouer (rare, cas anormal : comptes/montants déjà validés à la soumission) | `MobileMoneyPayoutOutcomeListener.onFailed` | `retry-payout` après avoir compris la cause (`failureCode`) |
| `MM_PAYOUT_ORPHAN_<paymentId>` | Un payout déjà vivant ou abouti a été retrouvé et rattaché plutôt que resoumis (claim précédent annulé pour une autre raison, événement de livraison rejoué, ou relance admin sur un payout déjà en vol) | `MobileMoneyPayoutInitiator.release` | Informatif la plupart du temps (c'est le mécanisme anti-double-versement qui fonctionne) ; vérifier que le paiement cité a bien un payout cohérent dans `GET /admin/mobile-money-payments`, puis résoudre |
| `PAWAPAY_REFUND_NO_DEP_<paymentId>` | Le paiement est `ESCROW` mais aucun deposit `COMPLETED` n'est retrouvé pour le rembourser | `RefundProcessor.refundEscrowedMobileMoney` | Investigation manuelle obligatoire : remboursement à traiter hors process, ce cas ne devrait structurellement pas arriver |
| `PAWAPAY_REFUND_REJECTED_<paymentId>` | pawaPay a refusé la soumission du refund | idem | Regarder `failureCode`, puis `retry-refund` |
| `PAWAPAY_REFUND_FAILED` | Le remboursement automatique (deposit après annulation) a été rejeté par pawaPay | `MobileMoneyBidPaymentService.refundAfterCancel` | Investigation manuelle : l'expéditeur a été débité, le remboursement automatique a échoué |
| `PAWAPAY_DEPOSIT_AFTER_CANCEL` | Un deposit est arrivé après annulation du bid (deadline dépassée pendant la saisie du PIN) et a été remboursé automatiquement | `confirmEscrow`, **non dédupliquée** (déclencheur ponctuel), informative | Aucune action requise en général ; vérifier que le remboursement cité a bien abouti chez pawaPay |
| `PAWAPAY_DEPOSIT_AFTER_CANCEL_REJECTED` | Le remboursement automatique du cas ci-dessus a lui-même été refusé par pawaPay | idem | Investigation manuelle, l'expéditeur est débité sans remboursement |
| `MM_EXP_NO_PAYMENT_<bidId>` | Un bid `AWAITING_PAYMENT`/mobile money n'a aucun paiement `PAWAPAY` associé (état structurellement anormal) | Scheduler d'expiration | Investigation manuelle du bid cité, incident à documenter |
| `MM_EXP_DEPOSIT_DONE_<bidId>` | Le délai de paiement est dépassé mais le dernier deposit est `COMPLETED` alors que le paiement est encore `PENDING` (confirmation en vol, pas un dépôt mort) | idem | Ne rien annuler ; vérifier que `confirmEscrow` finit par passer (déplacement possible d'une panne du listener asynchrone) ; si bloqué durablement, forcer manuellement la confirmation après vérification chez pawaPay |

## Les deux relances d'administration

Toutes deux sous `POST /admin/payments/{id}/mobile-money/...`, autorité `PAYMENT_RELEASE`.

### `retry-payout`

Ce qu'elle fait : relance un versement au voyageur en réutilisant exactement le chemin de la livraison (`MobileMoneyPayoutInitiator.release`), avec le **montant de la dernière opération payout morte** (jamais recalculé : un bon de parrainage consommé à la première tentative ne resservirait pas, un recalcul sous-paierait le voyageur).

Ce qu'elle refuse :
- Le paiement doit être `RELEASED` (sinon 422 `mobile-money-retry-not-allowed`) : aucun nouveau claim n'est posé ici, la décision de libérer a déjà été prise à la livraison ou à un force-release antérieur.
- La **dernière** opération PAYOUT connue doit être `FAILED` ou `SUBMIT_REJECTED` (sinon 422) : vivante, déjà `COMPLETED`, ou introuvable, la relance est refusée pour ne jamais déclencher un second versement. Si un payout est encore vivant, `MobileMoneyPayoutInitiator.release` le rattache silencieusement (alerte `MM_PAYOUT_ORPHAN_` dédupliquée) plutôt que d'en soumettre un nouveau.
- Un paiement non mobile money (`rail != PAWAPAY`) est rejeté (422).

### `retry-refund`

Ce qu'elle fait : relance le remboursement pawaPay du deposit d'origine. Ne mute **jamais** `payments.status` (déjà `REFUNDED`) : seul le refund côté pawaPay est rejoué.

Ce qu'elle refuse :
- Le paiement doit être `REFUNDED` (sinon 422).
- La dernière opération REFUND connue doit être `FAILED` ou `SUBMIT_REJECTED` (sinon 422).
- Il doit exister un deposit `COMPLETED` à rembourser (sinon 422 `Aucun deposit abouti à rembourser`) : sans lui, il n'y a rien à rejouer, investigation manuelle requise (voir alerte `PAWAPAY_REFUND_NO_DEP_`).

## Préfinancement manuel du portefeuille et alerte de solde bas

Le solde pawaPay de yadony (celui qui finance les payouts) se préfinance **manuellement**, par virement, hors de toute automatisation de ce rail : ce n'est pas une opération technique, c'est une démarche yadony auprès de pawaPay ou de sa banque.

`PawapayBalanceMonitor` interroge `walletBalances()` chaque heure (`PAWAPAY_BALANCE_CRON`) et compare à `PAWAPAY_BALANCE_MIN_XOF`/`_XAF` (désactivé par défaut, seuil à `0`). Sous le seuil : alerte `PAWAPAY_BALANCE_LOW_<DEVISE>`, dédupliquée par devise (une alerte non résolue XOF n'empêche pas une alerte XAF distincte).

**Attention à ne pas confondre avec `GET /admin/wallets`** : cet endpoint existant liste les portefeuilles internes yadony (`payments.wallet.WalletAccountRepository`, feature séparée, sans rapport avec pawaPay). Le solde du portefeuille pawaPay lui-même (celui qui finance les payouts) n'a **aucun écran admin dédié** à ce jour : il ne se consulte qu'indirectement, via l'alerte de solde bas, ou directement chez pawaPay (dashboard, ou un appel manuel à `GET /v2/wallet-balances`).

**Un solde insuffisant fait échouer un payout en `SUBMIT_REJECTED`** (alerte `PAWAPAY_PAYOUT_REJECTED`), jamais en silence : le paiement reste `RELEASED` côté yadony (la décision de payer a déjà été prise), en attente d'une relance `retry-payout` une fois le portefeuille réapprovisionné.

## Un versement est parti mais le paiement paraît encore ESCROW

Ce cas correspond exactement au mécanisme documenté dans la story (piège n°1 et n°4) : une soumission de payout a été **acceptée** par pawaPay (l'argent a quitté ou va quitter le solde yadony), mais quelque chose a échoué juste après côté yadony (panne réseau sur l'audit, redémarrage applicatif, exception inattendue) avant que le claim ne soit durablement acté, et la transaction s'est annulée. Diagnostic et reprise, dans l'ordre :

1. **La question « ce paiement a-t-il déjà un payout ? » se pose sur `pawapay_operations`, jamais sur `payments`.** La table `payments` ne porte aucune référence d'opération (les colonnes de confort de V243 ont été retirées par V245) : le détail admin affiche la dernière opération de chaque type lue dans `pawapay_operations`.
2. Consulter `GET /admin/mobile-money-payments` (ou interroger `pawapay_operations` directement) filtré sur le `paymentId` en cause : y a-t-il déjà une opération `PAYOUT` `ACCEPTED`, `PROCESSING` ou `COMPLETED` pour ce paiement ?
3. **Si oui** : ne rien soumettre de nouveau. Si le paiement affiche `ESCROW` alors qu'un payout vivant ou abouti existe déjà, l'affichage yadony est simplement en retard sur la réalité pawaPay ; l'alerte `MM_PAYOUT_ORPHAN_<paymentId>` ne part que si `release()` est rappelée entre-temps (un événement de livraison rejoué, un force-release, ou la relance `retry-payout`), pas spontanément. Vérifier chez pawaPay directement (dashboard, ou `GET /v2/payouts/{id}`) le statut réel de cette opération, puis déclencher un **force-release** (`POST /admin/payments/{id}/force-release`) pour faire converger l'état yadony : il rappelle le même chemin, retrouve l'opération existante via `findLive` et la rattache sans jamais en soumettre une seconde.
4. **Si non** (aucune opération payout trouvée du tout pour ce paiement) : c'est un incident réel, pas juste un affichage en retard. Investiguer les logs applicatifs autour de l'horodatage suspecté avant toute action ; ne jamais déclencher `retry-payout` sur un paiement encore `ESCROW` (l'endpoint le refuse de toute façon, il exige `RELEASED`) sans avoir d'abord confirmé qu'aucun versement n'est réellement parti.
5. Dans tous les cas, l'index unique base (`uq_pawapay_ops_live_per_payment`) interdit structurellement un second payout vivant pour le même paiement : un double versement complet (deux payouts réellement acceptés par pawaPay pour le même paiement) n'est pas possible, seule la **cohérence de l'affichage** yadony peut être en retard sur la réalité pawaPay.

## Couverture pays

La disponibilité réelle du rail (opérateurs, pays, devises) est portée entièrement par `activeConfiguration()` côté pawaPay : yadony ne code aucune liste de pays en dur, aucun écran admin ne l'expose non plus aujourd'hui. À la date de rédaction de la spec, confirmés couverts par pawaPay : Sénégal, Côte d'Ivoire, Cameroun, Burkina Faso, Bénin. Non couverts : **Mali**, **Guinée**, **Togo**. Cette liste évolue du côté de pawaPay indépendamment de ce dépôt : en cas de doute, interroger directement l'API pawaPay (`GET /v2/active-conf` sur l'environnement concerné, via le dashboard ou un appel manuel) plutôt que de se fier à ce document ou au code.
