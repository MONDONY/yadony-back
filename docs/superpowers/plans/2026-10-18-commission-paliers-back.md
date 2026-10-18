# Commission par paliers, back : plan de mise en œuvre

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal :** remplacer le taux unique par une grille marginale datée, réglable dans l'admin, avec un minimum par devise. La formule est la même pour la carte, les espèces et le mobile money. Sans grille programmée, le comportement reste identique à aujourd'hui.

**Architecture :**
- Nouveau sous-package `common/commission/`, qui contient :
  - un calculateur pur, `CommissionCalculator`, qui calcule une commission à partir d'une grille et des réductions ;
  - un service de grilles datées, `CommissionGridService`, avec persistance jsonb, cache Caffeine et `audit_log` ;
  - un simulateur de marge.
- Chaque objet qui porte un prix (bid, demande, fil) fige `commission_grid_id` à sa création. `NULL` signifie « ancien calcul », que l'on garde tel quel dans une branche `if`.
- `CommissionRateResolver` gagne une méthode qui rend les réductions (taux spécial, promo, bon) séparément, sans changer `resolve(...)`.

**Tech Stack :** Java 21, Spring Boot 3.4, PostgreSQL 16, Flyway, Hibernate 6 (`@JdbcTypeCode(SqlTypes.JSON)`), Caffeine, JUnit 5, Mockito, MockMvc, zonky embedded-postgres.

**Spec :** `docs/superpowers/specs/2026-10-18-commission-paliers-design.md`, à lire avant chaque tâche. Les numéros de section (§) renvoient à ce document.

Racine des sources : `J = src/main/java/com/yadony/api`, `T = src/test/java/com/yadony/api`. Worktree : `dony-back/.claude/worktrees/commission-paliers`, branche `feature/commission-paliers`.

## Global Constraints

- La grille est la même pour tous les rails et toutes les devises. La commission ne dépend jamais du moyen de paiement.
- Le propriétaire peut **à tout moment** programmer une nouvelle grille (taux, tranches, minimums), y compris avec un effet immédiat. Une hausse à moins de 30 jours déclenche un **avertissement à confirmer**, jamais un blocage.
- Grille marginale : `C_grille = Σ taux_i × max(0, min(N, borne_sup_i) − borne_inf_i)`. Les bornes sont en EUR et converties par `amount × SupportedCurrency.unitsPerEur()`, **sans arrondi**.
- Ordre de calcul (§4.2) :
  1. grille ;
  2. `min` avec le taux spécial ;
  3. promo sur le taux effectif `C/N`, plancher 0 ;
  4. minimum, sauf si le taux après promo vaut 0, auquel cas la commission est 0 ;
  5. bon de parrainage, en multiplication **après** le minimum ;
  6. arrondi unique HALF_UP à `minorUnit()`.
- Minimums par défaut : EUR 1.00, XOF 650, XAF 650, USD 1.10, CAD 1.50, GBP 0.90, CHF 0.95. Plafond de validation : `5 × unitsPerEur`.
- Bornes de validation : taux entre 0 et 0,30 ; de 1 à 10 tranches ; première borne à 0 ; bornes strictement croissantes ; `effective_from ≥ now` ; une seule grille programmée à la fois, une nouvelle programmation remplaçant l'ancienne.
- Une grille en vigueur est immuable et ne peut pas être supprimée (409). Seule une grille programmée peut être annulée, par suppression logique.
- Aucune grille n'est insérée par migration.
- Erreurs au format RFC 7807 via `YadonyBusinessException` et `GlobalExceptionHandler`. Endpoints admin protégés par `@PreAuthorize("hasRole('ADMIN') and hasAuthority('CONFIG_MANAGE')")`.
- Audit : `auditService.log("COMMISSION_GRID", gridId, "COMMISSION_GRID_SCHEDULED" | "COMMISSION_GRID_CANCELLED", adminId, payload)`.
- Les DTO existants gardent tous leurs champs (règle des PR jumelles). On ne fait qu'ajouter.
- Prix au kilo affiché, pour un objet avec grille : le **prix net du voyageur** (§7.3, décision du propriétaire).
- Promo sans effet (le minimum annule tout le gain) : `promoApplied = false`, `promoIneffectiveReason = "COMMISSION_MINIMUM"`, et ni `redeem`, ni `lockForRedemption`, ni `recordGrantedRedemption`.
- Ne jamais faire tourner deux `./mvnw` en même temps. Tests ciblés par `-Dtest=…`, suite complète en tâche finale. Couverture ≥ 90 %.
- Commits en français, au format Conventional Commits, sans ligne `Co-Authored-By`.

## Review Focus

