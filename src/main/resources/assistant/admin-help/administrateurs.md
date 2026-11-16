---
id: administrateurs
title: "Administrateurs : créer un compte, changer un rôle, réinitialiser un mot de passe"
category: systeme
order: 40
summary: "Gérer les comptes de la console : créer un admin ou un support, changer son rôle, le désactiver, réinitialiser son mot de passe ou le supprimer."
permission: ADMIN_MANAGE
keywords: administrateurs, comptes admin, créer un administrateur, nouvel administrateur, ajouter un collègue, donner accès à la console, rôle, changer le rôle, passer ADMIN, passer SUPPORT, super admin, désactiver un compte admin, activer, réinitialiser le mot de passe, mot de passe oublié, mot de passe temporaire, identifiants temporaires, supprimer un administrateur, départ d'un collègue, accès back-office
routes:
  - path: /administrateurs
    label: Ouvrir les administrateurs
---
## À quoi ça sert

La page **Administrateurs** (menu **Système**) gère les personnes qui ont accès à la console : création des comptes, rôles, accès. Les rôles sont expliqués dans [Rôles et permissions](/aide/roles-et-permissions).

## Comprendre ce que vous voyez

Une ligne par compte : **Email**, **Rôle** (SUPER_ADMIN, ADMIN ou SUPPORT), **Statut** (**Actif** ou **Désactivé**), **Dernière connexion**, et **Changement requis** quand la personne doit encore changer son mot de passe temporaire.

## Étapes : créer un compte

1. Ouvrez [Administrateurs](/administrateurs) et cliquez sur **Nouvel administrateur**.
2. Saisissez l'**Email** de la personne et choisissez le **Rôle** : ADMIN ou SUPPORT.
3. Cliquez sur **Créer**.
4. La fenêtre **Identifiants temporaires** affiche l'email et un mot de passe temporaire. Cliquez sur **Copier** et transmettez-les à la personne par un moyen sûr, puis **Fermer**.
5. À sa première connexion, la personne devra choisir son propre mot de passe et activer la double authentification. Voir [Se connecter](/aide/se-connecter).

> **Attention :** les identifiants temporaires ne sont affichés qu'une seule fois. Copiez-les avant de fermer la fenêtre.

## Étapes : les autres gestes

- **Changer le rôle** : cliquez sur **Passer ADMIN** ou **Passer SUPPORT**, puis **Confirmer**. Le nouvel accès s'applique immédiatement.
- **Désactiver ou réactiver** : cliquez sur **Désactiver** (ou **Activer**), puis **Confirmer**. Un compte désactivé ne peut plus se connecter, mais reste dans la liste.
- **Réinitialiser le mot de passe** (mot de passe oublié) : cliquez sur **Réinitialiser**, puis confirmez. Un nouveau mot de passe temporaire s'affiche ; l'ancien ne marche plus. Transmettez-le comme à la création.
- **Supprimer** (départ d'un collègue) : cliquez sur **Supprimer**, recopiez l'email exact du compte, puis **Supprimer**.

> **Attention :** la suppression désactive le compte et coupe toutes ses sessions. Il disparaît de la liste et ne peut plus se connecter.

## Bon à savoir

- Les boutons sont grisés sur un compte SUPER_ADMIN : il ne se gère pas depuis cette page.
- Vous ne pouvez pas supprimer votre propre compte. Pour votre mot de passe, voir [Changer mon mot de passe](/aide/changer-mot-de-passe).
- Toutes ces actions sont tracées dans le journal d'[Audit](/aide/audit).

## Qui peut le faire

Il faut la permission ADMIN_MANAGE : seul le Super admin a accès à cette page.
