# Story wallet-solde-unifié, lot 1 : commission prélevée sur tous les portefeuilles (Backend)

**Date :** 2026-10-28
**Status :** ✅ Complète (back). L'app (lot 2) vient après, en PR jumelle.

Spec et plan : dépôt `docs-claude`, `docs/specs/2026-10-27-wallet-solde-unifie-design.md` (sections 3, 4, 6) et `docs/plans/2026-10-27-wallet-solde-unifie-back.md` (lot 1). Le lot 0 (intégrité du solde) est la PR #668.

## Résumé

Un voyageur avec des euros, dont la devise active était le franc CFA, était refusé sur un colis en F CFA : la commission ne regardait que le portefeuille de la devise du colis et celui de la devise active. Elle se prélève maintenant sur TOUS les portefeuilles du voyageur : la devise du colis d'abord (sans conversion), puis les autres, du plus gros au plus petit en valeur, convertis au taux du jour. Un portefeuille qui ne règle qu'une partie est vidé exactement, le dernier est converti au reste, tout ou rien. Le remboursement, lui, repart toujours dans la devise d'origine : rien n'est converti tant qu'on ne dépense pas.

## Fichiers créés

- `payments/cash/CommissionLine.java` : un prélèvement sur UN portefeuille (montant débité, part de la commission réglée, taux appliqué).
- `payments/currency/ExchangeRateFreshness.java` : dit si un taux est trop ancien pour engager de l'argent, et lève l'alerte admin.
- `payments/cash/dto/CommissionFundingDetailDto.java` : détail par portefeuille d'un « solde insuffisant » (additif).
- `payments/cash/dto/CommissionAllocationPreviewDto.java` : réponse de l'aperçu.
- Tests : `FixedRateConversions` (aide de test), `ExchangeRateFreshnessTest`.

## Fichiers modifiés

- `payments/cash/WalletCommissionCollector.java` : `plan` (verrous), `allocate` (calcul pur), `preview` (lecture seule), `rateOfTheDay`, `execute*` qui bouclent sur les lignes.
- `payments/cash/CommissionSplit.java` : gagne `lines`, `availableInBid`, `wallets`, `excluded` ; les champs historiques et l'ancien constructeur sont conservés.
- `payments/cash/CashCommissionService.java` : passe la devise de complément choisie comme « préférée », expose `previewWalletAllocation`, audit enrichi de `lines` (les anciennes clés restent).
- `payments/cash/CashCommissionController.java` : `GET /bids/{bidId}/commission-allocation`.
- `payments/supplement/BidSupplementCashRail.java`, `payments/amendment/BidAmendmentCashRail.java` : même règle, via `preferredOrNull`.
- `payments/cash/CommissionUnfundedProblem.java`, `dto/AcceptBidResponse.java` : champ additif `fundingDetail`.
- `payments/currency/ExchangeRateService.java` : `convert(montant, de, vers, arrondi)`.
- `payments/wallet/WalletService.java` : `getBalanceForUpdate` relit la ligne après le verrou.
- `assistant/kb/{fr,en}/` : `portefeuille`, `commission`, `paiement-especes`, `devises`.

## Comment ça fonctionne (pour la maintenance)

### La règle de répartition (`WalletCommissionCollector.allocate`)

1. Portefeuille de la devise du colis : on prend `min(solde, commission)`, sans conversion.
2. Pour le reste `R`, chaque autre portefeuille à solde positif est évalué dans la devise du colis, ARRONDI VERS LE BAS (on ne surestime jamais ce qu'un solde peut couvrir). Les portefeuilles sont triés : la devise de complément choisie par le voyageur d'abord, puis par valeur décroissante, puis par code.
3. On parcourt : un portefeuille dont la valeur couvre ce qui reste devient le DERNIER (débit = reste converti, arrondi HALF_UP, jamais plus que son solde) ; sinon il est VIDÉ exactement et règle sa valeur arrondie. Un portefeuille qui ne vaut rien une fois arrondi est ignoré (aucune ligne à zéro).
4. Si le reste n'est pas entièrement servi : `covered = false`, aucune ligne, rien n'est débité (tout ou rien).

La devise ACTIVE n'a aucun rang : elle ne sert qu'aux champs historiques d'affichage. Changer la devise d'affichage ne change donc jamais d'où part l'argent (décision U1).

Exemple (spec 4.1 bis) : commission 4,00 €, aucun euro, 3,00 $ et 1 500 F CFA. Les dollars valent 2,77 €, le F CFA 2,28 € : dollars d'abord, vidés (−3,00 $ pour 2,77 €), puis le F CFA au reste (−807 F CFA pour 1,23 €). Total couvert : exactement 4,00 €.

### Verrous et exécution

- `plan` (MANDATORY) verrouille la devise du colis, la devise active et tout portefeuille à solde positif, dans l'ordre alphabétique des devises (jamais d'interblocage entre deux règlements croisés), puis relit chaque solde SOUS verrou (`getBalanceForUpdate` fait un `refresh`, sinon une entité en cache donnerait un solde périmé).
- Un portefeuille gelé par un remboursement en cours compte pour zéro et n'est pas verrouillé.
- `executeForBid`, `executeForNegotiation`, `executeForSupplement`, `executeForAmendment` bouclent sur `lines`. Chaque ligne convertie porte `sourceCurrency` = devise du colis, `sourceAmount` = part couverte et `appliedRate`.
- Clés d'idempotence : la devise du colis garde la clé historique, la devise ACTIVE garde son suffixe `_active` / `-active` (un paiement passé avant ce changement a écrit sa ligne sous cette clé, un rejeu ne doit pas la débiter deux fois), les autres portent leur code de devise.

### Contrat (additif)