1. **Grille activée entre le devis et la création du bid.** Le bid fige la grille en vigueur à `createBid`. Le paiement, l'acceptation espèces et le dépôt mobile money utilisent cette grille figée, même si une autre est entrée en vigueur depuis. Testé en tâche 7 (`createBid_freezesCurrentGrid`) et en tâche 8 (`createEscrow_usesFrozenGridNotCurrent`).
2. **Franchissement de `effective_from` pendant que le cache est chaud.** La grille programmée doit être servie dès l'heure d'effet, sans attendre l'expiration du cache. Testé en tâche 3 (`activeAt_switchesAtEffectiveFrom_withWarmCache`).
3. **XOF en négociation espèces.** Aujourd'hui la commission y est arrondie à 2 décimales. Avec une grille, elle doit être entière et respecter 650 FCFA minimum. Testé en tâche 9 (`settleNegotiationCommission_xofGrid_isWholeFrancsWithMinimum`).
4. **Code promo annulé par le minimum.** Il n'est pas consommé, sur aucun des trois rails. Testé en tâche 8 (`createEscrow_promoIneffective_notRedeemed`), en tâche 9 (`acceptCashBid_promoIneffective_notLocked`) et en tâche 10 (`acceptBid_promoIneffective_notRedeemed`).
5. **Budget de demande inférieur ou égal au minimum.** La réponse est un 422 `budget-below-commission-minimum`, jamais un net nul ou négatif. Testé en tâche 2 (`netForGross_atOrBelowMinimum_throws`) et en tâche 11 (`create_budgetBelowMinimum_422`).

---

### Task 1 : migration V331

**Files :**
- Create : `src/main/resources/db/migration/V331__commission_grids.sql`
- Test : `T/migrations/V331CommissionGridsMigrationTest.java`, sur le modèle de `V329AssistantUserDailyUsageMigrationTest`

**Interfaces :**
- Produces :
  - table `commission_grids(id uuid pk, effective_from timestamp not null, tiers jsonb not null, minimums jsonb not null, note varchar(500), created_by uuid not null, created_at, updated_at, deleted_at)` ;
  - colonnes nullables `bids.commission_grid_id uuid`, `bids.commission_override_rate numeric(7,6)`, `bids.commission_voucher_factor numeric(4,3)`, `package_requests.commission_grid_id uuid`, `negotiation_threads.commission_grid_id uuid`, toutes avec une FK vers `commission_grids(id)` ;
  - passage de `bids.commission_rate`, `negotiation_threads.commission_rate` et `promo_redemptions.applied_rate` en `numeric(7,6)`. Le CHECK `bids_commission_rate_range` reste valide.
- Format jsonb :
  - `tiers` = `[{"fromEur":"0","rate":"0.07"},…]` ;
  - `minimums` = `{"EUR":"1.00","XOF":"650",…}`.

- [ ] **Étape 1 : écrire le test de migration qui échoue**
  - `migrate_createsTableAndColumns` : après `flywayUpTo("331")`, les colonnes existent avec les types ci-dessus (lecture de `information_schema.columns`).
  - `trigger_rejectsUpdateOfEffectiveGrid` : insérer une grille avec `effective_from = now() - interval '1 minute'`. Un `UPDATE … SET tiers = …` lève une exception dont le message contient `commission_grid_immutable`. Un `UPDATE … SET deleted_at = now()` lève aussi une exception.
  - `trigger_allowsCancellingScheduledGrid` : une grille à `now() + interval '1 day'`, puis `UPDATE … SET deleted_at = now()`, réussit.
  - `existingRates_preserved` : un bid créé en V330 avec `commission_rate = 0.050` relit `0.050000` en V331.
- [ ] **Étape 2 :** `./mvnw test -Dtest=V331CommissionGridsMigrationTest`. Attendu : ÉCHEC (migration absente).
- [ ] **Étape 3 : écrire `V331__commission_grids.sql`.**
  - Fonction `prevent_effective_commission_grid_change()`, en `BEFORE UPDATE OR DELETE`. Elle lève `RAISE EXCEPTION 'commission_grid_immutable'` si `OLD.effective_from <= now() AT TIME ZONE 'UTC'`. Elle bloque aussi tout `DELETE`, puisque la suppression est logique.
  - Index `(effective_from) WHERE deleted_at IS NULL`.
- [ ] **Étape 4 :** relancer le même test. Attendu : SUCCÈS.
- [ ] **Étape 5 :** `git commit -m "feat(commission): table commission_grids et colonnes de grille figée (V331)"`

### Task 2 : calculateur pur et vecteurs de référence

**Files :**
- Create :
  - `J/common/commission/CommissionGrid.java`
  - `J/common/commission/CommissionTier.java`
  - `J/common/commission/CommissionReductions.java`
  - `J/common/commission/CommissionResult.java`
  - `J/common/commission/CommissionCalculator.java`
  - `src/test/resources/commission/vectors.json`
- Test :
  - `T/common/commission/CommissionCalculatorTest.java`
  - `T/common/commission/CommissionVectorsTest.java`

**Interfaces :**
- `record CommissionTier(BigDecimal fromEur, BigDecimal rate)`
- `record CommissionGrid(UUID id, LocalDateTime effectiveFrom, List<CommissionTier> tiers, Map<SupportedCurrency, BigDecimal> minimums)`, avec deux méthodes :
  - `BigDecimal gridCommission(BigDecimal net, SupportedCurrency c)`, non arrondie ;
  - `BigDecimal minimum(SupportedCurrency c)`.
- `record CommissionReductions(BigDecimal overrideRate, PromoDiscount promo, BigDecimal voucherFactor)`. Les trois champs acceptent null. Constante `static final CommissionReductions NONE`.
- `record CommissionResult(BigDecimal net, BigDecimal commission, BigDecimal gross, BigDecimal effectiveRate, boolean minimumApplied, boolean promoEffective)` :
  - `effectiveRate = commission / net` à 6 décimales ;
  - `promoEffective` est faux si la promo est absente ou sans effet.
