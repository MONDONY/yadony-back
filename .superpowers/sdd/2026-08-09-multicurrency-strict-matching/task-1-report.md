# Task 1 — Migrations colonnes devise + contrainte wallet

Date: 2026-08-09  
Worktree: `/Users/aboubakardiakite/Desktop/dony/.worktrees/dony-back-multicurrency`  
Branche: `feature/multicurrency-stripe-back`

## Résultat

Task 1 implémentée en TDD strict RED → GREEN, sans modifier de migration existante.  
`V196` a été laissée intacte.  
Créées uniquement:

- `src/main/resources/db/migration/V197__announcement_package_request_currency.sql`
- `src/main/resources/db/migration/V198__bid_negotiation_wallet_tx_currency.sql`
- `src/main/resources/db/migration/V199__wallet_accounts_per_currency.sql`
- `src/test/java/com/yadony/api/migrations/MultiCurrencySchemaMigrationTest.java`

## RED

### Test écrit avant tout SQL de production

Fichier ajouté d’abord:

- `src/test/java/com/yadony/api/migrations/MultiCurrencySchemaMigrationTest.java`

Objectif du test:

- échouer tant que `V197/V198/V199` n’existent pas ;
- verrouiller le contrat exact:
  - colonnes `currency VARCHAR(3) NOT NULL DEFAULT 'EUR'`
  - 7 devises exactes: `EUR, USD, CAD, GBP, CHF, XOF, XAF`
  - remplacement de l’unicité wallet par `UNIQUE(user_id, currency)`
  - usage du vrai nom de contrainte existante `wallet_accounts_user_id_unique`

### Commande RED

```bash
./mvnw -Dtest=MultiCurrencySchemaMigrationTest test
```

### Sortie pertinente RED

```text
org.flywaydb.core.api.FlywayException: No migration with a target version 199 could be found.
Ensure target is specified correctly and the migration exists.
```

### Raison de l’échec

Échec attendu: `V197`, `V198` et `V199` n’existaient pas encore, donc Flyway ne pouvait pas migrer jusqu’à `199`.

## GREEN

### Migrations créées

#### `V197__announcement_package_request_currency.sql`

- ajoute `currency` à `announcements`
- ajoute `currency` à `package_requests`
- `NOT NULL DEFAULT 'EUR'`
- check constraint exact sur `EUR, USD, CAD, GBP, CHF, XOF, XAF`

#### `V198__bid_negotiation_wallet_tx_currency.sql`

- ajoute `currency` à `bids`
- ajoute `currency` à `negotiation_threads`
- ajoute `currency` à `wallet_transactions`
- `NOT NULL DEFAULT 'EUR'`
- check constraint exact sur `EUR, USD, CAD, GBP, CHF, XOF, XAF`

#### `V199__wallet_accounts_per_currency.sql`

- drop de la contrainte existante réelle:
  - `wallet_accounts_user_id_unique`
- ajout de:
  - `UNIQUE (user_id, currency)`

### Ajustement test GREEN

Le premier run GREEN a révélé un défaut du test lui-même: j’exigeais à tort la liste des devises dans `V199`.  
Le test a été corrigé sans réduire les assertions métier:

- `V197/V198`: devises exactes toujours verrouillées
- `V199`: verrouillage sur le drop de `wallet_accounts_user_id_unique` + `UNIQUE(user_id, currency)`

### Commande GREEN

```bash
./mvnw -Dtest=MultiCurrencySchemaMigrationTest test
```

### Sortie pertinente GREEN

