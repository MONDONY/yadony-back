---
id: mettre-en-maintenance
title: "Mettre l'application en maintenance, puis la rouvrir"
category: systeme
order: 31
summary: "Fermer l'application mobile pour tous les utilisateurs pendant une intervention, en gardant l'accès à quelques comptes autorisés, puis la rouvrir."
permission: CONFIG_MANAGE
keywords: maintenance, mode maintenance, fermer l'application, couper l'app, bloquer l'application, écran de maintenance, panne, intervention, mise en production, rouvrir l'application, comptes autorisés, testeurs, message de maintenance, application ouverte
routes:
  - path: /parametres
    label: Ouvrir la carte Maintenance
---
## À quoi ça sert

Pendant une intervention (panne, mise à jour importante), vous pouvez afficher un **écran de maintenance** dans l'application mobile. Les utilisateurs ne peuvent plus rien faire tant que la maintenance est active, sauf les **comptes autorisés** (testeurs, propriétaire).

La carte **Maintenance** se trouve en haut de la page [Paramètres](/parametres). Son état est affiché : **Application ouverte** ou **En maintenance**.

## Étapes : activer la maintenance

1. Ouvrez [Paramètres](/parametres).
2. Si besoin, écrivez le message à afficher aux utilisateurs, puis **Enregistrer le message**.
3. Dans **Comptes autorisés pendant la maintenance**, cliquez sur **Ajouter un compte**, cherchez la personne (testeur, propriétaire...) puis **Ajouter**. Ces comptes continueront d'utiliser l'application pour vérifier que tout marche.
4. Cliquez sur **Activer la maintenance**, puis confirmez avec **Activer**.

> **Attention :** tous les utilisateurs, sauf les comptes autorisés, voient immédiatement l'écran de maintenance et ne peuvent plus rien faire dans l'application.

## Étapes : rouvrir l'application

1. Revenez sur la carte **Maintenance**.
2. Cliquez sur **Rouvrir l'application**, puis **Rouvrir**.
3. L'écran de maintenance disparaît et les utilisateurs retrouvent l'application là où ils l'avaient laissée.

## Bon à savoir

- La console d'administration reste utilisable pendant la maintenance.
- Retirez les comptes autorisés devenus inutiles avec l'icône à côté de leur nom.
- Prévenez l'équipe support avant d'activer la maintenance : les utilisateurs risquent d'écrire.

## Qui peut le faire

Il faut la permission CONFIG_MANAGE (Super admin et Admin).
