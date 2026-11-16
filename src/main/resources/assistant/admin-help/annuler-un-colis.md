---
id: annuler-un-colis
title: Annuler un colis
category: activite
order: 17
summary: Annuler un colis depuis sa fiche (geste super-admin), avec motif, note interne et confirmation de l'effet sur l'argent. Geste définitif.
permission: BID_VIEW
keywords: annuler un colis, annulation colis, annuler un envoi, stopper un colis, rembourser l'expéditeur, remboursement, colis en double, fraude, contenu interdit, voyageur injoignable, trajet abandonné, incident technique, motif d'annulation, note interne, geste super-admin, annulation impossible, colis déjà livré, voyageur déjà payé, retour du colis, annuler la commande
routes:
  - path: /colis?view=ACTIVE
    label: Voir les colis en cours
---
## À quoi ça sert

**Annuler le colis** arrête définitivement un envoi qui ne peut pas aller au bout : demande d'une des parties, voyageur injoignable, contenu interdit, fraude, doublon... Le colis passe au statut **Annulé**, l'argent est rendu à l'expéditeur si besoin, et les deux parties sont prévenues.

> **Attention :** ce geste est définitif. Un colis annulé ne peut pas être relancé.

## Étapes

1. Ouvrez **Colis** et cliquez sur le colis pour ouvrir sa fiche.
2. Cliquez sur **Actions**, puis, sous **Gestes super-admin**, sur **Annuler le colis**.
3. Choisissez un **Motif (obligatoire)** : **Demande de l'expéditeur**, **Demande du voyageur**, **Voyageur injoignable ou trajet abandonné**, **Contenu interdit ou non conforme**, **Suspicion de fraude**, **Colis en double ou créé par erreur**, **Incident technique** ou **Autre (à préciser)**.
4. Écrivez une **Note interne**. Elle est facultative, sauf avec « Autre » où il faut au moins 10 caractères. Elle n'est jamais montrée aux utilisateurs et reste liée à votre nom.
5. Lisez l'encadré qui décrit l'effet sur l'argent, puis cochez « J'ai lu l'effet sur l'argent et je confirme l'annulation. ».
6. Cliquez sur **Annuler le colis**. Le bouton **Retour** ferme la fenêtre sans rien faire.

## Comprendre ce que vous voyez

L'encadré sur l'argent dit, selon le cas :

- paiement bloqué chez Yadony : « L'expéditeur sera remboursé de ... ; aucun versement au voyageur. » ;
- paiement pas encore confirmé : l'autorisation de la carte est levée, rien n'est débité ;
- paiement en espèces : aucun paiement en ligne à rembourser, une commission déjà prélevée est rendue ;
- aucun paiement : rien à rembourser.

Si le colis est déjà chez le voyageur (remis, en transit ou arrivé), la fenêtre ajoute : « Le colis est déjà chez le voyageur : son retour s'organise avec le support. ».

Après l'envoi, un message vert apparaît en haut de la fiche, par exemple « Colis annulé. Remboursement de ... lancé pour l'expéditeur. Les deux parties sont prévenues. ». La fiche, la liste et les nombres se mettent à jour, et la chronologie affiche « Colis annulé par un admin ».

## Bon à savoir

L'annulation est impossible, et la raison s'affiche sous le bouton grisé, quand :

- le colis est déjà annulé, livré ou terminé autrement ;
- le voyageur a déjà été payé ;
- un litige bancaire est en cours chez la banque de l'expéditeur ;
- un litige est ouvert sur ce colis : résolvez-le d'abord dans [Incidents](/aide/incidents) ;
- il ne s'agit que d'une discussion de prix, sans colis réservé.

Si l'action n'existe pas encore sur le serveur, la console l'indique : « Action indisponible sur cet environnement ».

Pour un désaccord entre les parties sans arrêter l'envoi, préférez [Ouvrir un litige sur un colis](/aide/ouvrir-un-litige-colis).

## Qui peut le faire

Réservé aux super-administrateurs. Pour les rôles Admin et Support, l'entrée est grisée avec le message « Réservé aux super-administrateurs : demandez à l'un d'eux. ».