```text
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## Vérification fixtures H2

Recherche effectuée sur les inserts SQL bruts vers:

- `announcements`
- `package_requests`
- `bids`
- `negotiation_threads`
- `wallet_transactions`
- `wallet_accounts`

Constat:

- les inserts bruts concernés sont dans les tests Flyway PostgreSQL embarqué ;
- le profil `test` H2 existant ne passe pas par `V197/V198/V199` tant que les entités ne changent pas ;
- aucune fixture H2 réellement concernée n’a nécessité de modification pour cette task.

Donc:

- aucune fixture H2 modifiée ;
- aucune modification sur `V89MigrationTest`.

## Suite ciblée exécutée

### Commande

```bash
./mvnw -Dtest='*MigrationTest' test
```

### Sortie pertinente

```text
Tests run: 59, Failures: 0, Errors: 0, Skipped: 1
BUILD SUCCESS
```

Cela couvre notamment:

- `MultiCurrencySchemaMigrationTest`
- `PaymentCurrencyMigrationTest`
- `V171ContentCategoriesMigrationTest`
- `V185DraftStatusMigrationTest`
- `V117CommissionRateMigrationTest`
- `V89MigrationTest`
- `BidEntityMigrationTest`
- `PaymentEntityV38MigrationTest`

## Vérification démarrage dev

### Vérifications environnement

- `docker-compose.dev.yml`: présent
- `.env.dev`: présent
- `application-dev.yml`: présent

### Tentative `docker compose`

Commande:

```bash
set -a
source .env.dev
set +a
docker compose -f docker-compose.dev.yml up -d
```

Résultat:

```text
Error response from daemon: Conflict. The container name "/yadony_stripe_cli_kyc" is already in use
```

Contexte utile:

- `yadony_db` était déjà `Up (healthy)`
- `yadony_minio` était déjà `Up (healthy)`

### Démarrage backend dev direct

Commande:

```bash
set -a
source .env.dev
set +a
./mvnw spring-boot:run -Dspring.profiles.active=dev
```

Sortie pertinente:

```text
Current version of schema "public": 196
Migrating schema "public" to version "197 - announcement package request currency"
Migrating schema "public" to version "198 - bid negotiation wallet tx currency"
Migrating schema "public" to version "199 - wallet accounts per currency"
Successfully applied 3 migrations to schema "public", now at version v199
Tomcat started on port 8080 (http) with context path '/api/v1'
Started YadonyBackApplication
```

Puis interruption explicite du process à la demande utilisateur.

Conclusion dev-start:

- la montée Flyway `196 -> 199` a été confirmée sur `yadony_dev`
- le backend a atteint `Started YadonyBackApplication`
- je n’ai pas exécuté de `curl /actuator/health` séparé avant arrêt

## Auto-review

### Ce qui est bon

- RED exécuté avant création des migrations SQL
- `V196` non modifiée
- uniquement `V197/V198/V199` créées
- `V199` utilise bien `wallet_accounts_user_id_unique`, pas `wallet_accounts_user_id_key`
- set exact des 7 devises verrouillé côté test et côté SQL
- test métier wallet vérifie:
  - deux comptes autorisés pour un même user si devises différentes
  - refus d’un doublon même devise
- suite ciblée migrations verte

### Ce que j’ai volontairement laissé hors scope

- pas de modification des migrations existantes
- pas de modification des entités/services wallet applicatifs dans cette task

## Concerns / suivi

1. Le `docker compose up -d` complet échoue sur un conflit de noms de conteneurs Stripe CLI déjà existants (`yadony_stripe_cli_kyc`, `yadony_stripe_cli_payments`).  
   Cela n’a pas bloqué la vérification backend car `yadony_db` et `yadony_minio` étaient déjà disponibles.

2. Le code applicatif wallet n’est pas encore aligné conceptuellement avec le schéma multi-devise:

- `WalletAccountEntity` garde `@Column(... unique = true)` sur `user_id`
- `WalletAccountRepository` / `WalletService` continuent à raisonner en `findByUserId(...)`

Ce point n’a pas été modifié ici car la task demandée portait strictement sur les migrations + test de migration, mais c’est un follow-up probable pour exploiter réellement `UNIQUE(user_id, currency)`.

## Fichiers modifiés

- `src/main/resources/db/migration/V197__announcement_package_request_currency.sql`
- `src/main/resources/db/migration/V198__bid_negotiation_wallet_tx_currency.sql`
- `src/main/resources/db/migration/V199__wallet_accounts_per_currency.sql`
- `src/test/java/com/yadony/api/migrations/MultiCurrencySchemaMigrationTest.java`

## Fix round 1/5 — findings Important

### Finding 1 — preuve HTTP health après démarrage dev

Vérification préalable du port:

```bash
lsof -nP -iTCP:8080 -sTCP:LISTEN || true
```

Sortie:

```text
(aucune sortie, port libre)
```

Commande de démarrage:

```bash
set -a
source .env.dev
set +a
./mvnw spring-boot:run -Dspring.profiles.active=dev
```

Sortie pertinente:

```text
Tomcat started on port 8080 (http) with context path '/api/v1'
Started YadonyBackApplication in 24.078 seconds
```

Commande HTTP exacte demandée:

```bash
curl http://localhost:8080/api/v1/actuator/health
```

Sortie:

```text
{"status":"UP"}
```

Arrêt propre:

```text
Commencing graceful shutdown. Waiting for active requests to complete
Graceful shutdown complete
BUILD SUCCESS
```

Conclusion:

- la preuve `curl http://localhost:8080/api/v1/actuator/health` a bien été obtenue
- aucun secret n’a été affiché ni copié
- aucun process tiers n’a été tué; le port 8080 était libre

### Finding 2 — verrouillage réel `VARCHAR(3)` dans `MultiCurrencySchemaMigrationTest`

Changement apporté dans `assertCurrencyColumn(...)`:

- lecture de `data_type`
- lecture de `character_maximum_length`
- assertions réelles sur `character varying` et longueur `3`

Commande:

```bash
./mvnw -Dtest=MultiCurrencySchemaMigrationTest test
```

Sortie pertinente:

```text
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Précision importante:

- ce renforcement de test a passé immédiatement, car les migrations SQL satisfaisaient déjà le contrat `VARCHAR(3)`
- je ne fabrique donc pas un faux RED rétroactif

### Suite `*MigrationTest` relancée

Commande:

```bash
./mvnw -Dtest='*MigrationTest' test
```

Comme la sortie CLI a été volumineuse/tronquée pendant le run, j’ai vérifié le résultat final via les rapports Surefire générés par ce run:

```text
com.yadony.api.matching.BidEntityMigrationTest.txt: Tests run: 2, Failures: 0, Errors: 0, Skipped: 1
com.yadony.api.migrations.MultiCurrencySchemaMigrationTest.txt: Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
com.yadony.api.migrations.PaymentCurrencyMigrationTest.txt: Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
com.yadony.api.migrations.V117CommissionRateMigrationTest.txt: Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
com.yadony.api.migrations.V171ContentCategoriesMigrationTest.txt: Tests run: 36, Failures: 0, Errors: 0, Skipped: 0
com.yadony.api.migrations.V185DraftStatusMigrationTest.txt: Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
com.yadony.api.migrations.V189AdminUsersEmailIdentityMigrationTest.txt: Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
com.yadony.api.migrations.V89MigrationTest.txt: Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
com.yadony.api.payments.PaymentEntityV38MigrationTest.txt: Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
TOTAL=59
```

Conclusion:

- la suite ciblée `*MigrationTest` est verte sur 59 tests

### Minor documenté, non falsifié

Le RED initial de cette task était réel sur l’absence des migrations `V197/V198/V199`, mais il ne couvrait pas explicitement le contrat `VARCHAR(3)`. Ce manque de force initial est documenté ici; aucune “preuve” rétroactive n’a été inventée.
