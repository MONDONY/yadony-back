# Task 11 — WalletService à devise explicite

## Périmètre livré

- Toutes les opérations publiques de `WalletService` prennent désormais `String currency` : `getOrCreate`, `getBalance`, les deux variantes de `debit` et `credit`.
- `getAllBalances(UUID)` expose les comptes multi-devises d'un utilisateur.
- Chaque `WalletTransactionEntity` porte une devise non nulle, par défaut `EUR`; les crédits et débits la renseignent systématiquement.
- Les appels existants restent explicitement en `EUR` : `CashCommissionService`, `CashGateAdapter`, `PaymentStripeWebhookHandler`, `WalletController` et `ReferralRewardWalletListener`.
- `UserService` utilise aussi explicitement `findByUserIdAndCurrency(userId, "EUR")` afin de conserver sa sémantique legacy de solde wallet pour la suppression de compte.

## Résolution des bloqueurs Task 10

1. `WalletAccountRepository.findByUserId(UUID)` a été supprimé : il était ambigu dès qu'un utilisateur possède EUR et CAD.
2. `WalletAccountRepository.findByUserIdForUpdate(UUID)` a été supprimé pour la même raison; seuls `findByUserIdAndCurrency` et `findByUserIdAndCurrencyForUpdate` subsistent.
3. La transaction wallet enregistre maintenant sa devise, vérifiée pour crédit et débit dans `WalletServiceTest`.

## TDD

RED exécuté avant la production :

```text
./mvnw test -Dtest=WalletServiceTest
BUILD FAILURE — 22 erreurs de compilation attendues
```

Les erreurs prouvaient l'absence des signatures `WalletService` avec devise, de `getAllBalances`, ainsi que de `WalletTransactionEntity.getCurrency/setCurrency`.

Les tests ajoutés/migrés couvrent l'isolation EUR/CAD, la devise des transactions, les deux débits idempotents ou insuffisants, et les appelants EUR explicites.

## Vérifications

```text
./mvnw compile
BUILD SUCCESS

./mvnw test -Dtest=WalletServiceTest,CashCommissionServiceTest,PaymentStripeWebhookHandlerTest,WalletControllerIT,ReferralRewardWalletListenerTest,UserServiceTest,UserServiceDeleteAccountTest
BUILD SUCCESS — 142 tests, 0 échec, 0 erreur

./mvnw clean test jacoco:report
BUILD SUCCESS — 3 010 tests, 0 échec, 0 erreur, 7 ignorés
```

Couverture JaCoCo globale (lignes) : 15 525 couvertes / 1 997 manquées, soit **88,60 %**. Le seuil global du projet (90 %) n'est pas atteint par le dépôt après cette tâche, bien que les nouveaux comportements wallet soient couverts. Aucun élargissement hors périmètre n'a été fait pour gonfler cette métrique.

## Auto-revue

- Recherche de tous les appels `walletService` et des méthodes repository legacy : aucun appel ambigu restant.
- Aucune migration, logique FX ou modification de `WalletTopupOrchestrator` (réservé à Task 12).
- Diff sans erreur d'espacement (`git diff --check`).
- Les règles d'idempotence, de verrou pessimiste et `noRollbackFor` sont conservées.

## Commit

Commit de cette tâche : `HEAD` (hash exact fourni dans le handoff de la tâche).
