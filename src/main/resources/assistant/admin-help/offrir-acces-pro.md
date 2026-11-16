---
id: offrir-acces-pro
title: Offrir ou révoquer un accès PRO gratuit
category: comptes
order: 48
summary: Donner les avantages PRO à un utilisateur sans abonnement payant, ou retirer cet accès offert.
permission: USER_PRO_GRANT
keywords: accès PRO, compte PRO, offrir PRO, PRO gratuit, abonnement PRO, avantages PRO, geste commercial, révoquer l'accès PRO, retirer PRO, abonnement payant, résilier abonnement, source de l'accès, grâce historique, accès offert par un administrateur, onglet gestion
routes:
  - path: /users
    label: Chercher un utilisateur
---
## À quoi ça sert

Le **Compte PRO** donne des avantages réservés aux abonnés. Un administrateur peut l'offrir gratuitement à une personne (partenaire, geste commercial, test), puis le retirer.

Ne confondez pas avec le badge **GP Pro**, qui distingue les voyageurs réguliers de confiance. Voir [Accorder ou retirer le badge GP Pro](/aide/gp-pro).

## Étapes

### Offrir un accès PRO

1. Ouvrez la fiche de la personne depuis [Utilisateurs](/users).
2. Cliquez sur l'onglet **Gestion**.
3. Dans le bloc **Accès PRO**, cliquez sur **Offrir un accès PRO**.
4. La fenêtre **Offrir un accès PRO** explique que l'utilisateur obtient immédiatement les avantages PRO sans payer. Écrivez le **Motif (obligatoire)**.
5. Cliquez sur **Offrir l'accès PRO**.

La pastille **Compte PRO** apparaît dans l'en-tête de la fiche.

### Révoquer l'accès offert

1. Dans le bloc **Accès PRO**, cliquez sur **Révoquer l'accès offert**.
2. La fenêtre indique que l'utilisateur repassera en compte standard. Cliquez sur **Révoquer**.

## Comprendre ce que vous voyez

La ligne **Source** dit d'où vient l'accès :

- **abonnement payant** : la personne paie elle-même son abonnement.
- **accès offert par un administrateur** : suivi du motif saisi.
- **grâce historique** : accès conservé pour d'anciens utilisateurs.
- **aucun** : pas d'accès PRO.

Les boutons s'adaptent :

- **Offrir un accès PRO** n'apparaît pas si un accès est déjà offert, ni si un abonnement payant est en cours.
- **Révoquer l'accès offert** n'apparaît que pour un accès offert par un administrateur.
- « Abonnement payant : la résiliation se fait dans Stripe, pas ici. » : un abonnement payé ne se coupe pas depuis la console.

## Bon à savoir

- Le motif est journalisé avec votre identifiant d'administrateur.
- La révocation ne demande pas de motif.

## Qui peut le faire

Super admin et Admin. Le Support ne voit pas l'onglet **Gestion**.
