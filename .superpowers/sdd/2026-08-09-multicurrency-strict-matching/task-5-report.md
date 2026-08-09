# Task 5 — PackageRequestEntity + PackageRequestSpecifications devise

Date: 2026-08-09
Branche: `feature/multicurrency-stripe-back`

## Résultat

Implémenté:

- `PackageRequestEntity.currency` JPA `@Column(name = "currency", nullable = false, length = 3)`
- défaut Java `EUR`
- getter/setter `getCurrency()` / `setCurrency(String)`
- `PackageRequestSpecifications.hasCurrency(String)` avec filtre exact

Non touché:

- `PackageRequestService` (laissé pour Task 6)

## RED

Tests ajoutés avant code de production:

- `src/test/java/com/yadony/api/requests/specification/PackageRequestCurrencySpecificationDbTest.java`
- `src/test/java/com/yadony/api/requests/specification/PackageRequestSpecificationsTest.java`

Commande:

```bash
./mvnw -Dtest=PackageRequestCurrencySpecificationDbTest,PackageRequestSpecificationsTest test
```

Issue observée:

- échec de compilation attendu
- symboles manquants:
  - `PackageRequestEntity#getCurrency()`
  - `PackageRequestEntity#setCurrency(String)`
  - `PackageRequestSpecifications#hasCurrency(String)`

Extrait:

```text
[ERROR] cannot find symbol
[ERROR]   symbol:   method getCurrency()
[ERROR] cannot find symbol
[ERROR]   symbol:   method hasCurrency(java.lang.String)
[ERROR] cannot find symbol
[ERROR]   symbol:   method setCurrency(java.lang.String)
```

## GREEN

Code de prod ajouté:

- `src/main/java/com/yadony/api/requests/entity/PackageRequestEntity.java`
- `src/main/java/com/yadony/api/requests/specification/PackageRequestSpecifications.java`

Commande de revalidation ciblée:

```bash
./mvnw -Dtest=PackageRequestCurrencySpecificationDbTest,PackageRequestSpecificationsTest test
```

Résultat:

```text
[INFO] Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## Vérification élargie `requests/specification`

Commande:

```bash
./mvnw -Dtest=PackageRequestSpecificationsTest,PackageRequestUrgentSpecificationDbTest,PackageRequestCurrencySpecificationDbTest test
```

Résultat:

```text
[INFO] Tests run: 20, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Notes:

- Les warnings Hibernate/H2 déjà présents (`client_min_messages`, `JSONB`) restent non bloquants dans ces slices JPA.
- Le SQL observé montre bien la colonne `currency` sur `package_requests` et le filtre `where ... currency=?`.

## Fichiers modifiés

- `src/main/java/com/yadony/api/requests/entity/PackageRequestEntity.java`
- `src/main/java/com/yadony/api/requests/specification/PackageRequestSpecifications.java`
- `src/test/java/com/yadony/api/requests/specification/PackageRequestSpecificationsTest.java`
- `src/test/java/com/yadony/api/requests/specification/PackageRequestCurrencySpecificationDbTest.java`

## Auto-review

- Champ devise conforme au brief: non-null, longueur 3, défaut Java `EUR`
- Filtre `hasCurrency` exact, sans normalisation additionnelle
- Séparation `EUR` / `CAD` prouvée au niveau JPA réel
- Aucun changement service/controller sur cette task
