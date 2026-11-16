---
id: transactions-versement-retenu
title: Traiter un versement retenu (voyageur banni ou identité révoquée)
category: argent
order: 45
summary: "Un versement retenu ne repart jamais seul : remboursez l'expéditeur ou payez le voyageur par une dérogation motivée."
permission: PAYMENT_VIEW
keywords: versement retenu, versements retenus, paiement bloqué, argent bloqué, voyageur banni, identité révoquée, kyc révoqué, voyageur gelé, versements gelés, retenu chez yadony, payer quand même, dérogation, rembourser l'expéditeur, held, retenue, compte suspendu argent, voyageur rétabli
routes:
  - path: /transactions?alert=HELD
    label: Voir les versements retenus
  - path: /transactions?held=true
    label: Versements retenus (ancien lien)
---
## À quoi ça sert

Quand un voyageur est banni ou que son identité est révoquée, Yadony bloque ses versements. L'argent des colis qu'il transportait reste chez Yadony : c'est un **versement retenu**. Il ne repartira pas tout seul, même si le compte est rétabli plus tard. Une personne de l'équipe doit décider quoi faire.

## Étapes

1. Ouvrez [les versements retenus](/transactions?alert=HELD). Vous pouvez aussi, dans la section **Paiements**, cliquer sur **Plus de filtres** puis sur **Versements retenus**.
2. Cliquez sur un paiement : les lignes retenues ont un fond rouge pâle et l'étiquette **Versement retenu**.
3. Lisez le bandeau en haut de la fiche : « À traiter : versement retenu depuis le ... » avec la cause (**Le voyageur a été banni.** ou **L'identité du voyageur a été révoquée.**).
4. Dans l'onglet **Incidents**, le lien **Voir pourquoi** mène à la fiche du voyageur (onglet **Gestion** pour un bannissement, **Identité** pour une révocation). **Voir l'argent du voyageur** ouvre son onglet argent.
5. Décidez :
   - **Rembourser l'expéditeur** si la livraison n'a pas eu lieu ou pose problème. Voir [Rembourser l'expéditeur](/aide/transactions-rembourser-expediteur).
   - **Payer le voyageur (dérogation)** si le colis a bien été livré et que le voyageur doit être payé malgré tout. Voir [Débloquer un paiement](/aide/transactions-debloquer-paiement).

> **Attention :** les deux gestes déplacent de l'argent de façon définitive. La dérogation exige de cocher une case et d'écrire un motif, enregistré avec votre identifiant.

## Comprendre ce que vous voyez

- Dans la liste, la colonne **À faire** affiche **Versement retenu** en rouge.
- Dans le **Parcours de l'argent**, la dernière étape est un arrêt rouge **Versement retenu**.
- Sur la fiche, le voyageur porte **Banni** ou **Identité révoquée** et la mention **Versements gelés**.
- Si le paiement a aussi un litige bancaire ouvert, la fenêtre de dérogation l'indique : deux blocages sont levés en même temps.

## Bon à savoir

- Si le message « Le serveur ne sait pas encore isoler les versements retenus » s'affiche, le filtre n'est pas encore disponible sur ce serveur.
- Rétablir un voyageur ne débloque pas ses paiements retenus : traitez-les un par un ici.
- Le compteur de la vue **À traiter** inclut les versements retenus.

## Qui peut le faire

Tout le monde peut consulter la liste. Rembourser ou payer par dérogation : Super admin et Admin seulement.
