---
id: fiche-utilisateur-argent
title: Voir le solde, le portefeuille et les paiements d'un utilisateur
category: comptes
order: 31
summary: "L'onglet Argent de la fiche : soldes par devise, gels, journal des mouvements, correction du solde et liste des paiements."
permission: PAYMENT_VIEW
keywords: solde utilisateur, portefeuille, wallet, argent du compte, solde par devise, remboursable, devise gelée, gel du portefeuille, lever le gel, corriger le solde, crédit manuel, débit manuel, journal des mouvements, recharge, remboursement, commission prélevée, paiements d'un utilisateur, versement retenu, carte bancaire, mobile money, onglet argent
routes:
  - path: /users
    label: Chercher un utilisateur
  - path: /transactions?held=true
    label: Voir les versements retenus
  - path: /ajustements-solde
    label: Corrections à approuver
---
## À quoi ça sert

L'onglet **Argent** de la fiche utilisateur montre l'argent que la personne détient dans l'application (son portefeuille), les mouvements de ce portefeuille, et ses paiements liés aux colis.

## Étapes

1. Ouvrez la fiche de la personne depuis [Utilisateurs](/users).
2. Cliquez sur l'onglet **Argent**.
3. Lisez le bloc **Soldes**, puis le **Journal** et le bloc **Paiements**.

### Filtrer le journal

1. Dans le bloc **Journal**, choisissez une devise dans la première liste (**Toutes les devises** par défaut).
2. Choisissez un type dans la seconde liste (**Tous les mouvements** par défaut).

### Corriger le solde

1. Cliquez sur **Corriger le solde** à droite de **Soldes**.
2. Choisissez **Créditer** (ajouter) ou **Débiter** (retirer).
3. Choisissez la **Devise** et saisissez le **Montant**.
4. Écrivez le **Motif (obligatoire)** : il est journalisé avec votre identifiant.
5. Vérifiez le récapitulatif **Solde actuel** suivi du nouveau solde, puis cliquez sur **Continuer**.
6. Relisez le résumé et cliquez sur **Confirmer la correction** (ou **Modifier** pour revenir en arrière).

> **Attention :** les plafonds et le seuil d'approbation sont rappelés en haut de la fenêtre. Au-delà du seuil, la correction n'est pas appliquée tout de suite : un autre administrateur doit l'approuver dans [Corrections à approuver](/ajustements-solde).

### Lever un gel

1. Dans **Recharges remboursées hors de l'app**, repérez la ligne **Gel actif**.
2. Si besoin, débitez d'abord le montant remboursé avec **Corriger le solde**.
3. Cliquez sur **Lever le gel**, indiquez comment le solde a été régularisé, puis confirmez.

## Comprendre ce que vous voyez

- **Soldes** : une carte par devise avec le solde et la part **Remboursable**. La pastille **Gelé** veut dire que la devise ne peut pas être dépensée (remboursement en cours, contestation bancaire ou recharge remboursée hors de l'app). Aucune correction n'est possible sur une devise gelée.
- **Journal** : chaque mouvement avec son type (**Recharge**, **Paiement d'une demande**, **Commission prélevée**, **Remboursement**, **Récompense de parrainage**, **Crédit manuel par un admin**, **Débit manuel par un admin**, **Code de recharge**, **Recharge contestée (litige carte)**...), le montant en vert ou en rouge, la date et le solde après. Pour une correction manuelle, le motif et l'administrateur sont indiqués.
- **Paiements** : **Date**, **Rôle** (**Payeur** ou **Bénéficiaire**), **Moyen** (**Carte bancaire** ou **Mobile money**), **Statut** (**En attente**, **Sous séquestre**, **Libéré**, **Échoué**, **Remboursé**, **Annulé**), **Montant**, **Commission** et **Versement** (**Retenu** si le versement au voyageur est bloqué). Le lien **Ouvrir** mène au paiement.
- Les liens **Tous ses paiements** et **Son portefeuille** ouvrent la page Transactions filtrée sur cette personne.

## Bon à savoir

- « Aucun compte ouvert pour cet utilisateur » : la personne n'a jamais eu de portefeuille.
- « Portefeuille indisponible pour le moment » : réessayez plus tard.

## Qui peut le faire

Lire l'onglet : Super admin, Admin et Support. **Corriger le solde** et **Lever le gel** : Super admin et Admin seulement.
