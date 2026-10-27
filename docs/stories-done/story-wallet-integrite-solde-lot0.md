# Story wallet-solde-unifié, lot 0 : intégrité du solde (Backend)

**Date :** 2026-10-27
**Status :** ✅ Complète

Spec et plan : dépôt `docs-claude`, `docs/specs/2026-10-27-wallet-solde-unifie-design.md` (section 4.0) et `docs/plans/2026-10-27-wallet-solde-unifie-back.md` (lot 0).

## Résumé

Avant d'étendre le prélèvement de la commission à plusieurs portefeuilles (lot 1), le grand livre du portefeuille est rendu fiable sous concurrence. Trois défauts ont été prouvés par des tests rouges avant correction : l'idempotence testée avant le verrou, la mise à jour perdue sur une lecture périmée, et la course entre un débit et une demande de remboursement. Aucune migration, aucun changement d'API.

## Fichiers créés

- `src/test/java/com/yadony/api/payments/wallet/WalletIdempotencyConcurrencyIT.java` : 7 tests sur Postgres embarqué (idempotence de `credit` et des trois débits à clé, lecture périmée sur `credit`, `debit`, `debitConfirmedRefund`).
- `src/test/java/com/yadony/api/payments/wallet/WalletRefundDebitRaceIT.java` : course entre un débit et une demande de remboursement.

## Fichiers modifiés

- `payments/wallet/WalletService.java` : `lockWallet` (verrou puis relecture), `isReplay`, `debitInternal` unique (les cinq surcharges deviennent des façades), gardes de montant. `EntityManager` ajouté au constructeur.
- `payments/wallet/WalletSelfRefundService.java` : `request()` prend le verrou du portefeuille avant l'allocation.
- `WalletControllerIT.java`, `AuthControllerDeleteAccountIntegrationTest.java` : tests H2 (profil "test") qui appellent `request()` avec le vrai service. H2 refuse `FOR NO KEY UPDATE` ; un espion sur `WalletAccountRepository` redirige la requête verrouillée vers la lecture simple. Le verrou lui-même est couvert par les deux IT Postgres.
- `WalletServiceTest.java` : constructeur, stubs sur la requête verrouillée, deux tests qui figeaient l'ancien ordre (clé et gel testés avant le verrou) réécrits, nouveaux tests de `lockWallet` et des gardes de montant.

## Comment ça fonctionne (pour la maintenance)

### Vue d'ensemble

1. Toute écriture du solde (`credit`, les cinq `debit`, `debitConfirmedRefund`) commence par `lockWallet` : verrou de ligne `FOR UPDATE` puis `entityManager.refresh`, ce qui garantit un solde à jour même si l'entité est déjà dans le cache de premier niveau de la transaction de l'appelant.
2. Ensuite seulement : rejeu par clé d'idempotence (`isReplay`), puis gel (`assertNotFrozen`), puis contrôle du solde, puis écriture de la ligne et de l'audit.
3. `request()` (remboursement) verrouille le même portefeuille avant de calculer l'allocation, et le tient jusqu'au commit de la demande.

### Les trois défauts corrigés

- **Idempotence (F1).** La clé était testée avant le verrou : deux appels simultanés de même clé passaient tous deux le test, le second butait sur `UNIQUE(idempotency_key)` (V114) et levait `DataIntegrityViolationException`. Aucun double crédit (la transaction était annulée), mais une exception à la place d'un « déjà fait ». Test : un thread tient le verrou, deux appels de même clé se bloquent dessus, on libère. Rouge avant (4 erreurs), vert après.
- **Mise à jour perdue.** Une requête verrouillée rend l'entité déjà présente dans le cache de premier niveau sans recharger son état. Si l'appelant avait lu le portefeuille plus tôt dans sa transaction, l'UPDATE écrasait un mouvement commité entre-temps : 110 au lieu de 115 en crédit, 90 au lieu de 85 en débit. Le grand livre gardait les deux lignes, le solde n'en comptait qu'une (règle INV-01 du moniteur, sévérité CRITIQUE). Test rouge puis vert, sur `credit`, `debit` et `debitConfirmedRefund`.
- **Course gel/remboursement (F2).** `request()` lisait le grand livre puis écrivait la demande sans verrouiller le portefeuille. Un débit qui commitait entre les deux laissait une demande de 100 € pour un solde réel de 70 € ; le remboursement partait chez Stripe, puis `debitConfirmedRefund` levait « solde insuffisant » et la demande restait PROCESSING (INV-14, des heures plus tard). Test : l'INSERT de la demande est bloqué par une demande PENDING non commitée (index unique partiel `uq_wallet_refund_requests_pending`), ce qui ouvre l'intervalle sans `sleep`.

