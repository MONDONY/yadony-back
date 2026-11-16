---
id: transactions-relancer-mobile-money
title: Relancer un versement ou un remboursement mobile money échoué
category: argent
order: 60
summary: Quand un versement au voyageur ou un remboursement à l'expéditeur par mobile money échoue, le relancer depuis la fiche du paiement.
permission: PAYMENT_RELEASE
keywords: relancer versement, relancer remboursement, mobile money échoué, versement échoué, orange money échoué, wave échoué, mtn momo, moov, pawapay, retry, renvoyer l'argent, voyageur pas reçu son argent, remboursement mobile money pas reçu, échec opérateur, relancer depuis le paiement
routes:
  - path: /transactions?tab=mobile-money&view=failed
    label: Opérations mobile money en échec
  - path: /transactions?alert=MM_FAILED
    label: Paiements au versement échoué
---
## À quoi ça sert

Pour un paiement mobile money (Orange Money, Wave, MTN MoMo, Moov...), le versement au voyageur et le remboursement à l'expéditeur passent par l'opérateur. Si l'opérateur refuse ou échoue, l'argent n'est pas parti. Ces deux gestes renvoient une nouvelle opération du même montant.

> **Attention :** la relance envoie réellement de l'argent. Le serveur la refuse si la dernière tentative n'a pas échoué, mais vérifiez quand même l'état de l'opération avant de relancer.

## Étapes

### Relancer le versement au voyageur

1. Repérez le paiement : alerte **Versement échoué** dans la section **Paiements**, ou ligne **Échouée** dans [Mobile money, vue Échecs](/transactions?tab=mobile-money&view=failed) avec le lien **Relancer depuis le paiement**.
2. Ouvrez la fiche du paiement. Le bandeau indique « Le dernier versement mobile money au voyageur a échoué ».
3. Cliquez sur **Actions**, puis **Relancer le versement**.
4. Lisez la fenêtre **Relancer le versement** et confirmez avec **Relancer le versement**.

### Relancer le remboursement à l'expéditeur

1. Ouvrez la fiche d'un paiement mobile money **Remboursé** ou **Annulé** dont le remboursement a échoué.
2. Cliquez sur **Actions**, puis **Relancer le remboursement**.
3. Confirmez avec **Relancer le remboursement**. Un nouveau remboursement du dépôt d'origine part vers l'expéditeur.

## Comprendre ce que vous voyez

Le bouton est grisé avec sa raison quand la relance n'a pas de sens :

- « Réservé aux paiements mobile money. »
- « Seulement sur un paiement mobile money déjà versé au voyageur. »
- « Aucun versement mobile money connu pour ce paiement : rien à relancer. »
- « Seulement sur un paiement mobile money remboursé ou annulé. »
- « Aucun remboursement mobile money connu pour ce paiement : rien à relancer. »

Après la relance, la chronologie montre **Versement mobile money relancé par un admin** ou **Remboursement mobile money relancé par un admin**, puis le résultat de l'opérateur.

## Bon à savoir

- Si le voyageur est bloqué (banni, identité révoquée), la relance du versement passe par une dérogation : case à cocher et motif obligatoire, comme pour [Débloquer un paiement](/aide/transactions-debloquer-paiement).
- Le détail de chaque tentative est visible dans [Opérations mobile money](/aide/transactions-mobile-money) : statut, opérateur, code d'échec.
- Les références des opérations (dépôt, versement, remboursement) sont dans l'onglet **Références** de la fiche.

## Qui peut le faire

Super admin et Admin (permission de débloquer les paiements, utilisée pour les deux relances).
