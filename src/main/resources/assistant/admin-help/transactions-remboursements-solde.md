---
id: transactions-remboursements-solde
title: Traiter une demande de remboursement de solde (wallet)
category: argent
order: 91
summary: Un utilisateur demande à récupérer son solde. Les remboursements automatiques se font seuls ; les tickets manuels se versent puis se marquent remboursés.
permission: PAYMENT_VIEW
keywords: remboursement wallet, rembourser le solde, récupérer son solde, retirer son argent, demande de remboursement, ticket manuel, marquer remboursée, relancer le remboursement, verser maintenant, échec de remboursement, remboursement mobile money, suppression du compte, reprise d'un échec, frais d'opérateur, à traiter
routes:
  - path: /transactions?tab=wallet-refunds
    label: Ouvrir les remboursements wallet
---
## À quoi ça sert

Un utilisateur peut demander à récupérer l'argent de son solde, soit depuis son portefeuille, soit au moment de supprimer son compte. Ces demandes arrivent dans l'onglet **Remboursements wallet** de la page [Transactions](/aide/transactions), section **Soldes**.

## Comprendre ce que vous voyez

Les vues en haut de la liste : **À traiter**, **En cours**, **Échecs**, **Remboursées** et **Toutes**. La page s'ouvre sur **À traiter** quand des demandes attendent.

Les colonnes :

- **Utilisateur** : cliquez sur le nom pour ouvrir sa fiche.
- **Origine** : **Demande depuis le portefeuille** ou **Suppression du compte**. **Reprise d'un échec** signale une demande qui remplace une demande précédente échouée.
- **Canal** : **Carte, automatique**, **Mobile money, automatique**, **Carte et mobile money, automatique** (rien à faire, le versement part tout seul) ou **Manuel** (un geste de l'équipe est nécessaire).
- **Montant**, **Statut** (**En attente**, **En cours**, **Échec**, **Remboursée**, **Résolue**) et **Demandé le**.

## Étapes : ticket manuel versé hors de la plateforme

1. Ouvrez [Remboursements wallet](/transactions?tab=wallet-refunds), vue **À traiter**.
2. Versez l'argent à l'utilisateur par le moyen convenu avec lui.
3. Sur la ligne, cliquez sur **Marquer remboursée**.
4. Vérifiez le montant réellement versé (prérempli avec le montant demandé). S'il est plus petit, l'écart est affiché : ce sont les frais d'opérateur, à la charge de l'utilisateur.
5. Ajoutez une note si besoin, puis cliquez sur **Marquer remboursée**.

> **Attention :** le solde de l'utilisateur est débité en entier, même si le montant versé est plus petit.

## Étapes : relancer un remboursement mobile money

Quand le bouton **Relancer le remboursement** apparaît, la console peut verser l'argent directement sur le numéro mobile money qui a servi à recharger.

1. Cliquez sur **Relancer le remboursement**.
2. Lisez le montant qui sera versé (toujours le montant dû en entier, frais déduits).
3. Si la fenêtre indique qu'il manque des fonds chez l'opérateur, l'envoi reste bloqué : faites alimenter le compte, puis cliquez sur **Actualiser**.
4. Cliquez sur **Verser maintenant**.

> **Attention :** un versement envoyé ne peut pas être annulé depuis la console.

## Qui peut le faire

La liste demande la permission PAYMENT_VIEW. Les gestes qui versent ou clôturent un remboursement peuvent demander des droits supplémentaires : si un bouton est absent ou refusé, demandez à un Admin.
