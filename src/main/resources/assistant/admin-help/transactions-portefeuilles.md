---
id: transactions-portefeuilles
title: Voir les soldes (portefeuilles) des utilisateurs
category: argent
order: 90
summary: "L'onglet Soldes, Portefeuilles : le solde de chaque utilisateur par devise, avec des filtres par devise et par signe du solde."
permission: PAYMENT_VIEW
keywords: solde, soldes, portefeuille, portefeuilles, wallet, porte-monnaie, argent sur le compte, crédit utilisateur, solde négatif, solde positif, solde nul, devise, EUR, XOF, XAF, combien a un utilisateur, argent disponible, titulaire
routes:
  - path: /transactions?tab=wallets
    label: Ouvrir les portefeuilles
---
## À quoi ça sert

Chaque utilisateur a un **solde** dans l'application (aussi appelé portefeuille ou wallet). Il peut contenir de l'argent rechargé, un remboursement, un gain de parrainage, etc. L'onglet **Portefeuilles** de la page [Transactions](/aide/transactions) liste tous ces soldes.

## Étapes

1. Ouvrez [Transactions](/transactions?tab=wallets). Dans la section **Soldes**, choisissez le sous-onglet **Portefeuilles**.
2. Pour trouver une personne, tapez son nom, son pseudo ou son identifiant dans le champ de recherche.
3. Pour affiner, choisissez une **Devise** (ou **Toutes les devises**) et un signe : **Solde positif**, **Solde négatif**, **Solde nul** (ou **Tous les soldes**).
4. Cliquez sur le nom du titulaire pour ouvrir sa fiche, directement sur l'onglet **Argent**.

## Comprendre ce que vous voyez

- **Titulaire** : la personne qui possède le solde. **Compte supprimé** s'affiche quand le compte n'existe plus.
- **Devise** : un utilisateur peut avoir un solde par devise (EUR, XOF, XAF...).
- **Solde** : le montant disponible.
- **Mis à jour** : la date du dernier mouvement.

> **Bon à savoir :** un **solde négatif** est anormal en temps normal. Il apparaît par exemple après un remboursement ou une correction. Ouvrez la fiche de l'utilisateur pour comprendre d'où il vient.

## Pour aller plus loin

- Le détail des mouvements d'un solde se trouve dans la fiche de l'utilisateur. Voir [L'onglet Argent de la fiche](/aide/fiche-utilisateur-argent).
- Corriger un solde : voir [Corrections de solde à approuver](/aide/corrections-solde).
- Un utilisateur qui veut récupérer son solde : voir [Demandes de remboursement de solde](/aide/transactions-remboursements-solde).

## Qui peut le faire

Il faut la permission PAYMENT_VIEW (Super admin, Admin et Support). Le lien vers la fiche n'apparaît que si vous pouvez voir les utilisateurs.
