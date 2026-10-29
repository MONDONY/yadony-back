# Sécurité du portefeuille, lot C1 : contrôles des corrections admin et des taux (Backend)

**Date:** 2026-10-28
**Status:** ✅ Complète

## Résumé
Troisième lot des correctifs de l'audit du portefeuille du 28/10. Un admin seul pouvait créditer
ou débiter n'importe quel solde sans limite, et un taux de change saisi à la main n'avait aucune
garde contre une faute de frappe. Désormais : double validation au-delà de 100 € par correction,
plafond cumulé de 500 € sur 24 h par admin et par compte ciblé, confirmation explicite d'un taux
qui s'écarte de plus de 10 % du taux en vigueur.

## Fichiers créés
- `payments/wallet/WalletAdjustmentRequestEntity.java`, `WalletAdjustmentRequestRepository.java`, `WalletAdjustmentRequestStatus.java` — demande de correction en attente d'un second admin.
- `admin/AdminWalletAdjustmentRequestController.java` — file d'approbation (`/admin/wallet-adjustment-requests`).
- `admin/dto/AdminWalletAdjustmentRequestResponse.java` — vue d'une demande (auteur, décideur, statut).
- `db/migration/V359__wallet_adjustment_requests.sql` — table `wallet_adjustment_requests`.

## Fichiers modifiés
- `payments/wallet/WalletAdminAdjustmentService.java` — seuil de double validation, plafond 24 h, `approve`, `reject`, `requests` ; motif `wallet-dispute-hold` pendant un litige.
- `payments/wallet/WalletTransactionRepository.java` — sommes des corrections par admin et par cible, verrou de la ligne admin.
- `admin/AdminUserWalletController.java` — 202 quand la correction attend une approbation.
- `admin/dto/AdminWalletAdjustmentResponse.java` — champ `pendingRequest`.
- `payments/currency/ExchangeRateUpdateService.java`, `admin/AdminExchangeRateController.java`, `admin/dto/UpdateExchangeRateRequest.java` — garde de variation et `confirmLargeChange`.
- `payments/wallet/topupcode/WalletTopupCodeRedemptionService.java` — motif `wallet-dispute-hold` pendant un litige.

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble du flux
1. L'admin envoie `POST /admin/users/{id}/wallet/adjustments` (montant, sens, motif, en-tête `Idempotency-Key`).
2. Rejeu : une clé déjà écrite renvoie le mouvement existant ; une clé déjà en attente renvoie la demande existante.
3. Le montant est converti en équivalent euro et borné à 500 € par opération. Le plafond 24 h, lui, n'est vérifié qu'à l'écriture (étape 5 ou 6) : il somme les corrections déjà écrites par l'admin auteur et reçues par le compte, pas les demandes en attente.
4. Au-delà de 100 € : une demande PENDING est enregistrée (audit `WALLET_ADJUSTMENT_REQUESTED`), réponse **202** avec `pendingRequest`, aucun mouvement.
5. Sinon : verrou du portefeuille, relecture du rejeu sous verrou, plafond sous le verrou de l'admin, contrôle du gel, écriture.
6. Un second admin approuve (`POST /{id}/approve`) : la demande est réclamée par un UPDATE conditionnel (`status = 'PENDING'`), puis le mouvement est écrit **au nom de l'auteur**, avec le plafond de l'auteur ; l'audit porte `approvedBy`. Ou il rejette (`POST /{id}/reject`, note facultative).
7. Taux : `PUT /admin/exchange-rates/{devise}` refuse en 422 `exchange-rate-large-change` (taux actuel, nouveau, écart en %) un écart > 10 % sans `confirmLargeChange: true`.

### Points d'entrée API
- `POST /api/v1/admin/users/{userId}/wallet/adjustments` — 200 (écrit) ou 202 (en attente). `ADMIN` + `WALLET_ADJUST`.
- `GET /api/v1/admin/wallet-adjustment-requests?status=PENDING&page=0&size=20` — file des demandes. `ADMIN` + `WALLET_ADJUST`.
- `POST /api/v1/admin/wallet-adjustment-requests/{id}/approve` — approuve et écrit. `ADMIN` + `WALLET_ADJUST`.
- `POST /api/v1/admin/wallet-adjustment-requests/{id}/reject` — rejette (`{ "note": "..." }`). `ADMIN` + `WALLET_ADJUST`.
- `PUT /api/v1/admin/exchange-rates/{currency}` — nouveau champ facultatif `confirmLargeChange`.

