# Story admin — Réorganisation de la page Transactions (Backend)

**Date:** 2026-10-24
**Status:** ✅ Complète
**Spec:** `docs/superpowers/specs/2026-10-23-transactions-reorganisation-design.md` (dépôt dony-admin)
**PR jumelle:** dony-admin (page Transactions : vues à pastilles, recherche avec suggestions, tiroir de paiement avec liens). **À déployer AVANT la PR admin** : l'admin dépend des nouveaux champs et points d'entrée.

## Résumé
Les listes de la page Transactions du back-office (paiements, mobile money, portefeuilles et commissions cash, litiges bancaires) gagnent des vues avec nombres, une alerte unique par paiement, une recherche repliée (accents, casse) avec suggestions, des lignes enrichies (noms, trajet, n° de suivi, identifiants de destination) et les identifiants de liens qui permettent de rebondir vers le colis, le trajet, le litige, la conversation, l'alerte et le journal d'audit. Aucune migration, aucune nouvelle permission : tout est calculé en SQL natif (PostgreSQL et H2) à partir des tables existantes.

## Fichiers créés
- `admin/AdminPaymentSql.java` — SQL natif unique des paiements : prédicats bruts d'alerte, `ALERT_RANK`, vues, recherche, nombres, suggestions, jointures du contexte (`PAYMENT_CONTEXT_JOIN`, `LINKED_BID_CONDITION`), `ABANDONED_PREDICATE`, `MM_FAILED_OPERATION`.
- `admin/AdminPaymentView.java`, `AdminPaymentAlert.java` — vues (`ALL`, `ATTENTION`, `ESCROW`, `RELEASED_RECENT`, `REFUNDED`, `FAILED`) et alertes (`HELD`, `CHARGEBACK_OPEN`, `MM_FAILED`, `ESCROW_J48`).
- `admin/AdminPaymentLinks.java` — identifiants de liens et alertes ouvertes du détail, filtrés par permission.
- `admin/AdminFinanceListings.java`, `AdminFinanceSql.java` — lecture SQL natif des listes finance (mobile money, portefeuilles, commissions cash, litiges) et de leurs nombres.
- `admin/AdminFinanceCounts.java` — composant du `GET /admin/finance/counts`.
- `admin/AdminMobileMoneyView.java`, `AdminCashCommissionView.java`, `AdminChargebackView.java`, `AdminWalletBalanceSign.java` — énumérations des filtres.
- `admin/dto/AdminPaymentCountsResponse`, `AdminPaymentSuggestResponse`, `AdminMobileMoneyCountsResponse`, `AdminCashCommissionCountsResponse`, `AdminChargebackCountsResponse`, `AdminFinanceCountsResponse` — DTO des nombres et suggestions.
- Tests : `AdminPaymentViewsIT`, `AdminPaymentSearchIT`, `AdminPaymentCountsIT`, `AdminPaymentSuggestIT`, `AdminPaymentLinksIT` (+ `AdminPaymentLinksTestSupport`), `AdminMobileMoneyListingIT`, `AdminWalletsListingIT`, `AdminCashCommissionsListingIT`, `AdminChargebackListingIT`, `AdminFinanceCountsIT`, `AdminAuditEntityIdIT`, `AdminTransactionsConsistencyIT`, et la variante `*PostgresIT` de chacun (PostgreSQL embarqué + Flyway).

