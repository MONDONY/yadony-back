---
id: transactions-commissions-cash
title: Suivre les commissions des colis payés en espèces
category: argent
order: 92
summary: "Quand l'expéditeur paie le voyageur en espèces, la commission Yadony est prélevée au voyageur. L'onglet Commissions cash suit ces prélèvements."
permission: PAYMENT_VIEW
keywords: commission cash, commissions cash, espèces, paiement en espèces, cash, liquide, commission du voyageur, prélèvement de la commission, carte du voyageur, authentification requise, 3D Secure, prélevée, échouée, remboursée, remboursement échoué, tentatives, à traiter
routes:
  - path: /transactions?tab=cash-commissions
    label: Ouvrir les commissions cash
---
## À quoi ça sert

Pour certains colis, l'expéditeur paie le voyageur **en espèces**, de la main à la main. Yadony ne touche donc pas cet argent : sa commission est alors prélevée directement au voyageur (sur sa carte ou sur son solde). L'onglet **Commissions cash** de la page [Transactions](/aide/transactions), section **Soldes**, suit ces prélèvements.

## Étapes

1. Ouvrez [Commissions cash](/transactions?tab=cash-commissions).
2. Restez sur la vue **À traiter** pour voir ce qui demande votre attention, ou passez sur **Toutes**.
3. Utilisez la recherche ou le filtre de statut pour retrouver une commission.
4. Cliquez sur le colis ou sur le voyageur pour ouvrir sa fiche.

## Comprendre ce que vous voyez

Colonnes : **Colis**, **Voyageur**, **Montant** (prix du colis), **Commission**, **Canal** (carte ou **Portefeuille**), **Statut**, **Tentatives** et **Créé**.

Les statuts :

- **En attente** : le prélèvement n'a pas encore eu lieu.
- **Authentification requise** : la banque du voyageur demande une confirmation (3D Secure). Le voyageur doit la faire depuis l'application.
- **Prélevée** : la commission est encaissée. Tout est en ordre.
- **Échouée** : le prélèvement a échoué (carte refusée, fonds insuffisants...).
- **Remboursée** : la commission a été rendue au voyageur.
- **Remboursement échoué** : le remboursement de la commission n'a pas abouti.

La vue **À traiter** regroupe les échecs, les authentifications à finir et les remboursements en échec. « Rien à traiter » : tout va bien.

> **Bon à savoir :** le nombre de **Tentatives** indique combien de fois le prélèvement a été essayé. Si une commission reste en échec, contactez le voyageur depuis le [Support](/support).

## Qui peut le faire

Il faut la permission PAYMENT_VIEW (Super admin, Admin et Support).