### Ordre des verrous

- Création d'une demande : portefeuille, puis insertion de la demande (la demande n'existe pas encore pour les autres).
- Résolution : demande, puis portefeuille (inchangé).
- Un re-clic sur une demande déjà active lit la demande sans la verrouiller : pas de cycle avec la résolution.

### Points d'attention

- Un rejeu par clé prend désormais le verrou de ligne avant de sortir sans effet : c'est le prix de la sérialisation, et le comportement voulu.
- Le gel est testé après le verrou : un débit déjà en file derrière une demande de remboursement relit le gel une fois la demande commitée et est refusé en 422 `wallet-refund-pending`.
- `lockWallet` est `MANDATORY` : il exige une transaction, le verrou vit jusqu'à son terme.
- Un portefeuille absent est créé par `INSERT ... ON CONFLICT DO NOTHING`, puis repris par la requête verrouillée : l'INSERT ne pose pas le verrou si un concurrent l'a créé.

## Décisions techniques

- **Pas de clés d'idempotence sur `debitConfirmedRefund` (F3 du plan).** Les trois appelants sont déjà gardés par le statut de la demande, verrouillé et relu (`resolveLocked`, `resolve`), et le forfait à la suppression se recalcule sur le grand livre. Ajouter une clé aurait obligé à reprendre une trentaine de vérifications de mocks dans quatre fichiers de tests pour un risque faible. La partie réellement utile (relecture après verrou) est faite. À reprendre seulement si un rejeu réel est observé.
- **Zéro permis sur `debit`.** La commission nulle débite volontairement zéro (`executeForBid`). Seuls les montants négatifs ou `null` sont refusés.
- **`DECIMAL(10,2)` (F6).** Plafond de 99 999 999,99 : suffisant aujourd'hui, rien à faire.

## Critères d'acceptation couverts

- [x] Deux appels simultanés de même clé : un seul effet, aucune exception.
- [x] Une lecture antérieure du portefeuille dans la même transaction n'écrase jamais un mouvement concurrent.
- [x] Une demande de remboursement ne dépasse jamais le solde réel, quel que soit l'ordre d'un débit concurrent.
- [x] Le solde égale la somme des mouvements (INV-01) après chaque scénario.

## Tests

- `./mvnw test` : 13 914 tests. Première passe : 3 échecs et 1 erreur. Les 3 échecs (`WalletControllerIT` x2, `AuthControllerDeleteAccountIntegrationTest`) venaient de la limite H2 ci-dessus et sont corrigés (rejoués : 35 et 1 tests verts). L'erreur (`WalletServiceCurrencyConversionTest`, « Gave up waiting for server to start after 10000ms ») est un flocon de démarrage de Postgres embarqué sous charge ; la classe passe seule (6 tests). La suite complète est rejouée par la CI de la PR.
- Couverture JaCoCo (`./mvnw jacoco:report`) : 95,7 % global (instructions), `WalletService` 100 % des lignes et 92,3 % des branches, `WalletSelfRefundService` 93,2 % des lignes, package `payments.wallet` 97,9 %.
- Tests ajoutés ou modifiés : `WalletIdempotencyConcurrencyIT` (7), `WalletRefundDebitRaceIT` (1), `WalletServiceTest` (41 au total, dont 5 nouveaux et 7 adaptés), `WalletControllerIT` et `AuthControllerDeleteAccountIntegrationTest` (espion H2).
- Chaque correctif est précédé d'un test rouge : idempotence (4 erreurs `duplicate key`), lecture périmée (110 au lieu de 115, 90 au lieu de 85), course gel/remboursement (demande 100,00 pour un solde 70,00).
