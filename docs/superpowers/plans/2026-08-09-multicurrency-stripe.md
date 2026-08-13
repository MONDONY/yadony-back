# Gestion multidevise Stripe Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ajouter le paiement Stripe en devise locale supportée et le top-up local du wallet de commissions, sans modifier les règles métier existantes.

**Architecture:** Le backend reste la source de vérité. Les montants métier restent en EUR, puis un `FxRateService` convertit le montant vers la devise du PaymentIntent. La devise de transaction est persistée et propagée jusqu’à Flutter ; le wallet reste comptabilisé en EUR et ne sert qu’aux commissions.

**Tech Stack:** Spring Boot 3.5.x, Java 21, PostgreSQL/Flyway, Stripe Java SDK, JUnit 5/Mockito/MockMvc, Flutter/Dart, BLoC, Dio, `intl`, `flutter_stripe`.

## Global Constraints

- Ne jamais modifier une migration Flyway existante ; créer `V(n+1)`.
- Ne jamais modifier physiquement les données par DELETE ; respecter le soft-delete existant.
- Conserver la commission, l’escrow, le cash, les remboursements, le Connect et le wallet en EUR interne.
- Aucun Mobile Money dans ce chantier.
- Le backend valide la devise ; Flutter ne peut pas imposer une devise non supportée.
- TDD obligatoire : aucun code de production avant un test rouge observé.
- Tous les tests doivent passer et la couverture globale doit rester au moins à 90 % pour le backend et Flutter.
- Ne jamais committer sur `main`.
- Ne jamais ajouter `Co-Authored-By: Codex`.

---

### Task 1: Introduire le noyau devise et la précision Stripe

**Files:**
- Create: `dony-back/src/main/java/com/yadony/api/payments/currency/SupportedCurrency.java`
- Create: `dony-back/src/main/java/com/yadony/api/payments/currency/CurrencyCatalog.java`
- Create: `dony-back/src/main/java/com/yadony/api/payments/currency/CurrencyAmount.java`
- Create: `dony-back/src/test/java/com/yadony/api/payments/currency/CurrencyCatalogTest.java`
- Create: `dony-back/src/test/java/com/yadony/api/payments/currency/CurrencyAmountTest.java`

**Interfaces:**
- `SupportedCurrency` exposes `code()`, `minorUnit()`, `defaultForCountry(String countryCode)`.
- `CurrencyCatalog.resolve(String countryCode, String preferredCode)` returns a supported `SupportedCurrency` or throws une erreur métier dédiée.
- `CurrencyAmount.of(BigDecimal major, SupportedCurrency currency)` expose `major()`, `minor()`, `currency()`.

- [ ] **Step 1: Écrire les tests rouges** pour `EUR`, `USD`, `CAD`, `GBP`, `CHF` à deux décimales, `XOF` et `XAF` sans décimale, les pays US/CA/FR/SN/CI/CM et le fallback EUR.
- [ ] **Step 2: Exécuter les tests ciblés** : `./mvnw -Dtest=CurrencyCatalogTest,CurrencyAmountTest test`. Vérifier l’échec parce que les types n’existent pas encore.
- [ ] **Step 3: Implémenter le minimum** : enum fermé aux sept devises, catalogue pays explicite, arrondi `HALF_UP`, conversion en unités mineures.
- [ ] **Step 4: Réexécuter les tests ciblés** et vérifier zéro échec.
- [ ] **Step 5: Committer** : `git add src/main/java/com/yadony/api/payments/currency && git commit -m "feat: add currency catalog and minor units"`.

### Task 2: Ajouter les taux de change déterministes et leur cache

**Files:**
- Create: `dony-back/src/main/java/com/yadony/api/payments/currency/FxRateProvider.java`
- Create: `dony-back/src/main/java/com/yadony/api/payments/currency/FxRateService.java`
- Create: `dony-back/src/main/java/com/yadony/api/payments/currency/ExchangeRateProperties.java`
- Create: `dony-back/src/test/java/com/yadony/api/payments/currency/FxRateServiceTest.java`
- Modify: `dony-back/src/main/resources/application.yml`

**Interfaces:**
- `FxRateProvider.rate(String source, String target)` returns `BigDecimal` ou une exception technique.
- `FxRateService.convert(BigDecimal amountEur, SupportedCurrency target)` returns un `CurrencyAmount` arrondi dans la devise cible.
- Les taux `EUR→EUR=1`, `EUR→XOF` et `EUR→XAF` sont configurables ; les autres taux sont fournis par l’adapter configuré, jamais lus directement par `PaymentService`.

