---
id: demandes-envoi
title: Consulter les demandes d'envoi des expéditeurs
category: activite
order: 22
summary: L'onglet Demandes liste les demandes publiées par les expéditeurs qui cherchent un voyageur, avec leurs négociations et signalements.
permission: BID_VIEW
keywords: demandes d'envoi, demande d'envoi, demandes, expéditeur cherche voyageur, colis à envoyer, budget, budget net, date souhaitée, date flexible, négociations, négos, offres des voyageurs, prix proposé, signalées, demandes signalées, brouillons, demandes expirées, demandes retirées, taille du colis, quartier de remise, paiements acceptés, recherche par téléphone, rechercher une demande
routes:
  - path: /colis?tab=demandes
    label: Voir les demandes d'envoi
---
## À quoi ça sert

Un expéditeur peut publier une demande d'envoi : il décrit son colis, sa destination, sa date et son budget, et les voyageurs lui font des offres. L'onglet **Demandes** de la page **Colis** liste ces demandes. Il sert à répondre à un expéditeur, suivre les négociations et repérer les demandes signalées.

## Étapes

1. Ouvrez **Colis**, puis cliquez sur l'onglet **Demandes**.
2. Choisissez un statut avec les pastilles : **Toutes**, **Ouvertes**, **En négociation**, **Acceptées**, **Terminées**, **Expirées**, **Annulées**, **Brouillons** ou **Retirées**.
3. Pour chercher, tapez dans le champ « Ville, identifiant, +221 77… » puis appuyez sur Entrée. Vous pouvez chercher une ville, un mot de la description, l'identifiant de la demande ou de l'expéditeur, ou le téléphone de l'expéditeur au format international (+221...).
4. Cochez **Signalées seulement** pour ne voir que les demandes signalées par des utilisateurs.
5. Utilisez **Période** (dates de création) si besoin, et **Effacer** pour la vider.
6. Cliquez sur une ligne pour ouvrir la fiche de la demande.

## Comprendre ce que vous voyez

### Le tableau

- **Trajet** : villes de départ et d'arrivée, date de création.
- **Expéditeur** : son nom.
- **Date souhaitée** : la date voulue, ou « Date flexible ».
- **Poids** : en kilos, ou « Non renseigné ».
- **Budget net** : le montant que recevrait le voyageur, ou « À négocier ». Le prix affiché dans l'application est le prix brut, frais compris.
- **Négos** : le nombre de négociations ouvertes.
- **Statut** : l'état de la demande, et le nombre de signalements s'il y en a.

### La fiche de la demande

- En-tête : trajet, expéditeur, statut, et pour une demande retirée « Avant retrait : ... ».
- La description, puis les détails : date souhaitée et sa souplesse (« Date exacte » ou « ± ... jours »), date de création, poids, taille, transport (« Indifférent » si aucun), contenu, **Budget net (hors frais)** avec « Prix négociable » ou « Prix ferme », paiements acceptés, quartiers et adresses de remise et de livraison, ville du destinataire.
- **Photos** : cliquez pour agrandir.
- **Négociations** : pour chaque voyageur, le dernier prix et l'état : **En cours**, **Attente du trajet**, **Attente du paiement**, **Attente de la commission**, **Dépôt mobile money en cours**, **Acceptée**, **Refusée**, **Annulée**, **Écartée (autre offre retenue)** ou **Expirée**.
- **Signalements** : motif, date, statut, détails et auteur du signalement.
- **Modération** : les boutons de retrait ou de restauration (voir [Retirer ou restaurer une demande d'envoi](/aide/retirer-une-demande-envoi)).

Les statuts d'une demande : **Brouillon** (jamais publiée), **Ouverte**, **En négociation**, **Acceptée**, **Expirée**, **Annulée**, **Terminée** (colis livré) et **Retirée** (par un admin).

## Bon à savoir

- Si la console affiche « Modération des demandes indisponible pour le moment », le serveur ne propose pas encore cet écran.
- Les signalements visant une demande se traitent aussi depuis [Signalements](/aide/signalements), qui propose un lien **Voir la demande**.
- Le lien de la page garde la demande ouverte : vous pouvez le partager.

## Qui peut le faire

Tous les rôles qui voient la page Colis (Super admin, Admin, Support). La section **Modération** n'apparaît qu'avec le droit de retirer du contenu (Super admin et Admin).
