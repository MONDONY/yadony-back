---
id: fiche-utilisateur-gestion
title: Régler un compte depuis l'onglet Gestion
category: comptes
order: 34
summary: "L'onglet Gestion de la fiche : commission, limite mensuelle de trajets, accès PRO, badge GP Pro, mode recette et suppression du compte."
permission: USER_VIEW
keywords: onglet gestion, réglages du compte, paramètres utilisateur, commission personnalisée, dérogation de commission, limite de trajets, accès PRO, offrir PRO, badge GP Pro, accorder GP Pro, retirer GP Pro, mode recette, compte testeur, zone de danger, supprimer le compte, gérer un utilisateur
routes:
  - path: /users
    label: Chercher un utilisateur
---
## À quoi ça sert

L'onglet **Gestion** de la fiche utilisateur regroupe les réglages propres à un compte et la suppression définitive. Les gestes courants (suspendre, bannir, couper la messagerie) ne sont pas ici : ils sont dans le menu **Actions du compte** en haut de la fiche.

## Étapes

1. Ouvrez la fiche de la personne depuis [Utilisateurs](/users).
2. Cliquez sur l'onglet **Gestion**.
3. Repérez le bloc qui vous intéresse et suivez la fiche d'aide correspondante.

## Comprendre ce que vous voyez

L'onglet affiche des blocs, selon vos droits :

- **Commission** : indique « Taux global appliqué » ou « Dérogation actuelle ». Boutons **Appliquer** et **Réinitialiser**. Voir [Appliquer une commission personnalisée](/aide/commission-personnalisee).
- **Limite mensuelle de trajets** : la limite actuelle (ou « Aucune limite ») et le nombre de trajets publiés ce mois-ci. Bouton **Enregistrer**. Voir [Limiter le nombre de trajets par mois](/aide/limite-mensuelle-trajets).
- **Accès PRO** : la **Source** de l'accès (« abonnement payant », « accès offert par un administrateur », « grâce historique » ou « aucun »). Boutons **Offrir un accès PRO** et **Révoquer l'accès offert**. Voir [Offrir ou révoquer un accès PRO](/aide/offrir-acces-pro).
- **GP Pro** : « actif depuis le ... » ou « non attribué ». Boutons **Accorder GP Pro** ou **Retirer GP Pro**. Voir [Accorder ou retirer le badge GP Pro](/aide/gp-pro).
- **Mode recette (staging)** : un interrupteur **Compte testeur**, visible seulement par un super administrateur. Voir [Mode recette](/aide/mode-recette-testeurs).
- **Zone de danger** : le bouton **Supprimer le compte...**. Voir [Supprimer un compte utilisateur](/aide/supprimer-compte).

Si aucun bloc ne vous est accessible, la page affiche : « Aucun réglage de compte n'est disponible avec vos droits : les gestes courants sont dans « Actions du compte » ».

## Bon à savoir

- Une erreur renvoyée par le serveur s'affiche en rouge en haut de l'onglet.
- Chaque geste demande une confirmation. Les gestes importants sont enregistrés dans le journal du compte (onglet **Support et journal**).

## Qui peut le faire

L'onglet apparaît pour les profils qui ont au moins un de ces droits : commission, accès PRO, badge GP Pro, gestion des administrateurs ou suppression de compte. En pratique, Super admin et Admin. Le Support ne voit pas cet onglet. Le bloc **Mode recette** est réservé au Super admin.
