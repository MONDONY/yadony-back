# Gestion multidevise Stripe — Design

**Date :** 2026-08-09  
**Statut :** proposé pour implémentation  
**Dépôts concernés :** `dony-back`, `dony_app`

## Objectif

Permettre à un utilisateur de payer avec Stripe dans la devise adaptée à son pays ou à sa préférence, sans modifier les règles métier existantes de commission, d’escrow, de paiement cash, de wallet ou de versement Connect.

## Décisions produit validées

- Compte Stripe plateforme enregistré en France.
- Les paiements par carte passent par Stripe.
- États-Unis : devise par défaut `USD`.
- Canada : devise par défaut `CAD`.
- Zone euro : devise par défaut `EUR`.
- Royaume-Uni : devise par défaut `GBP`.
- Suisse : devise par défaut `CHF`.
- Afrique de l’Ouest : `XOF` lorsque la devise est disponible.
- Afrique centrale : `XAF` lorsque la devise est disponible.
- L’utilisateur peut conserver ou modifier sa devise préférée.
- En Afrique, si la carte ou la devise Stripe n’est pas disponible, le mode cash existant reste le fallback.
- Le wallet reste un solde fermé destiné uniquement aux commissions Yadony.
- Le wallet est comptabilisé en `EUR` en interne ; une recharge locale est convertie en EUR avant crédit.
- Aucun retrait, transfert entre utilisateurs ou achat depuis le wallet n’est ajouté.
- Les règles de calcul de commission, de cash, d’escrow, de remboursement et de Connect restent inchangées.

## État actuel constaté

- `PaymentService` crée encore les PaymentIntents des bids et des négociations avec `currency="eur"`.
- Les montants API et Flutter portent encore des noms spécifiques à l’euro (`amountEur`).
- `PaymentEntity` stocke les montants mais pas la devise de la transaction.
- `PaymentResponse` ne renvoie pas la devise.
- `UserBusinessPrefsDto` limite actuellement `currencyCode` à `EUR|XOF|XAF`.
- Le wallet possède déjà une devise persistée, initialisée à `EUR`.
- Le top-up Stripe du wallet crée actuellement un PaymentIntent en EUR.
- Le mode cash et le débit de commission du wallet existent déjà et ne doivent pas être redessinés.

## Architecture retenue

### 1. Devise de présentation et devise de paiement

La devise est résolue côté backend, jamais déduite uniquement côté Flutter :

1. La préférence explicite de l’utilisateur est utilisée si elle est supportée.
2. Sinon, la devise par défaut du pays enregistré sur son profil est utilisée.
3. Sinon, `EUR` est le fallback plateforme.
4. Le backend renvoie au client la devise résolue, le montant à payer et les moyens Stripe disponibles.

Le client peut demander une devise différente, mais le backend la valide contre la liste des devises supportées. Une devise non supportée produit une erreur RFC 7807 et ne crée aucun PaymentIntent.

### 2. Catalogue pays/devise initial

Le catalogue initial est une configuration backend testable :

| Pays / zone | Devise par défaut |
|---|---|
| US | USD |
| CA | CAD |
| GB | GBP |
| CH | CHF |
| FR, BE, DE, ES, IT, PT, NL, LU, IE, AT, FI, GR, etc. zone euro | EUR |
| SN, CI, ML, BF, BJ, TG, NE, GW | XOF |
| CM, GA, CG, TD, CF, GQ | XAF |
| Pays non configuré | EUR |

Le code pays est l’ISO alpha-2 déjà stocké sur l’utilisateur. La nationalité n’est jamais utilisée pour choisir la devise.

### 3. Conversion

Les prix métier restent calculés dans le contrat interne EUR afin de préserver les règles existantes. Un service `FxRateService` convertit ensuite le montant gross et la commission vers la devise du PaymentIntent.

- Les taux sont chargés depuis un fournisseur configurable basé sur les taux EUR de référence.
- `XOF` et `XAF` utilisent leur parité de lancement documentée dans la configuration.
- Le résultat est arrondi selon la précision Stripe de la devise.
- Le taux, la devise source, la devise cible et les montants convertis sont figés dans le paiement.
- Une indisponibilité de taux ne crée pas de paiement et permet au client d’afficher le fallback cash si le contexte métier l’autorise.

Le fournisseur de taux est une interface afin que les tests n’utilisent jamais Internet et qu’un fournisseur différent puisse être introduit sans toucher à `PaymentService`.

### 4. Paiement Stripe

Les deux flux existants, bid et négociation, utilisent la même résolution :

```text
prix net métier EUR
→ commission métier EUR
→ prix gross métier EUR
→ conversion vers la devise demandée
→ PaymentIntent Stripe dans la devise cible
```

`PaymentIntent.amount` est toujours envoyé dans l’unité mineure Stripe. `XOF` et `XAF` sont traités comme devises sans décimales ; EUR, USD, CAD, GBP et CHF utilisent deux décimales.

