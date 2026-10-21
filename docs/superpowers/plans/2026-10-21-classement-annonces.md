# Classement des annonces Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Classer les résultats de `GET /announcements` en deux blocs (exact / même trajet mais critères non respectés) ordonnés par un score interne caché, et retirer `senderIncidentCount` des DTO publics.

**Architecture:** Le filtrage exact reste en SQL (Specifications existantes). Les villes et les filtres `kycVerifiedOnly` / `maxPricePerKg` forment le socle strict. Le score se calcule en mémoire sur les annonces exactes (candidats plafonnés), via un calculateur pur. Le bloc 2 se calcule seulement si le total exact est < 7 : socle strict moins annonces exactes, avec la liste des critères non respectés.

**Tech Stack:** Spring Boot 3.4, Java 21, JPA Specifications, Flyway, JUnit 5 + Mockito + MockMvc, Caffeine.

**Spec:** `docs/superpowers/specs/2026-10-21-classement-annonces-design.md`

Plan **back uniquement**. L'app (deux blocs, messages, suppression de l'affichage de `senderIncidentCount`) a son propre plan une fois le contrat fixé par la PR back.

## Global Constraints

- Branche dédiée `feature/classement-annonces` dans un worktree `dony-back/.claude/worktrees/classement-annonces`, créé depuis `origin/main` à jour. Jamais de commit sur `main`. Pas de ligne `Co-Authored-By`.
- Migration Flyway : `V(n+1)` où n = plus haute version de `origin/main` au moment du travail (`ls src/main/resources/db/migration | sort -V | tail`). Ne jamais modifier une migration existante.
- Le score n'apparaît dans aucun DTO, aucun log, aucun événement d'analytics.
- Seuil du bloc 2 : strictement moins de 7 annonces exactes (total). Bloc 2 plafonné à 20, première page seulement.
- Poids de départ : fiabilité 30, note 25, envois 20, fraîcheur/départ 15, paiement en ligne 10 (ramenés à 100). Tous réglables par propriété `yadony.ranking.*`.
- Villes : égalité insensible à la casse (comportement actuel de `hasDepartureCity` / `hasArrivalCity`), sans pays.
- Filtres stricts : `kycVerifiedOnly`, `maxPricePerKg`. Souples : dates, `minAvailableKg`/`maxAvailableKg`, `transportMode`, `minRating`, `contentType`, rayon (`userLat`/`userLng`/`radiusKm`), `weekendOnly`, `urgent`, `kiloProOnly`.
- Tri `sortBy` : `score` (nouveau défaut), `date`, `price`. Le tri manuel remplace le score à l'intérieur de chaque bloc. Le tri « pro d'abord » (`travelerIsPro`) disparaît.
- Tests : `./mvnw test` vert, couverture ≥ 90 %, un seul `mvnw` à la fois.

## Review Focus

- Aucune ville fournie : pas de bloc 2, candidats plafonnés (`maxCandidates`), pas d'erreur.
- Exactement 6 puis 7 annonces exactes : bloc 2 présent à 6, absent à 7.
- Voyageur sans note (`averageRating` null) et sans historique : score défini, pas de NPE.
- Page ≥ 1 : jamais de bloc 2 ; bloc 2 exclut annonces non ACTIVE, délai de remise dépassé, voyageurs bloqués par le viewer.
- Fuite du score : aucun champ/clé JSON contenant `score` ou `reliab` dans la réponse de recherche.
- Tri manuel `price` : blocs toujours séparés, ordre prix croissant dans chacun.
- Cache `announcements-search` : deux viewers différents ne partagent pas leurs résultats (clé déjà par `viewerFirebaseUid`).

---

### Task 1: Propriétés de classement

**Files:**
- Create: `src/main/java/com/yadony/api/matching/ranking/TripRankingProperties.java`
- Modify: `src/main/resources/application.yml` (bloc `yadony.ranking`)
- Test: `src/test/java/com/yadony/api/matching/ranking/TripRankingPropertiesTest.java`

