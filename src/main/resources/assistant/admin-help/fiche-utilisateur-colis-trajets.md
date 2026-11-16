---
id: fiche-utilisateur-colis-trajets
title: Voir les colis, trajets et demandes d'un utilisateur
category: comptes
order: 30
summary: "L'onglet Colis, trajets et demandes de la fiche : tous les envois, annonces de voyage et demandes d'envoi d'un compte."
permission: BID_VIEW
keywords: colis d'un utilisateur, trajets d'un voyageur, annonces de trajet, demandes d'envoi, historique des colis, envois d'un client, kilos disponibles, prix au kilo, date de départ, référence colis, numéro de suivi, colis livré, colis en transit, négociations ouvertes, étape du trajet, voyage en plusieurs étapes, onglet colis
routes:
  - path: /users
    label: Chercher un utilisateur
---
## À quoi ça sert

L'onglet **Colis, trajets et demandes** de la fiche utilisateur montre tout ce que la personne a envoyé, transporté ou demandé. Il sert à répondre à une question comme « où en est son colis ? », « quels trajets a-t-il publiés ? » ou « a-t-il une demande d'envoi en cours ? ».

## Étapes

1. Ouvrez la fiche de la personne depuis [Utilisateurs](/users).
2. Cliquez sur l'onglet **Colis, trajets et demandes**.
3. Parcourez les trois blocs : **Colis**, **Trajets**, **Demandes d'envoi**.
4. Cliquez sur **Ouvrir** au bout d'une ligne pour aller sur l'écran détaillé de ce colis, de ce trajet ou de cette demande.
5. Si un bloc contient beaucoup de lignes, utilisez la pagination sous le bloc.

## Comprendre ce que vous voyez

### Colis

- **Référence** : le numéro de suivi, ou le début de l'identifiant à défaut.
- **Trajet** : la ville de départ et la ville d'arrivée.
- **Rôle** : **Expéditeur** si la personne envoie le colis, **Voyageur** si elle le transporte.
- **Statut** : **Attente paiement**, **En attente**, **Payé (séquestre)**, **Accepté**, **Remis**, **En transit**, **Arrivé**, **Livré**, **Refusé**, **Annulé**, **Absence (no-show)**, **Colis refusé**, **Expiré**.
- **Créé le**, **Livré le** et **Montant**.

### Trajets

Les annonces de voyage publiées par la personne quand elle est voyageur.

- **Trajet** et **Départ** (date du départ).
- **Statut** : **Brouillon**, **Publiée**, **Complète** (plus de place), **Voyage en cours**, **Terminée**, **Annulée**, **Retirée par un admin**.
- **Kilos disponibles** et **Prix au kilo**.
- **Étape** : pour un voyage en plusieurs étapes, par exemple « 2 sur 3 ». Un tiret s'il n'y a qu'une étape.

### Demandes d'envoi

Les demandes publiées par la personne quand elle cherche un voyageur pour son colis.

- **Trajet**, **Poids**, **Date souhaitée**, **Créée le**.
- **Statut** : **Brouillon**, **Ouverte**, **En négociation**, **Acceptée**, **Expirée**, **Annulée**, **Terminée**, **Retirée par l'admin**.
- **Négociations ouvertes** : le nombre de discussions en cours avec des voyageurs.

## Bon à savoir

- Un bloc vide affiche par exemple « Aucun colis pour ce compte ».
- Pour une vue rapide de ce qui bouge maintenant, l'onglet **Vue d'ensemble** liste les colis et trajets en cours ou livrés depuis 3 jours. Voir [Lire la fiche complète d'un utilisateur](/aide/fiche-utilisateur).
- Les dates et heures sont affichées à l'heure de Paris.

## Qui peut le faire

Il faut le droit de voir les colis. Super admin, Admin et Support l'ont.
