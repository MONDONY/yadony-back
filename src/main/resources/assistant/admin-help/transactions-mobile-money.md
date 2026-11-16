---
id: transactions-mobile-money
title: Suivre les opérations mobile money (dépôts, versements, remboursements)
category: argent
order: 80
summary: "Chaque mouvement d'argent chez les opérateurs mobile money : vues Échecs, En cours, Réussies, filtres type et opérateur, liens vers le paiement."
permission: PAYMENT_VIEW
keywords: mobile money, orange money, wave, mtn momo, moov money, free money, pawapay, opérateur, dépôt, versement, remboursement, recharge du solde, opération échouée, opération en cours, en rapprochement, refusée à la soumission, code d'échec, numéro masqué, relancer depuis le paiement, argent pas reçu afrique
routes:
  - path: /transactions?tab=mobile-money
    label: Opérations mobile money
  - path: /transactions?tab=mobile-money&view=failed
    label: Opérations en échec
  - path: /transactions?tab=mobile-money&view=open
    label: Opérations en cours
  - path: /transactions?tab=mobile-money&kind=PAYOUT
    label: Versements mobile money
---
## À quoi ça sert

Les paiements mobile money passent par un intermédiaire, pawaPay, qui parle aux opérateurs (Orange Money, Wave, MTN MoMo, Moov Money, Free Money...). Cet onglet montre chaque opération envoyée à l'opérateur : l'argent reçu d'un expéditeur, l'argent versé à un voyageur, l'argent rendu.

## Étapes

1. Ouvrez [Transactions](/transactions?tab=mobile-money), section **Mobile money**, sous-onglet **Opérations**.
2. Choisissez une vue : **Échecs**, **En cours**, **Réussies** ou **Toutes**. La page s'ouvre sur **Échecs** s'il y a quelque chose à traiter.
3. Pour chercher, tapez dans « Chercher : nom, référence d'opération, n° de suivi, trajet ». La recherche part toute seule après une courte pause, ou tout de suite avec Entrée.
4. Filtrez par type (**Tous les types**, **Dépôt**, **Versement**, **Remboursement**) et par opérateur (**Tous les opérateurs** ou un opérateur précis).
5. Utilisez les liens de la ligne pour agir : **Ouvrir le paiement**, **Ouvrir la demande de remboursement**, ou **Relancer depuis le paiement**.

## Comprendre ce que vous voyez

- **Type** : **Dépôt** (l'argent arrive chez Yadony), **Versement** (l'argent part vers un voyageur) ou **Remboursement**. En dessous, le motif s'il n'est pas un paiement de colis : **Recharge du solde**, **Remboursement du solde** ou **Supplément**.
- **Colis** : trajet et n° de suivi, cliquables.
- **Personne** : le nom, avec son rôle (**Expéditeur**, **Voyageur** ou **Utilisateur**).
- **Opérateur** : l'opérateur et le pays (par exemple « Orange Money · CI »), avec le numéro de téléphone masqué.
- **Montant** et **Créée** (ancienneté).
- **Statut** : **Créée**, **Acceptée**, **En cours**, **En file**, **En rapprochement**, **Terminée**, **Échouée** ou **Refusée à la soumission**. Le code d'échec de l'opérateur s'affiche à côté quand il existe.

## Bon à savoir

- Le nombre rouge de la vue **Échecs** compte aussi d'autres points à traiter : il peut différer du nombre de lignes.
- On ne peut pas chercher par numéro de téléphone.
- La relance d'une opération se fait toujours depuis la fiche du paiement : voir [Relancer un versement ou un remboursement mobile money](/aide/transactions-relancer-mobile-money).
- Une opération « En rapprochement » est en cours de vérification chez l'opérateur : attendez avant d'agir.
- Ce que Yadony gagne sur ces paiements est dans le sous-onglet **Commissions** : voir [Commissions mobile money](/aide/transactions-commissions-mobile-money).

## Qui peut le faire

Super admin, Admin et Support peuvent consulter. Les relances sont réservées au Super admin et à l'Admin.