- `final class CommissionCalculator`, statique :
  - `static CommissionResult compute(BigDecimal net, SupportedCurrency c, CommissionGrid g, CommissionReductions r)` ;
  - `static BigDecimal netForGross(BigDecimal gross, SupportedCurrency c, CommissionGrid g, CommissionReductions r)`, qui lève `YadonyBusinessException(422, "budget-below-commission-minimum")` si `gross ≤ C(0+)`.

- [ ] **Étape 1 : écrire `vectors.json`.** On y met les vecteurs du §4.6, la grille `[{0,0.07},{100,0.06},{300,0.05}]` et les minimums par défaut. Champs : `case, currency, net|gross, reductions{overrideRate,promoPoints,promoPricePercent,voucherFactor}, expected{commission,gross|net,minimumApplied,promoEffective}`.
  - Ajouter les vecteurs de négociation de trajet du §4.6 :
    - net 50 € avec taux spécial 0.04 et bon 0.5 : commission 1.00, brut 51.00 ;
    - inverse brut 51.00 avec le même contexte : net 50.00.
  - Ajouter le vecteur promo sans effet : net 20 €, promo de 5 points, `promoEffective = false`, commission 1.00.
- [ ] **Étape 2 : écrire les tests qui échouent.**
  - `CommissionVectorsTest` : un `@ParameterizedTest @MethodSource("vectors")` qui lit le JSON et vérifie `compute` ou `netForGross`.
  - `CommissionCalculatorTest` :
    - `compute_isMonotonicAcrossTierBounds` : pour net de 0.01 à 600.00 par pas de 0.01, le brut est strictement croissant ;
    - `compute_roundsOnceAtEnd_xof` : net 100 000 XOF donne une commission de 6656 avec `scale == 0` ;
    - `compute_zeroRateAfterPromo_isZeroNoMinimum` ;
    - `compute_overrideZero_isZero` ;
    - `compute_voucherAfterMinimum` : net 5 €, bon 0.5, commission 0.50 ;
    - `netForGross_inverseOfCompute_roundTrip` : pour 2 000 nets aléatoires dans chaque devise, `netForGross(compute(n).gross) == n` ;
    - `netForGross_atOrBelowMinimum_throws` : brut 1.00 € lève une erreur 422.
- [ ] **Étape 3 :** `./mvnw test -Dtest='Commission*Test'`. Attendu : ÉCHEC (classes absentes).
- [ ] **Étape 4 : implémenter.**
  - Promo : `r_promo = r.promo().applyTo(C_base / net)`, avec `C_base / net` à 6 décimales.
  - `promoEffective` : comparer `C` avec et sans promo, toutes autres réductions égales.
  - `netForGross` : chercher le segment par bornes (zone du minimum, puis chaque tranche), résoudre la forme linéaire, arrondir à `minorUnit` en CEILING, puis descendre d'un pas tant que `compute(n − pas).gross ≤ gross`. C'est le modèle de `BidNegotiationPricing.grossForNet`.
- [ ] **Étape 5 :** relancer. Attendu : SUCCÈS.
- [ ] **Étape 6 :** `git commit -m "feat(commission): calculateur de grille marginale et vecteurs de référence"`

### Task 3 : grilles datées (entité, service, cache, audit)

**Files :**
- Create :
  - `J/common/commission/CommissionGridEntity.java` (extends `BaseEntity`, `@Table("commission_grids")`, `@Where(clause="deleted_at IS NULL")`, champs jsonb en `@JdbcTypeCode(SqlTypes.JSON)`)
  - `J/common/commission/CommissionGridRepository.java`
  - `J/common/commission/CommissionGridDraft.java`
  - `J/common/commission/CommissionGridWarning.java`
  - `J/common/commission/CommissionGridService.java`
- Modify : `J/config/CacheConfig.java`, qui reçoit `registerCustomCache("commission-grids", maximumSize(1), expireAfterWrite(30 s))`.
- Test :
  - `T/common/commission/CommissionGridServiceTest.java` (Mockito, `Clock` fixe)
  - `T/common/commission/CommissionGridRepositoryIT.java`

**Interfaces :**
- `record CommissionGridDraft(List<CommissionTier> tiers, Map<SupportedCurrency, BigDecimal> minimums, LocalDateTime effectiveFrom, String note)`
- `enum CommissionGridWarning { INCREASE_WITHIN_30_DAYS }`
- `CommissionGridService`, avec un constructeur public `@Autowired` qui délègue au constructeur package-private avec `Clock.systemUTC()` (piège des deux constructeurs). Méthodes :
  - `Optional<CommissionGrid> current()` ;
  - `Optional<CommissionGrid> scheduled()` ;
  - `Optional<CommissionGrid> activeAt(LocalDateTime t)` ;
  - `CommissionGrid require(UUID id)` ;
  - `List<CommissionGridWarning> warningsFor(CommissionGridDraft d)` ;
  - `CommissionGrid schedule(CommissionGridDraft d, Set<CommissionGridWarning> acknowledged, UUID adminId)` ;
  - `void cancel(UUID id, UUID adminId)` ;
  - `Page<CommissionGrid> history(Pageable p)` ;
  - `static Map<SupportedCurrency, BigDecimal> defaultMinimums()`.
