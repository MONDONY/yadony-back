---
id: fiche-utilisateur-support-journal
title: Voir les conversations support et le journal d'un compte
category: comptes
order: 33
summary: "L'onglet Support et journal de la fiche : conversations support du compte et gestes des administrateurs sur ce compte."
permission: SUPPORT_TICKET_VIEW
keywords: conversations support, tickets d'un utilisateur, messages au support, historique support, journal du compte, audit du compte, qui a suspendu, qui a banni, gestes des administrateurs, historique des actions, motif de suspension, traçabilité, compte réactivé, commission modifiée, suppression annulée, onglet support, onglet journal
routes:
  - path: /users
    label: Chercher un utilisateur
  - path: /support
    label: Ouvrir le Support
---
## À quoi ça sert

L'onglet **Support et journal** de la fiche utilisateur répond à deux questions : « qu'est-ce que cette personne a demandé au support ? » et « qui a fait quoi sur ce compte, et pourquoi ? ».

## Étapes

1. Ouvrez la fiche de la personne depuis [Utilisateurs](/users).
2. Cliquez sur l'onglet **Support et journal**.
3. Dans **Conversations support**, cliquez sur une conversation pour l'ouvrir, ou sur **Voir tout** pour la liste complète des conversations de ce compte dans la page [Support](/support).
4. Plus bas, lisez le bloc **Gestes des administrateurs sur ce compte**.

## Comprendre ce que vous voyez

### Conversations support

Les dernières conversations du compte, avec leur statut. Le nombre total s'affiche à côté de **Voir tout** quand il y en a plus que celles affichées.

### Gestes des administrateurs sur ce compte

Un tableau avec quatre colonnes :

- **Date** : le jour et l'heure (heure de Paris).
- **Action** : ce qui a été fait, en clair. Exemples : **Compte suspendu**, **Suspension levée**, **Compte banni**, **Compte réactivé**, **Publication suspendue**, **Publication rétablie**, **Messagerie coupée**, **Messagerie rétablie**, **Commission personnalisée modifiée**, **Limite mensuelle de trajets modifiée**, **Statut GP Pro retiré**, **Versements retenus**, **Retenue levée**, **Suppression annulée par un administrateur**, **Compte supprimé par un administrateur**, **Suppression RGPD exécutée**, **Mode recette activé**.
- **Par** : la personne qui a fait le geste. Un tiret s'affiche quand aucun nom n'est enregistré.
- **Motif** : la raison saisie au moment du geste, quand il y en a une.

Une action inconnue s'affiche comme « Action non répertoriée » suivie de son code.

## Bon à savoir

- Ce journal ne montre que les gestes enregistrés sur le compte lui-même. L'identité, le portefeuille, les avis et les signalements ont leur propre historique : regardez l'onglet **Identité (KYC)**, l'onglet **Argent** ou l'écran du signalement.
- Pour l'historique complet de toute la console, utilisez la page [Audit](/audit).
- Pour écrire à la personne, utilisez **Actions du compte** puis **Écrire à l'utilisateur**. Voir [Lire la fiche complète d'un utilisateur](/aide/fiche-utilisateur).

## Qui peut le faire

L'onglet apparaît si vous pouvez voir les conversations support ou le journal d'audit. Le Support voit les conversations mais pas le bloc **Gestes des administrateurs sur ce compte**, réservé aux profils qui ont accès à l'audit (Super admin et Admin).
