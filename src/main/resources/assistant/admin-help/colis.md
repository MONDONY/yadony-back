---
id: colis
title: Suivre les colis, les annonces et les demandes d'envoi
category: activite
order: 10
summary: La page Colis regroupe trois onglets (Colis, Annonces, Demandes) pour suivre chaque envoi, chaque trajet publié et chaque demande d'expéditeur.
permission: BID_VIEW
keywords: colis, page colis, liste des colis, suivi des colis, envois, expéditions, paquets, annonces, trajets, voyageurs, demandes d'envoi, demandes, expéditeurs, vues rapides, à traiter, en cours, livrés, terminés, annulés, tous les colis, numéro de suivi, tableau des colis, net voyageur, statut du colis, onglets colis, bagages, bateau
routes:
  - path: /colis
    label: Ouvrir Colis
  - path: /colis?view=TODO
    label: Voir les colis à traiter
  - path: /colis?tab=announcements
    label: Voir les annonces
  - path: /colis?tab=demandes
    label: Voir les demandes d'envoi
---
## À quoi ça sert

La page **Colis** est le tableau de bord de toute l'activité de transport. Un colis naît quand un expéditeur confie un envoi à un voyageur qui a publié un trajet (en avion, en voiture, en bateau...). La page a trois onglets en haut :

- **Colis** : tous les envois, du premier contact jusqu'à la livraison.
- **Annonces** : les trajets publiés par les voyageurs. Voir [Consulter les annonces des voyageurs](/aide/annonces-voyageurs).
- **Demandes** : les demandes d'envoi publiées par les expéditeurs. Voir [Consulter les demandes d'envoi](/aide/demandes-envoi).

## Étapes

1. Ouvrez **Colis** dans le menu. L'onglet **Colis** s'affiche.
2. Choisissez une vue rapide sous les onglets : **À traiter**, **En cours**, **Livrés depuis 3 jours**, **Terminés**, **Annulés / refusés** ou **Tous**. Chaque vue affiche son nombre de colis.
3. Si besoin, tapez un numéro de suivi, un nom ou une ville dans la recherche, puis cliquez sur **Chercher**.
4. Affinez avec les filtres **Statut précis**, **Période**, **Mode de paiement** et **Contenu**.
5. Cliquez sur une ligne pour ouvrir la fiche du colis sur le côté droit.

## Comprendre ce que vous voyez

Le tableau de l'onglet **Colis** a sept colonnes :

- **Suivi** : le numéro de suivi et la date de création.
- **Colis** : le contenu déclaré et le poids.
- **Trajet** : les villes de départ et d'arrivée, et la date de départ.
- **Personnes** : l'expéditeur, une flèche, puis le voyageur. En dessous, le destinataire (« Pour ... »). Les noms sont cliquables si vous avez accès aux fiches utilisateur.
- **Net voyageur** : ce que le voyageur touchera, et le mode de paiement (Carte, Espèces à la remise, Mobile money...).
- **Statut** : l'étape du colis, avec parfois une étiquette d'alerte (Litige, No-show, Paiement, Retard).
- **Mis à jour** : la date du dernier changement.

Les vues rapides regroupent les statuts :

- **À traiter** : les colis qui ont une alerte (litige ouvert, no-show, paiement en attente, retard). La vue devient rouge quand il en reste.
- **En cours** : les colis pas encore terminés, de l'attente de paiement jusqu'à l'arrivée.
- **Livrés depuis 3 jours** : les livraisons récentes.
- **Terminés** : tous les colis livrés.
- **Annulés / refusés** : refusés, annulés, expirés, colis refusé à la remise et absences.
- **Tous** : sans tri.

Le détail de chaque statut est dans [Comprendre les statuts d'un colis](/aide/statuts-colis).

## Bon à savoir

- La page s'ouvre sur **À traiter** par défaut. Si la vue est vide, le message « Rien à traiter pour l'instant. » s'affiche avec un bouton **Voir tous les colis**.
- Les nombres d'« À traiter » se recoupent avec les autres vues : un colis en retard compte aussi dans **En cours**.
- Les filtres choisis restent dans l'adresse de la page. Vous pouvez copier le lien et l'envoyer à un collègue.
- Si les nombres ne se chargent pas, le message « Nombres indisponibles » apparaît avec un bouton **Réessayer**.

## Qui peut le faire

Il faut la permission de voir les colis. Les rôles Super admin, Admin et Support l'ont tous. Certaines actions de la fiche (annuler, ouvrir un litige, retirer une annonce) demandent des droits en plus, indiqués dans chaque fiche d'aide.