- Le cache contient la liste de toutes les grilles non supprimées. `activeAt` et `current` filtrent cette liste sur l'horloge à chaque appel.

- [ ] **Étape 1 : écrire les tests qui échouent.**
  - `schedule_validDraft_persistsAndAudits` : vérifie l'appel `auditService.log("COMMISSION_GRID", id, "COMMISSION_GRID_SCHEDULED", adminId, payload)`, et que `payload` contient `tiers`, `minimums`, `effectiveFrom` et `acknowledgedWarnings`.
  - `schedule_rejects` en paramétré, une erreur 422 par cas, chacune portant un `violations[field]` :
    - première borne ≠ 0 ;
    - bornes non croissantes ;
    - 0 ou 11 tranches ;
    - taux de 0.31 ;
    - taux négatif ;
    - minimum manquant pour une devise de `SupportedCurrency` ;
    - minimum supérieur à `5 × unitsPerEur` ;
    - `effectiveFrom` dans le passé.
  - `schedule_increaseWithin30Days_requiresAcknowledgement` :
    - sans acquittement, erreur 422 `commission-grid-warning-not-acknowledged`, avec la propriété `warnings` ;
    - avec acquittement, succès.
  - `warningsFor_decreaseOnly_none` et `warningsFor_minimumRaise_isIncrease`. Une hausse est détectée si la commission augmente pour au moins un net en EUR de 1 à 1000 (pas de 1), ou si un minimum augmente.
  - `schedule_replacesPreviouslyScheduled` : l'ancienne grille programmée est supprimée logiquement, avec l'audit `COMMISSION_GRID_CANCELLED`.
  - `cancel_effectiveGrid_409` : erreur 409 `commission-grid-already-effective`.
  - `activeAt_switchesAtEffectiveFrom_withWarmCache` : cache rempli à `t0`, grille B à `t0 + 1 min`, horloge avancée à `t0 + 1 min`, `current()` renvoie B sans nouvelle lecture du repository.
  - `defaultMinimums_values` : EUR 1.00, XOF 650, XAF 650, USD 1.10, CAD 1.50, GBP 0.90, CHF 0.95.
- [ ] **Étape 2 :** `./mvnw test -Dtest='CommissionGrid*'`. Attendu : ÉCHEC.
- [ ] **Étape 3 : implémenter** `CommissionGridEntity`, le repository (`findAllByOrderByEffectiveFromDesc()`), puis le service. Chaque écriture est suivie de `@CacheEvict("commission-grids")`.
- [ ] **Étape 4 :** relancer. Attendu : SUCCÈS.
- [ ] **Étape 5 :** `git commit -m "feat(commission): grilles datées, validation, avertissement de hausse, audit"`

### Task 4 : réductions séparées dans le resolver

**Files :**
- Modify : `J/common/CommissionRateResolver.java`
- Test : `T/common/CommissionRateResolverTest.java`

**Interfaces :**
- Produces : `public CommissionReductions reductions(UUID travelerId, UUID senderId, String promoCode, UUID promoUserId, UUID reference)`. Cette méthode renvoie :
  - le `min` des overrides du voyageur et de l'expéditeur, ou null ;
  - le `PromoDiscount` validé par `validateAndGetDiscount`, ou null s'il n'y a pas de code. Un code invalide lève une exception, comme dans `resolve` ;
  - le facteur du bon (`peekActive` ou `peekForReference`), ou null.
- Les surcharges `resolve(...)` existantes ne changent pas.

- [ ] **Étape 1 : tests.**
  - `reductions_minOfOverrides` ;
  - `reductions_promoValidated_returnsDiscount` ;
  - `reductions_invalidPromo_throws` ;
  - `reductions_withReference_usesPeekForReference` ;
  - `reductions_noneConfigured_allNull`.
- [ ] **Étape 2 :** `./mvnw test -Dtest=CommissionRateResolverTest`. Attendu : ÉCHEC sur les nouveaux tests.
- [ ] **Étape 3 :** implémenter.
- [ ] **Étape 4 :** relancer. Attendu : SUCCÈS.
- [ ] **Étape 5 :** `git commit -m "feat(commission): exposer les réductions séparément du taux"`

### Task 5 : API publique

**Files :**
- Create : `J/config/dto/CommissionGridResponse.java`
- Modify : `J/config/ConfigController.java`
- Test : `T/config/ConfigControllerCommissionGridIT.java`, et compléter `ConfigControllerPlatformSettingsIT`

**Interfaces :**
- `record CommissionGridResponse(GridView current, GridView next)`
- `record GridView(UUID gridId, LocalDateTime effectiveFrom, List<CommissionTier> tiers, Map<String, BigDecimal> minimums)`. Les clés de `minimums` sont les codes en majuscules.
- `GET /config/commission-grid` est public, déjà couvert par `/config/**`.
- `GET /config/commission-rate` renvoie le taux de la première tranche de `current()` si une grille existe, sinon `settings.commissionRate()`.

