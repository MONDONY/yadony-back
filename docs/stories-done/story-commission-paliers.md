# Story commission-paliers — Commission Yadony par grille marginale datée, minimum par devise (Backend)

**Date :** 2026-10-19
**Status :** ✅ Complète (code et documentation) ; suite complète et couverture : voir la PR (lancées par le contrôleur)

Spec : `docs/superpowers/specs/2026-10-18-commission-paliers-design.md`. Plan : `docs/superpowers/plans/2026-10-18-commission-paliers-back.md`.

## Résumé

La commission n'est plus un taux unique appliqué différemment selon le moyen de paiement : une seule formule, `CommissionCalculator`, sert la carte, les espèces (bid et fil de demande), le mobile money et tous les devis. Elle repose sur une grille marginale datée (tranches de taux en EUR) et un minimum par devise, que le propriétaire programmera depuis l'onglet admin « Tarification » (PR jumelle). Chaque offre, demande ou fil fige l'identifiant de sa grille à sa création, donc un changement de grille ne modifie jamais un prix déjà montré. Tant qu'aucune grille n'est programmée, le back calcule exactement comme avant : le déploiement ne change aucun prix.

## Fichiers créés

- `src/main/resources/db/migration/V332__commission_grids.sql` : table `commission_grids` (tranches et minimums en `jsonb`, suppression logique), trigger d'immuabilité, colonnes `commission_grid_id` sur `bids`, `package_requests`, `negotiation_threads`, colonnes `commission_override_rate` et `commission_voucher_factor` sur `bids`, élargissement des taux figés en `NUMERIC(7,6)`.
- `src/main/resources/db/migration/V333__negotiation_thread_sealed_gross.sql` : `negotiation_threads.sealed_gross_eur` (total payé figé au scellement d'un fil à grille) et son CHECK `> 0`.
- `common/commission/CommissionCalculator.java` : source unique du calcul (`compute`) et du calcul inverse (`netForGross`).
- `common/commission/CommissionGrid.java`, `CommissionTier.java`, `CommissionGridDraft.java`, `CommissionGridWarning.java`, `CommissionGridHistoryEntry.java` : modèle de grille (valeurs immuables), brouillon, avertissement de hausse, entrée d'historique.
- `common/commission/CommissionGridEntity.java`, `CommissionGridRepository.java` : persistance de `commission_grids`.
- `common/commission/CommissionGridService.java` : lecture (en vigueur, programmée, par id), programmation validée, annulation, historique, avertissement de hausse, cache, audit.
- `common/commission/CommissionGridWriteLock.java` : verrou consultatif PostgreSQL (`pg_advisory_xact_lock`) qui sérialise programmations et annulations ; sans effet sous H2.
- `common/commission/CommissionReductions.java`, `CommissionResult.java`, `CommissionPromoLabel.java` : réductions personnelles (taux spécial, remise promo, facteur du bon), résultat de calcul (net, commission, brut, taux effectif, `minimumApplied`, `promoEffective`, `storableRate`), libellé de promo.
- `common/commission/ThreadCommissionPricing.java` : devis unique de la commission d'un fil de demande à grille (montant annoncé = montant prélevé ou déposé).
- `config/dto/CommissionGridResponse.java` : réponse publique `{current, next}`.
- `admin/AdminCommissionGridController.java` et DTO `admin/dto/CommissionGrid{Admin,List}Response`, `CommissionGridRequest`, `CommissionSimulation{Request,Response}` : API admin.
- `admin/commission/CommissionSimulator.java`, `ProviderCostProperties.java` : simulateur de marge par rail (coûts prestataires en propriétés `yadony.costs.*`, jamais utilisés pour un prix).
- Tests : `CommissionCalculatorTest`, `CommissionVectorsTest` (vecteurs `src/test/resources/commission/vectors.json`), `CommissionResultTest`, `CommissionGridServiceTest`, `CommissionGridServiceIT`, `CommissionGridRepositoryIT`, `CommissionRatePrecisionPersistenceTest`, `ThreadCommissionPricingTest`, `ConfigControllerCommissionGridIT`, `AdminCommissionGridControllerIT`, `CommissionSimulatorTest`, `V332CommissionGridsMigrationTest`, `V333NegotiationThreadSealedGrossMigrationTest`, `PaymentServiceCommissionGridTest`, `CashCommissionServiceGridTest`, `MobileMoneyNegotiationPaymentServiceGridTest`, `BidNegotiationServiceGridTest`, `BidQuoteServiceGridTest`, `NegotiationServiceGridTest`, `PackageRequestServiceGridTest`.

## Fichiers modifiés

- `common/CommissionRateResolver.java` : ajout de `reductions(...)` (réductions séparées du taux), `reductionsWithPromoFallback(...)` (repli commun des trois rails quand le code promo est devenu invalide) et `reductionsForBid(...)` (règle unique pour un bid : code déjà racheté non revalidé), et de leurs variantes d'affichage `displayReductionsWithPromoFallback(...)` / `displayReductionsForBid(...)` (mêmes réductions, repli journalisé en DEBUG). `resolve(...)` est inchangé (chemin hérité).
- `common/commission/ThreadCommissionPricing.java` : `displayPrice(...)`, même devis que `price(...)` pour un affichage (repli en DEBUG).
- `payments/PaymentService.java` : commission carte par grille figée, reprise d'un paiement existant par les montants enregistrés ; `createEscrow` refuse (409 `payment-already-completed`) un bid matérialisé depuis un fil ; `gridCardAttempt(...)` expose à l'affichage ce que débitera une nouvelle tentative carte.
- `payments/NegotiationEscrowAdapter.java`, `requests/NegotiationEscrowPort.java` : `paidAmount(threadId)`, montant du paiement vivant du fil (carte ou mobile money), lu au scellement.
- `matching/BidRepository.java` (`findNegotiationsForUser`, `findArchivedNegotiationsForUser`) et `BidNegotiationService.loadNegotiation` : un bid matérialisé depuis un fil (`linked_negotiation_thread_id`) n'est jamais une discussion de prix, même s'il porte un brut.
- `payments/cash/CashCommissionService.java` : commission espèces par grille (bid et fil), rejet des bases `<= 0` sous grille.
- `payments/mobilemoney/MobileMoneyBidPricing.java`, `MobileMoneyBidPaymentService.java`, `MobileMoneyNegotiationPaymentService.java`, `NegotiationMobileMoneyAdapter.java`, `requests/NegotiationMobileMoneyPort.java` : commission mobile money par grille, minimum compris.
- `matching/BidService.java`, `BidCheckoutService.java`, `BidEntity.java`, `BidNegotiationService.java`, `PriceGridService.java`, `ThreadAcceptedBidListener.java`, DTO `BidQuoteResponse` et `BidNegotiationResponse` : grille figée à la création du bid, devis et négociation de trajet, prix au kilo affiché au net voyageur.
- `requests/service/PackageRequestService.java`, `NegotiationService.java`, `PackageRequestSearchMapper.java`, `entity/PackageRequestEntity.java`, `entity/NegotiationThreadEntity.java`, `event/PackageRequestAcceptedEvent.java`, `controller/NegotiationController.java`, `controller/PackageRequestController.java`, `PublicPackageRequestPageController.java`, `dto/NegotiationQuoteResponse.java`, `notifications/RequestEventsListener.java` : demandes, fils, devis et notification d'acceptation selon la grille.
- `promo/PromoService.java` (lecture seule `discountOfRedeemed`), `promo/PromoRedemptionEntity.java` (précision du taux).
- `config/ConfigController.java` (`GET /config/commission-grid`, `GET /config/commission-grid/{id}`, `GET /config/commission-rate` repris), `config/CacheConfig.java` (cache `commission-grids`), `application.yml` (coûts prestataires du simulateur).
- `assistant/kb/{fr,en}/commission.md` : fiches de l'assistant (voir « Textes de l'assistant »).

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble du flux