- [ ] **Step 1: Écrire les tests rouges** pour EUR identity, XOF/XAF sans décimale, conversion CAD avec arrondi, taux absent, et propagation d’une erreur provider.
- [ ] **Step 2: Exécuter** `./mvnw -Dtest=FxRateServiceTest test` et observer l’échec attendu.
- [ ] **Step 3: Implémenter** l’interface, le service, le cache Caffeine existant et les propriétés de parité/configuration sans appel réseau dans les tests.
- [ ] **Step 4: Vérifier** les tests ciblés et la validation des propriétés invalides.
- [ ] **Step 5: Committer** : `git add src/main/java/com/yadony/api/payments/currency src/main/resources/application.yml src/test/java/com/yadony/api/payments/currency && git commit -m "feat: add exchange rate service"`.

### Task 3: Persister la devise des paiements

**Files:**
- Create: `dony-back/src/main/resources/db/migration/V193__add_payment_currency.sql`
- Modify: `dony-back/src/main/java/com/yadony/api/payments/PaymentEntity.java`
- Modify: `dony-back/src/main/java/com/yadony/api/payments/dto/PaymentResponse.java`
- Create: `dony-back/src/test/java/com/yadony/api/migrations/PaymentCurrencyMigrationTest.java`
- Create: `dony-back/src/test/java/com/yadony/api/payments/PaymentResponseTest.java`

**Interfaces:**
- `PaymentEntity.currency` est non nul, longueur 3, défaut historique `EUR` dans la migration.
- `PaymentResponse.currency` expose le code ISO en minuscule pour Stripe et le code normalisé côté API selon le contrat existant.

- [ ] **Step 1: Écrire le test rouge** vérifiant que la migration ajoute `currency` et que les paiements historiques sont en EUR.
- [ ] **Step 2: Exécuter** le test de migration ciblé et confirmer son échec avant la migration.
- [ ] **Step 3: Ajouter `V193__add_payment_currency.sql`** après la dernière migration existante (`V192`), puis les champs Entity/DTO et le mapping `toPaymentResponse`.
- [ ] **Step 4: Exécuter** `./mvnw -Dtest=PaymentCurrencyMigrationTest,PaymentResponseTest test`.
- [ ] **Step 5: Committer** : `git add src/main/java/com/yadony/api/payments src/main/resources/db/migration src/test/java/com/yadony/api/migrations src/test/java/com/yadony/api/payments && git commit -m "feat: persist payment currency"`.

### Task 4: Généraliser le paiement bid et négociation

**Files:**
- Modify: `dony-back/src/main/java/com/yadony/api/payments/dto/CreatePaymentRequest.java`
- Modify: `dony-back/src/main/java/com/yadony/api/payments/PaymentService.java`
- Modify: `dony-back/src/main/java/com/yadony/api/payments/StripeGateway.java` si la signature Stripe doit recevoir la devise
- Modify: `dony-back/src/main/java/com/yadony/api/payments/PaymentServiceTest.java`
- Modify: `dony-back/src/test/java/com/yadony/api/requests/service/NegotiationServiceTest.java` pour les contrats impactés
- Create: `dony-back/src/test/java/com/yadony/api/payments/PaymentCurrencyServiceTest.java`

**Interfaces:**
- Le request de paiement accepte `currencyCode` optionnel ; son absence conserve la résolution serveur.
- `PaymentService` résout la devise via `CurrencyCatalog`, convertit avec `FxRateService`, crée le PaymentIntent dans cette devise, persiste cette même devise et renvoie la réponse enrichie.
- Les méthodes existantes gardent leurs règles d’éligibilité, montant métier EUR, commission, idempotence et statuts.

- [ ] **Step 1: Ajouter les tests rouges** : US → USD, CA → CAD, preference explicite prioritaire, devise invalide 422, PaymentIntent en `cad`, idempotence qui réutilise le même currency, négociation identique.
- [ ] **Step 2: Exécuter** les tests ciblés ; vérifier que les assertions échouent actuellement sur `currency="eur"` ou l’absence de champ.
- [ ] **Step 3: Implémenter** l’injection de `CurrencyCatalog`/`FxRateService`, le `currencyCode` optionnel, les métadonnées Stripe de devise et la persistance.
- [ ] **Step 4: Vérifier** les tests PaymentService, négociation, cash et webhooks concernés ; corriger uniquement les tests dont le contrat est réellement enrichi, jamais ceux qui couvrent une règle métier inchangée.
- [ ] **Step 5: Committer** : `git add src/main/java src/test/java && git commit -m "feat: create stripe payments in local currency"`.