- [ ] **Étape 1 : tests.**
  - `commissionGrid_noGrid_currentNull` ;
  - `commissionGrid_currentAndScheduled` ;
  - `commissionGrid_isPublic_noToken200` ;
  - `commissionRate_withGrid_returnsFirstTierRate` ;
  - `commissionRate_noGrid_unchanged`.
- [ ] **Étape 2 :** lancer les deux classes IT. Attendu : ÉCHEC.
- [ ] **Étape 3 :** implémenter.
- [ ] **Étape 4 :** relancer. Attendu : SUCCÈS.
- [ ] **Étape 5 :** `git commit -m "feat(commission): exposer la grille en vigueur et programmée"`

### Task 6 : API admin et simulateur

**Files :**
- Create :
  - `J/admin/AdminCommissionGridController.java`
  - `J/admin/dto/CommissionGridRequest.java`
  - `J/admin/dto/CommissionGridAdminResponse.java`
  - `J/admin/dto/CommissionSimulationRequest.java`
  - `J/admin/dto/CommissionSimulationResponse.java`
  - `J/common/commission/CommissionSimulator.java`
  - `J/common/commission/ProviderCostProperties.java`
- Modify : `src/main/resources/application.yml`, sous `yadony.costs.stripe.*`
- Test :
  - `T/admin/AdminCommissionGridControllerIT.java`
  - `T/common/commission/CommissionSimulatorTest.java`

**Interfaces :**
- Endpoints :
  - `GET /admin/commission-grids?page=` → `{current, scheduled, history: Page}` ;
  - `POST /admin/commission-grids` avec `{tiers, minimums, effectiveFrom, note, acknowledgedWarnings[]}` → 201 + la grille ;
  - `DELETE /admin/commission-grids/{id}` → 204 ;
  - `POST /admin/commission-grids/simulate`.
- `@ConfigurationProperties("yadony.costs.stripe") record ProviderCostProperties(...)`. Valeurs par défaut :
  - `cardStandardPercent 1.5`, `cardPremiumPercent 2.8`, `cardInternationalPercent 3.15`, `cardFixedEur 0.25` ;
  - `connectPayoutPercent 0.25`, `connectPayoutFixedEur 0.10`, `connectActiveAccountMonthlyEur 2.0`.
- `CommissionSimulator.simulate(CommissionSimulationRequest)` :
  - Entrée : `{draft? | gridId?, currency, nets[], vatRatePercent, effectiveFrom?}`.
  - Sortie, une ligne par net :
    - `{net, current{commission,gross}|null, draft{commission,gross,minimumApplied}, rails[{rail, providerCost, marginExVat}]}` ;
    - `rail` prend les valeurs `CARD_STANDARD`, `CARD_PREMIUM`, `CARD_INTERNATIONAL`, `MOBILE_MONEY_WORST`, `MOBILE_MONEY_BEST`, `CASH`.
  - La réponse porte aussi les `warnings` du brouillon.
- Formules de coût :
  - **carte** : `gross × p% + fixe + net × payout% + payoutFixe`, en devise. Les montants fixes sont convertis par `unitsPerEur`.
  - **mobile money** : `gross × dépôt% + net × versement%`, sur la grille `yadony.pawapay.fees` filtrée sur la devise. Pire et meilleur opérateur. Pour une devise sans opérateur, la ligne est absente.
  - **espèces** : coût de recharge par carte standard sur le montant de la commission.
  - **marge HT** : `commission / (1 + TVA) − coût`.

- [ ] **Étape 1 : tests.**
  - `simulate_card_standard_net20` : grille 7/6/5, commission 1.40, brut 21.40, coût `21.40×0.015 + 0.25 + 20×0.0025 + 0.10 = 0.721`, marge HT avec TVA 20 % = `1.40/1.2 − 0.721 = 0.4457` (±0.0001) ;
  - `simulate_mobileMoney_xof_worstAndBest` ;
  - `simulate_cash_rechargeCost` ;
  - `simulate_returnsWarningsForDraft` ;
  - pour le contrôleur :
    - `post_withoutConfigManage_403` ;
    - `post_invalid_422ProblemJson` ;
    - `post_increaseNotAcknowledged_422` ;
    - `delete_effective_409` ;
    - `get_returnsCurrentScheduledHistory`.
- [ ] **Étape 2 :** lancer les tests. Attendu : ÉCHEC.
- [ ] **Étape 3 :** implémenter.
- [ ] **Étape 4 :** relancer. Attendu : SUCCÈS.
- [ ] **Étape 5 :** `git commit -m "feat(admin): API des grilles de commission et simulateur de marge"`

### Task 7 : figer la grille à la création

**Files :**
- Modify :
  - `J/matching/BidEntity.java`, qui reçoit `commissionGridId`, `commissionOverrideRate` et `commissionVoucherFactor`
  - `J/requests/entity/PackageRequestEntity.java` (`commissionGridId`)
  - `J/requests/entity/NegotiationThreadEntity.java` (`commissionGridId`, `precision = 7, scale = 6` sur `commissionRate`)
  - `J/matching/BidService.java` (`createBid`)
  - `J/matching/BidNegotiationService.java` (`propose`, l.175-199)
  - `J/requests/service/PackageRequestService.java` (`createAndReturnEntity`)
  - `J/requests/service/NegotiationService.java` (`start`, l.182-286)
  - `J/matching/ThreadAcceptedBidListener.java`, qui copie la grille du fil sur le bid matérialisé
