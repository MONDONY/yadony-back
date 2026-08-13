# Multi-devise — matching strict (sans conversion) — Design

**Date:** 2026-08-09
**Statut:** Validé par l'utilisateur, prêt pour planification
**Remplace :** l'approche `docs/superpowers/plans/2026-08-09-multicurrency-stripe.md` (verrouillage de taux via Stripe `fx_quote`), abandonnée après avoir constaté que l'API preview `fx_quotes` de Stripe rejette systématiquement l'attachement au `PaymentIntent` pour ce compte (`"FX Quote's to_currency must match the payment intent's settlement currency"`, reproduit en curl brut, indépendant du code — cf. session de debug du jour).

## Contexte

Le backend `yadony-back` (branche `feature/multicurrency-stripe-back`) et le front `dony_app` (branche `feature/multicurrency-stripe-app`) avaient déjà posé une bonne partie de l'infrastructure multi-devise (wallet, business-preferences `currencyCode`, `SupportedCurrency`, `CurrencyCatalog`, `FxRateService`). Le seul point encore câblé sur une conversion Stripe (`StripeFxQuoteService` + `fx_quote`) s'est révélé cassé au niveau du compte Stripe lui-même (préview beta, contrainte de devise de règlement), pas du code.

Plutôt que de continuer à chasser ce bug côté Stripe, le produit pivote vers un modèle sans conversion : **chaque annonce/demande porte sa devise, figée à la création ; un utilisateur ne voit et ne paie que ce qui est dans sa devise active.**

## Principe général

```
Création trajet/colis
  └─ currency = devise active du créateur (business-prefs) → figée, immuable

Recherche/matching
  └─ CurrencyMatchGuard filtre : ne montre que currency = devise active du visiteur

Bid / Négociation / Paiement
  └─ currency héritée de l'annonce/demande à la création du Bid (dénormalisée)
  └─ CurrencyMatchGuard revalide à chaque étape (bid, accept, checkout)

Wallet
  └─ WalletAccountEntity : (user_id, currency) → plusieurs lignes possibles
  └─ Une seule "active" = devise business-prefs courante
  └─ Topup carte devise X → crédite ligne X, 1:1, zéro FX
  └─ Switch devise → change quelle ligne est active, rien n'est détruit, réversible

FxRateService (existant)
  └─ Reclassé "affichage informatif uniquement" (ex: solde bloqué ≈ équivalent)
  └─ Stripe fx_quote entièrement retiré (WalletTopupOrchestrator + PaymentService)
```

**Conséquence majeure :** avec un matching strict par devise et un wallet mono-devise active, plus aucune conversion FX n'est nécessaire dans le flux critique de paiement. Le montant chargé sur la carte = le montant déclaré dans l'annonce, dans la même devise, sans calcul.

## Décisions validées (Q&A)

1. Le prix négocié/commission/litige reste un nombre "dans la devise du contexte" — pas de conversion cross-devise entre parties. Les colonnes historiques `_eur` (`min_bid_price_eur`, `negotiated_net_eur`, `commission_eur`) gardent leur nom (renommage = chantier cosmétique séparé, hors scope).
2. N'importe quel trajet peut en théorie être vu, mais **filtré à la recherche** : un utilisateur ne voit que les trajets/demandes dans sa devise active. Pas de "trajet visible mais non payable".
3. Le solde wallet ne bouge jamais en base lors d'un switch de devise (pas de reconversion, pas d'arrondi cumulé). Le switch change juste quelle ligne `(user_id, currency)` est active.
4. Un trajet/une demande est publié(e) dans **une seule devise**, celle du créateur au moment de la création. Pas de multi-devise acceptée par annonce.
5. Un ancien solde (devise non active) reste **récupérable** en revenant sur cette devise dans les Réglages — jamais perdu, jamais forcé à se reconvertir.
6. Nouvel écran d'onboarding (devise seule, skippable) inséré entre `/auth/analytics-consent` et `/auth/referral-code`. Nom/prénom/date de naissance restent hors scope (déjà gérés ailleurs dans Profil).

## Approches retenues