## Fichiers modifiés
(`git diff --stat origin/main...HEAD` : 81 fichiers, environ 7 600 ajouts)
- `admin/AdminPaymentController.java` — filtres `view`, `alert`, `partyId`, `announcementId`, `bidId`, `amountMin`, `amountMax` sur la liste, le résumé et l'export ; `/counts`, `/suggest` ; détail enrichi. Aucune logique : elle vit dans `AdminPaymentInsights`, `AdminPaymentSql`, `AdminPaymentLinks`.
- `admin/AdminPaymentInsights.java`, `AdminPaymentFilter.java` — filtre, `where()`, tri d'urgence, libellé d'alerte, nombres, suggestions.
- `admin/AdminFinanceController.java`, `AdminChargebackController.java` — vues, recherche, `/counts`, pagination bornée des litiges ; délégation à `AdminFinanceListings`.
- `admin/AdminAlertController.java`, `AdminAlertRepository.java` — `GET /admin/alerts/{id}`.
- `admin/AdminAuditController.java`, `common/AuditLogRepository.java` — paramètre `entityId`.
- `admin/AdminSearchTerms.java`, `AdminBidSql.java` — `fold`/`tokens`/`FOLD_OPEN`/`FOLD_CLOSE` rendus partagés (valeurs inchangées, page Colis intacte) ; `AdminBidDetailAssembler.java` — extraction du contrôle de permission réutilisé pour les liens.
- `admin/metrics/AdminQueueCounter.java` — `countPendingWalletRefunds()` public (même nombre que la pastille du menu).
- DTO existants (`AdminPaymentListItemResponse`, `AdminPaymentDetailResponse`, `AdminMobileMoneyResponse`, `AdminWalletResponse`, `AdminCashCommissionResponse`, `AdminChargebackResponse`) — champs ajoutés EN FIN, contrat existant inchangé.
- Code mort supprimé : `BidRepository.findCashCommissions`, `PawapayOperationRepository.findAllByOrderByCreatedAtDesc`, `ChargebackRepository.findAllByOrderByOpenedAtDesc`, `ChargebackService.listAll`, `ChargebackDto`, `AdminChargebackResponse.from(entity)` et leurs tests.
- Tests existants adaptés : `AdminFinanceControllerIT`, `AdminPaymentControllerTest`, `AdminPaymentControllerListIT`, `AdminPaymentFilterTest`, etc.

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble du flux
1. L'admin ouvre Transactions : le front lit `GET /admin/finance/counts` (pastilles des quatre sections) puis `GET /admin/payments/counts` et `GET /admin/payments?view=ATTENTION`.
2. Le contrôleur construit un `AdminPaymentFilter` typé (une valeur inconnue de `view` ou `alert` répond 400 par la conversion Spring), avec `now` pris sur le `Clock` injecté.
3. `AdminPaymentInsights` exécute le SQL natif de `AdminPaymentSql` ; liste, résumé, export et nombres partagent le même `where()`.
4. L'étiquette `alert` de chaque ligne vient du rang exclusif `AdminPaymentSql.ALERT_RANK` (le plus petit gagne) ; le même rang sert au tri de la vue `ATTENTION` et au nombre `attention`.
5. La saisie dans la barre de recherche appelle `GET /admin/payments/suggest?q=` (cinq propositions au plus par groupe).
6. Le tiroir charge `GET /admin/payments/{id}` : liens et alertes ouvertes sont filtrés par permission.

### Alerte unique
`ALERT_RANK` est composé des prédicats BRUTS de chaque alerte, définis une seule fois : 1 `HELD` (séquestre retenu), 2 `CHARGEBACK_OPEN` (litige bancaire ouvert sur le paiement, ou sans `payment_id` et sur le colis effectif), 3 `MM_FAILED` (opération PAYOUT/REFUND morte sans opération plus récente du même type non morte, `MM_FAILED_OPERATION`), 4 `ESCROW_J48` (séquestre créé il y a plus de 48 h). Le filtre `alert=` et la décomposition `alerts.*` des nombres utilisent le prédicat brut ; l'étiquette de ligne et `attention` le rang exclusif.

### Vues, filtres, tri
`ATTENTION` = rang > 0 ; `ESCROW` = statut `ESCROW` ; `RELEASED_RECENT` = `RELEASED` depuis moins de 72 h ; `REFUNDED` ; `FAILED` = `FAILED` ou `CANCELLED` ; `ALL`. Tri `ATTENTION` : rang, puis `created_at` croissant, puis `id` ; autres vues : `created_at` décroissant puis `id` décroissant. Filtres : `partyId` (expéditeur OU voyageur), `announcementId`, `bidId` (colis direct ou lié au fil), `amountMin`/`amountMax` (centimes, bornes incluses), plus les filtres historiques.