### Task 5: Utiliser la devise persistée pour transferts, remboursements et webhooks

**Files:**
- Modify: `dony-back/src/main/java/com/yadony/api/payments/PaymentService.java`
- Modify: `dony-back/src/main/java/com/yadony/api/payments/RefundProcessor.java`
- Modify: `dony-back/src/main/java/com/yadony/api/payments/PaymentStripeWebhookHandler.java`
- Modify: listeners de release qui créent `Transfer` si une devise EUR est codée en dur
- Modify: tests Stripe/webhook/release correspondants

- [ ] **Step 1: Écrire les tests rouges** vérifiant que Transfer, refund et metadata utilisent la devise du PaymentEntity, y compris XOF/XAF.
- [ ] **Step 2: Exécuter** les tests ciblés et confirmer les échecs sur les appels Stripe actuellement EUR.
- [ ] **Step 3: Implémenter** la lecture unique de `payment.getCurrency()` et les unités mineures via `CurrencyAmount`.
- [ ] **Step 4: Exécuter** tous les tests du package payments et vérifier l’absence de régression sur l’escrow.
- [ ] **Step 5: Committer** : `git add src/main/java src/test/java && git commit -m "feat: preserve currency through payment lifecycle"`.

### Task 6: Généraliser le top-up Stripe du wallet EUR

**Files:**
- Modify: `dony-back/src/main/java/com/yadony/api/payments/wallet/dto/WalletTopupRequest.java`
- Modify: `dony-back/src/main/java/com/yadony/api/payments/wallet/WalletTopupOrchestrator.java`
- Modify: le handler webhook qui crédite `WalletService`
- Modify: tests wallet/top-up/webhook existants
- Create: `dony-back/src/test/java/com/yadony/api/payments/wallet/WalletTopupCurrencyTest.java`

- [ ] **Step 1: Écrire les tests rouges** pour un top-up CAD/USD/XOF/XAF, conversion en crédit EUR, rejeu webhook idempotent et rejet WAVE/ORANGE_MONEY.
- [ ] **Step 2: Exécuter** le test ciblé et vérifier l’échec sur `currency="eur"` et le crédit direct non converti.
- [ ] **Step 3: Implémenter** `currencyCode`, la conversion au moment de l’initiation, les métadonnées `wallet_topup`, `wallet_currency=EUR`, le taux figé et le crédit webhook.
- [ ] **Step 4: Vérifier** les tests wallet existants, sans changer les opérations `COMMISSION_DEDUCTED` ni les limites du solde.
- [ ] **Step 5: Committer** : `git add src/main/java src/test/java && git commit -m "feat: support local currency wallet topups"`.

### Task 7: Ajouter les contrats Flutter devise et formatage

**Files:**
- Create: `dony_app/lib/core/currency/supported_currency.dart`
- Create: `dony_app/lib/core/currency/currency_formatter.dart`
- Create: `dony_app/test/core/currency/supported_currency_test.dart`
- Create: `dony_app/test/core/currency/currency_formatter_test.dart`
- Modify: `dony_app/lib/core/storage/hive_service.dart` uniquement si la clé existante doit accepter les nouveaux codes.

- [ ] **Step 1: Écrire les tests rouges** pour le catalogue, les symboles, XOF/XAF sans décimales et le formatage `USD`, `CAD`, `EUR`.
- [ ] **Step 2: Exécuter** `flutter test test/core/currency` et confirmer l’échec attendu.
- [ ] **Step 3: Implémenter** le modèle immuable et `NumberFormat.currency` avec locale et code renvoyés par l’API.
- [ ] **Step 4: Vérifier** les tests ciblés et `flutter analyze` sur les nouveaux fichiers.
- [ ] **Step 5: Committer** : `git add lib/core/currency test/core/currency lib/core/storage/hive_service.dart && git commit -m "feat: add currency formatting contracts"`.

### Task 8: Propager la devise dans les modèles, BLoC et PaymentSheet

