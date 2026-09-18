# Story commission-portefeuille-devise-colis (Backend)

**Date :** 2026-09-18
**Status :** ✅ Complète

Spec : `docs-claude/docs/specs/2026-09-18-commission-portefeuille-devise-colis-design.md` (section 3). Plan : `docs-claude/docs/plans/2026-09-18-commission-portefeuille-devise-colis-back.md`.

## Résumé

La commission espèces d'un colis est désormais prélevée d'abord sur le portefeuille de la devise du colis (sans conversion), puis complétée sur le portefeuille de la devise active du voyageur (converti au taux courant), en tout ou rien. Un voyageur en euros avec 10 000 F CFA peut accepter un colis XOF sans « solde insuffisant ». Le détail du manque est exposé dans `AcceptBidResponse.breakdown`, et l'annulation recrédite chaque ligne dans sa devise.

## Critères d'acceptation

- **Étant donné** un voyageur actif EUR avec 10 000 XOF, un colis XOF de commission 1 050 XOF, **quand** il accepte, **alors** 1 050 XOF sont débités du portefeuille XOF, rien de l'euro, le bid passe `CHARGED` via `WALLET`.
- **Étant donné** 600 XOF et 1,33 € pour la même commission, **alors** deux lignes `COMMISSION_DEDUCTED` : 600 XOF et 0,69 € (450 XOF convertis, taux snapshoté), même transaction.
- **Étant donné** 600 XOF et 0,10 €, **alors** aucun débit, réponse `INSUFFICIENT_WALLET` avec `breakdown` (600 couverts, 450 XOF manquants ≈ 0,69 €, solde actif 0,10 €) en flux interactif, repli carte en flux automatique.
- **Étant donné** devise du colis = devise active, **alors** une seule ligne, `breakdown` absent, JSON identique à avant.
- **Étant donné** un bid accepté en deux lignes puis annulé, **alors** deux `REFUND` (XOF et EUR), soldes restaurés.

## Contrat `AcceptBidResponse` (INSUFFICIENT_WALLET)

Champs existants inchangés, toujours en devise active. Ajout additif, absent quand les devises sont identiques :

```json
"breakdown": {
  "bidCurrency": "XOF", "commission": 1050, "coveredByBidWallet": 600,
  "remainingBid": 450, "remainingInActive": 0.69, "activeCurrency": "EUR", "activeBalance": 1.33
}
```

## Fichiers créés

- `payments/cash/CommissionSplit.java` : record de répartition (parts, reste, taux, soldes, `covered`, `commissionInActive`).
- `payments/cash/WalletCommissionCollector.java` : `plan` (verrouille les deux portefeuilles en `PESSIMISTIC_WRITE`, ordre alphabétique des devises, `MANDATORY` ; portefeuille gelé compté pour 0 sans verrou) et `executeForBid` / `executeForNegotiation` (débits, clés `…` et `…_active`).
- `payments/cash/dto/CommissionShortfallDto.java`.
- Tests : `WalletCommissionCollectorTest`, `CashCommissionWalletSplitIT` (PostgreSQL embarqué : refus sans aucune ligne, acceptation en deux lignes, annulation recréditant les deux).

## Fichiers modifiés

- `payments/cash/CashCommissionService.java` : quatre chemins (`chargeCommissionFromWallet`, `chargeCommissionAuto`, `acceptCashBid` WALLET_FIRST, négociation WALLET_FIRST) passent par le collecteur ; `ExchangeRateService` retiré (plus d'usage) ; `refundCommissionToWallet` recrédite toutes les lignes (`findAllByUserIdAndBidIdAndType`), clé historique si une ligne, `clé-DEVISE` sinon.
- `payments/cash/dto/AcceptBidResponse.java` : `breakdown`.
- `payments/wallet/WalletService.java` : `getBalanceForUpdate` (lecture verrouillée, ne crée pas le portefeuille absent), `isFrozen`.
- `payments/wallet/WalletTransactionRepository.java` : `findAllByUserIdAndBidIdAndType`.
- Tests adaptés sans suppression : `CashCommissionServiceTest`, `CashCommissionServiceNegotiationTest`, `CashCommissionControllerTest`, `CashSenderVoucherConsumptionTest`, `WalletServiceTest`.

## Comment ça fonctionne

1. L'appelant (transactionnel) demande `plan(travelerId, deviseColis, deviseActive, commission)`.
2. `plan` verrouille les deux portefeuilles (`FOR UPDATE`, ordre stable), calcule `fromBidWallet = min(solde colis, commission)`, convertit le reste, décide `covered`.
3. Si `covered` : `execute*` débite la part colis puis le complément converti, dans la même transaction. Sinon aucun débit : exception (chemin direct), `insufficientWallet(... breakdown)` (interactif) ou repli carte (auto).
4. Garde d'idempotence par bid inchangée (`existsByUserIdAndBidIdAndType`).
5. Annulation : `refundCommissionToWallet` boucle sur toutes les lignes.

## Pièges

- `WalletService.debit` est `noRollbackFor(InsufficientWalletBalanceException)` et les appelants attrapent cette exception : sans le verrou pris dans `plan`, une course entre lecture et débit aurait laissé la ligne devise-du-colis commitée sans le complément. Le verrou est tenu jusqu'au commit de l'appelant ; ne jamais appeler `plan` hors transaction (`MANDATORY` le garantit).
- `getBalanceForUpdate` ne crée pas le portefeuille absent (course sur `UNIQUE(user_id, currency)` dans la transaction appelante) : absent = 0.
- H2 refuse `FOR NO KEY UPDATE` : les IT qui débitent tournent sur PostgreSQL embarqué (profil `e2e`).
- Un portefeuille actif gelé (remboursement en cours) donne désormais `INSUFFICIENT_WALLET` avec solde 0 au lieu d'un 422 explicite : choix à confirmer côté produit.
- `chargeCommissionAuto` n'a plus d'appelant en production depuis `26ca31e1` (listener mobile money retiré) : à supprimer dans une PR séparée (règle « pas de code mort »).

## Tests

- `./mvnw test` : 5905 tests, 0 échec, 7 ignorés préexistants.
- JaCoCo global : 91 %.
