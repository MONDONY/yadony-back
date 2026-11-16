---
id: rechercher-un-colis
title: Rechercher et filtrer les colis
category: activite
order: 13
summary: Retrouver un colis par numéro de suivi, expéditeur, voyageur, destinataire ou trajet, et filtrer par statut, période, paiement ou contenu.
permission: BID_VIEW
keywords: rechercher un colis, trouver un colis, numéro de suivi, n° de suivi, tracking, chercher expéditeur, chercher voyageur, chercher destinataire, chercher un trajet, ville de départ, ville d'arrivée, suggestions, filtre statut, statut précis, période, dates, mode de paiement, espèces, carte, mobile money, wave, orange money, contenu, vêtements, réinitialiser filtres, puces de filtre
routes:
  - path: /colis?view=ALL
    label: Rechercher dans tous les colis
  - path: /colis?view=ALL&paymentMethod=CASH
    label: Colis payés en espèces
  - path: /colis?view=ALL&paymentMethod=MOBILE_MONEY
    label: Colis payés en mobile money
---
## À quoi ça sert

La recherche de la page **Colis** permet de retrouver vite un envoi précis, par exemple quand un client appelle avec son numéro de suivi ou son nom. Les filtres servent à sortir une liste ciblée (tous les colis payés en espèces d'une semaine, tous les colis de vêtements...).

## Étapes

1. Ouvrez **Colis**, onglet **Colis**. Pensez à choisir la vue **Tous** si vous cherchez un colis ancien ou terminé : la recherche s'applique dans la vue choisie.
2. Dans la liste déroulante à gauche du champ, choisissez où chercher : **Tout**, **N° de suivi**, **Expéditeur**, **Voyageur**, **Destinataire** ou **Trajet**.
3. Tapez votre texte dans le champ « N° de suivi, personne, trajet… ». Des suggestions apparaissent, rangées par groupes : **Expéditeurs**, **Voyageurs**, **Destinataires**, **Trajets** et **N° de suivi**.
4. Cliquez sur une suggestion, ou appuyez sur Entrée, ou cliquez sur **Chercher**.
5. Ajoutez des filtres si besoin (voir ci-dessous), puis ouvrez le colis voulu en cliquant sur sa ligne.

## Comprendre ce que vous voyez

Ce que fait chaque suggestion :

- Une personne (expéditeur, voyageur, destinataire) lance la recherche sur son nom, limitée à ce rôle. Le nombre de colis de la personne est affiché à droite.
- Un trajet cherche ses deux villes à la fois.
- Un numéro de suivi ouvre directement la fiche du colis. Si vous tapez le numéro exact et appuyez sur Entrée, la fiche s'ouvre aussi.

Les filtres disponibles :

- **Statut précis** : un seul statut parmi les treize (voir [Comprendre les statuts d'un colis](/aide/statuts-colis)). « Tous les statuts » retire le filtre.
- **Période** : une date de début et une date de fin. Le bouton **Effacer** vide les deux dates.
- **Mode de paiement** : **Carte**, **Espèces à la remise** ou **Mobile money**. Le groupe **Anciens modes** propose **Wave** et **Orange Money** pour les colis passés.
- **Contenu** : un mot du contenu déclaré, par exemple « vêtements ». Validez avec Entrée.

Chaque filtre actif apparaît sous forme de puce (par exemple « Statut : En transit » ou « Expéditeur : Awa Diop »). Cliquez sur la croix d'une puce pour la retirer. Le bouton **Réinitialiser** retire tous les filtres d'un coup.

## Bon à savoir

- Les nombres affichés sur les vues rapides suivent la recherche et les filtres de période, de paiement et de contenu.
- Les filtres sont gardés dans l'adresse de la page : copiez le lien pour partager exactement la même liste.
- Si aucun colis ne correspond, le tableau affiche « Aucun colis ».
- La barre de recherche générale en haut de la console peut aussi ouvrir directement un colis.

## Qui peut le faire

Tous les rôles qui voient la page Colis (Super admin, Admin, Support).
