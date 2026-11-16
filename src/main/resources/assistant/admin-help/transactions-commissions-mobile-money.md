---
id: transactions-commissions-mobile-money
title: Voir les commissions encaissées par mobile money, mois par mois
category: argent
order: 93
summary: "Le tableau mensuel des paiements mobile money : montants encaissés, en séquestre, versés aux voyageurs, remboursés et commissions. Export CSV."
permission: PAYMENT_VIEW
keywords: commissions mobile money, commission par mois, chiffre d'affaires, revenus, encaissé, versé aux voyageurs, en séquestre, remboursée, envois livrés, export CSV, Excel, comptabilité, bilan mensuel, mobile money, orange money, wave
routes:
  - path: /transactions?tab=mm-commissions
    label: Ouvrir les commissions mobile money
---
## À quoi ça sert

Le sous-onglet **Commissions** de la section **Mobile money** de la page [Transactions](/aide/transactions) donne un bilan **par mois** des paiements faits par mobile money : ce qui est entré, ce qui a été versé aux voyageurs, et la commission acquise par Yadony.

## Étapes

1. Ouvrez [Transactions](/transactions?tab=mm-commissions), section **Mobile money**, sous-onglet **Commissions**.
2. Lisez le tableau ligne par ligne : une ligne par mois et par devise.
3. Pour travailler les chiffres dans un tableur, cliquez sur **Exporter en CSV**. Le fichier s'ouvre directement dans Excel en français.

## Comprendre ce que vous voyez

- **Mois** et **Devise**.
- **Envois** et **Envois livrés** : le nombre de colis payés, et ceux déjà livrés.
- **Encaissé** : l'argent reçu des expéditeurs.
- **En séquestre** : l'argent encore bloqué en attendant la livraison.
- **Versé aux voyageurs** : l'argent déjà reversé aux voyageurs.
- **Remboursée** : l'argent rendu aux expéditeurs.
- **Commission** : la part de Yadony, acquise une fois le colis livré.

> **Bon à savoir :** l'export reprend exactement le tableau affiché. Pour d'autres exports, voir la page [Exports](/aide/exports).

Pour le détail des opérations une par une, voir [Mobile money](/aide/transactions-mobile-money).

## Qui peut le faire

Il faut la permission PAYMENT_VIEW (Super admin, Admin et Support).