**Interfaces:**
- Produces: `record TripRankingProperties(Weights weights, int exactThreshold, int similarMax, int maxCandidates)` annoté `@ConfigurationProperties("yadony.ranking")`, avec `record Weights(int reliability, int rating, int deliveries, int freshness, int onlinePayment)`. Défauts : 30/25/20/15/10, seuil 7, similarMax 20, maxCandidates 1000. Méthode `double weightSum()`.

- [ ] **Step 1: Test** `defaults_matchSpec` : bind sans propriété → poids 30/25/20/15/10, `exactThreshold()==7`, `similarMax()==20`, `maxCandidates()==1000`, `weightSum()==100.0`.
- [ ] **Step 2: Run** `./mvnw test -Dtest=TripRankingPropertiesTest` → FAIL (classe absente).
- [ ] **Step 3: Implement** le record avec constructeur à valeurs par défaut (`@DefaultValue`), l'enregistrer comme les autres (`DeliveryNoShowProperties` est le modèle d'enregistrement), ajouter le bloc yml.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(matching): propriétés du classement des annonces`.

### Task 2: Compteur d'envois réussis

**Files:**
- Create: `src/main/resources/db/migration/V<n+1>__users_total_deliveries.sql`
- Modify: `src/main/java/com/yadony/api/auth/UserEntity.java`, `src/main/java/com/yadony/api/matching/BidEntity.java`, `src/main/java/com/yadony/api/auth/TravelerStatsListener.java`
- Test: `src/test/java/com/yadony/api/auth/TravelerStatsListenerTest.java` (étendre), test de migration selon le modèle des autres tests Flyway du repo.

**Interfaces:**
- Produces: `UserEntity.getTotalDeliveries(): int` (colonne `users.total_deliveries INT NOT NULL DEFAULT 0`), `BidEntity.isDeliveryCounted(): boolean` (colonne `bids.delivery_counted BOOLEAN NOT NULL DEFAULT FALSE`).

- [ ] **Step 1: Tests** dans `TravelerStatsListenerTest` : `onDeliveryConfirmed_incrementsTotalDeliveries_once` (deux événements pour le même bid COMPLETED → `totalDeliveries == 1`, `deliveryCounted == true`) ; `onDeliveryConfirmed_notCompleted_noIncrement`.
- [ ] **Step 2: Run** `./mvnw test -Dtest=TravelerStatsListenerTest` → FAIL.
- [ ] **Step 3: Migration** : ajoute les deux colonnes, puis rattrapage : pour chaque bid `COMPLETED` non supprimé, `delivery_counted = TRUE` et `users.total_deliveries` = nombre de bids COMPLETED des annonces du voyageur (jointure `bids.announcement_id → announcements.traveler_id`). Vérifier les noms réels de colonnes dans les migrations V3 et suivantes.
- [ ] **Step 4: Implement** : champs + accesseurs ; dans `onDeliveryConfirmed`, après la garde COMPLETED, si `!bid.isDeliveryCounted()` alors incrémenter le voyageur de l'annonce et marquer le bid, dans la transaction `REQUIRES_NEW` existante (le listener garde son idempotence propre, indépendante de `total_trips_counted`).
- [ ] **Step 5: Run** les tests du listener + la suite migration → PASS.
- [ ] **Step 6: Commit** `feat(auth): compteur d'envois réussis du voyageur`.

### Task 3: Calculateur de score

**Files:**
- Create: `src/main/java/com/yadony/api/matching/ranking/TripScoreCalculator.java`
- Test: `src/test/java/com/yadony/api/matching/ranking/TripScoreCalculatorTest.java`