- Test : compléter `BidServiceTest`, `BidNegotiationServiceTest`, `PackageRequestServiceTest` et `NegotiationServiceTest`

**Interfaces :**
- Consumes : `CommissionGridService.current()` et `CommissionRateResolver.reductions(...)`.
- Règles :
  - un objet nouveau reçoit `current().map(CommissionGrid::id).orElse(null)` ;
  - un fil reçoit la grille de sa demande ;
  - un bid matérialisé reçoit la grille du fil ;
  - dans `propose`, si la grille est non nulle, on fige aussi `reductions(t, s, null, null, null)` sur `commissionOverrideRate` et `commissionVoucherFactor`.

- [ ] **Étape 1 : tests.**
  - `createBid_freezesCurrentGrid` ;
  - `createBid_noGrid_null` ;
  - `propose_withGrid_freezesGridAndReductions` ;
  - `packageRequestCreate_freezesGrid` ;
  - `threadStart_inheritsRequestGrid` ;
  - `threadAccepted_bidInheritsThreadGrid`.
- [ ] **Étape 2 :** lancer les tests ciblés. Attendu : ÉCHEC.
- [ ] **Étape 3 :** implémenter.
- [ ] **Étape 4 :** relancer. Attendu : SUCCÈS.
- [ ] **Étape 5 :** `git commit -m "feat(commission): figer la grille sur bids, demandes et fils à la création"`

### Task 8 : rail carte

**Files :**
- Modify : `J/payments/PaymentService.java` (`createEscrow` l.476-776, `createNegotiationEscrow` l.1917-2079)
- Test : `T/payments/PaymentServiceCommissionGridTest.java`, qui reprend `PaymentServiceTestFactory`

**Interfaces :**
- Consumes :
  - `CommissionCalculator.compute` ;
  - `CommissionGridService.require(bid.getCommissionGridId())` ;
  - `CommissionRateResolver.reductions(t, s, promoCode, s, bidId)`, avec repli sans promo si le code est invalide, comme aujourd'hui.
- Règles :
  - **Grille non nulle** :
    - `amount = result.gross()` et `commission = result.commission()`, puis `CurrencyAmount.of` ;
    - `bid.setCommissionRate(result.effectiveRate())` à la création du paiement ;
    - `redeem` seulement si `result.promoEffective()` ;
    - `voucherService.consume` reste appelé chaque fois que des réductions incluent un bon.
  - **Bid négocié** (`negotiatedGross`/`negotiatedNet` présents) : on garde `gross − net`, les valeurs que `BidNegotiationService.accept` a persistées.
  - **Grille nulle** : la branche existante ne change pas.
  - Le paiement d'un fil passe par `thread.getCommissionGridId()`.

- [ ] **Étape 1 : tests.**
  - `createEscrow_grid_net10_commission1_gross11` ;
  - `createEscrow_usesFrozenGridNotCurrent` ;
  - `createEscrow_promoIneffective_notRedeemed` ;
  - `createEscrow_voucher_afterMinimum_net5_commission050` ;
  - `createEscrow_noGrid_legacyUnchanged` : net 10, taux 0.05, commission 0.50 ;
  - `createNegotiationEscrow_grid_xof_wholeFrancs`.
- [ ] **Étape 2 :** `./mvnw test -Dtest='PaymentService*'`. Attendu : ÉCHEC sur les nouveaux tests.
- [ ] **Étape 3 :** implémenter.
- [ ] **Étape 4 :** relancer. Attendu : SUCCÈS, et aucune régression dans les autres `PaymentService*Test`.
- [ ] **Étape 5 :** `git commit -m "feat(paiement): commission carte par grille figée"`

### Task 9 : rail espèces

**Files :**
- Modify : `J/payments/cash/CashCommissionService.java` (`quoteBidCommission` l.223-255, `settleNegotiationCommission` l.577-641, `bindAppliedPromo` l.1221, `redeemAppliedPromo` l.1238)
- Modify : `J/requests/service/NegotiationService.java` (`finalizeInternal`, branche CASH, l.1569-1570 ; `toResponse` l.2477)
- Test : `T/payments/cash/CashCommissionServiceGridTest.java`

**Interfaces :**
- `BidCommissionQuote` gagne un champ : `record BidCommissionQuote(BigDecimal commission, boolean promoApplied, boolean promoEffective)`. Il faut mettre à jour ses appelants.
- **Grille non nulle** :
  - le bid utilise `compute(base, currency, grid, reductions(t, s, promo, s, bidId))` ;
  - le fil utilise `compute(thread.currentPriceEur, threadCurrency, grid, reductions(t, s, null, null, threadId))` ;
  - le minimum de `CommissionProperties` ne sert plus que si la grille est nulle.
- Si `!promoEffective`, ni `lockForRedemption` ni `recordGrantedRedemption`.

