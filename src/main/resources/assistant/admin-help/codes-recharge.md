---
id: codes-recharge
title: Créer ou révoquer un code de recharge (crédit offert)
category: argent
order: 110
summary: Un code de recharge ajoute un crédit au solde de l'utilisateur, utilisable seulement pour les commissions. Création, suivi des utilisations, révocation.
permission: TOPUP_CODE_MANAGE
keywords: code de recharge, recharge, crédit offert, offrir du crédit, geste commercial, bon d'achat, top-up, topup, créditer un utilisateur, crédit non remboursable, commissions, révoquer un code, code épuisé, code expiré, utilisations, générer un code
routes:
  - path: /topup-codes
    label: Ouvrir les codes de recharge
---
## À quoi ça sert

Un **code de recharge** ajoute un montant au solde de l'utilisateur qui le saisit dans l'application. C'est pratique pour un geste commercial ou une opération de lancement. Ce crédit est **non remboursable** et ne sert qu'à payer des **commissions**. Chaque compte ne peut utiliser un même code qu'une fois.

## Étapes : créer un code

1. Ouvrez [Codes de recharge](/topup-codes) et cliquez sur **Nouveau code**.
2. Remplissez :
   - **Code** : laissez vide pour qu'il soit généré, ou cliquez sur **Générer**. Un code saisi à la main doit avoir assez de lettres ou de chiffres.
   - **Montant** et **Devise**.
   - **Nombre d'utilisations** : combien de comptes différents pourront l'utiliser.
   - **Expire le** : laissez vide pour la durée par défaut.
   - **Note interne** : pour vous souvenir pourquoi le code existe.
3. Cliquez sur **Créer**.
4. La fenêtre **Code créé** affiche le code. Copiez-le, puis cliquez sur **J'ai copié le code**.

> **Attention :** le code n'est affiché qu'une seule fois, à la création. Copiez-le avant de fermer la fenêtre.

> **Attention :** créer un code, c'est créer de l'argent. Vérifiez bien le montant et le nombre d'utilisations.

## Étapes : révoquer un code

1. Sur la ligne du code, cliquez sur **Révoquer**, puis confirmez.
2. Plus personne ne peut l'utiliser. Les crédits déjà versés restent acquis.

## Comprendre ce que vous voyez

Filtres : **Tous**, **Actifs**, **Épuisés**, **Expirés**, **Révoqués**. Colonnes : **Code**, **Montant**, **Utilisations**, **Expire le** et **État** (**Actif**, **Épuisé** quand toutes les utilisations sont prises, **Expiré**, **Révoqué**). Cliquez sur **Utilisations** pour voir qui a utilisé le code. « Personne n'a encore utilisé ce code » : aucune utilisation.

## Qui peut le faire

Il faut la permission TOPUP_CODE_MANAGE (Super admin et Admin, jamais le Support).
