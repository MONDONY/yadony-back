---
id: transactions-paiements-recherche
title: Rechercher et filtrer les paiements
category: argent
order: 20
summary: Vues À traiter, En séquestre, Versés, Remboursés ; recherche par n° de suivi ou personne ; filtres méthode, devise, période, montant ; export CSV.
permission: PAYMENT_VIEW
keywords: chercher un paiement, trouver un paiement, numéro de suivi, référence paiement, pi_, paiement d'un expéditeur, paiement d'un voyageur, à traiter, en séquestre, versés, remboursés, échoués, annulés, filtre devise, filtre carte, filtre mobile money, période, montant minimum, checkout abandonné, versements retenus, exporter csv, totaux, commission, net voyageur
routes:
  - path: /transactions
    label: Paiements à traiter
  - path: /transactions?view=ESCROW
    label: Paiements en séquestre
  - path: /transactions?view=ALL
    label: Tous les paiements
  - path: /transactions?alert=HELD
    label: Versements retenus
---
## À quoi ça sert

La section **Paiements** de [Transactions](/transactions) liste les paiements des expéditeurs. Elle s'ouvre sur la vue **À traiter** : les paiements qui demandent une action de l'équipe.

## Étapes

### Choisir une vue

1. Ouvrez [Transactions](/transactions), section **Paiements**.
2. Cliquez sur une vue : **À traiter**, **En séquestre**, **Versés (3 j)**, **Remboursés**, **Échoués / annulés** ou **Tous**. Le nombre de paiements s'affiche à côté.

### Chercher

1. Tapez dans le champ « N° de suivi, référence, personne, trajet… ».
2. Des suggestions apparaissent, rangées en **Expéditeurs**, **Voyageurs**, **Trajets** et **N° de suivi**.
3. Cliquez sur une personne : la liste ne montre plus que ses paiements (puce **Personne : ...**). Un trajet lance la recherche sur ses deux villes. Un n° de suivi ouvre directement la fiche du paiement.
4. Ou appuyez sur Entrée pour chercher le texte tapé. Une référence exacte de paiement ouvre sa fiche.

### Filtrer

1. **Méthode** : **Toutes**, **Carte** ou **Mobile money**.
2. **Devise** : EUR, USD, CAD, GBP, CHF, **XOF (F CFA ouest)** ou **XAF (F CFA central)**.
3. **Période** : une date de début et une date de fin. **Effacer** retire les dates.
4. **Montant (unités de la devise)** : un minimum et un maximum.
5. **Plus de filtres** ouvre deux boutons : **Versements retenus** et **Afficher les checkouts abandonnés**.

Les filtres actifs s'affichent en puces. Cliquez sur une puce pour la retirer, ou sur **Tout effacer**.

### Exporter

Cliquez sur **Exporter en CSV** sous la liste pour télécharger les paiements filtrés.

## Comprendre ce que vous voyez

- Les cartes de totaux, une par devise : **En séquestre**, **Versé (libéré)**, **Remboursé**, **Commissions**, avec le nombre de paiements.
- Colonne **À faire** : le statut (**En attente**, **Sous séquestre**, **Libéré**, **Échoué**, **Remboursé**, **Annulé**) et les alertes (**Retenu**, **Litige bancaire**, **Versement échoué**, **Séquestre > 48 h**, **Checkout abandonné**).
- **Colis** : le trajet, le type (**Colis** ou **Négociation**) et le n° de suivi, cliquables vers la fiche du colis.
- **Expéditeur → Voyageur** : les deux personnes, cliquables vers leur fiche.
- **Montant** : ce qu'a payé l'expéditeur, avec en dessous le **net voyageur** et la **commission**.
- **Méthode** et **Depuis** : le moyen de paiement et l'ancienneté.

Cliquez sur une ligne pour ouvrir la [fiche du paiement](/aide/transactions-fiche-paiement).

## Bon à savoir

- Les nombres d'« À traiter » et des alertes se recoupent : un paiement peut avoir plusieurs alertes. Ne les additionnez pas.
- Un checkout abandonné est un paiement resté en attente plus de 24 h : le client n'a jamais terminé. Aucun argent n'a bougé. Ils sont masqués par défaut ; le lien **les afficher** les fait revenir.
- Dès qu'un filtre est posé, la vue passe sur **Tous** pour ne rien cacher.

## Qui peut le faire

Super admin, Admin et Support voient les paiements. **Exporter en CSV** demande la permission d'export (Super admin et Admin).