1. **Programmation (admin).** `POST /admin/commission-grids` valide la grille (422 par champ), calcule les avertissements de hausse AVANT l'écriture, refuse si un avertissement n'est pas acquitté, enregistre la grille, journalise `COMMISSION_GRID_SCHEDULED`, annule (suppression logique) la grille déjà programmée, vide le cache après le commit.
2. **Création d'un objet qui porte un prix** (bid, demande de colis, fil). Le service lit la grille en vigueur à cet instant (`CommissionGridService.current()`) et fige son id dans `commission_grid_id`. Un fil hérite de la grille de sa demande, une négociation de bid de celle du bid. Aucune grille en vigueur : `commission_grid_id` reste NULL.
3. **Devis et affichage.** Les services appellent `CommissionCalculator.compute(net, devise, grille figée, réductions)`. Les réductions viennent de `CommissionRateResolver` (taux spécial, remise promo, facteur du bon).
4. **Paiement.** Carte, espèces et mobile money rappellent le même calculateur avec la grille figée et les mêmes réductions : le montant affiché est le montant débité, quel que soit le moyen de paiement.
5. **Fil de demande scellé.** Au scellement, le total payé est écrit dans `negotiation_threads.sealed_gross_eur` et n'est plus jamais recalculé à l'affichage.

