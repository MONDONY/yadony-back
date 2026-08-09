# Task 2 — CurrencyMatchGuard

## DONE

- Ajout de `CurrencyMatchGuard.assertMatches(String listingCurrency, String actorCurrency)`.
- Comparaison insensible à la casse.
- Cas `listingCurrency == null` et `actorCurrency == null` couverts.
- Aucune `NPE` : les propriétés RFC7807 utilisent la valeur stable `UNKNOWN` pour les devises absentes.
- `Map.of(...)` ne reçoit jamais de valeur null.

## Fichiers modifiés

- `src/main/java/com/yadony/api/payments/currency/CurrencyMatchGuard.java`
- `src/test/java/com/yadony/api/payments/currency/CurrencyMatchGuardTest.java`

## RED

Commande :

```bash
./mvnw test -Dtest=CurrencyMatchGuardTest
```

Sortie observée :

```text
[ERROR] /src/test/java/com/yadony/api/payments/currency/CurrencyMatchGuardTest.java:[11,19] cannot find symbol
  symbol:   class CurrencyMatchGuard
```

Interprétation :

- Échec attendu au stade rouge : la classe n’existait pas encore.

## GREEN

Commande :

```bash
./mvnw test -Dtest=CurrencyMatchGuardTest
```

Sortie observée :

```text
[INFO] Running com.yadony.api.payments.currency.CurrencyMatchGuardTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## Vérification package currency

Commande :

```bash
./mvnw test -Dtest=CurrencyMatchGuardTest,CurrencyAmountTest,CurrencyCatalogTest,FxRateServiceTest,StripeFxQuoteServiceTest
```

Sortie observée :

```text
[INFO] Tests run: 20, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## Auto-review

- La garde ne normalise pas les devises invalides ; elle compare uniquement et échoue sinon.
- Les valeurs null sont converties en `UNKNOWN` avant construction des propriétés pour rester sérialisables.
- Aucun ajout de dépendance, aucun changement hors du package currency.
- Les tests couvrent :
  - match exact
  - match insensible à la casse
  - mismatch normal
  - `listingCurrency == null`
  - `actorCurrency == null`