- **Garde de devise centralisée** (`CurrencyMatchGuard`), appelée à chaque point de contrôle (bid, négociation, paiement), plutôt que dupliquée service par service. Justification directe : la session de debug du jour a produit 3 bugs distincts issus d'une logique de conversion dispersée entre `StripeFxQuoteService`, `WalletTopupOrchestrator` et `PaymentService`.
- **Devise dénormalisée** sur `Bid` et `NegotiationThread` à la création (copiée depuis l'annonce/la demande), plutôt que recalculée par jointure à chaque lecture. Immuable après création donc pas de risque de désynchronisation ; évite des jointures dans les requêtes de matching (perf).

## Modèle de données (back)

Nouvelles migrations `V(n+1)` (num exact à déterminer au moment du plan, après la dernière migration existante) :

| Table | Colonne | Règle |
|---|---|---|
| `announcements` | `currency VARCHAR(3) NOT NULL DEFAULT 'EUR'` | Figée à la création |
| `package_requests` | `currency VARCHAR(3) NOT NULL DEFAULT 'EUR'` | Figée à la création |
| `bids` | `currency VARCHAR(3) NOT NULL DEFAULT 'EUR'` | Copiée de l'annonce/demande à la création du bid |
| `negotiation_threads` | `currency VARCHAR(3) NOT NULL DEFAULT 'EUR'` | Un thread peut exister sans bid (`bid_id` nullable) donc porte sa propre devise |
| `wallet_transactions` | `currency VARCHAR(3) NOT NULL DEFAULT 'EUR'` | Nécessaire dès que le wallet devient multi-lignes |

`wallet_accounts` : contrainte `UNIQUE(user_id)` → `UNIQUE(user_id, currency)`. Toutes les lignes historiques restent `currency='EUR'` (déjà la valeur par défaut implicite, pas de backfill réel).

`chk_currency` (même liste partout) : `EUR|USD|CAD|GBP|CHF|XOF|XAF`, réutilise l'enum `SupportedCurrency` déjà existant.

**Piège connu à anticiper** (rencontré aujourd'hui) : toute nouvelle colonne `NOT NULL` casse les tests de migration H2 (DDL généré depuis JPA, sans le `DEFAULT` Flyway) — l'ajouter explicitement dans les `INSERT` des tests concernés.

## Logique back

**`CurrencyMatchGuard`** (nouveau, `payments/currency/`)
```java
void assertMatches(SupportedCurrency listingCurrency, SupportedCurrency actorCurrency);
// throw YadonyBusinessException 422 "currency-mismatch" si différent
```
Appelé à : création de bid, acceptation/négociation, initiation paiement (checkout).

**Recherche / matching** : `AnnouncementSearchService` / `PackageRequestSearchService` ajoutent `WHERE currency = :userCurrency` (devise active de l'utilisateur connecté). Filtre systématique, pas de bypass.

**Création trajet/colis** : `AnnouncementService.create()` / `PackageRequestService.create()` lisent la devise business-prefs du créateur côté serveur (jamais confiée au client) et l'assignent à `currency`, immuable ensuite.

**Bid / Négociation** : copie la devise du parent + `CurrencyMatchGuard.assertMatches(...)`.

**Paiement (`PaymentService.createEscrow`)** : suppression complète de l'appareillage `fx_quote`/`StripeFxQuoteService` de ce flux (`createFxQuote()`, `convertForPayment()`, `putExtraParam("fx_quote", ...)`). `currency = bid.getCurrency()` directement — la devise n'est plus un choix libre par requête (`request.getCurrencyCode()` disparaît de ce chemin). `CurrencyMatchGuard` en dernier filet avant `PaymentIntent.create`.

**Wallet (`WalletTopupOrchestrator` + `WalletService`)** : suppression de `StripeFxQuoteService`/`FxRateService.convertToEur` du chemin critique. `topup()` résout/crée la ligne `(user_id, currency active)`, charge la carte dans cette devise, crédite 1:1. `getBalance()` retourne la ligne active ; les autres devises apparaissent en lecture seule avec équivalent indicatif (`FxRateService`, affichage uniquement, jamais utilisé pour un calcul de paiement).

**`StripeFxQuoteService`** : supprimée entièrement (code mort sinon, moins de surface à maintenir pour une feature Stripe cassée).

## Front (Flutter)

**Formulaire création trajet/colis** (aucune notion de devise aujourd'hui — chantier neuf) : pas de sélecteur, juste un bandeau informatif (« Publié en {devise active} — les expéditeurs dans une autre devise ne verront pas ce trajet »). La devise est déduite côté serveur, jamais envoyée par le client.

**Recherche/Home** : aucun changement UI — le filtre est serveur, la liste retournée est déjà correcte.

**Bid/Négociation/Paiement** : mapping dédié dans `ErrorPresenter` pour `currency-mismatch` (message ciblé, pas le générique "An unexpected error occurred").

**Wallet** : ligne active en avant, lignes verrouillées en dessous avec équivalent indicatif (`CurrencyFormatter` existant). Recharge déjà correctement câblée sur `kCurrencyCode`.

**Réglages** : popup de confirmation au changement de devise expliquant l'effet (trajets/colis existants restent visibles pour le créateur mais invisibles pour les autres ; solde ancien récupérable en revenant sur cette devise).

**Onboarding** : nouvel écran `/auth/currency-selection` entre `/auth/analytics-consent` et `/auth/referral-code`. Liste `SupportedCurrency`, bouton "Passer pour l'instant" → EUR par défaut (modifiable plus tard). Si choisi → `PUT /users/me/business-preferences` (endpoint déjà existant et validé). `post_signup_route.dart` : ajoute ce point de passage.

## Gestion d'erreurs

Nouveau code `currency-mismatch` (422, RFC 7807 `ProblemDetail`) :
```json
{
  "type": "https://yadony.app/errors/currency-mismatch",
  "title": "Currency Mismatch",
  "status": 422,
  "detail": "Ce trajet est publié en EUR, ton compte est en CAD.",
  "listingCurrency": "EUR",
  "actorCurrency": "CAD"
}
```

| Contexte | Déclencheur | Message user |
|---|---|---|
| Création bid | `CurrencyMatchGuard` avant sauvegarde | "Ce trajet n'est plus disponible dans ta devise" |
| Acceptation/négociation | Devise changée entretemps | "Le voyageur/expéditeur a changé de devise entretemps, transaction annulée" |
| Checkout paiement | Dernier filet avant `PaymentIntent.create` | "Impossible de payer : devise différente de celle du trajet" |
| Wallet topup | Désync client/serveur | "Recharge indisponible dans cette devise, actualise l'app" |

Ces cas devraient rester rares : la recherche filtrée côté serveur élimine la quasi-totalité des scénarios avant même d'atteindre le bid ; ne subsiste que la fenêtre où un utilisateur change de devise pendant une transaction déjà engagée.

## Tests

**Backend** (couverture ≥ 90 %, cf. CLAUDE.md) :
- `CurrencyMatchGuard` : unit, toutes paires match/mismatch
- Création annonce/demande : currency assignée depuis business-prefs, immuable
- Recherche/matching : integration, 2 annonces devises différentes → seule celle qui matche remonte
- `BidService` : currency copiée du parent, guard déclenché sur mismatch
- `PaymentService.createEscrow` : plus d'appel `StripeFxQuoteService` (mock jamais sollicité)
- `WalletTopupOrchestrator` : résolution/création ligne par devise, switch+retour préserve l'ancien solde
- Migration H2 : ajouter les nouvelles colonnes `NOT NULL` aux `INSERT` des tests de migration existants concernés

**Suppression** : `StripeFxQuoteServiceTest` et `WalletTopupOrchestratorFxQuoteTest` (écrits pendant la session de debug du jour) deviennent obsolètes puisque `fx_quote` est retiré — à supprimer avec le code, pas laissés en rouge.

**Flutter** :
- `CurrencySelectionScreen` (onboarding) : widget, sélection + skip → navigation correcte
- Formulaire création trajet/colis : bloc, currency reçue correctement (jamais envoyée par le client)
- `ErrorPresenter` : widget, mapping `currency-mismatch` → message dédié

**Manuel** : parcours complet 2 comptes test (1 EUR, 1 CAD) — publication, recherche croisée (invisible), tentative bid croisé (bloqué), wallet recharge + switch + retour.

## Hors scope (explicitement)

- Renommage des colonnes `_eur` historiques (`min_bid_price_eur`, `negotiated_net_eur`, `commission_eur`)
- Affichage converti des prix dans les listes de recherche pour des devises non correspondantes (puisqu'elles sont filtrées, pas affichées converties)
- Reporting/dashboard admin multi-devise agrégé (flag comme dette connue pour une session future)
- Collecte nom/prénom/date de naissance à l'onboarding (feature de complétion de profil séparée)
