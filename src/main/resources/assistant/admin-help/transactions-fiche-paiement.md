---
id: transactions-fiche-paiement
title: Lire la fiche d'un paiement (Résumé, Argent, Chronologie, Incidents)
category: argent
order: 30
summary: La fiche d'un paiement montre le parcours de l'argent, les deux personnes, les montants, l'historique, les incidents et les références.
permission: PAYMENT_VIEW
keywords: détail paiement, fiche paiement, ouvrir un paiement, parcours de l'argent, payé encaissé livré versé, net voyageur, commission yadony, remboursé, chronologie, journal, incidents, alertes ouvertes, références, payment intent, stripe, pawapay, copier numéro de suivi, colis lié, menu actions, bandeau à traiter
routes:
  - path: /transactions
    label: Ouvrir les paiements
  - path: /transactions?view=ALL
    label: Tous les paiements
---
## À quoi ça sert

La fiche d'un paiement s'ouvre en panneau sur la droite quand vous cliquez sur une ligne de la section **Paiements** de [Transactions](/transactions). Elle explique où en est l'argent et propose les gestes possibles.

## Étapes

1. Ouvrez [Transactions](/transactions), section **Paiements**.
2. Cliquez sur la ligne du paiement (ou cherchez son n° de suivi).
3. Lisez l'en-tête, le bandeau éventuel, puis passez d'un onglet à l'autre : **Résumé**, **Argent**, **Chronologie**, **Incidents**, **Références**.
4. Pour agir, cliquez sur le bouton **Actions** sous l'en-tête.
5. Fermez avec **Fermer**, la croix, ou la touche Échap.

## Comprendre ce que vous voyez

### En-tête

Le n° de suivi (bouton **Copier**), le trajet en titre, la méthode (**Carte** ou **Mobile money**), la date de création, puis les étiquettes : **Colis** ou **Négociation**, le statut, et **Versement retenu** si l'argent est bloqué.

### Bandeau « À traiter »

S'il y a un problème, un bandeau dit ce qui bloque : versement retenu, **Litige bancaire ouvert**, **Versement échoué**, ou **En séquestre depuis plus de 48 h**. Pour un litige, le lien **Voir le litige bancaire** ouvre sa ligne. Pour un checkout abandonné, un message indique qu'il n'y a rien à faire.

### Onglet Résumé

- **Parcours de l'argent** en quatre étapes : **Payé**, **Encaissé**, **Colis livré**, puis **Versé** ou **Remboursé**. Une étape en rouge signale un arrêt (paiement échoué, versement retenu...).
- Les deux personnes : **Expéditeur · paie** et **Voyageur · reçoit**. Un voyageur banni ou dont l'identité est révoquée porte l'étiquette **Banni** ou **Identité révoquée** et la mention **Versements gelés**.
- **En clair** : **Payé par l'expéditeur**, **Net voyageur**, **Commission Yadony**, et ce qui a été remboursé.
- **Colis lié** avec le lien **Ouvrir le colis**.

### Onglet Argent

Les montants ligne à ligne (payé, commission, net, remboursé), la méthode, le taux de change s'il y en a un, la date de création, l'**Encaissement carte** et la date **Libéré le**. Le lien **Voir la ventilation côté colis** ouvre l'onglet argent du colis. Pour un paiement par carte, le bouton **Resynchroniser avec Stripe** est ici (voir [Resynchroniser un paiement carte](/aide/transactions-resynchroniser-stripe)).

### Onglet Chronologie

Toutes les étapes datées (paiement créé, carte débitée, fonds en séquestre, séquestre libéré, versement forcé par un admin...). **Voir tout le journal** ouvre la page Audit filtrée sur ce paiement.

### Onglet Incidents

Un bloc par problème : versement retenu, litige bancaire (motif, date, issue), litige ouvert sur le colis, absence signalée, alertes ouvertes. Chaque bloc a son lien. Des raccourcis mènent au support de l'expéditeur ou du voyageur, à la conversation en modération et aux incidents du colis. Sans problème : « Aucun incident sur ce paiement. »

### Onglet Références

Les identifiants à copier (paiement, référence Stripe ou mobile money, opérations pawaPay, fil de négociation), le lien **Ouvrir dans Stripe**, **Voir les opérations mobile money**, et les raccourcis **Ouvrir le colis**, **Photos de remise et de livraison**, **Échanges des deux parties**, **Voir le trajet**.

## Bon à savoir

- L'adresse de la page garde le paiement ouvert et l'onglet choisi : vous pouvez l'envoyer à un collègue.
- Un lien n'apparaît que si vous avez le droit d'ouvrir la page visée.
- Les gestes du menu **Actions** sont décrits dans [Débloquer un paiement](/aide/transactions-debloquer-paiement), [Rembourser l'expéditeur](/aide/transactions-rembourser-expediteur) et [Relancer un versement mobile money](/aide/transactions-relancer-mobile-money).

## Qui peut le faire

Tous les rôles qui voient les paiements peuvent lire la fiche. Un geste impossible reste visible mais grisé, avec sa raison écrite en dessous.
