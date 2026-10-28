# Sécurité du portefeuille, lot A : argent réel en double et serveur bloqué (Backend)

**Date:** 2026-10-28
**Status:** ✅ Complète

## Résumé
Correctifs des quatre défauts les plus graves relevés par l'audit de sécurité du portefeuille du
28/10 : un montant démesuré bloquait un thread environ 24 s (déni de service), une erreur réseau
Stripe ouvrait un ticket manuel alors que Stripe avait peut-être déjà remboursé (double paiement),
les recharges carte et mobile money d'une même devise partaient en ticket manuel portant tout le
solde, non-cash compris, et deux déclencheurs concurrents recréditaient deux fois une commission.

## Fichiers créés
- `common/BoundedDecimals.java` — borne d'un décimal reçu du client (15 chiffres entiers, 18 décimales), contrôlée sans calcul.
- `config/BoundedDecimalParamConfig.java` — convertisseur String → BigDecimal des `@RequestParam`, même borne.
- `db/migration/V356__wallet_refund_channel_mixed.sql` — contrainte du canal étendue à `AUTOMATIC_MIXED`.

## Fichiers modifiés
- `config/JacksonConfig.java` — désérialiseur BigDecimal borné (400 `malformed-request` au-delà).
- `payments/wallet/WalletTopupOrchestrator.java` — plafond de recharge carte : 1 000 € convertis au taux du jour (422 `wallet-topup-above-max`), app et portail.
- `payments/wallet/WalletSelfRefundService.java` — erreur Stripe transitoire : item laissé PENDING, refund retrouvé par métadonnée à la reprise ; `charge_already_refunded` sans ticket manuel ; canal mixte (émission et réconciliation par rail de l'item).
- `payments/wallet/WalletRefundChannel.java` — `AUTOMATIC_MIXED`.
- `payments/wallet/WalletRefundIssueRecoveryScheduler.java` — reprend aussi les demandes mixtes.
- `payments/wallet/dto/WalletRefundRequestSummaryResponse.java`, `auth/dto/WalletSettlementDto.java`, `auth/UserService.java` — rail `MIXED` annoncé, avec les montants de l'allocateur.
- `common/stripe/AdminAlertService.java` — `wallet-self-refund-stripe-retry` en avertissement.
- `payments/cash/CashCommissionService.java`, `matching/BidRepository.java` — statut de commission relu en base sous verrou avant tout recrédit.
- `matching/NoShowService.java`, `matching/NoShowScheduler.java` — verrou du colis au signalement d'absence ; plus de transaction englobante sur le lot.
- Fiches assistant `recharger-solde` et `rembourser-solde` (fr, en).

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble du flux
1. **Décimaux reçus** : tout `BigDecimal` d'un corps JSON ou d'un paramètre de requête passe par `BoundedDecimals.isBounded` (sur `precision()` et `scale()`, gratuits) avant d'atteindre un service. `1e10000000` est refusé en 400 sans aucun calcul.
2. **Recharge carte** : `WalletTopupOrchestrator.requireWithinCardCap` compare le montant à `1 000 EUR` converti (`RoundingMode.DOWN`) dans la devise du portefeuille, avant tout appel Stripe.
3. **Remboursement Stripe** : `issueStripeRefund` pose la métadonnée `wallet_refund_item_id` et la clé `wallet-self-refund-<itemId>`. Erreur réseau, 5xx ou 429 : l'item reste PENDING avec `failureReason = stripe-transient:<code>`, une alerte d'avertissement part au premier échec, la reprise planifiée (5 min) réémet. À la reprise, `Refund.list` sur le PaymentIntent retrouve un refund déjà créé et l'adopte.
4. **Rails mixtes** : `request` ouvre une seule demande `AUTOMATIC_MIXED` sur la part remboursable. `issuePendingItems` envoie les items `pawapay:<dépôt>` à `WalletRefundRailIssuer`, les autres à Stripe ; `reconcile` rapproche chaque item auprès de son rail (`isPawapayItem`). Le brut n'est débité qu'une fois tous les items terminaux.
5. **Recrédit de commission** : `refundCommissionToWallet` et `refundCommission` appellent `lockCommissionStatus` : `lockForUpdate` puis projection scalaire `findCommissionStatusById`, reportée sur l'entité. Le second déclencheur voit `REFUNDED` et s'arrête.

### Points d'entrée API
- `POST /api/v1/wallet/topup`, `POST /api/v1/wallet/topup/checkout-session` — 422 `wallet-topup-above-max` au-delà du plafond carte.
- `POST /api/v1/wallet/{currency}/refund-request` — demande `AUTOMATIC_MIXED` pour des rails mixtes (rail `MIXED` dans `GET /wallet/refund-requests`).
- Tous les endpoints : 400 `malformed-request` sur un décimal démesuré.

### Entités JPA impliquées
- `WalletRefundRequestEntity` → `wallet_refund_requests.channel` : nouvelle valeur `AUTOMATIC_MIXED` (CHECK V356, longueur 20 suffisante).
- `WalletRefundRequestItemEntity.failureReason` : `stripe-transient:…` sur un item PENDING, `charge-already-refunded` sur un item FAILED sans ticket.

### Logique métier critique
- Un ticket manuel n'est jamais ouvert pour une charge déjà remboursée hors de l'app : il ferait payer deux fois. L'alerte critique `wallet-refund-external` signale le solde à régulariser.
- La part non-cash (parrainage, crédit admin, code) n'entre jamais dans une demande automatique : seuls les items de l'allocateur sont demandés.
- `findById` sur une entité déjà chargée renvoie le cache de premier niveau : seul un `refresh` ou une projection scalaire relit la base. D'où la projection après le verrou.

### Events Spring publiés / écoutés
- Inchangés : `WalletRefundItemsCreatedEvent` (émission après commit), `HandoverExpiredEvent`, `VoyageurNoShowEvent`.

### Pièges et points d'attention
- La clé d'idempotence Stripe expire après 24 h : ne jamais retirer la recherche par métadonnée à la reprise.
- `NoShowScheduler.detectNoShows` ne doit pas redevenir `@Transactional` (test de garde).
- Le canal `AUTOMATIC_MIXED` doit exister dans l'admin (PR jumelle dony-admin) : sans libellé, la colonne Canal reste vide.

## Critères d'acceptation couverts
- [x] Un décimal démesuré est refusé avant tout calcul (JSON et paramètres) — `BoundedDecimalWiringIT`.
- [x] Une recharge carte au-delà de 1 000 € équivalent est refusée sans appel Stripe — `WalletTopupCurrencyTest`.
- [x] Une erreur Stripe transitoire ne ferme ni n'ouvre de ticket, et la reprise adopte le refund déjà créé — `WalletSelfRefundServiceTest`.
- [x] Rails mixtes : une demande automatique sur la seule part remboursable, chaque rail émis — `WalletRefundIT`, `WalletSelfRefundServiceTest`.
- [x] Deux déclencheurs concurrents ne recréditent la commission qu'une fois — `CashCommissionWalletSplitIT` (échoue sans le correctif : 2 lignes au lieu d'une).