### Entités JPA impliquées
- `WalletAdjustmentRequestEntity` → `wallet_adjustment_requests` : `idempotency_key` unique, `eur_equivalent` figé à la demande, CHECK `decided_by <> requested_by`, index partiel des PENDING.

### Logique métier critique
- **Jamais son propre auteur** : 403 `wallet-adjustment-self-approval` dans le service, et CHECK SQL en dernier rempart (sur le statut APPROVED seulement : l'auteur peut retirer sa demande en la rejetant).
- **Une seule décision** : l'UPDATE conditionnel fait perdre la course au second décideur (409 `wallet-adjustment-request-already-decided`).
- **Plafond 24 h** (422 `wallet-adjustment-daily-cap-exceeded`) : 500 € exactement passe. Le verrou de la ligne `admin_users` empêche deux corrections simultanées du même admin, sur deux comptes, de franchir ensemble le plafond.
- **Litige ouvert** : la correction est refusée avec `wallet-dispute-hold` et non `wallet-refund-pending` (le gel du lot B couvre les deux).
- Erreurs : 404 `wallet-adjustment-request-not-found`, 400 `wallet-adjustment-request-status-invalid`.

### Events Spring publiés / écoutés
- Aucun nouvel événement.

### Pièges et points d'attention
- Un échec à l'approbation (solde devenu insuffisant, plafond, gel) annule toute la transaction : la demande reste PENDING et peut être rejetée.
- Le profil `test` (H2, `ddl-auto: create`) n'applique pas V359 : la contrainte « second admin » n'est couverte que par `WalletAdjustmentApprovalIT` (Postgres embarqué). Une première version la posait aussi sur les rejets et faisait échouer en 500 le retrait par l'auteur.
- `lockAdmin` est un UPDATE natif neutre (`SET id = id`) : `@Lock(PESSIMISTIC_WRITE)` produit `FOR NO KEY UPDATE`, refusé par H2.
- La garde de taux ne s'applique qu'à la saisie admin ; la synchronisation automatique garde son propre contrôle.

## Critères d'acceptation couverts
- [x] Correction > 100 € : en attente, écrite seulement après un second admin — `WalletAdminAdjustmentServiceTest`.
- [x] L'auteur ne peut pas approuver sa demande mais peut la retirer ; une demande ne se décide qu'une fois ; une approbation refusée laisse la demande en attente — idem, `WalletAdjustmentApprovalIT`, `AdminWalletAdjustmentRequestControllerTest`.
- [x] Plafond 500 €/24 h par admin et par cible, 500 € exactement accepté — idem.
- [x] Taux > 10 % d'écart : 422 sans confirmation, appliqué avec — `ExchangeRateUpdateServiceTest`, `AdminExchangeRateControllerTest`.
- [x] Correction ou code de recharge pendant un litige : motif `wallet-dispute-hold` — `WalletAdminAdjustmentServiceTest`, `WalletTopupCodeRedemptionServiceTest`.

## Tests
- `./mvnw clean test jacoco:report` → 14 161 tests. Les 11 classes en échec au premier passage (10 sur Postgres embarqué : `initdb` bloqué par des instances orphelines d'un run interrompu ; 1 test dépendant de l'heure, `TripSpaceAlertControllerIntegrationTest`, entre minuit et 1 h heure de Paris) passent toutes une fois relancées : 0 échec.
- Couverture JaCoCo des lignes : 96,43 % (classes nouvelles ou modifiées de C1 : 100 %).
- Tests ajoutés : `WalletAdjustmentApprovalIT` (Postgres), `AdminWalletAdjustmentRequestControllerTest` ; complétés : `WalletAdminAdjustmentServiceTest`, `AdminUserWalletControllerTest` (réponse 202), `ExchangeRateUpdateServiceTest`, `AdminExchangeRateControllerTest`, `WalletTopupCodeRedemptionServiceTest`.

## Décisions techniques
- **Table de demandes plutôt qu'un statut sur le mouvement** : le grand livre ne contient que des mouvements réellement appliqués, la demande garde l'auteur, le décideur et la note.
- **Écriture au nom de l'auteur** : le plafond et la responsabilité restent ceux de l'admin qui a demandé ; l'approbateur est tracé dans l'audit et la demande.
- Seuils (100 €, 500 €/24 h, 10 %) : décisions du propriétaire du 28/10.
