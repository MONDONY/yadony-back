---
id: transactions-resynchroniser-stripe
title: Resynchroniser un paiement carte avec Stripe
category: argent
order: 65
summary: Remettre d'accord Yadony et Stripe quand l'état d'un paiement carte diffère, depuis l'onglet Argent de la fiche du paiement.
permission: ADMIN_MANAGE
keywords: resynchroniser stripe, resync, paiement carte bloqué, paiement en attente alors que payé, stripe dit payé, désynchronisé, écart stripe, carte débitée mais en attente, encaissement, capture, autorisation expirée, 3d secure, payment intent, réconciliation stripe, statut stripe
routes:
  - path: /transactions
    label: Ouvrir les paiements
  - path: /transactions?method=STRIPE&view=ALL
    label: Paiements par carte
---
## À quoi ça sert

Pour un paiement par carte, Stripe (le prestataire de paiement) et Yadony gardent chacun un état. Il arrive qu'ils ne soient plus d'accord : par exemple la carte est débitée chez Stripe mais le paiement reste **En attente** chez Yadony. La resynchronisation relit Stripe et corrige l'état chez Yadony.

> **Attention :** si le colis est déjà livré, la resynchronisation peut verser l'argent au voyageur dans la foulée (« Colis déjà livré : versement envoyé au voyageur »).

## Étapes

1. Ouvrez la fiche du paiement depuis [Transactions](/transactions).
2. Cliquez sur l'onglet **Argent**.
3. En bas, cliquez sur **Resynchroniser avec Stripe**.
4. Lisez le résultat et le tableau **Avant** / **Après**. La fiche se recharge toute seule.

Le bouton n'apparaît que pour un paiement par carte qui a une référence Stripe.

## Comprendre ce que vous voyez

Le résultat dit ce qui a été fait :

- **Déjà à jour, rien à faire**
- **Paiement passé en séquestre et encaissé**
- **Séquestre encaissé : le voyageur pourra être payé**
- **Encaissement Stripe enregistré chez Yadony**
- **Paiement marqué échoué (la carte a été refusée chez Stripe)**
- **Paiement marqué annulé (annulé chez Stripe)**
- **Colis déjà livré : versement envoyé au voyageur**

Le tableau compare **Statut Yadony**, **Encaissé le**, **Statut Stripe** (par exemple **Autorisé, non encaissé**, **Encaissé**, **Action du client requise (3D Secure)**) et **Montant capturable**.

## Bon à savoir

Messages d'erreur fréquents :

- **Autorisation carte expirée** : il n'y a plus rien à encaisser. Remboursez ou recontactez l'expéditeur pour qu'il paie à nouveau.
- **Le montant ou la devise ne correspondent pas** : rien n'a été encaissé, transmettez à l'équipe technique.
- **Stripe a refusé l'encaissement** : rien n'a été versé, le paiement reste en séquestre et une alerte est levée.
- **Stripe injoignable** : réessayez dans quelques instants.
- **Action indisponible sur cet environnement** : le serveur n'est pas encore à jour.

Le lien **Ouvrir dans Stripe** de l'onglet **Références** permet de vérifier le paiement directement chez Stripe.

## Qui peut le faire

Super admin seulement. Pour les autres, le bouton est grisé avec le message « Réservé aux super-administrateurs : demandez à l'un d'eux de lancer la resynchronisation. »