**Files:**
- Modify: `dony_app/lib/features/payments/data/models/payment_model.dart`
- Modify: `dony_app/lib/features/payments/data/payment_gateway.dart`
- Modify: `dony_app/lib/features/payments/data/stripe_payment_sheet_params.dart`
- Modify: `dony_app/lib/features/payments/bloc/payment_event.dart`
- Modify: `dony_app/lib/features/payments/bloc/payment_state.dart`
- Modify: `dony_app/lib/features/payments/bloc/payment_sheet_bloc.dart`
- Modify: `dony_app/lib/features/payments/presentation/widgets/dony_payment_sheet.dart`
- Modify: tests PaymentModel, PaymentBloc, PaymentSheetBloc, PaymentSheet et datasource.

- [ ] **Step 1: Écrire les tests rouges** : parsing currency, propagation CAD/USD/XOF, confirmation avec la devise du backend, affichage sans symbole EUR codé en dur.
- [ ] **Step 2: Exécuter** les tests Flutter ciblés et observer les échecs de contrat.
- [ ] **Step 3: Implémenter** la propagation BLoC et les paramètres PaymentSheet sans modifier le choix card/PayPal existant.
- [ ] **Step 4: Vérifier** `flutter test test/features/payments` et `flutter analyze`.
- [ ] **Step 5: Committer** : `git add lib/features/payments test/features/payments && git commit -m "feat: propagate payment currency to stripe sheet"`.

### Task 9: Propager la devise dans le wallet et le fallback cash

**Files:**
- Modify: `dony_app/lib/features/payments/wallet/data/models/wallet_model.dart`
- Modify: `dony_app/lib/features/payments/wallet/bloc/wallet_event.dart`
- Modify: `dony_app/lib/features/payments/wallet/presentation/screens/wallet_topup_amount_screen.dart`
- Modify: `dony_app/lib/features/payments/wallet/presentation/screens/wallet_topup_method_screen.dart`
- Modify: `dony_app/lib/features/payments/wallet/presentation/screens/wallet_screen.dart`
- Modify: tests wallet model/BLoC/screens et tests de cash commission.

- [ ] **Step 1: Écrire les tests rouges** : wallet toujours affiché en EUR, top-up local affiché dans la devise choisie, confirmation du crédit EUR, aucun bouton de retrait ou transfert ajouté.
- [ ] **Step 2: Exécuter** les tests wallet ciblés et confirmer l’échec attendu.
- [ ] **Step 3: Implémenter** le champ currency du top-up et les libellés transparents “recharge en X, solde Yadony en EUR”.
- [ ] **Step 4: Vérifier** `flutter test test/features/payments/wallet test/features/payments/cash`.
- [ ] **Step 5: Committer** : `git add lib/features/payments/wallet test/features/payments/wallet test/features/payments/cash && git commit -m "feat: show local currency for commission wallet topups"`.

### Task 10: Vérification complète et couverture

**Files:**
- Modify: tests de contrat impactés uniquement si les tests rouges identifient un ancien contrat volontairement remplacé.
- Create: `dony-back/docs/testing/multicurrency-stripe-test-matrix.md` si la matrice de vérification nécessite une trace durable.

- [ ] **Step 1: Backend** exécuter `./mvnw test` puis `./mvnw test jacoco:report` dans le worktree backend.
- [ ] **Step 2: Flutter** exécuter `flutter test --coverage` puis `flutter analyze` dans le worktree Flutter.
- [ ] **Step 3: Inspecter les rapports** et atteindre au moins 90 % global sur chaque projet ; ajouter des tests avant toute modification de seuil ou exclusion.
- [ ] **Step 4: Rejouer les tests des anciens flux** : EUR, cash, wallet EUR, escrow, refund, Connect et webhooks.
- [ ] **Step 5: Vérifier** `git diff`, `git status`, les migrations et l’absence de secret.
- [ ] **Step 6: Committer** uniquement après vérification fraîche, sans toucher à `main` et sans `Co-Authored-By: Codex`.

## Auto-revue du plan

- Les règles métier existantes sont explicitement conservées dans la spécification et les tâches 4–6.
- La devise est persistée avant toute utilisation dans les transferts/remboursements.
- Les devises sans décimales sont couvertes dans les tests du noyau et des PaymentIntents.
- Les deux flux paiement, le wallet et Flutter ont des tâches TDD indépendantes.
- Aucun Mobile Money, retrait wallet ou changement de Connect n’est inclus.
- La migration `V193__add_payment_currency.sql` suit `V192`, dernier fichier présent dans le worktree au moment de l’écriture du plan.