- [ ] **Étape 1 : tests.**
  - `quoteBidCommission_grid_net20_140` ;
  - `quoteBidCommission_grid_net5_minimum100` ;
  - `settleNegotiationCommission_xofGrid_isWholeFrancsWithMinimum` : net 5 000 XOF, commission 650, `scale == 0` ;
  - `acceptCashBid_promoIneffective_notLocked` ;
  - `finalizeInternal_cashGrid_pendingEventCarriesGridCommission` ;
  - `quoteBidCommission_noGrid_legacyFloorUnchanged`.
- [ ] **Étape 2 :** `./mvnw test -Dtest='CashCommissionService*'`. Attendu : ÉCHEC sur les nouveaux tests.
- [ ] **Étape 3 :** implémenter.
- [ ] **Étape 4 :** relancer. Attendu : SUCCÈS.
- [ ] **Étape 5 :** `git commit -m "feat(especes): commission espèces par grille, négociation XOF arrondie au franc"`

### Task 10 : rail mobile money

**Files :**
- Modify :
  - `J/payments/mobilemoney/MobileMoneyBidPricing.java`, en mettant à jour la javadoc l.21 « aucun plancher »
  - `J/payments/mobilemoney/MobileMoneyBidPaymentService.java` (l.219-237)
  - `J/payments/mobilemoney/MobileMoneyNegotiationPaymentService.java` (`createPendingPayment` l.105)
  - `J/requests/service/NegotiationService.java` (`prepareMobileMoneyDeposit` l.1203-1247)
- Test : compléter `MobileMoneyBidPricingTest`, et ajouter `T/payments/mobilemoney/MobileMoneyNegotiationPaymentServiceGridTest.java`

**Interfaces :**
- `MobileMoneyBidPricing.Quote` devient `record Quote(PriceBreakdown price, boolean promoApplied, boolean promoEffective)`.
- `createPendingPayment(UUID threadId, UUID senderId, UUID travelerId, BigDecimal net, BigDecimal commissionRate, String currency, UUID commissionGridId)`. Quand la grille est non nulle, il calcule avec `compute` et ignore `commissionRate`, qui reste informatif.
- La port `NegotiationMobileMoneyPort` et l'adaptateur passent la grille.

- [ ] **Étape 1 : tests.**
  - `price_grid_xof_net20000_commission1400` ;
  - `price_grid_xof_net5000_minimum650` ;
  - `acceptBid_promoIneffective_notRedeemed` ;
  - `createPendingPayment_grid_usesGridNotRate` ;
  - `price_noGrid_legacyNoFloor`.
- [ ] **Étape 2 :** `./mvnw test -Dtest='MobileMoney*'`. Attendu : ÉCHEC sur les nouveaux tests.
- [ ] **Étape 3 :** implémenter.
- [ ] **Étape 4 :** relancer. Attendu : SUCCÈS.
- [ ] **Étape 5 :** `git commit -m "feat(mobile-money): commission mobile money par grille avec minimum"`

### Task 11a : affichages et devis côté trajets (matching)

**Files :**
- Modify :
  - `J/matching/BidService.java` (`quote` l.154-224, `toResponse` l.1705-1721)
  - `J/matching/PriceGridService.java` (`displayPrice` l.310-311)
  - `J/matching/BidNegotiationService.java` (`assertAtLeastTravelerMinimum` l.679, `counterGross` l.309, `accept` l.336-341, `myNegotiations` l.558, `publishPosted` l.774, `buildResponse` l.843, `grossUp` l.973, `suggestedGross` l.958, `travelerNet` l.922)
  - `J/matching/dto/BidQuoteResponse.java`
  - `J/matching/dto/BidNegotiationResponse.java`
- Test :
  - `T/matching/BidQuoteServiceGridTest.java`
  - `T/matching/BidNegotiationServiceGridTest.java`
  - compléter `PriceGridServiceTest`

**Interfaces :**
- `BidQuoteResponse` gagne, à la fin, quatre champs : `boolean minimumApplied`, `BigDecimal minimumAmount`, `UUID commissionGridId` et `String promoIneffectiveReason`.
- `BidNegotiationResponse` gagne `UUID commissionGridId`, `BigDecimal commissionOverrideRate` et `BigDecimal commissionVoucherFactor`. Ces deux derniers ne vont qu'au voyageur, comme `commissionRate`.
- **Bid avec grille, conversions négociées** :
  - brut vers net : `netForGross(gross, cur, grid, frozen)`, avec `frozen = new CommissionReductions(bid.commissionOverrideRate, null, bid.commissionVoucherFactor)` ;
  - net vers brut : `compute(net, cur, grid, frozen).gross()`.
- `quote` (avant la création du bid) : utilise `current()`.
- `displayPrice(net, travelerId)` : renvoie `net` si `current()` est présent, sinon l'ancien calcul. Décision du propriétaire : prix du voyageur au kilo.

- [ ] **Étape 1 : tests.**
  - `quote_grid_net20_totals_minimumFalse` ;
  - `quote_grid_net5_minimumApplied_amount1` ;
  - `quote_promoIneffective_reasonCommissionMinimum` ;
  - `toResponse_gridBid_totalSenderAmountFromCalculator` ;
  - `counter_gridBid_grossFromFrozenContext` : net 50, override 0.04, bon 0.5, brut 51.00 ;
  - `accept_gridBid_splitInverse` ;
  - `displayPrice_withGrid_returnsNet` ;
  - `displayPrice_noGrid_legacy`.
