---
id: transactions
title: "Transactions : suivre tout l'argent qui passe par Yadony"
category: argent
order: 10
summary: La page Transactions réunit paiements, litiges bancaires, mobile money, soldes, remboursements de solde et commissions cash.
permission: PAYMENT_VIEW
keywords: transactions, paiements, argent, séquestre, escrow, versement voyageur, remboursement, litige bancaire, contestation carte, mobile money, orange money, wave, soldes, portefeuilles, wallet, remboursement wallet, commissions cash, espèces, commission mobile money, pastille rouge, à traiter, finances, compta, onglets transactions
routes:
  - path: /transactions
    label: Ouvrir Transactions
  - path: /transactions?tab=chargebacks
    label: Litiges bancaires
  - path: /transactions?tab=mobile-money
    label: Opérations mobile money
  - path: /transactions?tab=wallets
    label: Soldes des utilisateurs
---
## À quoi ça sert

La page **Transactions** (menu **Argent**) montre tout l'argent qui circule dans Yadony : ce que les expéditeurs paient, ce qui est gardé en attente de livraison (le séquestre), ce qui est versé aux voyageurs, ce qui est remboursé, et l'argent laissé sur les portefeuilles des utilisateurs.

Le sous-titre de la page est « Paiements & escrow ». « Escrow » veut dire séquestre : Yadony garde l'argent de l'expéditeur jusqu'à la livraison du colis, puis le verse au voyageur.

## Étapes

1. Dans le menu de gauche, ouvrez le groupe **Argent**, puis cliquez sur **Transactions**.
2. En haut, choisissez une des quatre sections : **Paiements**, **Mobile money**, **Soldes** ou **Litiges bancaires**.
3. Dans **Mobile money** et **Soldes**, une seconde rangée d'onglets apparaît pour choisir la liste.
4. Cliquez sur une ligne ou sur ses liens pour aller plus loin.

## Comprendre ce que vous voyez

### Les quatre sections

- **Paiements** : un paiement par colis ou par négociation. C'est ici que l'on ouvre la fiche d'un paiement pour débloquer, rembourser ou relancer. Voir [Rechercher et filtrer les paiements](/aide/transactions-paiements-recherche).
- **Mobile money** : sous-onglets **Opérations** (chaque dépôt, versement ou remboursement chez l'opérateur) et **Commissions** (ce que Yadony a gagné sur les paiements mobile money). Voir [Opérations mobile money](/aide/transactions-mobile-money) et [Commissions mobile money](/aide/transactions-commissions-mobile-money).
- **Soldes** : sous-onglets **Portefeuilles**, **Remboursements wallet** et **Commissions cash**. Voir [Soldes des utilisateurs](/aide/transactions-portefeuilles), [Demandes de remboursement de solde](/aide/transactions-remboursements-solde) et [Commissions cash](/aide/transactions-commissions-cash).
- **Litiges bancaires** : les contestations faites par un porteur de carte auprès de sa banque. Voir [Litiges bancaires](/aide/transactions-litiges-bancaires).

### Les pastilles rouges

Chaque section porte une pastille rouge avec le nombre d'éléments qui demandent une action. Le nombre apparaît après un court chargement. Si le message **Nombres des sections indisponibles** s'affiche, cliquez sur **Réessayer**.

Sur un serveur plus ancien, vous pouvez voir à la place sept onglets à plat (**Paiements**, **Litiges bancaires**, **Mobile money**, **Commissions mobile money**, **Portefeuilles**, **Remboursements wallet**, **Commissions cash**). Le contenu est le même.

## Bon à savoir

- L'adresse de la page garde l'onglet ouvert et vos filtres. Vous pouvez copier l'adresse et l'envoyer à un collègue : il verra la même liste.
- Chaque onglet garde ses propres filtres. Quand vous revenez sur un onglet, vous retrouvez ce que vous aviez choisi.
- Les montants ne sont jamais additionnés entre devises : les euros et les francs CFA restent séparés.
- Depuis une fiche utilisateur, les liens vers les paiements ou le portefeuille de la personne ouvrent cette page déjà filtrée.

## Qui peut le faire

Il faut la permission de voir les paiements. Super admin, Admin et Support peuvent ouvrir la page. Les gestes qui déplacent de l'argent (débloquer, rembourser, relancer) sont réservés au Super admin et à l'Admin.
