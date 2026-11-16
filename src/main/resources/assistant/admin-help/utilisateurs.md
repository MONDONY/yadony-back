---
id: utilisateurs
title: Rechercher et filtrer les utilisateurs
category: comptes
order: 10
summary: Trouver un compte par nom, e-mail, téléphone ou identifiant, filtrer par statut et ouvrir sa fiche.
permission: USER_VIEW
keywords: utilisateurs, liste des utilisateurs, chercher un utilisateur, trouver un compte, rechercher un client, recherche par téléphone, recherche par e-mail, numéro de téléphone, identifiant utilisateur, UID Firebase, comptes suspendus, comptes bannis, suppression demandée, filtre statut, membres, voyageurs, expéditeurs, statut KYC, note moyenne, ouvrir une fiche, annuaire des comptes
routes:
  - path: /users
    label: Ouvrir Utilisateurs
  - path: /users?status=SUSPENDED
    label: Voir les comptes suspendus
  - path: /users?status=BANNED
    label: Voir les comptes bannis
  - path: /users?status=PENDING_DELETION
    label: Voir les suppressions demandées
---
## À quoi ça sert

La page **Utilisateurs** (menu **Comptes**) liste tous les comptes de l'application : voyageurs et expéditeurs. Elle sert à retrouver une personne, à repérer les comptes suspendus ou bannis, et à ouvrir la fiche complète d'un compte pour agir dessus.

Raccourci clavier : tapez **g** puis **U** pour y aller depuis n'importe quel écran.

## Étapes

### Retrouver une personne

1. Ouvrez **Comptes** puis **Utilisateurs**.
2. Dans le champ de recherche (texte grisé « Nom, e-mail, téléphone, UID ou identifiant »), tapez ce que vous connaissez de la personne.
3. Appuyez sur la touche **Entrée** pour lancer la recherche.
4. Cliquez sur la ligne de la personne : sa fiche complète s'ouvre.

> **Bon à savoir :** l'UID Firebase et l'identifiant du compte doivent être collés en entier, sinon la recherche ne les trouve pas.

### Filtrer par statut

Au-dessus du tableau, cliquez sur l'une des pastilles :

- **Tous** : tous les comptes.
- **Actifs** : comptes utilisables normalement.
- **Suspendus** : accès bloqué temporairement par un administrateur.
- **Bannis** : comptes exclus définitivement.
- **Suppression demandée** : la personne a demandé la suppression de son compte, qui n'est pas encore exécutée.

Le filtre et la recherche restent dans l'adresse de la page : vous pouvez recharger la page ou envoyer le lien à un collègue, il verra la même liste.

## Comprendre ce que vous voyez

Le tableau affiche 20 comptes par page. Utilisez les boutons de pagination en bas pour voir la suite.

- **Nom** : prénom et nom. Un tiret s'affiche si la personne ne les a pas renseignés.
- **Email** : l'adresse e-mail. Si le compte n'en a pas (inscription par téléphone, ou compte anonymisé), vous voyez à la place le début de l'identifiant du compte, en petits caractères. Survolez-le pour voir l'identifiant complet.
- **Téléphone** : le numéro du compte.
- **Ville** : la ville déclarée.
- **Statut** : **Actif**, **Suspendu**, **Banni** ou **Suppression**.
- **KYC** : l'état de la vérification d'identité, sous forme de code (par exemple VERIFIED pour vérifiée, PENDING pour en cours, REJECTED pour refusée, NOT_STARTED pour non commencée).
- **Note** : la note moyenne reçue des autres utilisateurs.

Si la liste ne se charge pas, un message d'erreur s'affiche avec un bouton pour réessayer. « Aucun utilisateur » veut dire que rien ne correspond à votre recherche ou à votre filtre.

## Bon à savoir

- Changer de filtre ou de recherche vous ramène à la première page.
- Les gestes sur un compte (suspendre, bannir, supprimer...) se font depuis la fiche, pas depuis la liste. Voir [Lire la fiche complète d'un utilisateur](/aide/fiche-utilisateur).
- Pour une demande de suppression au titre du RGPD, passez par [Demandes RGPD](/aide/demandes-rgpd).
- En environnement de test (staging), un super administrateur voit en plus une pastille **Testeurs recette** et des cases à cocher. Voir [Mode recette](/aide/mode-recette-testeurs).

## Qui peut le faire

Tous les profils qui ont le droit de voir les utilisateurs : Super admin, Admin et Support.
