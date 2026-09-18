# Story wallet-multidevise-total-estime (Backend)

**Date :** 2026-09-18
**Status :** ✅ Complète

Spec : `docs-claude/docs/specs/2026-09-18-wallet-multidevise-total-estime-design.md` (section 3). Plan : `docs-claude/docs/plans/2026-09-18-wallet-multidevise-total-estime-back.md`.

## Contexte

Un utilisateur dont la devise active est l'euro recharge 10 000 XOF par mobile money. Chaque devise garde son portefeuille (aucun change réel, décision D1 du chantier « recharge mobile money »), mais l'écran ne donnait aucune vue d'ensemble : le solde XOF apparaissait « verrouillé » sous le solde EUR. L'app veut afficher un total estimé de tous les portefeuilles dans la devise active, avec l'équivalent estimé de chaque devise. Ce lot expose ce calcul côté back, de façon strictement additive.

## Critères d'acceptation

- **Étant donné** un utilisateur avec 1,33 EUR et 10 000 XOF, devise active EUR, **quand** il appelle `GET /wallet/balance`, **alors** la réponse porte `estimatedTotal = 16.57`, `estimateComplete = true`, et chaque `balances[]` porte `estimatedInActive` (1,33 pour EUR, 15,24 pour XOF).
- **Étant donné** une devise détenue sans ligne dans `exchange_rates`, **quand** il appelle `GET /wallet/balance`, **alors** la réponse est 200, la devise est exclue du total, `estimateComplete = false` et son `estimatedInActive` est absent du JSON.
- **Étant donné** un utilisateur qui ne détient que sa devise active, **alors** `estimatedTotal` vaut son solde et `estimatedInActive` aussi.
- **Étant donné** un ancien client, **alors** il ignore les nouveaux champs (aucun champ existant n'a changé).

## Contrat `GET /wallet/balance`

Avant :

```json
{ "balance": 1.33, "currency": "EUR", "balances": [ { "currency": "XOF", "balance": 10000, "active": false } ] }
```

Après :

```json
{
  "balance": 1.33,
  "currency": "EUR",
  "estimatedTotal": 16.57,
  "estimateComplete": true,
  "balances": [
    { "currency": "EUR", "balance": 1.33, "active": true, "estimatedInActive": 1.33 },
    { "currency": "XOF", "balance": 10000, "active": false, "estimatedInActive": 15.24 }
  ]
}
```

`estimatedTotal` et `estimatedInActive` sont nuls (donc absents, Jackson `NON_NULL`) quand aucun taux n'est disponible.

## Fichiers créés

- `payments/wallet/WalletEstimate.java` : record `(inActiveByCurrency, total, complete)`.
- `payments/wallet/WalletEstimateService.java` : convertit chaque portefeuille dans la devise active via `ExchangeRateService.convert` ; une devise sans taux (`exchange-rate-missing`) est exclue et signalée, toute autre exception métier est relancée ; total arrondi aux décimales de la devise active.
- Tests : `WalletEstimateServiceTest` (7 cas unitaires).

## Fichiers modifiés

- `payments/wallet/dto/WalletBalanceResponse.java` : `estimatedTotal`, `estimateComplete`.
- `payments/wallet/dto/WalletCurrencyBalanceDto.java` : `estimatedInActive` (9e composant).
- `payments/wallet/WalletController.java` : injecte `WalletEstimateService`, calcule l'estimation une fois à partir des portefeuilles déjà chargés.
- Tests : `WalletControllerIT` (3 scénarios : total multi-devises, taux manquant avec restauration du taux en `finally`, devise active seule).

## Notes de maintenance

- Le taux vient de la table `exchange_rates`. XOF/XAF y sont fixes (655,957), les devises flottantes sont synchronisées par `ExchangeRateSyncService`. Attention : `ExchangeRateService.convert` appelle `rateOf` en interne, donc le cache Caffeine `exchange-rates` (proxy Spring) n'est PAS exercé sur ce chemin : chaque devise non active coûte une lecture par clé primaire sur une table de 7 lignes (préexistant, partagé avec le feed d'annonces ; à corriger un jour en sortant `rateOf` dans un bean dédié).
- Aucune migration, aucun événement, aucun changement de frais ni de remboursement.
- Suite : 5870 tests verts, couverture 91 %.
