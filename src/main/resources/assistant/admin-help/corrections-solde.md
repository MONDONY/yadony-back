---
id: corrections-solde
title: Corriger le solde d'un utilisateur et approuver une correction
category: argent
order: 120
summary: "Créditer ou débiter le solde d'un utilisateur depuis sa fiche. Au-delà d'un certain montant, un second admin doit approuver dans Corrections à approuver."
permission: WALLET_ADJUST
keywords: corriger le solde, correction de solde, ajustement de solde, créditer, débiter, ajouter de l'argent, retirer de l'argent, régulariser, erreur de solde, corrections à approuver, approuver une correction, rejeter, retirer ma correction, double validation, second admin, quatre yeux, gel du portefeuille, lever le gel, solde gelé
routes:
  - path: /ajustements-solde
    label: Ouvrir les corrections à approuver
  - path: /users
    label: Chercher un utilisateur
---
## À quoi ça sert

Il arrive qu'un solde soit faux (erreur, geste commercial, régularisation). Un admin peut alors **corriger le solde** : le créditer (ajouter de l'argent) ou le débiter (en retirer). Pour les gros montants, une seconde personne doit approuver : c'est la page **Corrections à approuver** (menu **Argent**).

## Étapes : corriger un solde

1. Ouvrez la fiche de l'utilisateur depuis [Utilisateurs](/users), onglet **Argent**.
2. Cliquez sur **Corriger le solde**.
3. Choisissez **Créditer** ou **Débiter**, la devise, et le montant (ex. 12,50).
4. Écrivez le **Motif (obligatoire)** : pourquoi ce solde doit changer.
5. Cliquez sur **Continuer**, relisez le récapitulatif, puis **Confirmer la correction**. **Modifier** permet de revenir en arrière.

Petit montant : la correction est écrite tout de suite. Gros montant : elle part dans **Corrections à approuver** et attend un second admin.

> **Attention :** une correction écrit de l'argent réel sur le compte. Vérifiez le sens (créditer ou débiter) et le montant.

## Étapes : approuver ou rejeter une correction

1. Ouvrez [Corrections à approuver](/ajustements-solde).
2. Lisez chaque ligne : **Demandée le**, **Utilisateur**, **Montant** (+ pour un crédit, − pour un débit), **Équivalent** en euros, **Motif** et **Par** (l'admin qui l'a demandée). **Fiche** ouvre la fiche de l'utilisateur.
3. Cliquez sur **Approuver** puis confirmez : le montant est écrit sur le solde, au nom de l'auteur.
4. Ou cliquez sur **Rejeter**, indiquez pourquoi, puis confirmez : rien n'est écrit sur le solde.

> **Bon à savoir :** vous ne pouvez pas approuver votre propre correction. Sur vos propres demandes, le bouton devient **Retirer**.

« Aucune correction en attente » : il n'y a rien à valider.

## Solde gelé

Une devise peut être **Gelée** (remboursement en cours, contestation bancaire, recharge remboursée hors de l'application). Aucune correction n'est possible tant que le gel n'est pas traité. Une fois la situation régularisée, cliquez sur **Lever le gel** et indiquez comment le solde a été régularisé.

## Qui peut le faire

Il faut la permission WALLET_ADJUST (Super admin et Admin, jamais le Support).
