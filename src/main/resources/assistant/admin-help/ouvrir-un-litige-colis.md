---
id: ouvrir-un-litige-colis
title: Ouvrir un litige sur un colis
category: activite
order: 18
summary: Ouvrir un litige au nom de l'expéditeur ou du voyageur depuis la fiche colis (geste super-admin) pour geler le versement et tracer le différend.
permission: BID_VIEW
keywords: ouvrir un litige, créer un litige, déclarer un litige, réclamation, plainte, colis abîmé, colis perdu, colis non remis, contenu différent, désaccord sur le prix, comportement, geler le versement, bloquer le paiement du voyageur, au nom de l'expéditeur, au nom du voyageur, différend, contestation, litige impossible, geste super-admin
routes:
  - path: /colis?view=ACTIVE
    label: Voir les colis en cours
  - path: /incidents?tab=disputes
    label: Voir les litiges
---
## À quoi ça sert

Quand un client se plaint par téléphone ou au support (colis abîmé, perdu, pas livré...), un super-administrateur peut ouvrir le litige à sa place depuis la fiche du colis. Le litige apparaît ensuite dans la page [Incidents](/aide/incidents), où il sera tranché. Si l'argent est encore bloqué chez Yadony, le versement au voyageur est gelé jusqu'à la décision.

## Étapes

1. Ouvrez **Colis** et cliquez sur le colis pour ouvrir sa fiche.
2. Cliquez sur **Actions**, puis, sous **Gestes super-admin**, sur **Ouvrir un litige**.
3. Choisissez **Au nom de (obligatoire)** : **Expéditeur** ou **Voyageur** (leur nom est affiché entre parenthèses).
4. Choisissez un **Motif (obligatoire)** : **Colis abîmé**, **Colis perdu**, **Colis non remis au destinataire**, **Contenu différent de la déclaration**, **Désaccord sur le prix ou le paiement**, **Comportement d'une des parties** ou **Autre**.
5. Écrivez la **Description (obligatoire)** du différend, d'au moins 10 caractères. Elle est enregistrée avec votre nom.
6. Lisez l'encadré sur l'argent, puis cochez « J'ai lu l'effet sur l'argent et je confirme l'ouverture du litige. ».
7. Cliquez sur **Ouvrir le litige** (ou **Retour** pour abandonner).

## Comprendre ce que vous voyez

L'encadré sur l'argent indique :

- si l'argent est en séquestre : « Le versement au voyageur (... net) est gelé jusqu'à la résolution du litige dans Incidents. » ;
- sinon : « Aucun argent en séquestre à geler : le litige trace le différend, les deux parties sont prévenues. ».

Après l'envoi, la fiche affiche « Litige ouvert : le versement au voyageur est gelé. Les deux parties sont prévenues. » (ou sans la mention du gel). Le colis prend l'alerte **Litige ouvert** et apparaît dans la vue **À traiter**. Dans la chronologie, la ligne « Litige ouvert par un admin » est ajoutée.

## Bon à savoir

L'ouverture est impossible, avec la raison affichée sous le bouton grisé, quand :

- un litige est déjà ouvert sur ce colis ;
- aucune transaction n'est engagée (colis **En attente**, **Attente paiement**, **Refusé**, **Annulé** ou **Expiré**) ;
- le voyageur a déjà été payé : un litige ne peut plus geler l'argent. Il faut passer par le support ou un remboursement manuel.

Un message peut aussi indiquer qu'un litige pour ce motif a déjà été traité sur ce colis : choisissez un autre motif.

> **Attention :** une fois ouvert, le litige ne peut pas être supprimé. Il se clôt seulement par une décision dans Incidents (voir [Résoudre un litige](/aide/resoudre-un-litige)).

## Qui peut le faire

Réservé aux super-administrateurs. Pour les rôles Admin et Support, l'entrée est grisée avec « Réservé aux super-administrateurs : demandez à l'un d'eux. ». Tout le monde peut en revanche suivre le litige dans Incidents s'il y a accès.
