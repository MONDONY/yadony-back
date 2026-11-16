---
id: statuts-colis
title: Comprendre les statuts d'un colis
category: activite
order: 14
summary: Signification de chaque statut de colis (en attente, payé, accepté, remis, en transit, arrivé, livré, annulé, refusé, expiré, absence) et de la frise d'étapes.
permission: BID_VIEW
keywords: statuts colis, statut d'un colis, que veut dire, attente paiement, en attente, payé séquestre, séquestre, accepté, remis, en transit, arrivé, livré, refusé, annulé, expiré, absence, no-show, colis refusé, étapes du colis, frise, cycle de vie, où en est le colis, état du colis, signification statut, livraison
routes:
  - path: /colis?view=ALL
    label: Voir tous les colis
  - path: /colis?view=CLOSED
    label: Voir les colis annulés ou refusés
---
## À quoi ça sert

Chaque colis a un statut, affiché en pastille dans la liste et dans la fiche. Cette fiche explique chaque statut en mots simples, dans l'ordre normal du parcours, puis les fins anticipées.

## Comprendre ce que vous voyez

### Le parcours normal

- **En attente** : la demande est envoyée au voyageur, on attend sa réponse.
- **Attente paiement** : le voyageur a accepté, on attend le paiement de l'expéditeur.
- **Payé (séquestre)** : l'expéditeur a payé. L'argent est bloqué chez Yadony (le séquestre) en attendant la décision du voyageur.
- **Accepté** : le voyageur a accepté, on attend la remise du colis.
- **Remis** : le colis est remis au voyageur, on attend le départ.
- **En transit** : le colis est en route avec le voyageur, on attend la livraison.
- **Arrivé** : le colis est à destination, on attend la livraison au destinataire.
- **Livré** : le colis est livré au destinataire. C'est la fin heureuse.

### Les fins anticipées

- **Refusé** : le voyageur a refusé la demande.
- **Annulé** : le colis est annulé (par une des parties, automatiquement ou par un admin).
- **Expiré** : la demande a expiré sans réponse du voyageur.
- **Colis refusé** : le voyageur a refusé le colis au moment de la remise.
- **Absence (no-show)** : une absence a été signalée au rendez-vous.

### La phrase et la frise de la fiche

En haut de la fiche colis, une phrase résume la situation, par exemple « Accepté par le voyageur, en attente de la remise du colis. ». Si le voyageur a donné un motif de refus, il apparaît en dessous (« Motif du refus : ... »).

La frise montre six étapes : **Créé**, **Payé**, **Accepté**, **Remis**, **En transit**, **Livré**. Une coche marque les étapes faites, l'étape courante est mise en avant avec « En cours », les suivantes sont grisées. Les statuts **Remis**, **En transit** et **Arrivé** placent tous l'étape courante sur **En transit**. Pour une fin anticipée, un bandeau sous la frise affiche **Refusé**, **Annulé**, **Expiré**, **Colis refusé** ou **No-show**, et les étapes réellement franchies avant l'arrêt restent cochées.

### Dans quelle vue rapide

- **En cours** : En attente, Attente paiement, Payé (séquestre), Accepté, Remis, En transit, Arrivé.
- **Livrés depuis 3 jours** puis **Terminés** : Livré.
- **Annulés / refusés** : Refusé, Annulé, Expiré, Colis refusé, Absence (no-show).

## Bon à savoir

- Le statut change tout seul selon ce que font l'expéditeur et le voyageur dans l'application. Il n'existe pas de bouton pour le modifier à la main. Le seul geste admin possible est l'annulation, réservée aux super-administrateurs (voir [Annuler un colis](/aide/annuler-un-colis)).
- Le statut ne dit pas tout : un colis **En transit** peut aussi avoir une alerte (retard, litige). Regardez l'étiquette à côté du statut. Voir [Traiter les colis à problème](/aide/colis-a-traiter).
- Un statut inconnu de la console s'affiche tel quel, en gris.
- Pour filtrer sur un statut, utilisez le filtre **Statut précis** (voir [Rechercher et filtrer les colis](/aide/rechercher-un-colis)).
