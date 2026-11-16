---
id: colis-a-traiter
title: Traiter les colis à problème (vue À traiter et alertes)
category: activite
order: 12
summary: Repérer les colis avec un litige ouvert, une absence (no-show), un paiement en attente ou un retard, et filtrer par type d'alerte.
permission: BID_VIEW
keywords: colis à traiter, colis à problème, alertes colis, litige ouvert, no-show, absence, paiement en attente, retard, colis en retard, colis bloqué, colis non payé, expéditeur n'a pas payé, livraison en retard, urgence, priorités, file de travail, à faire, problèmes de livraison, incidents colis, étiquette alerte, rien à traiter
routes:
  - path: /colis?view=TODO
    label: Voir les colis à traiter
  - path: /colis?alert=DISPUTE_OPEN
    label: Voir les colis en litige
  - path: /colis?alert=PAYMENT_PENDING
    label: Voir les paiements en attente
  - path: /colis?alert=LATE
    label: Voir les colis en retard
---
## À quoi ça sert

La vue **À traiter** de la page Colis rassemble les colis qui demandent une attention. Chaque colis concerné porte une alerte. Il n'en affiche qu'une seule, la plus urgente, dans cet ordre :

1. **Litige ouvert** : un désaccord est en cours sur ce colis.
2. **No-show** : une absence a été signalée au rendez-vous.
3. **Paiement en attente** : le voyageur a accepté mais l'expéditeur n'a pas payé depuis plus de 24 heures.
4. **En retard** : le colis est accepté, remis ou en transit, et la date d'arrivée prévue du trajet est dépassée sans livraison.

## Étapes

1. Ouvrez **Colis**, onglet **Colis**, puis la vue **À traiter** (elle s'ouvre par défaut).
2. Sous la recherche, cliquez sur une pastille d'alerte pour ne garder qu'un type : **Litige ouvert**, **No-show**, **Paiement en attente** ou **En retard**. Le nombre de colis est affiché dans chaque pastille.
3. Cliquez à nouveau sur la même pastille pour retirer le filtre, ou sur la croix de la puce « Alerte : ... ».
4. Ouvrez un colis. Un bandeau coloré en haut de la fiche rappelle l'alerte (voir plus bas).
5. Traitez le problème depuis la bonne page : litiges et absences dans [Incidents](/aide/incidents), paiement dans la fiche paiement (menu **Actions**, **Voir le paiement**).

## Comprendre ce que vous voyez

Le bandeau de la fiche colis dit :

- **Litige ouvert** : le statut du litige et un lien **Voir l'incident**.
- **Absence signalée** : un lien **Voir l'absence signalée**.
- **Paiement en attente** : « L'expéditeur n'a pas encore payé ce colis. ».
- **Livraison en retard** : « La date d'arrivée prévue du trajet est dépassée sans livraison. ».

Dans la liste :

- La vue **À traiter** devient rouge tant qu'il reste au moins un colis. Son nombre reste affiché même à zéro.
- Les pastilles d'alerte sont visibles dans les vues **À traiter** et **Tous** uniquement.
- Dans le tableau, l'alerte apparaît en étiquette courte à côté du statut : **Litige**, **No-show**, **Paiement** ou **Retard**. Rouge pour litige et no-show, orange pour paiement et retard.
- Si aucun colis n'est à traiter : « Rien à traiter pour l'instant. Aucun litige, no-show, paiement en attente ni retard. ».
- Si vos filtres vident la vue : « Aucun colis à traiter pour ces filtres. » avec un bouton **Voir dans Tous**.

## Bon à savoir

- Les alertes sont calculées automatiquement. Elles disparaissent seules quand la situation se règle (litige résolu, paiement reçu, colis livré).
- Ces alertes de colis sont différentes de la page [Alertes](/aide/alertes), qui surveille surtout la cohérence de l'argent.
- Un lien direct vers une alerte (boutons ci-dessus) affiche la vue **Tous** filtrée sur cette alerte.

## Qui peut le faire

Tous les rôles qui voient la page Colis. Les liens vers un litige ou une absence demandent l'accès aux incidents ; le lien vers le paiement demande l'accès aux transactions.