### Ordre de calcul (`CommissionCalculator.compute`)

1. Les bornes de tranche sont saisies en EUR et converties dans la devise du net par `SupportedCurrency.unitsPerEur()`, sans arrondi.
2. Commission de grille marginale : `Σ taux_i × max(0, min(N, borne_sup_i) − borne_inf_i)`.
3. Taux spécial : `min(commission de grille, N × taux spécial)` (le plus bas du voyageur et de l'expéditeur).
4. Code promo : appliqué au taux effectif `C / N` (6 décimales) par `PromoDiscount.applyTo`, plancher 0.
5. Minimum de la devise, SAUF si le taux après promo vaut 0 (promo « commission offerte », taux spécial à 0) : la commission reste alors 0.
6. Bon de parrainage : multiplié par le facteur APRÈS le minimum (1,00 € devient 0,50 €).
7. Un seul arrondi HALF_UP à `minorUnit()` de la devise (2 décimales en EUR, 0 en XOF et XAF).

Minimums par défaut : EUR 1.00, XOF 650, XAF 650, USD 1.10, CAD 1.50, GBP 0.90, CHF 0.95 (`CommissionGridService.defaultMinimums()`, valeurs pré-remplies que l'admin peut corriger).

`netForGross(brut, devise, grille, réductions)` donne le plus grand net `N` tel que `N + C(N) <= brut`, par dichotomie sur les unités mineures. Elle refuse un code promo (la fonction n'est plus monotone avec une promo en points). Si même la plus petite unité de net coûte plus que le brut : 422 `budget-below-commission-minimum`.

### CommissionGridService

- **Grilles datées.** La grille en vigueur à l'instant t est la plus récente avec `effective_from <= t` et non annulée. `current()`, `scheduled()`, `activeAt(t)`, `require(id)`, `history(Pageable)`.
- **Validation (422).** Première borne 0, bornes strictement croissantes, 1 à 10 tranches, taux entre 0 et 30 %, un minimum par devise supportée, `>= 0` et plafonné à l'équivalent de 5 € (garde-fou de saisie), note de 500 caractères au plus.
- **Date d'effet.** Nulle ou comprise entre maintenant moins 5 minutes et maintenant : ramenée à maintenant (effet immédiat possible, pas d'échec sur la latence de saisie). Plus ancienne que 5 minutes : 422 `commission-grid-effective-in-past`.
- **Avertissement de hausse.** `INCREASE_WITHIN_30_DAYS` quand la grille prend effet à moins de 30 jours et augmente la commission pour au moins un net de 1 € à 1 000 € (pas de 1 €) ou un minimum d'au moins une devise. Jamais un blocage : 422 `commission-grid-warning-not-acknowledged` tant que l'avertissement n'est pas acquitté dans le payload. Sans grille en vigueur, la référence est `flatPlatformGrid()` : le taux plat `commission_rate` sur toutes les tranches, minimum 0 dans toutes les devises (comportement carte actuel).
- **Au plus une grille programmée.** Programmer remplace la précédente (annulée avec `reason: REPLACED`). Une grille programmée à effet immédiat annule donc la programmée précédente.
- **Annulation.** `DELETE` d'une grille programmée : suppression logique et `COMMISSION_GRID_CANCELLED`. Grille déjà en vigueur : 409 `commission-grid-already-effective`, y compris quand elle le devient entre le contrôle et l'écriture (l'annulation écrit tout de suite et traduit le refus du trigger).
- **Verrou.** `schedule` et `cancel` prennent d'abord `CommissionGridWriteLock` (`pg_advisory_xact_lock`, relâché au commit) : la seconde de deux programmations simultanées attend la première, puis la voit et la remplace. Sans lui, chacune lisait « aucune grille programmée » et deux restaient programmées.
- **Cache.** Cache Caffeine `commission-grids` (TTL 30 s) qui contient TOUTES les grilles non annulées ; `activeAt` filtre sur l'horloge à chaque appel, donc une grille prend effet à l'instant dit sans attendre l'expiration. Chaque écriture vide le cache APRÈS le commit.
- **Audit.** `audit_log` entité `COMMISSION_GRID` : `COMMISSION_GRID_SCHEDULED` (tranches, minimums, date d'effet, avertissements acquittés, note) et `COMMISSION_GRID_CANCELLED`.

### ThreadCommissionPricing

Devis unique de la commission d'un fil de demande à grille : sert le montant annoncé (événement de commission en attente, contrôle du solde du voyageur, devis), le montant prélevé au règlement espèces et le montant déposé en mobile money. `price(...)` (avec ou sans code promo du fil, repli si invalide), `publicGross(...)` (brut public sans aucune réduction personnelle, montré au voyageur), `minimum(...)`. Lecture pure : ni le bon ni le code ne sont consommés.

### CommissionRateResolver : réductions

- `reductions(travelerId, senderId, promoCode, promoUserId, reference)` : taux spécial (le plus bas des deux), remise du code validé, facteur du bon (lecture seule). Aucune réduction : `CommissionReductions.NONE`.
- `reductionsWithPromoFallback(...)` : sans code, réductions sans code ; code devenu invalide, repli sur les réductions sans code au lieu de faire échouer le paiement. Règle commune aux trois rails.
- `reductionsForBid(travelerId, senderId, promoCode, promoCodeId, bidId)` : règle unique d'un bid. Si le code est déjà racheté ou verrouillé (`promoCodeId`), la remise est lue sans revalidation (`PromoService.discountOfRedeemed`), sinon `reductionsWithPromoFallback`. Utilisée par l'affichage (`BidService`), la carte, les espèces et le mobile money.
- Variantes `display…` : mêmes réductions, repli d'un code invalide journalisé en DEBUG au lieu de WARN. Réservées aux affichages (réponse d'un bid, brut et notifications d'un fil), où le repli se répétait à chaque rendu de liste ; les prélèvements gardent le WARN, les devis valident strictement (422).

### Rails

- **Carte** (`PaymentService`) : devis sur la grille figée du bid, taux effectif persisté, montants enregistrés sur le paiement. Une nouvelle tentative avec un paiement existant réutilise les montants déjà enregistrés au lieu de rejouer un taux. Reprise avec un net changé : nouveau devis sur la grille figée, remise du code déjà racheté honorée sans revalidation, ni rachat ni consommation. Un bid matérialisé depuis un fil est payé sur le fil : `createEscrow` le refuse.
- **Espèces** (`CashCommissionService`) : bid et fil à grille passent par le calculateur (le minimum espèces propre à l'ancien chemin disparaît pour eux). Base `<= 0` sous grille : 422 `invalid-amount`. Le rail espèces d'un fil reste sans code promo (comportement hérité).
- **Mobile money** (`MobileMoneyBidPricing`, `MobileMoneyNegotiationPaymentService`) : le « aucun plancher » disparaît pour les objets à grille. Le dépôt d'un fil prend aussi le code promo du fil (repli si invalide). Les francs entiers XOF/XAF sont respectés.
- **Code promo sans effet.** Quand le minimum annule tout le gain (`commission avec promo == commission sans promo`), `promoApplied=false`, `promoIneffectiveReason="COMMISSION_MINIMUM"`, et aucun `redeem`, `lockForRedemption` ni `recordGrantedRedemption`.

### Devis et affichages

- `BidQuoteResponse`, `NegotiationQuoteResponse`, `BidNegotiationResponse` : champs AJOUTÉS (`minimumApplied`, `minimumAmount`, `commissionGridId`, `promoIneffectiveReason`, réductions figées du fil pour le voyageur) ; aucun champ existant retiré ni renommé.
- Le devis d'un bid valide le code promo strictement (422 si invalide), contrairement aux rails qui retombent sur les réductions sans code.
- `/package-requests/quote` gagne un paramètre facultatif `currency` (défaut : la devise que retiendrait la création). Avec grille et promo, le devis de budget calcule `net = netForGross(budget, NONE)`, `commission = compute(net, promo)`, `total = net + commission` : l'expéditeur paie moins que son budget.
- Prix au kilo des listes (`pricePerKgDisplay`, `unitPriceDisplay`, `minGridUnitPriceDisplay`) : sous grille, prix du voyageur au net, sans frais. Les lignes de grille d'un bid négocié affichent le prix unitaire voyageur ; le minimum ne s'applique qu'au total.
- Le voyageur qui regarde un fil voit le brut public (`publicGross`, sans taux spécial, bon ni code de l'expéditeur) ; l'expéditeur voit son brut réel.
- « Mes envois » d'un bid matérialisé depuis un fil à grille payé par carte ou mobile money : le brut scellé du fil (`senderGross` de l'événement, posé en `negotiated_gross_eur`), réductions comprises. En espèces : net + commission publique de la grille, comme l'ancien `net × (1 + taux)`.
- Bid carte à grille ayant déjà un paiement : le total annoncé est ce que débitera la reprise (`PaymentService.gridCardAttempt`) : montants enregistrés s'ils correspondent au net, sinon grille figée sans un code jamais racheté.

### Points d'entrée API

- `GET /config/commission-grid` (public, comme `/config/**`) : `{current: {gridId, effectiveFrom, tiers[{fromEur, rate}], minimums{EUR, XOF, …}} | null, next: … | null}`. Les deux clés sont toujours présentes, même à `null`.
- `GET /config/commission-grid/{id}` (public) : la `GridView` d'une grille par identifiant (celle figée sur un bid, un fil ou une demande), en vigueur, passée ou programmée ; 404 RFC 7807 `commission-grid-not-found` si inconnue ou annulée.
- `GET /config/commission-rate` (public, conservé pour les anciennes versions de l'app) : taux de la première tranche de la grille en vigueur, sinon `commission_rate`. Forme de réponse inchangée.
- `GET /admin/commission-grids?page=&size=` : grille en vigueur, grille programmée, historique paginé (grilles annulées comprises).
- `POST /admin/commission-grids` : programmer une grille (201), réponse avec `warnings[]`. Champ `acknowledged` pour acquitter les avertissements.
- `DELETE /admin/commission-grids/{id}` : annuler une grille programmée (204 ; 404 inconnue ; 409 déjà en vigueur).
- `POST /admin/commission-grids/simulate` : commission actuelle et brouillon, total payé, coût prestataire et marge HT par rail.
- Toutes les routes admin : `@PreAuthorize("hasRole('ADMIN') and hasAuthority('CONFIG_MANAGE')")`, redéclaré sur chaque méthode.

### Entités JPA impliquées

- `CommissionGridEntity` → table `commission_grids` : `effective_from` (UTC, sans fuseau), `tiers` et `minimums` en `jsonb`, `note`, `created_by` (sans clé étrangère), `deleted_at`.
- `BidEntity` → `bids` : `commission_grid_id`, `commission_override_rate`, `commission_voucher_factor`, `commission_rate` en `NUMERIC(7,6)`.
- `PackageRequestEntity` → `package_requests.commission_grid_id`.
- `NegotiationThreadEntity` → `negotiation_threads` : `commission_grid_id`, `commission_rate` en `NUMERIC(7,6)`, `sealed_gross_eur`.
- `PromoRedemptionEntity` → `promo_redemptions.applied_rate` en `NUMERIC(7,6)`.

### Chemin hérité (`commission_grid_id` NULL)

Tout objet sans grille (créé avant la première grille, ou tant qu'aucune n'est programmée) garde STRICTEMENT l'ancien calcul : taux figé, minimum espèces actuel, branche `if` conservée dans chaque service. Il n'y a aucune migration de données. Le chemin hérité est à supprimer 90 jours après l'activation de la première grille, une fois les objets antérieurs clos.

### Events Spring publiés / écoutés

- `PackageRequestAcceptedEvent` gagne `commissionGridId` et `senderGross` (calculé au scellement pour un fil à grille payé par carte ou mobile money ; en espèces, la notification reste au net) ; `RequestEventsListener` les consomme sans recalculer.
- Aucun nouvel événement : la commission reste calculée dans les services de chaque package, le partage passe par `common/commission/`.

### Textes de l'assistant

Fiches `assistant/kb/{fr,en}/commission.md` (même `id`, mêmes routes) : les frais de service dépendent du prix du transport, un montant minimum peut s'appliquer selon le prix (sans montant cité, la fiche doit être vraie avant toute grille), le montant exact minimum compris est affiché avant de confirmer, aucun pourcentage en dur et aucune affirmation sur le moyen de paiement (Ruling 27). Le paragraphe espèces est conservé tel quel. Les autres fiches (`kilos-prix`, `ecran-grille-prix`, `gp-pro`, `paiement-especes`, etc.) ne citent aucun taux ni ne nient le minimum : inchangées. Les fiches sont lues au démarrage depuis le jar, donc effectives après déploiement du back.

## Pièges et points d'attention

- **CHECK `[0, 1)` sur les taux figés (V118).** Avec le minimum, le taux effectif peut dépasser 1 (net 0,50 € : commission 1,00 €, taux 2,0). Les colonnes `bids.commission_rate`, `negotiation_threads.commission_rate`, `promo_redemptions.applied_rate` reçoivent `CommissionResult.storableRate()` = `min(taux effectif, 0.999999)`, 6 décimales. Pour un objet à grille la valeur n'est qu'informative : la commission vient de la grille.
- **H2 ne relit pas le `jsonb`.** Sous le profil test (H2), les colonnes `tiers` et `minimums` ne se relisent pas : les tests des rails mockent `CommissionGridService`, et toute IT qui persiste une grille utilise le profil e2e (PostgreSQL embarqué), modèle `ConfigControllerCommissionGridIT` (purge avec trigger désactivé, vidage du cache).
- **Trigger d'immuabilité.** `trg_commission_grids_immutable` interdit tout UPDATE et DELETE d'une grille dont `effective_from <= now() UTC`, y compris son annulation logique. Le service n'écrit jamais `effective_from` d'une grille existante : seul `deleted_at` d'une grille programmée change. Une grille qui devient effective entre le test applicatif et l'écriture fait lever l'exception du trigger : `cancel` la traduit en 409 `commission-grid-already-effective`. Attention en test : avec `hibernate.jdbc.time_zone: UTC`, une date écrite en SQL brut est relue décalée du fuseau de la JVM (quelques heures).
- **Pageable sans tri pour l'historique.** `history(Pageable)` s'appuie sur une requête native déjà triée : ne jamais lui passer un `Pageable` trié (le contrôleur construit un `PageRequest` sans tri).
- **Précision des colonnes en test.** Le profil test génère le schéma depuis les entités : `@Column(precision = 7, scale = 6)` sur les trois taux et `CommissionRatePrecisionPersistenceTest` empêchent qu'une troncature passe inaperçue.
- **Migration V332 à poser avec soin au déploiement.** `ALTER COLUMN … TYPE NUMERIC(7,6)` sur `bids` et `negotiation_threads` réécrit les tables sous verrou `ACCESS EXCLUSIVE` : à déployer hors pointe. Vérifier aussi l'absence de collision de version Flyway avec les autres PR avant fusion (renumérotées V332/V333 le 19/10 après la fusion de #590, qui a pris V331).
- **Aucune grille n'est insérée par migration.** Le propriétaire programme la grille 7/6/5 dans l'admin avec une date d'effet ; tant qu'il ne l'a pas fait, aucun prix ne change.
- **Anciennes versions de l'app.** Elles calculent `net × (1 + taux)` en local : monter `min_supported_build` avant la date d'effet de la première grille.
- **Exceptions de calcul.** `CommissionCalculator` lève `IllegalArgumentException` pour un net `<= 0` ou une promo passée à `netForGross` : les appelants filtrent ou convertissent en `YadonyBusinessException` avant l'appel.
- **Ordre des PR.** Le back est fusionné et déployé avant les clients (admin, app, pro, web) ; les clients tolèrent `current: null` et l'ancien contrat.

## Critères d'acceptation couverts

- [x] Une seule formule de commission pour carte, espèces, espèces négociées et mobile money : `CommissionCalculator`, appelé par tous les rails.
- [x] Grille marginale, minimum par devise, vecteurs de référence du §4.6 : `CommissionVectorsTest` sur `vectors.json`.
- [x] Grille et minimums réglables sans redéploiement, versions datées, conservées et tracées dans `audit_log` : `CommissionGridService`, `AdminCommissionGridController`.
- [x] Hausse à moins de 30 jours : avertissement à acquitter, jamais un blocage.
- [x] Une offre, une demande ou un fil déjà créé ne change pas de prix avec une nouvelle grille : `commission_grid_id` figé à la création.
- [x] Le montant affiché avant validation est exactement le montant prélevé : calcul partagé, montants enregistrés réutilisés à la reprise, fil scellé jamais re-tarifé.
- [x] La commission ne dépend pas du moyen de paiement : même grille, mêmes réductions pour les trois rails.
- [x] Aucun prix ne change au déploiement : chemin hérité strictement conservé quand `commission_grid_id` est NULL, aucune grille insérée par migration.
- [x] Minimum annoncé dans les devis (`minimumApplied`, `minimumAmount`, `commissionGridId`) et `GET /config/commission-grid`.
- [x] Promo « commission offerte » : 0 € ; promo sans effet à cause du minimum : non consommée et signalée.
- [x] Textes de l'assistant alignés (fr et en), sans pourcentage en dur.

## Tests

- Suite complète et couverture : voir la PR (lancées par le contrôleur).
- Tests de chargement de la base de fiches relancés pour cette tâche : `./mvnw -q test -Dtest='KnowledgeBase*Test,AssistantServiceTest'` → `KnowledgeBaseFichesTest` 14, `KnowledgeBaseLoaderTest` 45, `KnowledgeBaseSearchTest` 19, `AssistantServiceTest` 45, 0 échec.
- Tests ajoutés : voir « Fichiers créés » (classes `Commission*`, `ThreadCommissionPricingTest`, `*GridTest`, `*GridIT`, `V332…`, `V333…`) ; tests existants adaptés dans les packages `matching`, `payments`, `requests`, `promo`, `notifications`, `config`.

## Décisions techniques

- **Calcul inverse par dichotomie sur unités mineures** (accordé au dispatch) plutôt qu'inversion analytique segment par segment : exacte à l'arrondi près, sans ajustement d'une unité. Seuil 422 = brut `<= C(0+)` (inclut le bon : minimum effectif = minimum × facteur) au lieu de « brut `<= minimum` » de la spec. Alternative écartée : analytique, plus fragile avec taux spécial et bon.
- **`netForGross` refuse un code promo.** Avec une promo en points sur une grille dégressive, le brut n'est plus monotone en N. Devis de demande avec promo : `net = netForGross(budget, NONE)`, `commission = compute(net, promo)`, `total = net + commission` (l'expéditeur paie moins que son budget) ; à reporter dans la PR app (l'étape 3 du wizard affiche une baisse de prix pour l'expéditeur au lieu de l'ancien « boost » voyageur).
- **Vecteur « promo sans effet »** : net 10 €, promo 5 points, commission 1,00 €, `promoEffective=false` ; à net 20 € la commission passe de 1,40 € à 1,00 € et la promo est effective. La règle §4.2 compare la commission avec et sans promo, le vecteur initial du plan la contredisait.
- **Date d'effet** : `effectiveFrom` nulle ou dans [maintenant − 5 min, maintenant] ramenée à maintenant (effet immédiat) ; plus ancienne : 422 `commission-grid-effective-in-past`. Alternative écartée : refuser toute date passée, qui ferait échouer un « appliquer tout de suite » sur la latence de saisie. Aucun objet n'est recalculé : la grille est figée à la création.
- **Référence sans grille en vigueur** (avertissement de hausse, `simulate.current`) : taux plat `commission_rate` sur toutes tranches, minimum 0 dans toutes les devises (comportement carte actuel). La première grille 7/6/5 avec minimum est donc détectée comme une hausse.
- **`PackageRequestAcceptedEvent`** porte `commissionGridId` dès le lot des bids (le bid matérialisé en a besoin) et `senderGross` calculé au scellement (carte, mobile money). Espèces : notification d'acceptation au net (hérité). `RequestEventsListener` ne recalcule pas : il tourne en parallèle du rachat du code.
- **Précision des taux** : `@Column(precision = 7, scale = 6)` sur `BidEntity.commissionRate`, `NegotiationThreadEntity.commissionRate`, `PromoRedemptionEntity.appliedRate` avec un test de persistance à 6 décimales (le profil test génère le schéma et masquerait une troncature).
- **Historique** : `history(Pageable)` renvoie `Page<CommissionGridHistoryEntry>` (grille, date d'annulation, auteur, note, création) pour distinguer les grilles annulées ; requête native, `Pageable` sans tri.
- **Simulateur** dans `admin/commission/` (`CommissionSimulator`, `ProviderCostProperties`) plutôt que `common/commission/`, car l'admin est son seul consommateur ; coûts mobile money tirés de `PawapayFeeTable.providers()` (taux de dépôt, taux de versement), devise déduite du suffixe pays de la clé opérateur : CIV, SEN, BEN, BFA, MLI, TGO, NER, GNB en XOF ; CMR, GAB, COG, TCD, CAF, GNQ en XAF.
- **Brouillon de demande** : garde la grille figée à la création du brouillon, y compris à sa publication (c'est le prix vu par l'expéditeur, et le net a été calculé avec cette grille). Alternative écartée : re-figer à la publication, qui obligerait à recalculer le net sans brut stocké.
- **Nouvelle tentative de paiement** d'un bid ou fil à grille avec paiement existant : réutilise les montants enregistrés (`payments.amount`, `commission_amount`) plutôt que de rejouer un taux : exact au centime, sans revalider un code promo déjà racheté.
- **Taux stockés bornés** : `min(taux effectif, 0.999999)` (`storableRate`) pour respecter le CHECK `[0, 1)` de V118 ; valeur informative pour les objets à grille.
- **Test de carte XOF** : la carte est refusée en XOF avant calcul (aucune lecture de grille) ; l'arrondi unique est couvert en CHF.
- **Espèces avec grille, base `<= 0`** : 422 `invalid-amount` (le chemin hérité prélevait le plancher), comme la carte.
- **Reprise d'un bid à grille dont le net a changé** : nouveau devis sur la grille figée, sans revalider le code déjà racheté (remise lue par `discountOfRedeemed`), taux mis à jour par `storableRate`, ni rachat ni consommation ; reprend la logique héritée (taux figé réappliqué au nouveau net).
- **Une seule règle de réductions pour un bid** : `CommissionRateResolver.reductionsForBid` (affichage, carte, espèces, mobile money) ; un code verrouillé ou racheté n'est jamais revalidé, sinon un rappel après verrouillage prélèverait sans la remise affichée.
- **Devis de bid et de fil** : un code invalide donne 422 (l'expéditeur doit savoir qu'il est refusé) ; les rails gardent le repli silencieux.
- **Repli du code promo** : `reductionsWithPromoFallback` dans le résolveur, source unique pour carte, espèces et mobile money (quatre copies auparavant).
- **Fil de demande** : calcul unique dans `common/commission/ThreadCommissionPricing` (annonce, prélèvement espèces, dépôt mobile money). Sa surcharge avec `promoCode` sert le dépôt mobile money, comme la carte ; le rail espèces du fil reste sans code (hérité). Un code sans effet ou invalide est effacé du fil hors essai en cours, comme `NegotiationController` pour la carte.
- **Lignes de grille d'un bid négocié** : prix unitaire voyageur (le minimum ne s'applique qu'au total) ; total frais compris = `suggestedGrossEur`.
- **`toResponse` d'un bid à grille avec `promoCodeId` déjà racheté** : relit la remise via `discountOfRedeemed`, sans revalidation (aligné sur la reprise carte), pour que le total affiché soit le montant débité.
- **Fil à grille scellé jamais re-tarifé à l'affichage** : le brut affiché est le brut scellé (`sealed_gross_eur` en V333, exact au centime ; en espèces, net + commission prélevée). Avant scellement, calcul via `ThreadCommissionPricing`. Alternative écartée : recalculer à la volée, qui afficherait un total supérieur à celui débité une fois le code racheté et le bon consommé.
- **Brut vu par le voyageur** : brut public sans réductions personnelles de l'expéditeur (taux spécial, bon, code), pour ne pas les exposer par division brut / net ; l'expéditeur voit son brut réel.
- **`/package-requests/quote`** : paramètre `currency` facultatif (défaut : la devise que retiendrait la création) ; à reporter dans la PR app.
- **Bid matérialisé d'un fil à grille (revue finale, I1)** : il porte le brut payé sur le fil dans `negotiated_gross_eur` (carte et mobile money ; espèces : `senderGross` nul, net seul) plutôt qu'une nouvelle colonne (pas de migration). Ce brut identifiait jusque-là une négociation de trajet : les listes « Discussions de prix » et `loadNegotiation` excluent désormais les bids liés à un fil, et `createEscrow` refuse ces bids (aucun second escrow ne relit ce brut). Les rails mobile money et espèces exigent un bid PENDING : jamais atteints.
- **Verrou des grilles (M1)** : verrou consultatif transactionnel plutôt qu'un verrou de ligne (il n'y a pas de ligne à verrouiller quand aucune grille n'est programmée) ; isolé dans `CommissionGridWriteLock`, sans effet hors PostgreSQL (dialecte lu une fois sur la connexion).
- **Journal du repli promo (M2)** : variantes d'affichage plutôt qu'un paramètre booléen sur l'API publique ; montants strictement identiques à ceux des rails.
- **Affichage d'un bid carte déjà tenté (M3)** : suit `createEscrow` (`existing` = toute ligne `payments` du bid) ; les montants enregistrés font foi quand ils correspondent encore au net, y compris pour un bid payé.
- **Brut scellé (M4)** : lu sur le paiement du fil (`NegotiationEscrowPort.paidAmount`, statuts PENDING, ESCROW, RELEASED), recalculé seulement en dernier recours (WARN). Un taux spécial, un bon ou un code changés entre l'initiation et le scellement ne faussent plus le total figé.
- **Grille par identifiant (M5)** : `GET /config/commission-grid/{id}` public, une grille annulée répond 404 (jamais figée sur un objet).
- **Annulation qui croise la date d'effet (M8)** : écriture immédiate (`flush`) dans `cancel` et traduction du refus du trigger (message `commission_grid_immutable`) en 409, au lieu d'un 500 au commit.
- **Orchestration** : T12 se limite aux fiches et à cette documentation ; la suite complète, JaCoCo et la PR sont gérées par le contrôleur après la revue finale de branche.
