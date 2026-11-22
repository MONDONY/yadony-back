---
id: transactions-frais-annulation
title: Frais d'annulation d'un colis (Renoncer à la retenue, Relancer le versement)
category: argent
order: 55
summary: La carte Frais d'annulation de la fiche du paiement montre la retenue faite à l'expéditeur fautif, et propose de renoncer à la retenue ou de relancer le versement au voyageur.
permission: PAYMENT_VIEW
keywords: frais d'annulation, retenue d'annulation, renoncer à la retenue, relancer le versement, dédommagement voyageur, expéditeur annule, annulation fautive, remboursement partiel refusé, remboursement intégral, retenue contestée, versement en attente, payout pending, frais retenus
routes:
  - path: /transactions
    label: Ouvrir les paiements
---
## À quoi ça sert

Quand un expéditeur annule à tort un colis déjà accepté, Yadony retient des frais sur son remboursement pour dédommager le voyageur. La carte **Frais d'annulation** de la fiche du paiement (panneau ouvert depuis [Transactions](/transactions)) montre l'état de cette retenue : le motif, le taux, les montants (frais bruts, commission, net voyageur) et le statut. La carte n'apparaît que si le colis a une retenue.

## Statuts

- **Due** : la retenue est calculée, le remboursement de l'expéditeur n'a pas encore eu lieu.
- **Retenue** : le remboursement partiel est fait, le versement au voyageur est à venir.
- **Versement en attente** : le dédommagement du voyageur n'a pas pu partir, le dernier motif est affiché.
- **Versé** : le voyageur a reçu son dédommagement.
- **Sans objet** : il n'y a plus de retenue (par exemple après une renonciation).

## Renoncer à la retenue

Le bouton **Renoncer à la retenue** n'existe que tant que la retenue est **Due**. À utiliser quand l'opérateur refuse durablement le remboursement partiel, ou quand l'expéditeur conteste les frais. Écrivez le motif (obligatoire). La retenue passe à **Sans objet** et le prochain remboursement de l'expéditeur est **intégral**. Cette action ne rembourse pas toute seule : lancez ensuite le remboursement habituel (voir [Rembourser l'expéditeur](/aide/transactions-rembourser-expediteur)). Elle exige la permission de rembourser.

## Relancer le versement

Le bouton **Relancer le versement** n'existe que lorsque le dédommagement du voyageur est **Versement en attente**. Il remet le compteur d'essais à zéro et retente le versement aussitôt. Écrivez le motif (obligatoire), puis vérifiez le nouveau statut affiché. Il exige la permission de libérer un versement.

## Qui peut le faire

La carte est visible de tous les rôles qui voient les paiements, Support compris. Les deux boutons sont réservés à Admin et Super admin, le Support les voit grisés.