- [ ] **Étape 2 :** lancer les tests ciblés. Attendu : ÉCHEC.
- [ ] **Étape 3 :** implémenter.
- [ ] **Étape 4 :** relancer, ainsi que `BidNegotiationPricingTest` et `BidNegotiationServiceTest`. Attendu : SUCCÈS.
- [ ] **Étape 5 :** `git commit -m "feat(commission): devis, négociation et prix au kilo des trajets selon la grille"`

### Task 11b : affichages et devis côté demandes (requests)

**Files :**
- Modify :
  - `J/requests/service/PackageRequestService.java` (`quote` l.141-160, `createAndReturnEntity` l.286-291, `update` l.405-409, `toResponse` l.1117-1118)
  - `J/requests/service/PackageRequestSearchMapper.java` (`grossPriceEur` l.67, dont on conserve la javadoc sur l'absence de réductions)
  - `J/requests/PublicPackageRequestPageController.java` (l.230-234)
  - `J/requests/service/NegotiationService.java` (`displayRate` l.1267, `senderGross` l.1277, `quote` l.2115-2155, `toResponse` l.2465)
  - `J/notifications/RequestEventsListener.java` (l.293), qui passe la grille par `PackageRequestAcceptedEvent`
  - le record `NegotiationQuoteResponse`, qui gagne les quatre mêmes champs que `BidQuoteResponse`
- Test : `T/requests/service/PackageRequestServiceGridTest.java` et `T/requests/service/NegotiationServiceGridTest.java`

**Interfaces :**
- Grille de la demande ou du fil non nulle :
  - **sans réductions** : `netForGross(budget, cur, grid, NONE)` et `compute(net, cur, grid, NONE).gross()` ;
  - **devis avec promo** : `reductions(null, s, code, s, null)`, sur le modèle de l'ancien « boost » ;
  - `PackageRequestAcceptedEvent` gagne `UUID commissionGridId`.
- Le devis pour un nouveau budget, sans demande existante, utilise `current()`.

- [ ] **Étape 1 : tests.**
  - `create_grid_budget2140_net20` ;
  - `create_budgetBelowMinimum_422` : budget 1.00 € ;
  - `update_keepsFrozenGrid` ;
  - `toResponse_grid_grossFromCalculator` ;
  - `search_grid_grossNoPersonalReductions` ;
  - `threadToResponse_grid_gross` ;
  - `negotiationQuote_grid_promoIneffective` ;
  - `noGrid_legacyUnchanged`, un test par méthode modifiée.
- [ ] **Étape 2 :** lancer les tests ciblés. Attendu : ÉCHEC.
- [ ] **Étape 3 :** implémenter.
- [ ] **Étape 4 :** relancer, ainsi que `PackageRequestServiceTest` et `NegotiationServiceTest`. Attendu : SUCCÈS.
- [ ] **Étape 5 :** `git commit -m "feat(commission): budgets, fils et devis des demandes selon la grille"`

### Task 12 : fiches de l'assistant, documentation, suite complète

**Files :**
- Modify : `src/main/resources/assistant/kb/fr/commission.md` et `src/main/resources/assistant/kb/en/commission.md`. Faire un grep `frais|commission|%` sur `kb/` et corriger toute fiche qui cite un taux.
- Create : `docs/stories-done/story-commission-paliers.md`, au format de `CLAUDE.md` du back.
- Test : `T/assistant/…` existant de chargement de la KB. Le relancer suffit.

**Contenu imposé des fiches (§5.6) :**
- Les frais de service Yadony dépendent du montant, avec un minimum de 1 € (650 FCFA).
- Ils sont affichés avant validation.
- Ils sont identiques quel que soit le moyen de paiement.
- Aucun pourcentage en dur.
- La phrase « l'app n'affiche pas de pourcentage » est conservée si elle reste vraie : l'app ne montre que des montants.

- [ ] **Étape 1 :** mettre à jour les fiches en français et en anglais, avec le même `id`.
- [ ] **Étape 2 :** `./mvnw test jacoco:report`, lancé seul, en arrière-plan avec `nohup`. Attendu : 0 test rouge et une couverture globale ≥ 90 %. Lire `target/site/jacoco/index.html`.
- [ ] **Étape 3 :** écrire la documentation de story, avec le pourcentage de couverture mesuré.
- [ ] **Étape 4 :** `git commit -m "docs(commission): fiches assistant et story des paliers"`
- [ ] **Étape 5 :** pousser la branche et ouvrir la PR back. Description : résumé, « aucun changement de prix tant qu'aucune grille n'est programmée », liste des contrats ajoutés pour les PR jumelles (admin, app, pro).

## Plans suivants (hors de ce plan)

Chacun cite la PR back et se fusionne après elle :
- **dony-admin** : onglet Tarification (§6).
- **dony_app** : portage Dart, vecteurs recopiés depuis `src/test/resources/commission/vectors.json`, remplacement des calculs locaux, affichage du minimum, `min_supported_build` (§7.2-7.3).
- **dony-pro** (§7.4).
- **yadony-web** : CGU (§7.5).
