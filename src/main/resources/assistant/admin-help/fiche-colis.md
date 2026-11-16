---
id: fiche-colis
title: Lire la fiche d'un colis
category: activite
order: 15
summary: Ouvrir la fiche d'un colis et lire ses onglets Résumé, Suivi, Argent, Échanges et Incidents, les personnes concernées et le menu Actions.
permission: BID_VIEW
keywords: fiche colis, détail du colis, ouvrir un colis, tiroir colis, résumé, suivi, argent, échanges, incidents, expéditeur, voyageur, destinataire, téléphone masqué, identifiants, copier le numéro de suivi, menu actions, voir le paiement, voir la conversation, écrire via le support, trajet du colis, poids, contenu, code de remise, commission, net voyageur, versé au voyageur
routes:
  - path: /colis
    label: Ouvrir Colis
---
## À quoi ça sert

La fiche d'un colis s'ouvre sur le côté droit de la page **Colis** quand vous cliquez sur une ligne. Elle rassemble tout ce qu'il faut pour répondre à un client : où en est le colis, qui sont les personnes, combien a été payé, ce qui s'est dit et les incidents.

## Étapes

1. Ouvrez **Colis**, trouvez le colis, cliquez sur sa ligne.
2. Lisez l'en-tête : numéro de suivi (bouton **Copier**), trajet, pays, statut et phrase de situation.
3. Lisez la frise d'étapes et les cartes des personnes.
4. Passez d'un onglet à l'autre : **Résumé**, **Suivi**, **Argent**, et selon vos droits **Échanges** et **Incidents**.
5. Utilisez le bouton **Actions** pour les liens et gestes disponibles.
6. Fermez avec **Fermer**, la croix ou la touche Échap.

## Comprendre ce que vous voyez

### Les personnes

- **Expéditeur** et **Voyageur** : nom (cliquable vers sa fiche si vous y avez accès), téléphone masqué, état du compte. Pour le voyageur, aussi l'identité (KYC), Stripe Connect (**Utilisable** ou **Non utilisable**) et Mobile money. Le bouton **Écrire via le support** ouvre une conversation.
- **Destinataire** : nom et téléphone masqué.

### Les onglets

- **Résumé** : le **Trajet** (départ, arrivée, dates, lieux, état de l'annonce, capacité restante, transport, prix au kilo, heure limite de remise, étape d'un voyage à étapes), le **Colis** (poids, contenu, description, code de remise, lieu de remise, dates de livraison ou de retour) et les **Identifiants**, chacun avec un bouton **Copier**. Les boutons **Voir l'annonce** et **... autres colis sur ce trajet** sont expliqués dans [Consulter les annonces des voyageurs](/aide/annonces-voyageurs).
- **Suivi** : les photos par étape et la chronologie. Voir [Voir les photos et la chronologie d'un colis](/aide/suivi-photos-chronologie-colis).
- **Argent** : **Payé par l'expéditeur**, **Commission Yadony**, **Net voyageur**, **Devise**, **Méthode**, **Statut du paiement**, **Encaissé**, **Versé au voyageur**, et s'il y a lieu **Remboursé**, **Versement retenu** (avec **Voir la retenue**) ou **Litige bancaire**.
- **Échanges** : la conversation entre les parties, en lecture seule, avec le lien **Ouvrir dans la modération**.
- **Incidents** : les litiges et absences du colis, avec **Ouvrir le litige** ou **Ouvrir l'absence**. La pastille compte les litiges ouverts et les absences.

### Le menu Actions

Selon le colis et vos droits : **Voir le paiement**, **Voir le litige**, **Voir l'absence signalée**, **Voir la conversation** et **Copier les identifiants**. En bas, sous **Gestes super-admin** : **Annuler le colis** et **Ouvrir un litige**. Quand un geste est impossible, la raison est écrite juste en dessous.

## Bon à savoir

- Le code de remise n'est jamais affiché : la fiche dit seulement s'il est « Généré (visible seulement par l'expéditeur) » ou « Pas encore généré ».
- Les téléphones sont toujours masqués.
- Le lien de la page garde le colis ouvert et l'onglet choisi : vous pouvez l'envoyer à un collègue.
- Dans l'onglet **Argent**, le bouton **Resynchroniser avec Stripe** relit un paiement par carte ; il est réservé aux super-administrateurs.

## Qui peut le faire

Tous les rôles qui voient la page Colis. L'onglet **Échanges** demande l'accès à la modération, l'onglet **Incidents** l'accès aux incidents, les montants de l'onglet **Argent** l'accès aux transactions, les coordonnées l'accès aux fiches utilisateur, et **Écrire via le support** la gestion du support. Le rôle Support a tous ces accès.
