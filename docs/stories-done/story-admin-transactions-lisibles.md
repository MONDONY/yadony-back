# Story — Transactions admin lisibles (Backend)

**Date:** 2026-10-06
**Status:** ✅ Complète

## Résumé
La liste Transactions › Paiements du back-office ne montrait qu'un UUID de colis, vide pour
les paiements de négociation. La fiche ne disait ni qui paie, ni qui reçoit, ni ce qui s'est
passé. Le back fournit maintenant pour chaque paiement :
- le type (colis classique ou négociation), le colis résolu, l'expéditeur, le voyageur et le
  trajet ;
- les références Stripe, avec un lien direct vers le dashboard ;
- une recherche libre, le masquage des checkouts abandonnés, des totaux par devise, un export
  CSV et une chronologie.

## Fichiers créés
- `admin/AdminPaymentFilter.java` — filtres partagés par la liste, les totaux et l'export (même périmètre).
- `admin/AdminPaymentInsights.java` — recherche (SQL dynamique, un seul `WHERE`), totaux, lignes d'export, contexte de chaque paiement.
- `admin/AdminPaymentTimeline.java` — chronologie : dates du paiement + `audit_log` (`entity_type = 'PAYMENT'`), auteurs résolus.
- `admin/AdminPaymentCsv.java` — CSV de la liste filtrée.
- `admin/export/CsvWriter.java` — écriture CSV commune aux exports admin (BOM, guillemets, neutralisation des formules).
- `admin/dto/AdminPaymentInsight.java` — contexte renvoyé dans la liste et le détail.
- Tests : `AdminPaymentInsightsIT` (H2), `AdminPaymentInsightsPostgresIT` (mêmes scénarios sur Postgres embarqué + Flyway), `AdminPaymentTimelineTest`, `CsvWriterTest`, ajouts dans `AdminPaymentControllerTest`.

## Fichiers modifiés
- `admin/AdminPaymentController.java` :
  - nouveaux paramètres `q` et `hideAbandoned` sur la liste ;
  - nouveaux endpoints `/summary`, `/export` et `/{id}/timeline` ;
  - champ `insight` dans la liste et dans le détail.
- `admin/dto/AdminPaymentListItemResponse.java`, `AdminPaymentDetailResponse.java` — champ `insight` + `withInsight(...)`.
- `admin/export/AdminExportService.java` — utilise `CsvWriter`, qui neutralise désormais aussi les cellules commençant par `=`, `+`, `-` (hors nombres) et `@`.
- `common/AuditLogRepository.java` — `findTop200ByEntityTypeAndEntityIdOrderByCreatedAtAscIdAsc`.
- `payments/PaymentRepository.java` — suppression de `findAdminFiltered`, remplacé par `AdminPaymentInsights.search` (aucun autre appelant).
- Tests adaptés : `AdminPaymentControllerHoldTest`, `AdminPaymentHoldIT`, `AdminPaymentControllerMobileMoneyIT`, `PayoutHoldIntegrationTest`.

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble du flux
1. Le front envoie les filtres (statut, rail, devise, période, `held`, `q`, `hideAbandoned`).
2. `AdminPaymentFilter.of` les normalise : vide devient absent, et statut, rail et devise passent en majuscules.
3. `AdminPaymentInsights.where` construit un seul `WHERE` avec paramètres nommés. Il sert à la page (`search`), aux totaux (`totals`) et à l'export (`exportRows`).
4. `insightsOf` charge le contexte de tous les paiements de la page en deux requêtes :
   - une jointure pour les colis, négociations, demandes et annonces ;
   - une requête sur les noms des utilisateurs.

### Points d'entrée API (tous `PAYMENT_VIEW`)
- `GET /api/v1/admin/payments?q=&hideAbandoned=` — liste ; chaque ligne porte `insight`.
- `GET /api/v1/admin/payments/summary` — totaux par devise du périmètre filtré.
- `GET /api/v1/admin/payments/export` — CSV (en plus `EXPORT_RUN`, audité `EXPORT_RUN` type `payments`), 10 000 lignes max.
- `GET /api/v1/admin/payments/{id}/timeline` — chronologie.
- `GET /api/v1/admin/payments/{id}` — détail, avec `insight`.

### Logique métier critique
- **Paiement sans colis** : un paiement de négociation est rattaché à `negotiation_thread_id`. Son `bid_id` reste vide même une fois le colis créé, qui pointe vers le fil par `bids.linked_negotiation_thread_id`. `insight.bidId` donne le colis résolu. Avant la création du colis, les parties et le trajet viennent du fil et de sa demande (`package_requests`).
- **Recherche** :
  - un UUID est cherché parmi le paiement, le colis direct, le fil et le colis lié ;
  - `pi_…`, `ch_…` ou `mm_…` est cherché dans les références Stripe ;
  - tout autre texte est cherché dans le pseudo ou dans « prénom nom » de l'expéditeur ou du voyageur. Les caractères `%`, `_` et `@` sont retirés.
- **Checkout abandonné** : paiement `PENDING` depuis plus de 24 h. Aucun argent n'a bougé. Le front le masque par défaut.
- **Totaux** : une ligne par devise, on n'additionne jamais des EUR et des XOF.
  - « En séquestre » : montants `ESCROW`.
  - « Versé » : montants `RELEASED`.
  - « Commissions » : paiements en séquestre ou libérés.
- **Lien Stripe** : construit seulement pour le rail STRIPE et un `pi_…`. Il pointe vers `/test/` sauf si la clé secrète commence par `sk_live` ou `rk_live`.

### Events Spring publiés / écoutés
Aucun.

### Pièges et points d'attention
- Le SQL dynamique doit tourner sur H2 (tests) et sur Postgres (prod). `AdminPaymentInsightsPostgresIT` rejoue les scénarios sur Postgres : le lancer après toute modification du SQL.
- Les fragments en text block perdent leur indentation : toujours préfixer d'un espace explicite avant `AND`.
- Lecture seule : aucune méthode de `AdminPaymentInsights` ne modifie un paiement.

## Critères d'acceptation couverts
- [x] Un paiement de négociation sans `bid_id` affiche ses parties, son trajet et, s'il existe, son colis.
- [x] La fiche paiement montre les personnes et le colis, une chronologie, les liens Stripe et pawaPay et les montants détaillés.
- [x] Les checkouts abandonnés sont étiquetés et peuvent être masqués.
- [x] La liste offre une recherche, des colonnes utiles, des totaux et un export CSV.

## Tests
- Tests ciblés verts, sur H2 et sur Postgres embarqué.
- `mvn test` complet : voir la PR.

## Décisions techniques
- **SQL dynamique (NamedParameterJdbcTemplate) plutôt qu'une requête native `@Query`** : liste, totaux et export partagent le même `WHERE` sans le recopier trois fois, et les filtres absents disparaissent de la requête au lieu des `CAST(:x AS …) IS NULL`.
- **Contexte calculé à la lecture plutôt que de remplir `payments.bid_id`** : pas de migration de données sur une table d'argent. L'invariant « un paiement de négociation est rattaché au fil » reste intact.
