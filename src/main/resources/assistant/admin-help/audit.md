---
id: audit
title: "Journal d'audit : retrouver qui a fait quoi dans la console"
category: systeme
order: 10
summary: "Le journal d'audit garde la trace de chaque action importante : date, administrateur, action et élément concerné. Il ne peut pas être modifié."
permission: AUDIT_VIEW
keywords: audit, qui a supprimé, qui a modifié, qui a validé, qui a remboursé, qui a suspendu, qui a changé, journal d'audit, historique des actions, traçabilité, qui a fait quoi, logs, journal, acteur, administrateur, action, entité, contrôle, enquête, preuve, vérifier une action, historique admin, filtrer par date
routes:
  - path: /audit
    label: Ouvrir le journal d'audit
---
## À quoi ça sert

Chaque action importante faite dans la console (suspendre un compte, rembourser, valider une identité, changer un réglage...) laisse une trace dans le **journal d'audit**. La page **Audit** (menu **Système**) permet de retrouver qui a fait quoi, et quand.

> **Bon à savoir :** le journal ne peut être ni modifié ni effacé, par personne. Il sert de preuve.

## Comprendre ce que vous voyez

Chaque ligne est une action :

- **Date** : le moment de l'action.
- **Acteur** : l'administrateur (ou le système) qui l'a faite.
- **Action** : ce qui a été fait. Les actions connues sont écrites en clair (par exemple « Fichier DAC7 généré »).
- **Entité** : le type d'élément concerné (utilisateur, paiement, colis...) et son identifiant.
- **Détails** : cliquez pour afficher les informations complémentaires, **Masquer** pour les refermer.

## Étapes : retrouver une action

1. Ouvrez [Audit](/audit).
2. Remplissez un ou plusieurs filtres :
   - **Action** : un mot de l'action recherchée.
   - **Type d'entité** : par exemple PAYMENT pour un paiement, USER pour un utilisateur.
   - **ID entité** : l'identifiant exact de l'élément.
   - **ID acteur** : l'identifiant de l'administrateur.
   - **Du** et **Au** : la période.
3. Cliquez sur **Filtrer**. **Réinitialiser** efface tous les filtres.

Les filtres restent dans l'adresse de la page : vous pouvez copier le lien pour le partager avec un collègue.

## Bon à savoir

- Depuis la fiche d'un paiement, un lien ouvre directement le journal filtré sur ce paiement. Voir [La fiche d'un paiement](/aide/transactions-fiche-paiement).
- « Aucune entrée » : aucun résultat pour ces filtres. Élargissez la période ou retirez un filtre.

## Qui peut le faire

Il faut la permission AUDIT_VIEW (Super admin et Admin). Le Support n'a pas accès à cette page.
