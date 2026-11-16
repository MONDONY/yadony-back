---
id: transactions-rembourser-expediteur
title: Rembourser l'expéditeur d'un paiement en séquestre
category: argent
order: 50
summary: Rendre à l'expéditeur l'argent encore en séquestre, depuis le menu Actions de la fiche du paiement, avec un motif obligatoire.
permission: PAYMENT_REFUND
keywords: rembourser, remboursement expéditeur, rendre l'argent, annuler paiement, colis non livré, colis perdu, voyageur absent, refund, remboursement carte, remboursement mobile money, séquestre, geste commercial, rembourser le client, argent de l'expéditeur, remboursement échoué, versement retenu rembourser
routes:
  - path: /transactions?view=ESCROW
    label: Paiements en séquestre
  - path: /transactions?view=REFUNDED
    label: Paiements remboursés
---
## À quoi ça sert

Rembourser rend à l'expéditeur l'argent qu'il a payé, tant que ce paiement est encore en séquestre (pas encore versé au voyageur). C'est le geste à faire quand la livraison pose problème : colis non remis, voyage annulé, versement au voyageur bloqué sans raison de le payer.

> **Attention :** le remboursement est définitif. L'argent repart vers la carte ou le compte mobile money de l'expéditeur et le voyageur ne sera pas payé pour ce paiement.

## Étapes

1. Ouvrez [Transactions](/transactions?view=ESCROW), vue **En séquestre**, puis cliquez sur le paiement.
2. Vérifiez dans **Résumé** et **Incidents** que le remboursement est la bonne décision (litige, absence signalée, alertes).
3. Cliquez sur **Actions**, puis **Rembourser l'expéditeur**.
4. Dans la fenêtre **Rembourser l'expéditeur**, écrivez le motif (obligatoire).
5. Cliquez sur **Rembourser**.

Quand le paiement porte un versement retenu, le bandeau rouge du haut propose aussi directement le bouton **Rembourser l'expéditeur**.

## Comprendre ce que vous voyez

- Après un succès, la fiche se recharge : le statut devient **Remboursé**, le **Parcours de l'argent** se termine par **Remboursé** et l'onglet **Chronologie** montre l'étape du remboursement.
- Si le geste est impossible, le bouton est grisé avec sa raison : « Seul un paiement en séquestre peut être remboursé depuis l'admin. » ou « Permission manquante : réservé aux admins qui peuvent rembourser. »
- Un message d'erreur rouge en haut de la fiche donne la raison d'un refus du serveur.

## Bon à savoir

- Un paiement déjà **Libéré** (versé au voyageur) ne se rembourse pas depuis cette fiche.
- Pour un paiement mobile money, le remboursement passe par l'opérateur. S'il échoue, utilisez **Relancer le remboursement** : voir [Relancer un versement ou un remboursement mobile money](/aide/transactions-relancer-mobile-money).
- Le remboursement apparaît ensuite dans la vue **Remboursés** et dans les totaux **Remboursé**.
- Le rôle Support peut consulter mais doit demander à un Admin de rembourser.

## Qui peut le faire

Super admin et Admin (permission de rembourser). Le rôle Support voit le bouton grisé.
