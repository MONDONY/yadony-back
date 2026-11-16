---
id: resoudre-un-litige
title: Résoudre un litige sur un colis
category: activite
order: 51
summary: "Trancher un litige : en faveur de l'expéditeur, du voyageur, partager l'argent bloqué, verser le fonds de garantie ou classer sans suite."
permission: DISPUTE_RESOLVE
keywords: résoudre un litige, trancher un litige, clore un litige, décision litige, rembourser l'expéditeur, payer le voyageur, partager le séquestre, partage, fonds de garantie, verser le fonds de garantie, classer sans suite, colis perdu, colis abîmé, accord des deux parties, argent bloqué, séquestre, remboursement gelé, reprendre le partage
routes:
  - path: /incidents?tab=disputes
    label: Ouvrir les litiges
---
## À quoi ça sert

Pendant un litige, l'argent payé par l'expéditeur reste **bloqué** (en séquestre) : ni remboursé, ni versé au voyageur. Votre décision dit où il va.

## Étapes

1. Ouvrez [les litiges](/incidents?tab=disputes) et cliquez sur un litige **Ouvert**.
2. Lisez le dossier : **Montant et paiement**, **Statut du colis**, **Trajet**, historique, autres incidents des deux parties. Ouvrez le colis ou la conversation si besoin.
3. Dans le panneau **Décision**, choisissez :
   - **Trancher pour l'expéditeur** : le litige est clos en sa faveur.
   - **Trancher pour le voyageur** : le litige est clos en sa faveur.
   - **Partager le séquestre** : une part est remboursée à l'expéditeur, l'autre versée au voyageur.
   - **Verser le fonds de garantie** : Yadony verse une aide prise sur le fonds de garantie.
   - **Classer sans suite** : le litige est clos sans décision sur l'argent.
4. Écrivez ce que vous avez vérifié (obligatoire). Des notes rapides sont proposées : **Preuves examinées**, **Accord des deux parties**, **Aucune réponse de l'autre partie**. Ce motif est interne : il n'est pas transmis aux parties.
5. Cliquez sur le bouton de confirmation.

> **Attention :** la décision déplace de l'argent réel et ne peut pas être annulée. Un litige résolu ne se rouvre pas.

## Partager le séquestre

Le formulaire **Partage du séquestre** montre ce qui a été **Payé**, la **Commission (conservée)**, ce qui est **Déjà remboursé** et le **Net disponible**. Saisissez le montant pour chaque partie, puis **Résoudre avec ce partage**.

Si le message **Une étape du partage reste à finir** apparaît, le versement n'a pas été exécuté jusqu'au bout chez le prestataire de paiement. Une fois la cause réglée, cliquez sur **Reprendre le partage**.

## Le fonds de garantie

Avec **Verser le fonds de garantie**, choisissez le **Bénéficiaire** et le montant, puis **Activer le fonds de garantie**. Les versements du fonds peuvent s'exporter depuis la page [Exports](/aide/exports).

## Qui peut le faire

Il faut la permission DISPUTE_RESOLVE (Super admin et Admin). **Partager le séquestre** demande en plus les droits de libérer et de rembourser un paiement.

Pour ouvrir un litige depuis un colis, voir [Ouvrir un litige](/aide/ouvrir-un-litige-colis).