### Recherche
Texte replié (`AdminSearchTerms.fold`, identique en Java et en SQL), au plus quatre jetons combinés en ET, sur noms, pseudos, n° de suivi, villes ; UUID exact de paiement, colis, négociation, annonce, litige, opération ; références Stripe/pawaPay exactes. Une saisie qui ne contient que `%`, `_`, `'` ou `@` devient `NO_MATCH_TOKEN` : liste vide, jamais « tout ». **Aucune recherche par numéro de téléphone** (ni `msisdn` chiffré, ni `msisdn_masked`).

### Nombres
`GET /admin/payments/counts` : mêmes filtres que la liste sauf `view`, `alert`, `status`, `page`, `size`. Chaque nombre égale la taille de la liste de la vue. `abandoned` = checkouts `PENDING` de plus de 24 h dans le périmètre, comptés MÊME quand `hideAbandoned` les masque (c'est le nombre du lien « les afficher ») ; `all` et les autres colonnes respectent `hideAbandoned`. `GET /admin/finance/counts` : `payments = paymentsAttention`, `mobileMoney = mobileMoneyAttention`, `soldes = walletRefundsPending + cashCommissionsAttention`, `chargebacks = chargebacksOpen`, avec le détail.

### Suggestions
`exactPaymentId` renseigné quand `q` désigne UN seul paiement (UUID de paiement, colis, négociation, opération pawaPay, annonce, litige bancaire, ou référence exacte) ; ambigu = absent. Groupes expéditeurs, voyageurs, trajets, colis ; comptes supprimés exclus.

### Détail : liens et permissions
`links.disputeId/disputeStatus/cancellationId` avec `DISPUTE_VIEW` ; `links.conversationId` avec `MODERATION_VIEW` ; `links.announcementId/packageRequestId` avec `BID_VIEW` ; `links.chargebackId` et `chargeback` avec `PAYMENT_VIEW` ; `openAlerts` avec `ALERT_VIEW`. Sans la permission le champ est ABSENT (Jackson `NON_NULL`), jamais `null`. `openAlerts` rattache une alerte au paiement par le payload ou par l'UUID dans `admin_alerts.type` (`RECON_STRIPE_<id>`, `ESCROW_CAPTURE_FAILED_<id>`, `PAYOUT_HELD_<id>`).

### Listes finance
- Mobile money : vues `FAILED`/`OPEN`/`DONE`, `kind`, `provider`, `q` ; personne (expéditeur, voyageur ou titulaire du solde), colis, n° de suivi, trajet, demande de remboursement de solde, `retryable` (miroir des gardes de relance, pour ne jamais proposer une relance que l'API refuserait).
- Portefeuilles : `q` (nom, prénom, pseudo ou UUID du titulaire), `currency`, `balanceSign`, `partyId` ; montants en centimes, une ligne de totaux par devise.
- Commissions cash : `view=ATTENTION` (`FAILED`, `REQUIRES_3DS`, `REFUND_FAILED`), `status`, `q`, `partyId` (voyageur).
- Litiges bancaires : `view` `OPEN`/`WON`/`LOST`, `q` ; paiement, colis, trajet, expéditeur, voyageur, issue.

### Audit
`GET /admin/audit-log?entityId=<uuid>` filtre sur `audit_log.entity_id` (400 si l'UUID est mal formé) : le tiroir affiche le journal du paiement.

### Points d'entrée API
Permissions : `PAYMENT_VIEW` partout sauf indication.
- `GET /admin/payments` (liste ; `view`, `alert`, `partyId`, `announcementId`, `bidId`, `amountMin`, `amountMax`, `hideAbandoned`, `q`...), `/summary`, `/counts`, `/suggest`, `/{id}`, `/{id}/timeline`.
- `GET /admin/payments/export` — `PAYMENT_VIEW` + `EXPORT_RUN`, mêmes filtres.
- `GET /admin/mobile-money-payments` et `/counts`.
- `GET /admin/wallets`.
- `GET /admin/cash-commissions` et `/counts`.
- `GET /admin/chargebacks` et `/counts`.
- `GET /admin/finance/counts`.
- `GET /admin/alerts/{id}` — `ALERT_VIEW` (404 `alert-not-found`).
- `GET /admin/audit-log?entityId=` — `AUDIT_VIEW`.

### Entités JPA impliquées
`PaymentEntity` (`payments`), `PawapayOperationEntity` (`pawapay_operations`, `msisdn` chiffré, `msisdn_masked`), `ChargebackEntity`, `BidEntity` (commission cash), wallet (`wallet_accounts`), `AdminAlert` (`admin_alerts`), `audit_log`. Lecture seule : aucune écriture nouvelle, aucun geste modifié.

### Events Spring publiés / écoutés
Aucun.

## Pièges et points d'attention
- **Jointure `lb`** : un paiement de fil de négociation (`p.bid_id IS NULL`) retrouve son colis via `bids.linked_negotiation_thread_id`. Sur PostgreSQL l'index unique partiel `idx_bids_linked_thread` (V66) garantit un seul colis par fil ; il est ABSENT du schéma H2 des tests. Toute jointure sur `lb` doit donc rester sans doublon par construction (sous-requête `ORDER BY lb.id LIMIT 1` pour les litiges) et le doublon n'est prouvé que par les `*PostgresIT`.
- **Numéro de téléphone** : `msisdn` est chiffré, `msisdn_masked` vaut `+221 •••• 67`. Aucune réponse ni recherche de la page ne doit exposer le numéro en clair (`AdminTransactionsConsistencyIT` le vérifie sur toutes les réponses).
- **Les trois nombres « séquestre de plus de 48 h »** sont égaux sur le même jeu : la carte `escrowJ48` de la vue d'ensemble (`AdminMetricsService.countEscrowJ48`), l'alerte `ESCROW_J48_TIMEOUT` et `alerts.escrowJ48`. Même critère : statut `ESCROW`, `created_at < now - 48 h`, non supprimé. Ne jamais en changer un seul.
- **`alerts.*` se recoupent** alors que l'étiquette de ligne est exclusive : un séquestre retenu de plus de 48 h compte dans `held` ET `escrowJ48` mais ne porte que `HELD`. Les quatre `alerts.*` peuvent donc sommer plus que `attention`. De même, `attention` du mobile money compte PAR OPÉRATION et peut dépasser `alerts.mmFailed`.
- **Pagination des litiges** désormais bornée (`size` entre 1 et 100) : avant, aucune borne. `bidId` d'un litige est maintenant renseigné via le paiement.
- **Méthodes de dépôt retirées** (voir ci-dessus) : tout nouvel appelant doit passer par `AdminFinanceListings`.
- **Le front doit relire le paiement après un geste** (force-release, remboursement, relance, retenue) : les gestes renvoient l'ancien détail, sans `alert`, `links` ni `openAlerts` recalculés.
- **Règle `cancellationId` différente** : la fiche paiement exige `DISPUTE_VIEW`, la fiche colis (`AdminBidDetailAssembler.extras`) renvoie `links.cancellationId` à tout admin `BID_VIEW`. Pré-existant, hors périmètre : **à trancher par le propriétaire**.
- **Opérations de supplément** : un `PAYOUT` de `BID_SUPPLEMENT` est rattaché au voyageur du colis (rôle `TRAVELER`), `DEPOSIT`/`REFUND` à l'expéditeur.
- **Tests sur H2 partagée** : les données de test utilisent des jetons ALÉATOIRES EN LETTRES SEULEMENT, sinon une recherche numérique (ex. « 67 ») trouve des UUID par hasard.
- Fiches de l'assistant IA : rien à changer (aucun parcours visible dans l'app mobile).

## Critères d'acceptation couverts
- [x] Les vues et l'alerte unique sont calculées une fois en SQL et partagées par liste, nombres, tri, étiquette et carte `escrowJ48`.
- [x] Chaque nombre égale la taille de la liste de sa vue (`AdminPaymentCountsIT`, `AdminTransactionsConsistencyIT`).
- [x] Recherche accentuée/insensible à la casse, saisie `%`/`'`/`_`/`@` seule = liste vide, aucune recherche par téléphone.
- [x] Un fil de négociation avec colis lié ne duplique pas le paiement (liste, nombres, export) ; un compte supprimé garde ses paiements et sort des suggestions.
- [x] Aucun identifiant de lien sans la permission de la destination (champ absent).
- [x] Les trois nombres « séquestre > 48 h » sont égaux.
- [x] Aucune réponse de la page ne contient un numéro de téléphone en clair.
- [x] Le SQL tourne sur PostgreSQL réel (`*PostgresIT`) et sur H2.

## Tests
- `./mvnw verify -DforkCount=2` → 13 160 tests, 0 échec, 0 erreur, 7 ignorés (BUILD SUCCESS) ; SpotBugs : aucun défaut (spotbugs:check)
- JaCoCo : 96,32 % des lignes (52 849 / 54 870), relevé sur target/site/jacoco/jacoco.csv
- SpotBugs : 0.
- Tests ajoutés : voir « Fichiers créés » ; `AdminTransactionsConsistencyIT` (+ `PostgresIT`) vérifie sur UN jeu de données la cohérence des nombres (liste, comptes, section du menu, carte `escrowJ48` de la vue d'ensemble), la bonne vue de chaque état de paiement et l'absence de téléphone en clair (liste, détail, chronologie, suggestions, export, résumé, mobile money, portefeuilles, commissions cash, litiges). Mutation de contrôle : une attente fausse sur `attention` fait échouer le test.

## Décisions techniques
- **Prédicat brut pour `alerts.*` et `alert=`, rang exclusif pour l'étiquette, le tri et `attention`** : exigence du spec que carte `escrowJ48` = alerte `ESCROW_J48_TIMEOUT` = `alerts.escrowJ48`. Coût : les `alerts.*` se recoupent.
- **`exactPaymentId` accepte aussi l'id d'une annonce ou d'un litige bancaire** quand il ne désigne qu'un paiement (plus large que le brief, cohérent avec la recherche en liste ; ambigu = absent).
- **`openAlerts` lit le payload ou l'UUID dans `type`** (convention `AdminAlertResponse.PAYMENT_SUFFIXED`) pour ne pas manquer d'alertes sans payload.
- **Supplément `PAYOUT` → voyageur** plutôt qu'expéditeur (afficher l'expéditeur sur un versement serait faux).
- **`retryable` plus strict que le statut** : reprend les gardes de `retryMobileMoneyPayout/Refund` pour ne jamais proposer une relance refusée (422).
- **`mobileMoney(...)` sans paramètre `now`** : aucune règle du mobile money ne dépend de l'heure.
- **Pas de recherche par téléphone** : numéro chiffré, et le masqué n'identifie personne.
- **Suppression du code mort hors liste du brief** (`ChargebackService.listAll`, `ChargebackDto`, `AdminChargebackResponse.from`) : règle 22 du CLAUDE.md.
- **Mineurs reportés** (non bloquants) : `AMOUNT_NOISE` retire tout `f` (« 1f2 » = 12 €), règle `NO_MATCH` dupliquée entre `AdminBidQuery` et `appendSearch`, `amountMin > amountMax` non validé, `from(op)`/`Context.NONE` utilisés seulement par des tests, requête `admin_alerts` sans index partiel, cinq requêtes par frappe de suggestion.