- `AcceptBidResponse.fundingDetail` et la propriété `fundingDetail` du 422 `*-commission-unfunded` : commission, total disponible, manque, portefeuilles utilisables (solde et valeur), portefeuilles écartés avec leur motif. Un « solde insuffisant » signifie désormais que la SOMME de tous les portefeuilles utilisables ne couvre pas : `alternatives` est donc quasi toujours vide.
- `breakdown` et les champs historiques sont inchangés. Un ancien client continue de lire l'ancien contrat.
- `GET /bids/{bidId}/commission-allocation?fundingCurrency=` (rôle TRAVELER, propriétaire du trajet, colis en attente) : l'aperçu, en lecture seule, de ce que l'acceptation débitera.

### Taux : fraîcheur et affichage

- Un taux de devise flottante (USD, CAD, GBP, CHF) plus ancien que `yadony.exchange-rates.max-age-hours` (48 h) écarte son portefeuille de la dépense (`rate-stale`) et lève UNE alerte admin non résolue par devise (`EXCHANGE_RATE_STALE_<CODE>`, dans sa propre transaction, jamais bloquante). Euro, F CFA (XOF, XAF) ne périment jamais. Un taux absent écarte aussi le portefeuille (`rate-missing`).
- `appliedRate` est recalculé sur les montants ARRONDIS (656,098 au lieu de 655,957 pour 807 F CFA / 1,23 €) : c'est le taux réellement appliqué, figé pour l'audit. L'aperçu expose en plus `rateOfTheDay` (le taux de la table) : c'est celui-là qu'on montre au voyageur.

## Pièges et points d'attention

- Le bon de réduction du voyageur est consommé au prélèvement, pas au devis : l'aperçu ignore donc le bon et ne sous-estime jamais ce qui sera prélevé.
- `WalletCommissionCollector` n'a qu'UN constructeur (trois dépendances) : deux constructeurs sans `@Autowired` cassent Spring.
- INV-09 (une ligne `COMMISSION_DEDUCTED` par colis et par devise) : chaque portefeuille n'est débité qu'une fois, y compris quand il est à la fois devise du colis et devise active. INV-17 : toute ligne convertie doit porter `sourceCurrency` = devise du colis.
- Le recrédit à l'annulation boucle déjà sur toutes les lignes (une clé par devise) : aucune modification, testé avec quatre devises.
- Audit : celui du colis et du supplément porte `lines` (devise, débit, part couverte). Celui de la MODIFICATION de colis ne porte jamais de montant (ils vivent dans le grand livre, `BidAmendmentCashRailIT` le vérifie) : il ne garde que `walletCurrencies`. Un test de CI l'a rappelé ; ne pas y remettre `lines`.
- H2 ne connaît pas `FOR NO KEY UPDATE` : tout test de la répartition qui verrouille tourne sur Postgres embarqué (profil `e2e`).

## Critères d'acceptation couverts

- [x] Euros seulement, devise active F CFA, colis en F CFA : accepté (avant : refusé).
- [x] Plusieurs portefeuilles partiels : vidés exactement, le dernier converti au reste ; total couvert = commission.
- [x] La somme ne couvre pas : rien n'est débité, le détail dit pourquoi.
- [x] Portefeuille gelé, taux périmé, taux absent : écartés avec leur motif.
- [x] Annulation du colis : chaque devise est recréditée à l'identique.
- [x] Remboursement : inchangé (devise d'origine).
- [x] L'aperçu égale exactement ce que l'acceptation débite ensuite.
- [x] Règles du moniteur d'intégrité INV-01, 02, 03, 09, 10, 17 : zéro anomalie après les répartitions.

## Tests

- Périmètre commission (354 tests, dont `CashCommissionWalletSplitIT` sur Postgres embarqué) : tout vert. La suite complète est rejouée par la CI de la PR.
- Couverture JaCoCo de ce périmètre : `WalletCommissionCollector` 97,6 % (branches 91,8 %), `CashCommissionService` 95,3 %, `CashCommissionController` 95,2 %, `BidSupplementCashRail` 99,6 %, `BidAmendmentCashRail` 90,5 %, `CommissionSplit` 94,9 % (lignes 100 %), `ExchangeRateFreshness` 96,4 %, `ExchangeRateService` 98,0 %, `WalletService` 93,6 %.
- Tests ajoutés ou modifiés : `WalletCommissionCollectorTest` (réécrit sur un vrai `ExchangeRateService` à taux fixes), `CashCommissionWalletSplitIT` (8 + 3 : quatre portefeuilles, recrédit, taux périmé, insuffisance globale, devise choisie, aperçu), `ExchangeRateFreshnessTest`, `ExchangeRateServiceTest`, `WalletServiceTest`, `CashCommissionControllerTest`, `CashCommissionServiceTest` (deux tests réécrits : l'ancien décrivait « alternative proposée », le portefeuille règle maintenant la commission), `BidSupplementCashRailTest`, `BidAmendmentCashRailTest`.

## Décisions techniques

- **Pas de rang pour la devise active.** Un rang spécial aurait fait dépendre d'où part l'argent de l'affichage choisi. Écarté.
- **Capacités arrondies vers le bas, dernier portefeuille arrondi HALF_UP.** Garantit qu'on ne surestime jamais un solde, et que le débit du dernier portefeuille ne dépasse jamais son solde.
- **Calcul pur séparé du verrouillage (`allocate`).** Le même code sert au prélèvement (soldes verrouillés) et à l'aperçu (soldes lus sans verrou).
- **Aperçu par un `GET` dédié.** La réponse d'acceptation ne sort qu'après le prélèvement : elle ne peut pas servir d'aperçu. Un endpoint de lecture évite de changer son contrat.
- **Alerte par devise, pas par jour.** `raiseOnce` ne lève qu'une alerte non résolue par type : équivalent, plus simple.
