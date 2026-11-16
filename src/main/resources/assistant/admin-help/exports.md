---
id: exports
title: Exporter des données en CSV (transactions, utilisateurs, litiges)
category: systeme
order: 20
summary: "La page Exports télécharge un fichier CSV des transactions, des utilisateurs, des litiges ou des versements du fonds de garantie sur une période."
permission: EXPORT_RUN
keywords: export, exporter, CSV, Excel, tableur, télécharger, extraction, extraire les données, rapport, reporting, comptabilité, export transactions, export utilisateurs, export litiges, versements fonds de garantie, période, fichier
routes:
  - path: /exports
    label: Ouvrir les exports
---
## À quoi ça sert

La page **Exports** (menu **Système**) télécharge un fichier **CSV**, lisible dans Excel ou Google Sheets, pour faire des analyses ou de la comptabilité.

Quatre types d'export sont proposés :

- **Transactions** : les paiements.
- **Utilisateurs** : les comptes.
- **Litiges** : les litiges ouverts sur les colis.
- **Versements fonds de garantie** : les sommes versées par le fonds de garantie.

## Étapes

1. Ouvrez [Exports](/exports).
2. Choisissez le **Type d'export**.
3. Choisissez la période avec **Du** et **Au**.
4. Cliquez sur **Exporter en CSV**. Le bouton affiche **Génération…** le temps de préparer le fichier, puis le téléchargement démarre.

Si un message « L'export a échoué » apparaît, réessayez avec une période plus courte.

> **Attention :** ces fichiers contiennent des données personnelles. Ne les envoyez pas par email non sécurisé et supprimez-les de votre ordinateur quand vous n'en avez plus besoin.

## Autres exports dans la console

- Le bilan mensuel du mobile money a son propre bouton **Exporter en CSV**. Voir [Commissions mobile money](/aide/transactions-commissions-mobile-money).
- La déclaration fiscale annuelle a son propre fichier. Voir [Déclaration DAC7](/aide/declaration-dac7).

## Qui peut le faire

Il faut la permission EXPORT_RUN (Super admin et Admin). Le Support n'a pas accès à cette page.