Les types de moyens de paiement restent ceux déjà activés par le flux actuel (`card` et les types autorisés par le backend). Aucun moyen Mobile Money n’est ajouté.

Le modèle Connect, les statuts d’escrow et le transfert après livraison restent inchangés. Le transfert doit reprendre la devise enregistrée sur `PaymentEntity`, jamais supposer EUR.

### 5. Persistance et audit

Une migration Flyway ajoute la devise de transaction à `payments` avec `EUR` pour les paiements historiques. Les migrations existantes ne sont jamais modifiées.

Le paiement conserve :

- `currency` de présentation/paiement ;
- `amount` gross dans cette devise ;
- `commission_amount` dans cette devise ;
- les montants métier EUR nécessaires à l’audit ;
- le taux de change et sa source ;
- les identifiants Stripe existants.

Les reçus, remboursements, webhooks et transferts utilisent la devise figée du paiement. Un changement ultérieur de préférence ne modifie jamais un paiement existant.

### 6. Wallet de commissions

Le wallet reste un compte interne en EUR. Le top-up accepte une devise de paiement demandée par l’utilisateur :

```text
montant top-up local
→ PaymentIntent Stripe dans la devise locale
→ webhook confirmé
→ conversion figée en EUR
→ crédit du wallet en EUR
```

Le crédit est idempotent sur l’identifiant du PaymentIntent. Les types `WAVE` et `ORANGE_MONEY` restent rejetés comme aujourd’hui. Les transactions `TOP_UP`, `COMMISSION_DEDUCTED` et `REFUND` gardent le contrat métier existant.

## Contrat API cible

Les réponses de devis/paiement ajoutent `currency`, sans supprimer immédiatement les champs EUR utilisés par les anciens clients. Les nouveaux champs deviennent la source de l’affichage et du PaymentSheet.

Exemple :

```json
{
  "amount": 5100,
  "currency": "cad",
  "amountEur": 35.00,
  "commissionAmount": 612,
  "commissionCurrency": "cad",
  "paymentMethodTypes": ["card"]
}
```

Les montants décimaux d’API restent des montants majeurs pour préserver les contrats existants ; la conversion en unités mineures est réservée à l’appel Stripe.

## Flutter

- Ajouter un modèle partagé de devise supportée avec code ISO, symbole, précision et libellé.
- Utiliser `intl` pour formater les montants selon la devise renvoyée par l’API.
- Propager `currency` dans les modèles de paiement, états BLoC, événements et paramètres PaymentSheet.
- Remplacer les formats EUR codés en dur uniquement dans les écrans concernés par le paiement et le wallet.
- Conserver le wallet affiché en EUR, avec une information explicite lors d’une recharge dans une autre devise.
- Ne pas utiliser `setState`, `Navigator.push()` ou placer un `DonyButton` de bottom sheet dans le contenu scrollable.

## Erreurs et fallback

- Devise non supportée : HTTP 422 `unsupported-currency`.
- Taux indisponible : HTTP 503 `exchange-rate-unavailable` avant création du PaymentIntent.
- Carte refusée par Stripe : le flux Stripe échoue comme aujourd’hui ; le client peut proposer le cash si le mode cash est autorisé.
- PaymentIntent existant : son `currency` est réutilisé, même si la préférence utilisateur a changé.
- Aucun changement des règles d’éligibilité Connect ou de la gestion existante du cash.

## Tests obligatoires

Backend :

- catalogue pays/devise et fallback EUR ;
- précision mineure EUR/USD/CAD/GBP/CHF/XOF/XAF ;
- conversion et arrondi déterministes ;
- PaymentIntent créé avec la devise demandée dans les deux flux ;
- devise persistée et réutilisée lors de l’idempotence ;
- transfert et remboursement avec la devise du paiement ;
- top-up Stripe local crédité en EUR après conversion ;
- webhook top-up idempotent ;
- anciens paiements migrés en EUR ;
- erreurs 422/503 RFC 7807 ;
- couverture globale backend maintenue à au moins 90 %.

Flutter :

- parsing et formatage de chaque devise ;
- résolution de la devise par défaut et préférence utilisateur ;
- propagation de `currency` jusqu’au PaymentSheet ;
- affichage du wallet EUR lors d’un top-up local ;
- fallback cash après indisponibilité/refus carte ;
- tests BLoC, datasource, repository et écrans de paiement ;
- couverture globale Flutter maintenue à au moins 90 %.

## Hors périmètre

- Mobile Money ;
- nouveau fournisseur de versement ;
- retrait ou transfert depuis le wallet ;
- changement de commission ou de règles cash ;
- refonte du modèle Connect ;
- modification des migrations existantes ;
- prise en charge de toutes les devises mondiales sans catalogue validé.
