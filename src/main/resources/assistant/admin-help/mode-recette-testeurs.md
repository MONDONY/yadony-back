---
id: mode-recette-testeurs
title: Désigner des comptes testeurs en mode recette (staging)
category: comptes
order: 49
summary: En environnement de test seulement, activer ou retirer le mode recette sur un compte ou sur plusieurs comptes à la fois.
permission: ADMIN_MANAGE
keywords: mode recette, testeur, compte testeur, testeurs recette, staging, environnement de test, recette, tests de l'application, valider une remise avant le départ, destinataire de son propre colis, activer le mode recette, désactiver le mode recette, sélection en masse, cases à cocher, super admin
routes:
  - path: /users
    label: Ouvrir Utilisateurs
---
## À quoi ça sert

Le **mode recette** sert uniquement aux tests, dans l'environnement de test (staging). Un compte testeur peut, en staging, valider une remise avant le départ du trajet et être destinataire de son propre colis. Chaque utilisation est tracée. Hors staging, ces réglages n'existent pas.

## Étapes

### Pour un seul compte

1. Ouvrez la fiche du compte depuis [Utilisateurs](/users).
2. Cliquez sur l'onglet **Gestion**.
3. Dans le bloc **Mode recette (staging)**, cliquez sur l'interrupteur **Compte testeur**.
4. Une question s'affiche : « Désigner ce compte comme testeur de recette ? » (ou « Retirer le statut de testeur à ce compte ? »).
5. Cliquez sur **Activer** (ou **Désactiver**). **Annuler** referme la question.

La pastille **Testeur** apparaît dans le bloc quand le mode est actif.

### Pour plusieurs comptes à la fois

1. Ouvrez [Utilisateurs](/users). En staging, une colonne de cases à cocher s'affiche, ainsi qu'une pastille **Testeurs recette** qui ne montre que les testeurs.
2. Cochez les comptes voulus, ou la case du haut pour toute la page.
3. Une barre apparaît avec le nombre de comptes sélectionnés. Le lien **Sélectionner les ... résultats du filtre** permet d'étendre la sélection à toutes les pages.
4. Cliquez sur **Activer le mode recette** ou **Désactiver le mode recette**.
5. Confirmez avec **Activer** ou **Désactiver**. **Désélectionner** vide la sélection.

Un message vert ou rouge indique le résultat.

## Comprendre ce que vous voyez

- « Indisponible hors staging. » : vous êtes sur l'environnement réel, le mode recette n'y existe pas.
- La pastille **Testeur** à côté d'un nom dans la liste signale un compte testeur.
- Si le nombre de résultats dépasse ce qui peut être traité en une fois, un message vous invite à affiner le filtre pour le reste.

## Bon à savoir

- Changer de filtre ou de recherche vide la sélection, pour ne jamais viser des comptes que vous ne voyez plus.
- Les activations et retraits sont enregistrés dans le journal du compte (**Mode recette activé**, **Mode recette retiré**).

## Qui peut le faire

Super admin uniquement, et seulement en staging.