**Interfaces:**
- Consumes: `TripRankingProperties.Weights`.
- Produces: `record TravelerQuality(int cancellations, int noShows, int deliveries, BigDecimal averageRating /*nullable*/, int ratingCount)`, et `double score(TravelerQuality q, boolean onlinePayment, long daysToDeparture, long ageDays)` retournant 0 à 100 (méthode d'instance, `TripScoreCalculator(TripRankingProperties props)`).

Formules (spec) : `reliability = (d + 4) / (d + 5 + 4 × (cancellations + 2 × noShows))` ; `rating = clamp(((avg × n + 4 × 5) / (n + 5) − 3) / 2)` avec `avg` null traité comme 0 pour n = 0 ; `deliveries = min(1, ln(1 + d) / ln(51))` ; `freshness = 0,5 × clamp(1 − daysToDeparture/30) + 0,5 × clamp(1 − ageDays/30)` ; `onlinePayment = 1 ou 0`. Score = Σ poids × composante / Σ poids × 100.

- [ ] **Step 1: Tests** (valeurs de la simulation validée, tolérance ±1) : `veteran_awa_scoresHigh` (0 incident, 4,9 sur 60 avis, 45 envois, paiement en ligne, J+5, 2 j → entre 85 et 95) ; `fiveStarOneReview_belowVeteran` (5,0 sur 1 avis, 1 envoi < Awa) ; `newcomer_noHistory_isNeutral` (0/0/0 avis null → fiabilité 0,8, pas d'exception) ; `threeCancellations_dropsBelowNewcomerOnReliability` ; `cashOnly_losesExactlyOnlinePaymentWeight` (même voyageur, paiement 1 vs 0 → écart = poids paiement / somme × 100) ; `weightsZeroSum_returnsZero`.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** la classe pure sans accès base.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(matching): calculateur du score de classement`.

### Task 4: Critères non respectés

**Files:**
- Create: `src/main/java/com/yadony/api/matching/ranking/SearchCriteria.java`, `UnmetCriterion.java`, `SearchCriteriaMatcher.java`
- Test: `src/test/java/com/yadony/api/matching/ranking/SearchCriteriaMatcherTest.java`

**Interfaces:**
- Produces: `record SearchCriteria(LocalDate dateFrom, LocalDate dateTo, BigDecimal minAvailableKg, BigDecimal maxAvailableKg, String transportMode, BigDecimal minRating, String contentType, Set<UUID> idsInRadius /*null = pas de filtre rayon*/, boolean weekendOnly, boolean urgent, LocalDate urgentTo, boolean kiloProOnly)` ; `record UnmetCriterion(String code, String requested, String actual)` avec codes : `DATE`, `AVAILABLE_KG`, `TRANSPORT_MODE`, `MIN_RATING`, `CONTENT_TYPE`, `RADIUS`, `WEEKEND_ONLY`, `URGENT`, `KILO_PRO_ONLY` ; `List<UnmetCriterion> SearchCriteriaMatcher.unmet(AnnouncementEntity a, BigDecimal travelerAverageRating, SearchCriteria c)` (liste vide = exacte).

- [ ] **Step 1: Tests** : un test par code (annonce du 12/11 pour fenêtre 10/11 → `DATE` avec `requested` « 2026-11-10 » et `actual` « 2026-11-12 » ; 5 kg pour 8 demandés → `AVAILABLE_KG`), `exactAnnouncement_returnsEmpty`, `multipleMisses_returnsAll`, `nullTravelerRating_withMinRating_isUnmet`.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** en reprenant la sémantique des Specifications de `AnnouncementSpecification` (même comparaison de bornes, même définition de week-end et de `contentType`).
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(matching): détection des critères de recherche non respectés`.

### Task 5: Recherche en deux blocs

**Files:**
- Modify: `src/main/java/com/yadony/api/matching/AnnouncementService.java` (`searchAnnouncements`, `buildSort`), `src/main/java/com/yadony/api/matching/AnnouncementController.java` (`searchAnnouncements`, défaut `sortBy`), `src/main/java/com/yadony/api/matching/AnnouncementRepository.java` si une requête de lot est nécessaire
- Create: `src/main/java/com/yadony/api/matching/dto/AnnouncementSearchPageResponse.java`, `SimilarAnnouncementResponse.java`, `UnmetCriterionDto.java`
- Test: `src/test/java/com/yadony/api/matching/AnnouncementSearchRankingTest.java` (unitaire), `AnnouncementSearchRankingIT.java` (MockMvc), ajuster `AnnouncementProPriorityTest`, `AnnouncementServiceTest`, `AnnouncementControllerIntegrationTest`

**Interfaces:**
- Consumes: `TripRankingProperties`, `TripScoreCalculator`, `SearchCriteriaMatcher`, `UserEntity.getTotalDeliveries()`.
- Produces: `record AnnouncementSearchPageResponse(List<AnnouncementSearchResponse> content, int page, int size, long totalElements, int totalPages, boolean last, List<SimilarAnnouncementResponse> similarAnnouncements)` (mêmes noms que `PageResponse` pour rester lisible par l'ancienne app) ; `record SimilarAnnouncementResponse(AnnouncementSearchResponse announcement, List<UnmetCriterionDto> unmet)` ; `record UnmetCriterionDto(String code, String requested, String actual)`. `searchAnnouncements(...)` renvoie `AnnouncementSearchPageResponse` (même liste de paramètres). `sortBy` défaut `score`.

Algorithme (décisions du plan) : socle = statut ACTIVE, `publicOrOpenSurplus`, délai de remise, non bloqué, villes, `kycVerifiedOnly`, `maxPricePerKg`. Exactes = socle + filtres souples via les Specifications existantes. Total exact par `count`. `sortBy=score` : charger au plus `maxCandidates` exactes triées par date de départ, charger les voyageurs en une requête (`findAllById`), calculer le score, trier décroissant (départage par id), découper la page demandée. `date`/`price` : tri SQL existant sans `travelerIsPro`. Bloc 2 : seulement si `page == 0`, au moins une ville fournie, et total exact < `exactThreshold` : socle moins exactes (jusqu'à `maxCandidates`), `unmet` via le matcher, ordre = moins d'écarts d'abord puis score (ou tri manuel choisi, écarts d'abord), plafonné à `similarMax`.

- [ ] **Step 1: Tests unitaires** (`AnnouncementSearchRankingTest`) : `exactSortedByScore_notByDate` ; `sixExact_returnsSimilarBlock` ; `sevenExact_noSimilarBlock` ; `similarBlock_onlyOnFirstPage` ; `similarBlock_cappedAt20` ; `similarBlock_orderedByFewerMisses` ; `noCity_noSimilarBlock` ; `kycVerifiedOnly_staysStrict` ; `maxPricePerKg_staysStrict` ; `manualPriceSort_keepsBlocksSeparated` ; `similarBlock_excludesBlockedTravelers` ; `travelerWithoutRating_noNpe`.
- [ ] **Step 2: Test d'intégration** (`AnnouncementSearchRankingIT`) : `GET /announcements?departureCity=Paris&arrivalCity=Dakar&departureDateFrom=...` renvoie `similarAnnouncements[0].unmet[0].code == "DATE"` ; `jsonPath` sur toute la réponse : aucune clé contenant `score`, `reliab`, `cancellationCount`, `noShowCount` ; le contrat reste lisible (`content`, `totalElements` présents).
- [ ] **Step 3: Run** `./mvnw test -Dtest='AnnouncementSearchRanking*'` → FAIL.
- [ ] **Step 4: Implement** `searchAnnouncements` et `buildSort` selon l'algorithme ci-dessus ; mapper le bloc 2 avec `mapAnnouncements` existant ; le contrôleur renvoie directement l'enveloppe ; conserver la clé de cache existante (elle contient déjà tri, page et viewer).
- [ ] **Step 5: Mettre à jour** `AnnouncementProPriorityTest` (le tri pro d'abord n'existe plus : remplacer par un test vérifiant que `travelerIsPro` ne devance plus à score inférieur) et les tests existants cassés par le nouveau type de retour.
- [ ] **Step 6: Run** `./mvnw test` → PASS complet.
- [ ] **Step 7: Commit** `feat(matching): recherche d'annonces en deux blocs classés par score`.

### Task 6: Retrait de senderIncidentCount des DTO publics

**Files:**
- Modify: `src/main/java/com/yadony/api/auth/dto/ProfilePublicResponse.java`, `auth/ProfilePublicService.java:116`, `matching/dto/BidResponse.java:132`, `matching/BidService.java:1671`, `requests/dto/PackageRequestResponse.java:65`, `requests/service/PackageRequestService.java:1138`, `requests/service/PackageRequestSearchMapper.java:114`, `requests/dto/PackageRequestSearchResponse.java`
- Test: `ProfilePublicServiceTest:222`, `PackageRequestServiceTest:692,2084`, `CancellationReliabilityIT:141,152`, `GuestDataExposureIT:234`

**Interfaces:**
- Produces: les DTO ci-dessus sans le champ `senderIncidentCount`. `UserEntity.senderReliabilityIncidentCount()` reste (signal interne).

- [ ] **Step 1: Tests** : dans `GuestDataExposureIT` et `ProfilePublicServiceTest`, remplacer les assertions positives par `jsonPath("$.senderIncidentCount").doesNotExist()` pour le profil public, les bids et les demandes ; `CancellationReliabilityIT` : vérifier que l'incident est toujours compté en base (`sender.senderReliabilityIncidentCount()`) mais plus exposé.
- [ ] **Step 2: Run** `./mvnw test -Dtest='ProfilePublicServiceTest,CancellationReliabilityIT,GuestDataExposureIT,PackageRequestServiceTest'` → FAIL.
- [ ] **Step 3: Implement** : retirer le champ et son alimentation dans chaque DTO et service ; supprimer le constructeur de compatibilité de `PackageRequestResponse` devenu inutile ; `grep -rn senderIncidentCount src/main` ne doit plus rien renvoyer.
- [ ] **Step 4: Run** la suite complète → PASS.
- [ ] **Step 5: Commit** `feat(auth): retirer senderIncidentCount des profils et DTO publics`.

### Task 7: Fiches de l'assistant

**Files:**
- Modify: fiches concernées dans `src/main/resources/assistant/kb/fr/*.md` et `kb/en/*.md` (même `id`)

- [ ] **Step 1:** `grep -il "recherche\|fiabilit\|incident" src/main/resources/assistant/kb/fr/*.md` ; pour chaque fiche qui décrit la recherche d'annonces ou l'affichage de la fiabilité, réécrire : les autres annonces du même trajet sont proposées avec un message quand moins de 7 correspondent exactement ; plus d'affichage de la fiabilité expéditeur.
- [ ] **Step 2:** Respecter la limite de 2 000 caractères par fiche, gardes `routes` inchangées sauf route réellement supprimée.
- [ ] **Step 3:** Lancer les tests des fiches (`./mvnw test -Dtest='*Assistant*,*KnowledgeBase*'`) → PASS.
- [ ] **Step 4: Commit** `docs(assistant): fiches recherche en deux blocs`.

### Task 8: Vérification finale et PR

- [ ] **Step 1:** `./mvnw test jacoco:report` ; couverture globale ≥ 90 % et chaque classe nouvelle couverte.
- [ ] **Step 2:** `git diff origin/main...HEAD --stat` : seuls les fichiers de ce plan.
- [ ] **Step 3:** Ouvrir la PR back (titre `Classement des annonces en deux blocs`), description : contrat ajouté (`similarAnnouncements`, défaut `sortBy=score`), retrait de `senderIncidentCount`, migration, rattrapage ; mentionner que la PR app jumelle suivra et se fusionne après celle-ci. Appliquer le flux `ccd_pr` du projet après l'ouverture.
