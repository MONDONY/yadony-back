---
id: transactions-litiges-bancaires
title: Suivre les litiges bancaires (contestations de paiement par carte)
category: argent
order: 70
summary: "Liste des contestations faites par un porteur de carte auprès de sa banque : ouverts, gagnés, perdus, avec motif et lien vers le paiement."
permission: PAYMENT_VIEW
keywords: litige bancaire, contestation bancaire, chargeback, rétrofacturation, opposition carte, client conteste, banque conteste, fraude présumée, colis non reçu, paiement non reconnu, paiement en double, litige ouvert, litige gagné, litige perdu, dispute stripe, motif du litige, versement bloqué litige
routes:
  - path: /transactions?tab=chargebacks
    label: Litiges bancaires
  - path: /transactions?tab=chargebacks&view=open
    label: Litiges ouverts
---
## À quoi ça sert

Un litige bancaire (en anglais « chargeback ») arrive quand le porteur d'une carte conteste un paiement auprès de sa banque, au lieu de passer par Yadony. La banque peut reprendre l'argent. Cette liste permet de voir ces contestations et leur issue.

Tant qu'un litige est ouvert sur un paiement, le versement au voyageur est bloqué.

## Étapes

1. Ouvrez [Transactions](/transactions?tab=chargebacks), section **Litiges bancaires**.
2. Choisissez une vue : **Ouverts**, **Gagnés**, **Perdus** ou **Tous**. La liste s'ouvre sur **Ouverts** s'il y en a.
3. Pour retrouver un litige, tapez dans « Chercher : n° de suivi, nom, trajet ».
4. Cliquez sur **Ouvrir le paiement** pour voir la fiche du paiement concerné.

## Comprendre ce que vous voyez

- **Colis** : le trajet et le n° de suivi, cliquables vers la fiche du colis.
- **Expéditeur → Voyageur** : les deux personnes, cliquables vers leur fiche.
- **Montant** contesté.
- **Motif** donné par la banque : **Fraude présumée**, **Colis non reçu**, **Colis non conforme**, **Paiement en double**, **Paiement non reconnu**, **Remboursement non traité**, **Abonnement annulé** ou **Motif général**.
- **Statut** : **Ouvert** (en cours), **Gagné** (Yadony garde l'argent), **Perdu** (la banque a rendu l'argent au porteur). Une ligne **Issue** et la date **Tranché le** s'ajoutent quand le litige est fini.
- **Ouvert** : la date d'ouverture et l'ancienneté.

## Bon à savoir

- La réponse au litige (envoi de preuves à la banque) ne se fait pas depuis cette page.
- Sur la fiche du paiement, le bandeau « Litige bancaire ouvert » et l'onglet **Incidents** rappellent le motif, la date et l'issue. Le lien **Ouvrir dans Litiges bancaires** revient ici avec la ligne mise en évidence.
- Si la ligne demandée n'est pas sur la page affichée, le message « Ce litige n'est pas dans cette page de la liste. » s'affiche : changez de page avec **Suivant**.
- Pour payer quand même le voyageur malgré un litige ouvert, il faut une dérogation motivée : voir [Débloquer un paiement](/aide/transactions-debloquer-paiement).
- Le filtre **Litige bancaire ouvert** existe aussi dans la section **Paiements**, sous forme d'alerte.

## Qui peut le faire

Super admin, Admin et Support peuvent consulter cette liste. Elle ne contient aucun bouton d'action.
