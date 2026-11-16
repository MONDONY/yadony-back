---
id: codes-promo
title: Créer, modifier, désactiver ou supprimer un code promo
category: argent
order: 100
summary: "La page Codes promo : créer une réduction en pourcentage pour les expéditeurs, les voyageurs ou tout le monde, avec dates et limites d'utilisation."
permission: PROMO_MANAGE
keywords: code promo, codes promo, promotion, réduction, remise, coupon, bon de réduction, pourcentage, offre, campagne marketing, créer un code, désactiver un code, supprimer un code, utilisations, limite par utilisateur, validité, expéditeurs, voyageurs
routes:
  - path: /promo
    label: Ouvrir les codes promo
---
## À quoi ça sert

Un **code promo** donne une réduction en pourcentage. Un utilisateur le saisit dans l'application. La page **Codes promo** (menu **Argent**) permet de les créer et de les gérer.

## Étapes : créer un code

1. Ouvrez [Codes promo](/promo) et cliquez sur **Nouveau code**.
2. Remplissez :
   - **Code** : le mot à saisir dans l'application (il est mis en majuscules).
   - **Réduction (%)** : entre 0,1 et 99,9.
   - **Cible** : **Expéditeurs**, **Voyageurs** ou **Tous**.
   - **Valide du ... au ...** : les dates de début et de fin.
   - **Utilisations max** : laissez vide pour un nombre illimité.
   - **Limite par utilisateur** : combien de fois une même personne peut l'utiliser.
3. Cliquez sur **Créer**.

## Étapes : modifier, désactiver, supprimer

- **Modifier** : cliquez sur **Éditer** sur la ligne, changez les champs, puis **Enregistrer**.
- **Désactiver ou réactiver** : cliquez sur l'état de la ligne (**Actif** ou **Désactivé**). Un code désactivé ne fonctionne plus mais peut être réactivé.
- **Supprimer** : cliquez sur **Supprimer**, puis confirmez.

> **Attention :** la suppression est irréversible, le code ne pourra plus être utilisé. Pour une pause, préférez la désactivation.

## Comprendre ce que vous voyez

Les filtres en haut : **Tous**, **Actifs**, **Désactivés**. Colonnes : **Code**, **Réduction**, **Cible**, **Validité**, **Utilisations** et **État**.

## Bon à savoir

Pour donner du crédit (un montant) plutôt qu'une réduction, utilisez les [codes de recharge](/aide/codes-recharge).

## Qui peut le faire

Il faut la permission PROMO_MANAGE (Super admin et Admin).