## Tests
- Voir la description de la PR pour le résultat de `./mvnw test` et la couverture JaCoCo.
- Tests ajoutés ou modifiés : `BoundedDecimalsTest`, `BoundedDecimalWiringIT`, `WalletTopupCurrencyTest`, `WalletSelfRefundServiceTest`, `WalletRefundIT`, `WalletRefundIssueRecoverySchedulerTest`, `WalletRefundRequestSummaryResponseTest`, `UserServiceDeleteAccountTest`, `CashCommissionServiceTest`, `CashCommissionWalletSplitIT`, `NoShowServiceTest`, `NoShowSchedulerTest`.

## Décisions techniques
- **Borne globale plutôt que `@Digits` sur 39 DTO** : un oubli sur un futur DTO rouvrirait la faille ; le désérialiseur couvre tout.
- **Canal mixte plutôt que deux demandes enchaînées** : l'index unique n'autorise qu'une demande vivante par devise, et un seul débit à la clôture évite tout état intermédiaire.
- **Projection scalaire plutôt que `refresh`** : `CashCommissionService` n'a pas d'`EntityManager`, et la projection ne dépend pas de l'état géré de l'entité passée par l'appelant.
- Plafond carte de 1 000 € et comportement de chaque correctif : décisions du propriétaire du 28/10.
